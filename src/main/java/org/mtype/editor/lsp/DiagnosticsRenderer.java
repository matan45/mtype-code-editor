package org.mtype.editor.lsp;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.syntax.MTypeStyles;
import org.mtype.editor.ui.editor.EditorTab;

import java.net.URI;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

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
            return;
        }
        EditorTab tab = ctx.getTabPane().findByPath(path);
        if (tab == null) return;

        CodeArea area = tab.getCodeArea();
        String text = area.getText();
        int len = text.length();
        if (len == 0) {
            tab.applyDiagnosticSpans(new StyleSpansBuilder<Collection<String>>().create());
            return;
        }

        List<Diagnostic> diags = params.getDiagnostics();
        diags.sort(Comparator
                .comparingInt((Diagnostic d) -> d.getRange().getStart().getLine())
                .thenComparingInt(d -> d.getRange().getStart().getCharacter()));

        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        int cursor = 0;
        for (Diagnostic d : diags) {
            int start = offsetOf(text, d.getRange().getStart().getLine(), d.getRange().getStart().getCharacter());
            int end = offsetOf(text, d.getRange().getEnd().getLine(), d.getRange().getEnd().getCharacter());
            if (end <= start) end = Math.min(start + 1, len);
            start = Math.max(0, Math.min(start, len));
            end = Math.max(start, Math.min(end, len));
            if (start < cursor) continue; // skip overlapping ranges
            b.add(Collections.emptyList(), start - cursor);
            b.add(Collections.singleton(styleFor(d.getSeverity())), end - start);
            cursor = end;
        }
        b.add(Collections.emptyList(), len - cursor);

        StyleSpans<Collection<String>> spans = b.create();
        tab.applyDiagnosticSpans(spans);
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
        int idx = 0;
        int curLine = 0;
        while (curLine < line && idx < text.length()) {
            if (text.charAt(idx) == '\n') curLine++;
            idx++;
        }
        return Math.min(text.length(), idx + col);
    }
}
