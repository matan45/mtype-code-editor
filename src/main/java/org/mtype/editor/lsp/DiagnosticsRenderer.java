package org.mtype.editor.lsp;

import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.syntax.MTypeStyles;
import org.mtype.editor.ui.editor.EditorTab;

import java.net.URI;
import java.nio.file.Path;

public class DiagnosticsRenderer {
    private final AppContext ctx;

    public DiagnosticsRenderer(AppContext ctx) {
        this.ctx = ctx;
    }

    public void apply(PublishDiagnosticsParams params) {
        Path path;
        try {
            path = Path.of(URI.create(params.getUri()));
        } catch (Exception e) {
            ctx.getOutputPane().appendLspLog("[diag bad uri] " + params.getUri());
            return;
        }
        EditorTab tab = ctx.getTabPane().findByPath(path);
        if (tab == null) {
            // Most likely a Windows path-case mismatch (server normalises drive letter).
            tab = findTabCaseInsensitive(path);
        }
        if (tab == null) return;

        // EditorTab owns span-building so it can re-map diagnostics to display offsets whenever inlay
        // hints are inserted/removed (which shifts display offsets).
        tab.applyDiagnostics(params.getDiagnostics());
    }

    private EditorTab findTabCaseInsensitive(Path target) {
        var pane = ctx.getTabPane();
        if (pane == null) return null;
        String targetStr = target.toAbsolutePath().toString().toLowerCase();
        for (var entry : pane.openTabs()) {
            if (entry.getPath().toAbsolutePath().toString().toLowerCase().equals(targetStr)) {
                return entry;
            }
        }
        return null;
    }

    public static DiagnosticSeverity worse(DiagnosticSeverity a, DiagnosticSeverity b) {
        return rank(a) <= rank(b) ? a : b;
    }

    private static int rank(DiagnosticSeverity s) {
        if (s == null) return 99;
        return switch (s) {
            case Error -> 0;
            case Warning -> 1;
            case Information -> 2;
            case Hint -> 3;
        };
    }

    public static String styleFor(DiagnosticSeverity severity) {
        if (severity == null) return MTypeStyles.DIAG_ERROR;
        return switch (severity) {
            case Error -> MTypeStyles.DIAG_ERROR;
            case Warning -> MTypeStyles.DIAG_WARNING;
            case Information, Hint -> MTypeStyles.DIAG_INFO;
        };
    }

}
