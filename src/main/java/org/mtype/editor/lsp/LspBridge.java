package org.mtype.editor.lsp;

import javafx.application.Platform;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageServer;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.process.StreamPump;
import org.mtype.editor.workspace.Workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Stream;

public class LspBridge {
    private final AppContext ctx;

    private Process process;
    private LanguageServer server;
    private Launcher<LanguageServer> launcher;
    private Future<?> listening;
    private ExecutorService rpcExecutor;
    private MTypeLanguageClient client;
    private boolean ready;
    private long session;
    private SemanticTokensLegend semanticLegend;
    private Map<String, Long> watchedMtFiles = Collections.emptyMap();

    public LspBridge(AppContext ctx) {
        this.ctx = ctx;
    }

    public boolean isReady() { return ready; }
    public synchronized long getSession() { return session; }
    public synchronized SemanticTokensLegend getSemanticLegend() { return semanticLegend; }

    public synchronized void start(Workspace ws) throws IOException {
        stop();
        long startSession = ++session;
        watchedMtFiles = scanMtFiles(ws.root());

        String lspExe = resolveLanguageServerExecutable();
        ctx.getOutputPane().appendLspLog("[lsp] starting " + lspExe);
        ProcessBuilder pb = new ProcessBuilder(lspExe, "--stdio")
                .directory(ws.root().toFile())
                .redirectErrorStream(false);
        process = pb.start();

        new StreamPump(process.getErrorStream(),
                line -> ctx.getOutputPane().appendLspLog("[stderr] " + line),
                "mtype-lsp-stderr").start();

        client = new MTypeLanguageClient(ctx);
        rpcExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mtype-lsp-rpc");
            t.setDaemon(true);
            return t;
        });
        launcher = LSPLauncher.createClientLauncher(
                client,
                process.getInputStream(),
                process.getOutputStream(),
                rpcExecutor,
                consumer -> consumer);
        listening = launcher.startListening();
        LanguageServer startedServer = launcher.getRemoteProxy();
        server = startedServer;

        InitializeParams init = new InitializeParams();
        init.setProcessId((int) ProcessHandle.current().pid());
        String rootUri = ws.root().toUri().toString();
        // The mType LSP still uses rootUri/rootPath to initialize project config
        // and mt_modules aliases, while modern clients use workspaceFolders.
        init.setRootUri(rootUri);
        init.setRootPath(ws.root().toString());
        WorkspaceFolder folder = new WorkspaceFolder(rootUri,
                ws.root().getFileName() == null ? "root" : ws.root().getFileName().toString());
        init.setWorkspaceFolders(Collections.singletonList(folder));

        ClientCapabilities caps = new ClientCapabilities();
        TextDocumentClientCapabilities td = new TextDocumentClientCapabilities();
        td.setSynchronization(new org.eclipse.lsp4j.SynchronizationCapabilities(false, false, false));
        td.setHover(new org.eclipse.lsp4j.HoverCapabilities());

        CompletionCapabilities completion = new CompletionCapabilities();
        CompletionItemCapabilities itemCaps = new CompletionItemCapabilities();
        itemCaps.setSnippetSupport(true);
        itemCaps.setResolveSupport(new CompletionItemResolveSupportCapabilities(
                List.of("additionalTextEdits", "detail", "documentation")));
        completion.setCompletionItem(itemCaps);
        td.setCompletion(completion);

        td.setPublishDiagnostics(new org.eclipse.lsp4j.PublishDiagnosticsCapabilities());
        td.setDefinition(new org.eclipse.lsp4j.DefinitionCapabilities());
        td.setFormatting(new org.eclipse.lsp4j.FormattingCapabilities());
        td.setCodeLens(new CodeLensCapabilities());
        td.setReferences(new ReferencesCapabilities());
        td.setInlayHint(new InlayHintCapabilities());
        td.setSignatureHelp(new SignatureHelpCapabilities());
        td.setDocumentSymbol(new DocumentSymbolCapabilities());

        SemanticTokensCapabilities stCaps = new SemanticTokensCapabilities(false);
        SemanticTokensClientCapabilitiesRequests stReq =
                new SemanticTokensClientCapabilitiesRequests(true);
        stCaps.setRequests(stReq);
        stCaps.setTokenTypes(List.of(
                "namespace", "type", "class", "enum", "interface", "struct",
                "typeParameter", "parameter", "variable", "property", "enumMember",
                "event", "function", "method", "macro", "keyword", "modifier",
                "comment", "string", "number", "regexp", "operator", "decorator"));
        stCaps.setTokenModifiers(List.of(
                "declaration", "definition", "readonly", "static", "deprecated",
                "abstract", "async", "modification", "documentation", "defaultLibrary"));
        stCaps.setFormats(List.of(TokenFormat.Relative));
        td.setSemanticTokens(stCaps);

        RenameCapabilities rename = new RenameCapabilities();
        rename.setPrepareSupport(true);
        td.setRename(rename);

        CallHierarchyCapabilities chCaps = new CallHierarchyCapabilities();
        td.setCallHierarchy(chCaps);

        caps.setTextDocument(td);
        WorkspaceClientCapabilities wc = new WorkspaceClientCapabilities();
        wc.setWorkspaceFolders(true);
        wc.setApplyEdit(true);
        wc.setSymbol(new SymbolCapabilities());
        caps.setWorkspace(wc);
        init.setCapabilities(caps);

        startedServer.initialize(init).whenComplete((InitializeResult result, Throwable err) -> {
            synchronized (LspBridge.this) {
                if (startSession != session || server != startedServer) return;
            }
            if (err != null) {
                Platform.runLater(() -> {
                    ctx.getStatusBar().setLspState("LSP: error");
                    ctx.getOutputPane().appendLspLog("[init error] " + err.getMessage());
                });
                return;
            }
            startedServer.initialized(new InitializedParams());
            synchronized (LspBridge.this) {
                if (startSession != session || server != startedServer) return;
                ready = true;
                if (result != null && result.getCapabilities() != null
                        && result.getCapabilities().getSemanticTokensProvider() != null) {
                    semanticLegend = result.getCapabilities().getSemanticTokensProvider().getLegend();
                }
            }
            Platform.runLater(() -> {
                ctx.getStatusBar().setLspState("LSP: ready");
                if (ctx.getTabPane() != null) ctx.getTabPane().syncOpenDocumentsWithLsp();
            });
        });
    }

    private String resolveLanguageServerExecutable() {
        String configured = ctx.getSettings() != null
                && ctx.getSettings().toolchain != null
                ? ctx.getSettings().toolchain.languageServer
                : null;
        configured = configured == null ? "" : configured.trim();

        if (!configured.isEmpty()) {
            Path configuredPath = Path.of(configured);
            if (Files.isRegularFile(configuredPath)) {
                return configuredPath.toString();
            }
            if (!looksLikePath(configured)) {
                return configured;
            }
            ctx.getOutputPane().appendLspLog("[lsp] configured server not found: " + configured);
        }

        for (Path candidate : defaultLanguageServerCandidates()) {
            if (Files.isRegularFile(candidate)) {
                ctx.getOutputPane().appendLspLog("[lsp] using default server: " + candidate);
                return candidate.toString();
            }
        }

        String executable = languageServerExecutableName();
        ctx.getOutputPane().appendLspLog("[lsp] falling back to PATH lookup: " + executable);
        return executable;
    }

    private static List<Path> defaultLanguageServerCandidates() {
        List<Path> candidates = new ArrayList<>();
        String home = System.getenv("MTYPE_HOME");
        if (home != null && !home.isBlank()) {
            candidates.add(languageServerPathUnder(Path.of(home)));
        }
        candidates.add(languageServerPathUnder(Path.of("C:\\matan\\mType")));
        return candidates;
    }

    private static Path languageServerPathUnder(Path root) {
        return root.resolve(Path.of(
                "bin",
                "mtype-language-server",
                "Release",
                "x64",
                languageServerExecutableName()));
    }

    private static String languageServerExecutableName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win") ? "mtype-language-server.exe" : "mtype-language-server";
    }

    private static boolean looksLikePath(String value) {
        return value.contains("\\") || value.contains("/") || Path.of(value).isAbsolute();
    }

    public synchronized void stop() {
        session++;
        ready = false;
        LanguageServer serverToStop = server;
        Future<?> listeningToStop = listening;
        Process processToStop = process;
        ExecutorService executorToStop = rpcExecutor;

        server = null;
        listening = null;
        process = null;
        rpcExecutor = null;
        client = null;
        launcher = null;
        watchedMtFiles = Collections.emptyMap();

        if (serverToStop != null) {
            try { serverToStop.shutdown().get(1, TimeUnit.SECONDS); } catch (Exception ignored) {}
            try { serverToStop.exit(); } catch (Exception ignored) {}
        }
        if (listeningToStop != null) {
            listeningToStop.cancel(true);
        }
        if (processToStop != null) {
            try {
                try { processToStop.getOutputStream().close(); } catch (Exception ignored) {}
                try { processToStop.getInputStream().close(); } catch (Exception ignored) {}
                processToStop.destroy();
                if (!processToStop.waitFor(2, TimeUnit.SECONDS)) processToStop.destroyForcibly();
            } catch (Exception ignored) {
                processToStop.destroyForcibly();
            }
        }
        if (executorToStop != null) {
            executorToStop.shutdownNow();
        }
    }

    /* ============================== sync ============================== */

    public void didOpen(Path path, String text, int version) {
        if (!ready || server == null) return;
        TextDocumentItem item = new TextDocumentItem(path.toUri().toString(), "mtype", version, text);
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(item));
    }

    public void didChange(Path path, String text, int version) {
        if (!ready || server == null) return;
        VersionedTextDocumentIdentifier id = new VersionedTextDocumentIdentifier(path.toUri().toString(), version);
        TextDocumentContentChangeEvent change = new TextDocumentContentChangeEvent(text);
        server.getTextDocumentService().didChange(new DidChangeTextDocumentParams(id, Collections.singletonList(change)));
    }

    public void didClose(Path path) {
        if (!ready || server == null) return;
        TextDocumentIdentifier id = new TextDocumentIdentifier(path.toUri().toString());
        server.getTextDocumentService().didClose(new DidCloseTextDocumentParams(id));
    }

    public void refreshWatchedMtFiles() {
        Workspace ws = ctx.getWorkspace();
        if (ws == null) return;

        Map<String, Long> current = scanMtFiles(ws.root());
        List<FileEvent> changes = new ArrayList<>();
        synchronized (this) {
            for (Map.Entry<String, Long> entry : current.entrySet()) {
                Long previousModified = watchedMtFiles.get(entry.getKey());
                if (previousModified == null) {
                    changes.add(new FileEvent(entry.getKey(), FileChangeType.Created));
                } else if (!previousModified.equals(entry.getValue())) {
                    changes.add(new FileEvent(entry.getKey(), FileChangeType.Changed));
                }
            }
            for (String uri : watchedMtFiles.keySet()) {
                if (!current.containsKey(uri)) {
                    changes.add(new FileEvent(uri, FileChangeType.Deleted));
                }
            }
            watchedMtFiles = current;
        }

        if (!changes.isEmpty()) {
            didChangeWatchedFiles(changes);
        }
    }

    public void didChangeWatchedFiles(List<FileEvent> changes) {
        if (!ready || server == null || changes == null || changes.isEmpty()) return;
        server.getWorkspaceService().didChangeWatchedFiles(new DidChangeWatchedFilesParams(changes));
    }

    private static Map<String, Long> scanMtFiles(Path root) {
        if (root == null || !Files.isDirectory(root)) return Collections.emptyMap();
        Map<String, Long> files = new HashMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(LspBridge::isMtFile)
                    .forEach(path -> {
                        try {
                            Path absolute = path.toAbsolutePath().normalize();
                            files.put(absolute.toUri().toString(),
                                    Files.getLastModifiedTime(absolute).toMillis());
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
        return files;
    }

    private static boolean isMtFile(Path path) {
        Path name = path == null ? null : path.getFileName();
        return name != null && name.toString().toLowerCase().endsWith(".mt");
    }

    /* ============================== completion ============================== */

    public CompletableFuture<List<CompletionItem>> completion(Path path, int line, int col, String triggerChar) {
        if (!ready || server == null) return CompletableFuture.completedFuture(Collections.emptyList());
        CompletionParams params = new CompletionParams(
                new TextDocumentIdentifier(path.toUri().toString()),
                new Position(line, col));
        CompletionContext context = new CompletionContext();
        if (triggerChar != null && !triggerChar.isEmpty()) {
            context.setTriggerKind(CompletionTriggerKind.TriggerCharacter);
            context.setTriggerCharacter(triggerChar);
        } else {
            context.setTriggerKind(CompletionTriggerKind.Invoked);
        }
        params.setContext(context);
        return server.getTextDocumentService().completion(params).thenApply(either -> {
            if (either == null) return Collections.<CompletionItem>emptyList();
            if (either.isLeft()) return either.getLeft();
            CompletionList list = either.getRight();
            return list != null ? list.getItems() : Collections.<CompletionItem>emptyList();
        }).exceptionally(_ -> Collections.emptyList());
    }

    public CompletableFuture<CompletionItem> resolveCompletion(CompletionItem item) {
        if (!ready || server == null || item == null) {
            return CompletableFuture.completedFuture(item);
        }
        return server.getTextDocumentService().resolveCompletionItem(item)
                .exceptionally(_ -> item);
    }

    /* ============================== hover ============================== */

    public CompletableFuture<String> hover(Path path, int line, int col) {
        if (!ready || server == null) return CompletableFuture.completedFuture("");
        HoverParams params = new HoverParams(
                new TextDocumentIdentifier(path.toUri().toString()),
                new Position(line, col));
        return server.getTextDocumentService().hover(params).thenApply(LspBridge::renderHover)
                .exceptionally(_ -> "");
    }

    private static String renderHover(Hover h) {
        if (h == null || h.getContents() == null) return "";
        // Modern servers reply with MarkupContent (the right side). The left side is the legacy
        // MarkedString[] form (deprecated); we only consume its plain-string entries so we never
        // touch the deprecated MarkedString type.
        var contents = h.getContents();
        String raw;
        if (contents.isRight() && contents.getRight() != null) {
            raw = contents.getRight().getValue();
        } else if (contents.isLeft() && contents.getLeft() != null) {
            StringBuilder sb = new StringBuilder();
            for (var e : contents.getLeft()) {
                if (e.isLeft()) {
                    if (!sb.isEmpty()) sb.append("\n");
                    sb.append(e.getLeft());
                }
            }
            raw = sb.toString();
        } else {
            return "";
        }
        return stripMarkdownFences(raw);
    }

    // Strip ```lang … ``` fences so JavaFX Tooltip (plain text only) doesn't
    // show literal backticks. Drops lines that are exactly a fence marker;
    // keeps everything inside.
    private static String stripMarkdownFences(String s) {
        if (s == null || s.isEmpty()) return s;
        String[] lines = s.split("\\r?\\n", -1);
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            String t = line.stripLeading();
            if (t.startsWith("```")) continue;
            if (!out.isEmpty()) out.append('\n');
            out.append(line);
        }
        return out.toString().strip();
    }

    /* ============================== formatting ============================== */

    public CompletableFuture<List<? extends TextEdit>> format(Path path, int tabSize, boolean insertSpaces) {
        if (!ready || server == null) return CompletableFuture.completedFuture(Collections.emptyList());
        FormattingOptions opts = new FormattingOptions(tabSize, insertSpaces);
        DocumentFormattingParams params = new DocumentFormattingParams(
                new TextDocumentIdentifier(path.toUri().toString()),
                opts);
        return server.getTextDocumentService().formatting(params)
                .exceptionally(_ -> Collections.emptyList());
    }

    /* ============================== definition ============================== */

    public CompletableFuture<Location> definition(Path path, int line, int col) {
        if (!ready || server == null) return CompletableFuture.completedFuture(null);
        DefinitionParams params = new DefinitionParams(
                new TextDocumentIdentifier(path.toUri().toString()),
                new Position(line, col));
        return server.getTextDocumentService().definition(params).thenApply(either -> {
            if (either == null) return null;
            if (either.isLeft()) {
                List<? extends Location> locs = either.getLeft();
                return locs != null && !locs.isEmpty() ? locs.getFirst() : null;
            }
            if (either.isRight()) {
                List<? extends LocationLink> links = either.getRight();
                if (links != null && !links.isEmpty()) {
                    LocationLink link = links.getFirst();
                    return new Location(link.getTargetUri(), link.getTargetSelectionRange());
                }
            }
            return null;
        }).exceptionally(_ -> null);
    }

    /* ============================== code lens ============================== */

    public CompletableFuture<List<? extends CodeLens>> codeLens(Path path) {
        if (!ready || server == null) return CompletableFuture.completedFuture(Collections.emptyList());
        CodeLensParams params = new CodeLensParams(new TextDocumentIdentifier(path.toUri().toString()));
        return server.getTextDocumentService().codeLens(params)
                .thenApply(list -> list == null ? Collections.<CodeLens>emptyList() : list)
                .exceptionally(_ -> Collections.emptyList());
    }

    public CompletableFuture<List<InlayHint>> inlayHints(Path path, Range range) {
        if (!ready || server == null || range == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        InlayHintParams params = new InlayHintParams();
        params.setTextDocument(new TextDocumentIdentifier(path.toUri().toString()));
        params.setRange(range);
        return server.getTextDocumentService().inlayHint(params)
                .thenApply(list -> list == null ? Collections.<InlayHint>emptyList() : list)
                .exceptionally(_ -> Collections.emptyList());
    }

    public CompletableFuture<List<? extends Location>> references(Path path, int line, int col, boolean includeDeclaration) {
        if (!ready || server == null) return CompletableFuture.completedFuture(Collections.emptyList());
        ReferenceParams params = new ReferenceParams();
        params.setTextDocument(new TextDocumentIdentifier(path.toUri().toString()));
        params.setPosition(new Position(line, col));
        params.setContext(new ReferenceContext(includeDeclaration));
        return server.getTextDocumentService().references(params)
                .thenApply(list -> list == null ? Collections.<Location>emptyList() : list)
                .exceptionally(_ -> Collections.emptyList());
    }

    /* ============================== rename ============================== */

    public CompletableFuture<PrepareInfo> prepareRename(Path path, int line, int col) {
        if (!ready || server == null) return CompletableFuture.completedFuture(null);
        PrepareRenameParams params = new PrepareRenameParams();
        params.setTextDocument(new TextDocumentIdentifier(path.toUri().toString()));
        params.setPosition(new Position(line, col));
        return server.getTextDocumentService().prepareRename(params).thenApply(either -> {
            if (either == null) return null;
            if (either.isFirst()) {
                Range r = either.getFirst();
                return new PrepareInfo(r, null);
            }
            if (either.isSecond()) {
                PrepareRenameResult res = either.getSecond();
                return new PrepareInfo(res.getRange(), res.getPlaceholder());
            }
            return null;
        }).exceptionally(_ -> null);
    }

    public CompletableFuture<WorkspaceEdit> rename(Path path, int line, int col, String newName) {
        if (!ready || server == null) return CompletableFuture.completedFuture(null);
        RenameParams params = new RenameParams();
        params.setTextDocument(new TextDocumentIdentifier(path.toUri().toString()));
        params.setPosition(new Position(line, col));
        params.setNewName(newName);
        return server.getTextDocumentService().rename(params).exceptionally(_ -> null);
    }

    public record PrepareInfo(Range range, String placeholder) {
    }

    /* ============================== call hierarchy ============================== */

    public CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchy(Path path, int line, int col) {
        if (!ready || server == null) return CompletableFuture.completedFuture(Collections.emptyList());
        CallHierarchyPrepareParams params = new CallHierarchyPrepareParams();
        params.setTextDocument(new TextDocumentIdentifier(path.toUri().toString()));
        params.setPosition(new Position(line, col));
        return server.getTextDocumentService().prepareCallHierarchy(params)
                .thenApply(list -> list == null ? Collections.<CallHierarchyItem>emptyList() : list)
                .exceptionally(_ -> Collections.emptyList());
    }

    public CompletableFuture<List<CallHierarchyIncomingCall>> incomingCalls(CallHierarchyItem item) {
        if (!ready || server == null) return CompletableFuture.completedFuture(Collections.emptyList());
        CallHierarchyIncomingCallsParams params = new CallHierarchyIncomingCallsParams();
        params.setItem(item);
        return server.getTextDocumentService().callHierarchyIncomingCalls(params)
                .thenApply(list -> list == null ? Collections.<CallHierarchyIncomingCall>emptyList() : list)
                .exceptionally(_ -> Collections.emptyList());
    }

    public CompletableFuture<List<CallHierarchyOutgoingCall>> outgoingCalls(CallHierarchyItem item) {
        if (!ready || server == null) return CompletableFuture.completedFuture(Collections.emptyList());
        CallHierarchyOutgoingCallsParams params = new CallHierarchyOutgoingCallsParams();
        params.setItem(item);
        return server.getTextDocumentService().callHierarchyOutgoingCalls(params)
                .thenApply(list -> list == null ? Collections.<CallHierarchyOutgoingCall>emptyList() : list)
                .exceptionally(_ -> Collections.emptyList());
    }

    /* ============================== code actions ============================== */

    public CompletableFuture<List<Either<org.eclipse.lsp4j.Command, org.eclipse.lsp4j.CodeAction>>>
    codeAction(Path path, Range range, List<org.eclipse.lsp4j.Diagnostic> contextDiagnostics) {
        if (!ready || server == null) return CompletableFuture.completedFuture(Collections.emptyList());
        org.eclipse.lsp4j.CodeActionParams params = new org.eclipse.lsp4j.CodeActionParams();
        params.setTextDocument(new TextDocumentIdentifier(path.toUri().toString()));
        params.setRange(range);
        org.eclipse.lsp4j.CodeActionContext cctx = new org.eclipse.lsp4j.CodeActionContext();
        cctx.setDiagnostics(contextDiagnostics == null ? Collections.emptyList() : contextDiagnostics);
        params.setContext(cctx);
        return server.getTextDocumentService().codeAction(params)
                .thenApply(list -> list == null ? Collections.<Either<org.eclipse.lsp4j.Command, org.eclipse.lsp4j.CodeAction>>emptyList() : list)
                .exceptionally(_ -> Collections.emptyList());
    }

    /* ============================== signature help ============================== */

    public CompletableFuture<SignatureHelp> signatureHelp(Path path, int line, int col, String triggerChar) {
        if (!ready || server == null) return CompletableFuture.completedFuture(null);
        SignatureHelpParams params = new SignatureHelpParams(
                new TextDocumentIdentifier(path.toUri().toString()),
                new Position(line, col));
        SignatureHelpContext shCtx = new SignatureHelpContext();
        if (triggerChar != null && !triggerChar.isEmpty()) {
            shCtx.setTriggerKind(SignatureHelpTriggerKind.TriggerCharacter);
            shCtx.setTriggerCharacter(triggerChar);
        } else {
            shCtx.setTriggerKind(SignatureHelpTriggerKind.Invoked);
        }
        shCtx.setIsRetrigger(false);
        params.setContext(shCtx);
        return server.getTextDocumentService().signatureHelp(params)
                .exceptionally(_ -> null);
    }

    /* ============================== document symbols ============================== */

    public CompletableFuture<List<DocumentSymbol>> documentSymbol(Path path) {
        if (!ready || server == null) return CompletableFuture.completedFuture(Collections.emptyList());
        DocumentSymbolParams params = new DocumentSymbolParams(
                new TextDocumentIdentifier(path.toUri().toString()));
        return server.getTextDocumentService().documentSymbol(params)
                .thenApply(list -> {
                    if (list == null || list.isEmpty()) return Collections.<DocumentSymbol>emptyList();
                    List<DocumentSymbol> out = new ArrayList<>(list.size());
                    for (Either<SymbolInformation, DocumentSymbol> e : list) {
                        if (e != null && e.isRight() && e.getRight() != null) out.add(e.getRight());
                    }
                    return out;
                })
                .exceptionally(_ -> Collections.emptyList());
    }

    /* ============================== workspace symbols ============================== */

    public CompletableFuture<List<WorkspaceSymbol>> workspaceSymbol(String query) {
        if (!ready || server == null) return CompletableFuture.completedFuture(Collections.emptyList());
        WorkspaceSymbolParams params = new WorkspaceSymbolParams(query == null ? "" : query);
        return server.getWorkspaceService().symbol(params).thenApply(either -> {
            // Modern servers reply with WorkspaceSymbol[] (the right side). The left side is the
            // legacy, deprecated SymbolInformation[] form, which the mType server does not emit.
            if (either == null || !either.isRight() || either.getRight() == null) {
                return Collections.<WorkspaceSymbol>emptyList();
            }
            return new ArrayList<WorkspaceSymbol>(either.getRight());
        }).exceptionally(_ -> Collections.emptyList());
    }

    /* ============================== semantic tokens ============================== */

    public CompletableFuture<SemanticTokens> semanticTokensFull(Path path) {
        if (!ready || server == null) return CompletableFuture.completedFuture(null);
        SemanticTokensParams params = new SemanticTokensParams(
                new TextDocumentIdentifier(path.toUri().toString()));
        return server.getTextDocumentService().semanticTokensFull(params)
                .exceptionally(_ -> null);
    }

    public void executeCommand(String command, List<Object> args) {
        if (!ready || server == null) {
            CompletableFuture.completedFuture(null);
            return;
        }
        org.eclipse.lsp4j.ExecuteCommandParams params = new org.eclipse.lsp4j.ExecuteCommandParams();
        params.setCommand(command);
        params.setArguments(args == null ? Collections.emptyList() : args);
        server.getWorkspaceService().executeCommand(params)
                .exceptionally(_ -> null);
    }
}
