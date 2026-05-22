package org.mtype.editor.lsp;

import javafx.application.Platform;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MarkedString;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentClientCapabilities;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageServer;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.process.StreamPump;
import org.mtype.editor.workspace.Workspace;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class LspBridge {
    private final AppContext ctx;

    private Process process;
    private LanguageServer server;
    private Launcher<LanguageServer> launcher;
    private Future<?> listening;
    private MTypeLanguageClient client;
    private boolean ready;

    public LspBridge(AppContext ctx) {
        this.ctx = ctx;
    }

    public synchronized void start(Workspace ws) throws IOException {
        stop();

        String lspExe = ws.getSettings().toolchain.languageServer;
        ProcessBuilder pb = new ProcessBuilder(lspExe, "--stdio")
                .directory(ws.getRoot().toFile())
                .redirectErrorStream(false);
        process = pb.start();

        // Pump stderr to LSP log
        new StreamPump(process.getErrorStream(),
                line -> ctx.getOutputPane().appendLspLog("[stderr] " + line),
                "mtype-lsp-stderr").start();

        client = new MTypeLanguageClient(ctx);
        launcher = LSPLauncher.createClientLauncher(client, process.getInputStream(), process.getOutputStream());
        listening = launcher.startListening();
        server = launcher.getRemoteProxy();

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
        td.setCompletion(new org.eclipse.lsp4j.CompletionCapabilities());
        td.setPublishDiagnostics(new org.eclipse.lsp4j.PublishDiagnosticsCapabilities());
        caps.setTextDocument(td);
        WorkspaceClientCapabilities wc = new WorkspaceClientCapabilities();
        wc.setWorkspaceFolders(true);
        caps.setWorkspace(wc);
        init.setCapabilities(caps);

        server.initialize(init).whenComplete((InitializeResult result, Throwable err) -> {
            if (err != null) {
                Platform.runLater(() -> {
                    ctx.getStatusBar().setLspState("LSP: error");
                    ctx.getOutputPane().appendLspLog("[init error] " + err.getMessage());
                });
                return;
            }
            server.initialized(new InitializedParams());
            ready = true;
            Platform.runLater(() -> ctx.getStatusBar().setLspState("LSP: ready"));
        });
    }

    public synchronized void stop() {
        ready = false;
        if (server != null) {
            try {
                server.shutdown().get(1, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            try { server.exit(); } catch (Exception ignored) {}
        }
        if (listening != null) {
            listening.cancel(true);
            listening = null;
        }
        if (process != null) {
            try {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (Exception ignored) {
                process.destroyForcibly();
            }
            process = null;
        }
        server = null;
        client = null;
        launcher = null;
    }

    public void didOpen(Path path, String text, int version) {
        if (!ready || server == null) return;
        TextDocumentItem item = new TextDocumentItem(
                path.toUri().toString(),
                "mtype",
                version,
                text);
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

    public CompletableFuture<List<String>> completion(Path path, int line, int col) {
        if (!ready || server == null) return CompletableFuture.completedFuture(Collections.emptyList());
        CompletionParams params = new CompletionParams(
                new TextDocumentIdentifier(path.toUri().toString()),
                new Position(line, col));
        return server.getTextDocumentService().completion(params).thenApply(either -> {
            if (either == null) return Collections.<String>emptyList();
            List<CompletionItem> items;
            if (either.isLeft()) items = either.getLeft();
            else if (either.isRight() && either.getRight() != null) items = either.getRight().getItems();
            else items = Collections.emptyList();
            List<String> labels = new ArrayList<>(items.size());
            for (CompletionItem ci : items) {
                String label = ci.getInsertText() != null ? ci.getInsertText() : ci.getLabel();
                if (label != null) labels.add(label);
            }
            return labels;
        }).exceptionally(t -> Collections.emptyList());
    }

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
        if (contents.isRight() && contents.getRight() != null) {
            return contents.getRight().getValue();
        }
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
}
