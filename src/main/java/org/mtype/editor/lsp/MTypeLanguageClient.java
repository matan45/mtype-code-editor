package org.mtype.editor.lsp;

import javafx.application.Platform;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.services.LanguageClient;
import org.mtype.editor.app.AppContext;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class MTypeLanguageClient implements LanguageClient {
    private final AppContext ctx;
    private final DiagnosticsRenderer renderer;

    public MTypeLanguageClient(AppContext ctx) {
        this.ctx = ctx;
        this.renderer = new DiagnosticsRenderer(ctx);
    }

    @Override
    public void telemetryEvent(Object object) {}

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        List<Diagnostic> diags = diagnostics.getDiagnostics() == null
                ? Collections.emptyList()
                : diagnostics.getDiagnostics();
        String logLine = diagnosticLogLine(diagnostics.getUri(), diags);
        ctx.getDiagnosticsBus().publish(diagnostics);
        Platform.runLater(() -> {
            ctx.getOutputPane().appendLspLog(logLine);
            try { renderer.apply(diagnostics); }
            catch (Exception ex) {
                ctx.getOutputPane().appendLspLog("[diag render error] " + ex.getMessage());
            }
        });
    }

    private static String diagnosticLogLine(String uri, List<Diagnostic> diagnostics) {
        int errors = 0;
        int warnings = 0;
        int infos = 0;
        int hints = 0;
        for (Diagnostic d : diagnostics) {
            DiagnosticSeverity severity = d.getSeverity() == null
                    ? DiagnosticSeverity.Error
                    : d.getSeverity();
            switch (severity) {
                case Error -> errors++;
                case Warning -> warnings++;
                case Information -> infos++;
                case Hint -> hints++;
            }
        }
        return "[diag] " + shortName(uri)
                + " (" + diagnostics.size()
                + ", errors=" + errors
                + ", warnings=" + warnings
                + ", info=" + infos
                + ", hints=" + hints
                + ")";
    }

    private static String shortName(String uri) {
        if (uri == null) return "<no uri>";
        int slash = uri.lastIndexOf('/');
        return slash >= 0 ? uri.substring(slash + 1) : uri;
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
