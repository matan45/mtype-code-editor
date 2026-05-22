package org.mtype.editor.lsp;

import javafx.application.Platform;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.mtype.editor.app.AppContext;

import java.util.concurrent.CompletableFuture;

public class MTypeLanguageClient implements LanguageClient {
    private final AppContext ctx;
    private DiagnosticsRenderer renderer;

    public MTypeLanguageClient(AppContext ctx) {
        this.ctx = ctx;
        this.renderer = new DiagnosticsRenderer(ctx);
    }

    @Override
    public void telemetryEvent(Object object) {}

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        Platform.runLater(() -> renderer.apply(diagnostics));
    }

    @Override
    public void showMessage(MessageParams messageParams) {
        ctx.getOutputPane().appendLspLog("[" + messageParams.getType() + "] " + messageParams.getMessage());
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
        ctx.getOutputPane().appendLspLog("[req] " + requestParams.getMessage());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void logMessage(MessageParams message) {
        ctx.getOutputPane().appendLspLog(message.getMessage());
    }
}
