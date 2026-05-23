package org.mtype.editor.lsp;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.syntax.MTypeStyles;
import org.mtype.editor.ui.editor.EditorTab;

import java.net.URI;
import java.nio.file.Path;
import java.util.*;

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

        CodeArea area = tab.getCodeArea();
        String text = area.getText();
        int len = text.length();

        List<Diagnostic> diags = params.getDiagnostics() == null
                ? Collections.emptyList()
                : new ArrayList<>(params.getDiagnostics());

        if (len == 0 || diags.isEmpty()) {
            // RichTextFX throws "No spans have been added" on an empty builder —
            // seed a zero-length empty span so create() succeeds and clears any
            // previously-overlaid diagnostic styles.
            StyleSpansBuilder<Collection<String>> empty = new StyleSpansBuilder<>();
            empty.add(Collections.emptyList(), Math.max(len, 1));
            tab.applyDiagnosticSpans(empty.create());
            tab.applyDiagnosticLines(Collections.emptyMap());
            return;
        }

        diags.sort(Comparator
                .comparingInt((Diagnostic d) -> d.getRange().getStart().getLine())
                .thenComparingInt(d -> d.getRange().getStart().getCharacter()));

        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        int cursor = 0;
        Map<Integer, DiagnosticSeverity> linesBySeverity = new HashMap<>();
        for (Diagnostic d : diags) {
            int start = offsetOf(text, d.getRange().getStart().getLine(), d.getRange().getStart().getCharacter());
            int end = offsetOf(text, d.getRange().getEnd().getLine(), d.getRange().getEnd().getCharacter());
            if (end <= start) end = Math.min(start + 1, len);
            start = Math.max(0, Math.min(start, len));
            end = Math.max(start, Math.min(end, len));

            // Track worst severity per source line for gutter markers.
            int startLine = d.getRange().getStart().getLine();
            int endLine = d.getRange().getEnd().getLine();
            DiagnosticSeverity sev = d.getSeverity() == null ? DiagnosticSeverity.Error : d.getSeverity();
            for (int line = startLine; line <= endLine; line++) {
                linesBySeverity.merge(line, sev, DiagnosticsRenderer::worse);
            }

            if (start < cursor) continue; // skip overlapping spans (style overlay can't handle them)
            b.add(Collections.emptyList(), start - cursor);
            b.add(Collections.singleton(styleFor(sev)), end - start);
            cursor = end;
        }
        b.add(Collections.emptyList(), len - cursor);

        tab.applyDiagnosticSpans(b.create());
        tab.applyDiagnosticLines(linesBySeverity);
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

    private static DiagnosticSeverity worse(DiagnosticSeverity a, DiagnosticSeverity b) {
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

    private static String styleFor(DiagnosticSeverity severity) {
        if (severity == null) return MTypeStyles.DIAG_ERROR;
        return switch (severity) {
            case Error -> MTypeStyles.DIAG_ERROR;
            case Warning -> MTypeStyles.DIAG_WARNING;
            case Information, Hint -> MTypeStyles.DIAG_INFO;
        };
    }

    private static int offsetOf(String text, int line, int col) {
        return Positions.offset(text, line, col);
    }
}
