package org.mtype.editor.lsp;

import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** A LanguageServer that records what the bridge wrote, in order, and lets the test answer requests. */
final class FakeLanguageServer implements LanguageServer {
    /** Every message written to the server, in order: "didOpen:1", "didChange:5", "completion", ... */
    final List<String> log = Collections.synchronizedList(new ArrayList<>());
    /** The future returned for each completion request, in order; the test completes or inspects them. */
    final List<CompletableFuture<Either<List<CompletionItem>, CompletionList>>> completions =
            Collections.synchronizedList(new ArrayList<>());
    /** The params of each completion request, in order (MYT-424: the trigger context it carried). */
    final List<CompletionParams> completionParams = Collections.synchronizedList(new ArrayList<>());
    /** When set, the next completion requests throw it instead of returning a future. */
    volatile RuntimeException completionThrows;

    private final TextDocumentService text = new TextDocumentService() {
        @Override public void didOpen(DidOpenTextDocumentParams params) {
            log.add("didOpen:" + params.getTextDocument().getVersion());
        }

        @Override public void didChange(DidChangeTextDocumentParams params) {
            log.add("didChange:" + params.getTextDocument().getVersion());
        }

        @Override public void didClose(DidCloseTextDocumentParams params) {
            log.add("didClose");
        }

        @Override public void didSave(DidSaveTextDocumentParams params) {
            log.add("didSave");
        }

        @Override
        public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
            log.add("completion");
            completionParams.add(params);
            RuntimeException failure = completionThrows;
            if (failure != null) throw failure;
            CompletableFuture<Either<List<CompletionItem>, CompletionList>> response = new CompletableFuture<>();
            completions.add(response);
            return response;
        }
    };

    private final WorkspaceService workspace = new WorkspaceService() {
        @Override public void didChangeConfiguration(DidChangeConfigurationParams params) {
            log.add("didChangeConfiguration");
        }

        @Override public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
            log.add("didChangeWatchedFiles");
        }
    };

    @Override public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        log.add("initialize");
        return CompletableFuture.completedFuture(new InitializeResult());
    }

    @Override public CompletableFuture<Object> shutdown() {
        log.add("shutdown");
        return CompletableFuture.completedFuture(null);
    }

    @Override public void exit() {
        log.add("exit");
    }

    @Override public TextDocumentService getTextDocumentService() { return text; }

    @Override public WorkspaceService getWorkspaceService() { return workspace; }
}
