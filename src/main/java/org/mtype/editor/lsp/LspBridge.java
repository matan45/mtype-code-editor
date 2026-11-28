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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Owns the language-server process and every message sent to it.
 *
 * <p><b>Ordering (MYT-422).</b> Each session has one {@link Outbox}: a single outgoing thread
 * ({@code mtype-lsp-out}) writes every notification and request, one at a time, in the order the
 * public methods were called. Callers never write to the pipe themselves, the FX thread included. A
 * caller that queues a didChange and then a request from the same thread therefore puts them on the
 * wire in that order, and the server (which reads messages one by one) answers the request against
 * that text. Parameters, including the document text, are captured when the call is made.
 *
 * <p><b>Versions.</b> The outbox remembers the last version sent per document and drops a didChange
 * that is not newer ({@link DocumentVersions}); a didOpen resets it.
 *
 * <p><b>Requests</b> return a future that always completes: with the mapped response, with the
 * method's fallback when the server fails, the request can't be sent or the session is closing, or
 * cancelled by the caller. Cancelling before the request is written means it is never sent; after,
 * lsp4j writes {@code $/cancelRequest} (on the outgoing thread).
 */
public class LspBridge {
    private final AppContext ctx;
    /** Where the bridge reports dropped or failed sends. Called on the outgoing thread. */
    private final Consumer<String> log;

    private Process process;
    private Launcher<LanguageServer> launcher;
    private Future<?> listening;
    private ExecutorService rpcExecutor;
    private MTypeLanguageClient client;
    /** The session whose initialize is in flight (or failed), so {@link #stop()} can tear it down. */
    private Outbox starting;
    /** The ready session; null while stopped or initializing. */
    private volatile Outbox outbox;
    private volatile long session;
    private volatile SemanticTokensLegend semanticLegend;
    /** The server's completion trigger characters (MYT-424); empty until a session is initialized. */
    private volatile Set<String> completionTriggers = Set.of();
    private Map<String, Long> watchedMtFiles = Collections.emptyMap();

    public LspBridge(AppContext ctx) {
        this.ctx = ctx;
        this.log = line -> {
            if (ctx.getOutputPane() != null) ctx.getOutputPane().appendLspLog(line);
        };
    }

    /**
     * A bridge that is ready at once and talks to {@code server} through {@code out}, for tests. No
     * process and no JavaFX. {@code out} must run one task at a time, in submission order (the
     * ordering and cancellation guarantees depend on it).
     */
    LspBridge(LanguageServer server, Executor out, Consumer<String> log) {
        this.ctx = null;
        this.log = log;
        this.session = 1;
        this.outbox = new Outbox(server, out, null);
    }

    public boolean isReady() { return outbox != null; }
    public long getSession() { return session; }
    public SemanticTokensLegend getSemanticLegend() { return semanticLegend; }
    /** The single-character completion triggers the server advertised; never null, empty before init. */
    public Set<String> getCompletionTriggerCharacters() { return completionTriggers; }

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

