package org.mtype.editor.lsp;

import javafx.application.Platform;
import org.eclipse.lsp4j.CallHierarchyCapabilities;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.eclipse.lsp4j.CallHierarchyOutgoingCallsParams;
import org.eclipse.lsp4j.CallHierarchyPrepareParams;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensCapabilities;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.CompletionCapabilities;
import org.eclipse.lsp4j.CompletionContext;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemCapabilities;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.CompletionTriggerKind;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.FormattingOptions;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintCapabilities;
import org.eclipse.lsp4j.InlayHintParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MarkedString;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PrepareRenameParams;
import org.eclipse.lsp4j.PrepareRenameResult;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceContext;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.ReferencesCapabilities;
import org.eclipse.lsp4j.RenameCapabilities;
import org.eclipse.lsp4j.RenameParams;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    public LspBridge(AppContext ctx) {
        this.ctx = ctx;
    }

    public boolean isReady() { return ready; }
    public synchronized long getSession() { return session; }

    public synchronized void start(Workspace ws) throws IOException {
        stop();
        long startSession = ++session;

        String lspExe = resolveLanguageServerExecutable();
        ctx.getOutputPane().appendLspLog("[lsp] starting " + lspExe);
        ProcessBuilder pb = new ProcessBuilder(lspExe, "--stdio")
                .directory(ws.getRoot().toFile())
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
        init.setRootUri(ws.getRoot().toUri().toString());
        WorkspaceFolder folder = new WorkspaceFolder(ws.getRoot().toUri().toString(),
                ws.getRoot().getFileName() == null ? "root" : ws.getRoot().getFileName().toString());
        init.setWorkspaceFolders(Collections.singletonList(folder));

        ClientCapabilities caps = new ClientCapabilities();
        TextDocumentClientCapabilities td = new TextDocumentClientCapabilities();
        td.setSynchronization(new org.eclipse.lsp4j.SynchronizationCapabilities(false, false, false));
        td.setHover(new org.eclipse.lsp4j.HoverCapabilities());

        CompletionCapabilities completion = new CompletionCapabilities();
        CompletionItemCapabilities itemCaps = new CompletionItemCapabilities();
        itemCaps.setSnippetSupport(false);
        completion.setCompletionItem(itemCaps);
        td.setCompletion(completion);

        td.setPublishDiagnostics(new org.eclipse.lsp4j.PublishDiagnosticsCapabilities());
        td.setDefinition(new org.eclipse.lsp4j.DefinitionCapabilities());
        td.setFormatting(new org.eclipse.lsp4j.FormattingCapabilities());
        td.setCodeLens(new CodeLensCapabilities());
        td.setReferences(new ReferencesCapabilities());
        td.setInlayHint(new InlayHintCapabilities());

        RenameCapabilities rename = new RenameCapabilities();
        rename.setPrepareSupport(true);
        td.setRename(rename);

        CallHierarchyCapabilities chCaps = new CallHierarchyCapabilities();
        td.setCallHierarchy(chCaps);

        caps.setTextDocument(td);
        WorkspaceClientCapabilities wc = new WorkspaceClientCapabilities();
        wc.setWorkspaceFolders(true);
        wc.setApplyEdit(true);
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

    /* ============================== completion ============================== */

    public CompletableFuture<List<CompletionItem>> completion(Path path, int line, int col) {
        return completion(path, line, col, null);
    }

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
        }).exceptionally(t -> Collections.emptyList());
    }

    /* ============================== hover ============================== */

    public CompletableFuture<String> hover(Path path, int line, int col) {
        if (!ready || server == null) return CompletableFuture.completedFuture("");
        HoverParams params = new HoverParams(
                new TextDocumentIdentifier(path.toUri().toString()),
                new Position(line, col));
        return server.getTextDocumentService().hover(params).thenApply(LspBridge::renderHover)
                .exceptionally(t -> "");
    }

    private static String renderHover(Hover h) {
        if (h == null || h.getContents() == null) return "";
        Either<List<Either<String, MarkedString>>, MarkupContent> contents = h.getContents();
        if (contents.isRight() && contents.getRight() != null) return contents.getRight().getValue();
        if (contents.isLeft() && contents.getLeft() != null) {
            StringBuilder sb = new StringBuilder();
            for (Either<String, MarkedString> e : contents.getLeft()) {
                if (sb.length() > 0) sb.append("\n");
                if (e.isLeft()) sb.append(e.getLeft());
                else if (e.isRight()) sb.append(e.getRight().getValue());
            }
            return sb.toString();
        }
        return "";
    }

    /* ============================== formatting ============================== */

    public CompletableFuture<List<? extends TextEdit>> format(Path path, int tabSize, boolean insertSpaces) {
        if (!ready || server == null) return CompletableFuture.completedFuture(Collections.emptyList());
        FormattingOptions opts = new FormattingOptions(tabSize, insertSpaces);
        DocumentFormattingParams params = new DocumentFormattingParams(
                new TextDocumentIdentifier(path.toUri().toString()),
                opts);
        return server.getTextDocumentService().formatting(params)
                .exceptionally(t -> Collections.emptyList());
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
                return locs != null && !locs.isEmpty() ? locs.get(0) : null;
            }
            if (either.isRight()) {
                List<? extends LocationLink> links = either.getRight();
                if (links != null && !links.isEmpty()) {
                    LocationLink link = links.get(0);
                    return new Location(link.getTargetUri(), link.getTargetSelectionRange());
                }
            }
            return null;
        }).exceptionally(t -> null);
    }

    /* ============================== code lens ============================== */

    public CompletableFuture<List<? extends CodeLens>> codeLens(Path path) {
        if (!ready || server == null) return CompletableFuture.completedFuture(Collections.emptyList());
        CodeLensParams params = new CodeLensParams(new TextDocumentIdentifier(path.toUri().toString()));
        return server.getTextDocumentService().codeLens(params)
                .thenApply(list -> list == null ? Collections.<CodeLens>emptyList() : list)
                .exceptionally(t -> Collections.emptyList());
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
                .exceptionally(t -> Collections.emptyList());
    }

    public CompletableFuture<List<? extends Location>> references(Path path, int line, int col, boolean includeDeclaration) {
        if (!ready || server == null) return CompletableFuture.completedFuture(Collections.emptyList());
        ReferenceParams params = new ReferenceParams();
        params.setTextDocument(new TextDocumentIdentifier(path.toUri().toString()));
        params.setPosition(new Position(line, col));
        params.setContext(new ReferenceContext(includeDeclaration));
        return server.getTextDocumentService().references(params)
                .thenApply(list -> list == null ? Collections.<Location>emptyList() : list)
                .exceptionally(t -> Collections.emptyList());
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
        }).exceptionally(t -> null);
    }

    public CompletableFuture<WorkspaceEdit> rename(Path path, int line, int col, String newName) {
        if (!ready || server == null) return CompletableFuture.completedFuture(null);
        RenameParams params = new RenameParams();
        params.setTextDocument(new TextDocumentIdentifier(path.toUri().toString()));
        params.setPosition(new Position(line, col));
        params.setNewName(newName);
        return server.getTextDocumentService().rename(params).exceptionally(t -> null);
    }

    public static final class PrepareInfo {
        public final Range range;
        public final String placeholder;
        public PrepareInfo(Range r, String p) { this.range = r; this.placeholder = p; }
    }

    /* ============================== call hierarchy ============================== */

    public CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchy(Path path, int line, int col) {
        if (!ready || server == null) return CompletableFuture.completedFuture(Collections.emptyList());
        CallHierarchyPrepareParams params = new CallHierarchyPrepareParams();
        params.setTextDocument(new TextDocumentIdentifier(path.toUri().toString()));
        params.setPosition(new Position(line, col));
        return server.getTextDocumentService().prepareCallHierarchy(params)
                .thenApply(list -> list == null ? Collections.<CallHierarchyItem>emptyList() : list)
                .exceptionally(t -> Collections.emptyList());
    }

    public CompletableFuture<List<CallHierarchyIncomingCall>> incomingCalls(CallHierarchyItem item) {
        if (!ready || server == null) return CompletableFuture.completedFuture(Collections.emptyList());
        CallHierarchyIncomingCallsParams params = new CallHierarchyIncomingCallsParams();
        params.setItem(item);
        return server.getTextDocumentService().callHierarchyIncomingCalls(params)
                .thenApply(list -> list == null ? Collections.<CallHierarchyIncomingCall>emptyList() : list)
                .exceptionally(t -> Collections.emptyList());
    }

    public CompletableFuture<List<CallHierarchyOutgoingCall>> outgoingCalls(CallHierarchyItem item) {
        if (!ready || server == null) return CompletableFuture.completedFuture(Collections.emptyList());
        CallHierarchyOutgoingCallsParams params = new CallHierarchyOutgoingCallsParams();
        params.setItem(item);
        return server.getTextDocumentService().callHierarchyOutgoingCalls(params)
                .thenApply(list -> list == null ? Collections.<CallHierarchyOutgoingCall>emptyList() : list)
                .exceptionally(t -> Collections.emptyList());
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
                .exceptionally(t -> Collections.emptyList());
    }

    public CompletableFuture<Object> executeCommand(String command, List<Object> args) {
        if (!ready || server == null) return CompletableFuture.completedFuture(null);
        org.eclipse.lsp4j.ExecuteCommandParams params = new org.eclipse.lsp4j.ExecuteCommandParams();
        params.setCommand(command);
        params.setArguments(args == null ? Collections.emptyList() : args);
        return server.getWorkspaceService().executeCommand(params)
                .exceptionally(t -> null);
    }
}