        ExecutorService outThread = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mtype-lsp-out");
            t.setDaemon(true);
            return t;
        });
        Outbox box = new Outbox(startedServer, outThread, outThread);
        starting = box;

        InitializeParams init = new InitializeParams();
        init.setProcessId((int) ProcessHandle.current().pid());
        String rootUri = ws.root().toUri().toString();
        setLegacyWorkspaceRoot(init, rootUri, ws.root().toString());
        WorkspaceFolder folder = new WorkspaceFolder(rootUri,
                ws.root().getFileName() == null ? "root" : ws.root().getFileName().toString());
        init.setWorkspaceFolders(Collections.singletonList(folder));

        ClientCapabilities caps = new ClientCapabilities();
        TextDocumentClientCapabilities td = new TextDocumentClientCapabilities();
        td.setSynchronization(new org.eclipse.lsp4j.SynchronizationCapabilities(false, false, false));
        td.setHover(new org.eclipse.lsp4j.HoverCapabilities());

        td.setCompletion(completionCapabilities());

        td.setPublishDiagnostics(new org.eclipse.lsp4j.PublishDiagnosticsCapabilities());
        td.setDefinition(new org.eclipse.lsp4j.DefinitionCapabilities());
        td.setFormatting(new org.eclipse.lsp4j.FormattingCapabilities());
        td.setCodeLens(new CodeLensCapabilities());
        td.setReferences(new ReferencesCapabilities());
        td.setInlayHint(new InlayHintCapabilities());
        td.setSignatureHelp(new SignatureHelpCapabilities());
        td.setDocumentSymbol(new DocumentSymbolCapabilities());

        SemanticTokensCapabilities stCaps = getSemanticTokensCapabilities();
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

        // initialize is written by the outgoing thread like everything else; its response is handled
        // on the FX thread, which is where start()/stop() run, so the two never contend for the lock.
        CompletableFuture<InitializeResult> initResponse = new CompletableFuture<>();
        boolean queued = box.execute(() -> {
            try {
                startedServer.initialize(init).whenComplete((result, err) -> {
                    if (err != null) initResponse.completeExceptionally(err);
                    else initResponse.complete(result);
                });
            } catch (RuntimeException e) {
                initResponse.completeExceptionally(e);
            }
        });
        if (!queued) initResponse.completeExceptionally(new RejectedExecutionException("LSP outbox closed"));
        initResponse.whenCompleteAsync(
                (result, err) -> onInitialized(box, startSession, result, err),
                Platform::runLater);
    }

    /** FX thread: the initialize response (or its failure) for the session {@code box} belongs to. */
    private void onInitialized(Outbox box, long startSession, InitializeResult result, Throwable err) {
        synchronized (this) {
            if (starting != box || session != startSession) return;
            if (err == null) {
                // Queued before the outbox is published, so every didOpen and request queues behind it.
                box.execute(() -> send(box, b -> b.server.initialized(new InitializedParams())));
                if (result != null && result.getCapabilities() != null
                        && result.getCapabilities().getSemanticTokensProvider() != null) {
                    semanticLegend = result.getCapabilities().getSemanticTokensProvider().getLegend();
                }
                completionTriggers = completionTriggerCharacters(result);
                starting = null;
                outbox = box;
            }
            // On error the session stays in `starting`, so stop() still shuts it down.
        }
        if (err != null) {
            ctx.getStatusBar().setLspState("LSP: error");
            ctx.getOutputPane().appendLspLog("[init error] " + err.getMessage());
            return;
        }
        ctx.getStatusBar().setLspState("LSP: ready");
        if (ctx.getTabPane() != null) ctx.getTabPane().syncOpenDocumentsWithLsp();
    }

    /** What the client supports in textDocument/completion. */
    static CompletionCapabilities completionCapabilities() {
        CompletionCapabilities completion = new CompletionCapabilities();
        CompletionItemCapabilities itemCaps = new CompletionItemCapabilities();
        itemCaps.setSnippetSupport(true);
        // Accepting an item applies it as received, with no completionItem/resolve (MYT-423), so only the
        // documentation may be left for resolve; additionalTextEdits (auto-imports) and detail must come
        // with the list.
        itemCaps.setResolveSupport(new CompletionItemResolveSupportCapabilities(List.of("documentation")));
        completion.setCompletionItem(itemCaps);
        // Every request carries a CompletionContext whose trigger kind says what caused it (MYT-424).
        completion.setContextSupport(true);
        return completion;
    }

    /**
     * The completion trigger characters in the server's initialize result (MYT-424). Only single
     * characters are kept, since the editor matches them against the one character typed. Never null.
     */
    static Set<String> completionTriggerCharacters(InitializeResult result) {
        if (result == null || result.getCapabilities() == null) return Set.of();
        CompletionOptions options = result.getCapabilities().getCompletionProvider();
        if (options == null || options.getTriggerCharacters() == null) return Set.of();
        Set<String> triggers = new LinkedHashSet<>();
        for (String trigger : options.getTriggerCharacters()) {
            if (trigger != null && trigger.length() == 1) triggers.add(trigger);
        }
        return Collections.unmodifiableSet(triggers);
    }

    private static SemanticTokensCapabilities getSemanticTokensCapabilities() {
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
        return stCaps;
    }

    @SuppressWarnings("deprecation")
    private static void setLegacyWorkspaceRoot(InitializeParams init, String rootUri, String rootPath) {
        // The mType LSP still uses rootUri/rootPath to initialize project config
        // and mt_modules aliases, while modern clients use workspaceFolders.
        init.setRootUri(rootUri);
        init.setRootPath(rootPath);
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
        Outbox box = outbox != null ? outbox : starting;
        outbox = null;
        starting = null;
        Future<?> listeningToStop = listening;
        Process processToStop = process;
        ExecutorService executorToStop = rpcExecutor;

        listening = null;
        process = null;
        rpcExecutor = null;
        client = null;
        launcher = null;
        watchedMtFiles = Collections.emptyMap();

        if (box != null) {
            // From here on, queued requests complete with their fallback without being sent. Queued
            // notifications (e.g. the didClose of every tab closed on exit) are still written, in
            // order, ahead of shutdown/exit.
            box.closing = true;
            FutureTask<Void> teardown = new FutureTask<>(() -> {
                try { box.server.shutdown().get(1, TimeUnit.SECONDS); } catch (Exception ignored) {}
                try { box.server.exit(); } catch (Exception ignored) {}
                return null;
            });
            if (box.execute(teardown)) {
                try {
                    teardown.get(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception ignored) {
                }
            }
            box.dead = true;
        }
        if (listeningToStop != null) {
            listeningToStop.cancel(true);
        }
        if (processToStop != null) {
            // Destroy BEFORE closing the streams: an outgoing write blocked on a full pipe holds the
            // stream's lock, and close() (which flushes) would wait for it forever. Killing the
            // process breaks the pipe and fails that write.
            try {
                processToStop.destroy();
                if (!processToStop.waitFor(2, TimeUnit.SECONDS)) processToStop.destroyForcibly();
            } catch (InterruptedException e) {
                processToStop.destroyForcibly();
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
                processToStop.destroyForcibly();
            }
            try { processToStop.getOutputStream().close(); } catch (Exception ignored) {}
            try { processToStop.getInputStream().close(); } catch (Exception ignored) {}
        }
        if (executorToStop != null) {
            executorToStop.shutdownNow();
        }
        if (box != null && box.service != null) {
            box.service.shutdownNow();
        }
    }

    /* ============================== outbox ============================== */

    /**
     * One session's outgoing side: every notification and request is written by {@link #out}, one
     * task at a time, in submission order.
     */
    static final class Outbox {
        final LanguageServer server;
        private final Executor out;
        /** Shut down with the session; null when the caller owns {@link #out} (tests). */
        final ExecutorService service;
        /** Last version sent per URI. Touched only by tasks running on {@link #out}. */
        final DocumentVersions versions = new DocumentVersions();
        /** stop() has begun: queued requests complete with their fallback instead of being sent. */
        volatile boolean closing;
        /** shutdown/exit were sent: nothing else may be written. */
        volatile boolean dead;

        Outbox(LanguageServer server, Executor out, ExecutorService service) {
            this.server = server;
            this.out = out;
            this.service = service;
        }

        /** Queue {@code task}; false when the session's executor no longer accepts work. */
        boolean execute(Runnable task) {
            try {
                out.execute(task);
                return true;
            } catch (RejectedExecutionException e) {
                return false;
            }
        }
    }

    /** Queue a notification on the ready session; does nothing when the LSP isn't ready. */
    private void post(Consumer<Outbox> notification) {
        Outbox box = outbox;
        if (box == null) return;
        box.execute(() -> send(box, notification));
    }

    /** Outgoing thread: write one notification unless the session already sent shutdown/exit. */
    private void send(Outbox box, Consumer<Outbox> notification) {
        if (box.dead) return;
        try {
            notification.accept(box);
        } catch (RuntimeException e) {
            log.accept("[lsp] send failed: " + e.getMessage());
        }
    }

    /**
     * Queue a request on the ready session. The returned future completes with {@code map} applied to
     * the response, or with {@code fallback} when the LSP isn't ready, the session is closing, the
     * request can't be sent, or the server answers with an error. Cancelling it before the request is
     * written means it is never sent; after, the lsp4j request is cancelled too ($/cancelRequest),
     * from the outgoing thread.
     */
    private <R, T> CompletableFuture<T> call(Function<LanguageServer, CompletableFuture<R>> send,
                                             Function<? super R, ? extends T> map, T fallback) {
        Outbox box = outbox;
        if (box == null) return CompletableFuture.completedFuture(fallback);
        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicReference<CompletableFuture<R>> sent = new AtomicReference<>();
        result.whenComplete((_, _) -> {
            if (!result.isCancelled()) return;
            box.execute(() -> {
                CompletableFuture<R> request = sent.get();
                if (box.dead || request == null || request.isDone()) return;
                try {
                    request.cancel(true);
                } catch (RuntimeException e) {
                    log.accept("[lsp] cancel failed: " + e.getMessage());
                }
            });
        });
        boolean queued = box.execute(() -> {
            if (result.isDone()) return;   // cancelled before it was sent: never written
            if (box.closing) {
                result.complete(fallback);
                return;
            }
            CompletableFuture<R> request;
            try {
                request = send.apply(box.server);
            } catch (RuntimeException e) {
                log.accept("[lsp] request failed: " + e.getMessage());
                request = null;
            }
            if (request == null) {
                result.complete(fallback);
                return;
            }
            sent.set(request);
            request.whenComplete((response, err) -> {
                T value = fallback;
                if (err == null) {
                    try {
                        value = map.apply(response);
                    } catch (RuntimeException ignored) {
                    }
                }
                result.complete(value);
            });
        });
        if (!queued) result.complete(fallback);
        return result;
    }

    /* ============================== sync ============================== */

    public void didOpen(Path path, String text, int version) {
        String uri = path.toUri().toString();
        DidOpenTextDocumentParams params = new DidOpenTextDocumentParams(
                new TextDocumentItem(uri, "mtype", version, text));
        post(box -> {
            box.versions.opened(uri, version);
            box.server.getTextDocumentService().didOpen(params);
        });
    }

    public void didChange(Path path, String text, int version) {
        String uri = path.toUri().toString();
        VersionedTextDocumentIdentifier id = new VersionedTextDocumentIdentifier(uri, version);
        TextDocumentContentChangeEvent change = new TextDocumentContentChangeEvent(text);
        DidChangeTextDocumentParams params = new DidChangeTextDocumentParams(id, Collections.singletonList(change));
        post(box -> {
            if (!box.versions.acceptChange(uri, version)) {
                log.accept("[lsp] dropped stale didChange v" + version + " for " + path.getFileName()
                        + " (last sent v" + box.versions.lastSent(uri) + ")");
                return;
            }
            box.server.getTextDocumentService().didChange(params);
        });
    }

    public void didClose(Path path) {
        String uri = path.toUri().toString();
        DidCloseTextDocumentParams params = new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri));
        post(box -> {
            box.versions.closed(uri);
            box.server.getTextDocumentService().didClose(params);
        });
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
        if (changes == null || changes.isEmpty()) return;
        DidChangeWatchedFilesParams params = new DidChangeWatchedFilesParams(new ArrayList<>(changes));
        post(box -> box.server.getWorkspaceService().didChangeWatchedFiles(params));
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

    /**
     * textDocument/completion at a SOURCE position. {@code triggerChar} is the trigger character whose
     * typing caused this very request (TriggerCharacter); null or empty means Invoked: identifier typing,
     * backspace or Ctrl+Space (MYT-424).
     */
    public CompletableFuture<List<CompletionItem>> completion(Path path, int line, int col, String triggerChar) {
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
        return call(s -> s.getTextDocumentService().completion(params), either -> {
            if (either == null) return Collections.emptyList();
            if (either.isLeft()) return either.getLeft();
            CompletionList list = either.getRight();
            return list != null ? list.getItems() : Collections.emptyList();
        }, Collections.emptyList());
    }

    /* ============================== hover ============================== */

    public CompletableFuture<String> hover(Path path, int line, int col) {
        HoverParams params = new HoverParams(
                new TextDocumentIdentifier(path.toUri().toString()),
                new Position(line, col));
        return call(s -> s.getTextDocumentService().hover(params), LspBridge::renderHover, "");
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
        FormattingOptions opts = new FormattingOptions(tabSize, insertSpaces);
        DocumentFormattingParams params = new DocumentFormattingParams(
                new TextDocumentIdentifier(path.toUri().toString()),
                opts);
        return call(s -> s.getTextDocumentService().formatting(params), edits -> edits,
                Collections.emptyList());
    }

    /* ============================== definition ============================== */

    public CompletableFuture<Location> definition(Path path, int line, int col) {
        DefinitionParams params = new DefinitionParams(
                new TextDocumentIdentifier(path.toUri().toString()),
                new Position(line, col));
        return call(s -> s.getTextDocumentService().definition(params), either -> {
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
        }, null);
    }

    /* ============================== code lens ============================== */

    public CompletableFuture<List<? extends CodeLens>> codeLens(Path path) {
        CodeLensParams params = new CodeLensParams(new TextDocumentIdentifier(path.toUri().toString()));
        return call(s -> s.getTextDocumentService().codeLens(params),
                list -> list == null ? Collections.emptyList() : list,
                Collections.emptyList());
    }

    public CompletableFuture<List<InlayHint>> inlayHints(Path path, Range range) {
        if (range == null) return CompletableFuture.completedFuture(Collections.emptyList());
        InlayHintParams params = new InlayHintParams();
        params.setTextDocument(new TextDocumentIdentifier(path.toUri().toString()));
        params.setRange(range);
        return call(s -> s.getTextDocumentService().inlayHint(params),
                list -> list == null ? Collections.emptyList() : list,
                Collections.emptyList());
    }

    public CompletableFuture<List<? extends Location>> references(Path path, int line, int col, boolean includeDeclaration) {
        ReferenceParams params = new ReferenceParams();
        params.setTextDocument(new TextDocumentIdentifier(path.toUri().toString()));
        params.setPosition(new Position(line, col));
        params.setContext(new ReferenceContext(includeDeclaration));
        return call(s -> s.getTextDocumentService().references(params),
                list -> list == null ? Collections.emptyList() : list,
                Collections.emptyList());
    }

    /* ============================== rename ============================== */

    public CompletableFuture<PrepareInfo> prepareRename(Path path, int line, int col) {
        PrepareRenameParams params = new PrepareRenameParams();
        params.setTextDocument(new TextDocumentIdentifier(path.toUri().toString()));
        params.setPosition(new Position(line, col));
        return call(s -> s.getTextDocumentService().prepareRename(params), either -> {
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
        }, null);
    }

    public CompletableFuture<WorkspaceEdit> rename(Path path, int line, int col, String newName) {
        RenameParams params = new RenameParams();
        params.setTextDocument(new TextDocumentIdentifier(path.toUri().toString()));
        params.setPosition(new Position(line, col));
        params.setNewName(newName);
        return call(s -> s.getTextDocumentService().rename(params), edit -> edit, null);
    }

    public record PrepareInfo(Range range, String placeholder) {
    }

    /* ============================== call hierarchy ============================== */

    public CompletableFuture<List<CallHierarchyItem>> prepareCallHierarchy(Path path, int line, int col) {
        CallHierarchyPrepareParams params = new CallHierarchyPrepareParams();
        params.setTextDocument(new TextDocumentIdentifier(path.toUri().toString()));
        params.setPosition(new Position(line, col));
        return call(s -> s.getTextDocumentService().prepareCallHierarchy(params),
                list -> list == null ? Collections.emptyList() : list,
                Collections.emptyList());
    }

    public CompletableFuture<List<CallHierarchyIncomingCall>> incomingCalls(CallHierarchyItem item) {
        CallHierarchyIncomingCallsParams params = new CallHierarchyIncomingCallsParams();
        params.setItem(item);
        return call(s -> s.getTextDocumentService().callHierarchyIncomingCalls(params),
                list -> list == null ? Collections.emptyList() : list,
                Collections.emptyList());
    }

    public CompletableFuture<List<CallHierarchyOutgoingCall>> outgoingCalls(CallHierarchyItem item) {
        CallHierarchyOutgoingCallsParams params = new CallHierarchyOutgoingCallsParams();
        params.setItem(item);
        return call(s -> s.getTextDocumentService().callHierarchyOutgoingCalls(params),
                list -> list == null ? Collections.emptyList() : list,
                Collections.emptyList());
    }

    /* ============================== code actions ============================== */

    public CompletableFuture<List<Either<org.eclipse.lsp4j.Command, org.eclipse.lsp4j.CodeAction>>>
    codeAction(Path path, Range range, List<org.eclipse.lsp4j.Diagnostic> contextDiagnostics) {
        org.eclipse.lsp4j.CodeActionParams params = new org.eclipse.lsp4j.CodeActionParams();
        params.setTextDocument(new TextDocumentIdentifier(path.toUri().toString()));
        params.setRange(range);
        org.eclipse.lsp4j.CodeActionContext cctx = new org.eclipse.lsp4j.CodeActionContext();
        cctx.setDiagnostics(contextDiagnostics == null ? Collections.emptyList() : contextDiagnostics);
        params.setContext(cctx);
        return call(s -> s.getTextDocumentService().codeAction(params),
                list -> list == null ? Collections.emptyList() : list,
                Collections.emptyList());
    }

    /* ============================== signature help ============================== */

    public CompletableFuture<SignatureHelp> signatureHelp(Path path, int line, int col, String triggerChar) {
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
        return call(s -> s.getTextDocumentService().signatureHelp(params), help -> help, null);
    }

    /* ============================== document symbols ============================== */

    public CompletableFuture<List<DocumentSymbol>> documentSymbol(Path path) {
        DocumentSymbolParams params = new DocumentSymbolParams(
                new TextDocumentIdentifier(path.toUri().toString()));
        return call(s -> s.getTextDocumentService().documentSymbol(params), list -> {
            if (list == null || list.isEmpty()) return Collections.emptyList();
            List<DocumentSymbol> out = new ArrayList<>(list.size());
            for (Either<SymbolInformation, DocumentSymbol> e : list) {
                if (e != null && e.isRight() && e.getRight() != null) out.add(e.getRight());
            }
            return out;
        }, Collections.emptyList());
    }

    /* ============================== workspace symbols ============================== */

    public CompletableFuture<List<WorkspaceSymbol>> workspaceSymbol(String query) {
        WorkspaceSymbolParams params = new WorkspaceSymbolParams(query == null ? "" : query);
        // The mType server sends SymbolInformation-shaped entries ({name, kind, location}); lsp4j
        // decodes them as WorkspaceSymbol[] unless an element carries `deprecated`, in which case
        // they arrive on the legacy left side. Accept both shapes (MYT-447).
        return call(s -> s.getWorkspaceService().symbol(params), WorkspaceSymbols::fromEither,
                Collections.emptyList());
    }

    /* ============================== semantic tokens ============================== */

    public CompletableFuture<SemanticTokens> semanticTokensFull(Path path) {
        SemanticTokensParams params = new SemanticTokensParams(
                new TextDocumentIdentifier(path.toUri().toString()));
        return call(s -> s.getTextDocumentService().semanticTokensFull(params), tokens -> tokens, null);
    }

    public void executeCommand(String command, List<Object> args) {
        org.eclipse.lsp4j.ExecuteCommandParams params = new org.eclipse.lsp4j.ExecuteCommandParams();
        params.setCommand(command);
        params.setArguments(args == null ? Collections.emptyList() : args);
        call(s -> s.getWorkspaceService().executeCommand(params), _ -> null, null);
    }
}
