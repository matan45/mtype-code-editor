package org.mtype.editor.ui.editor;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.mtype.editor.lsp.DiagnosticsRenderer;
import org.mtype.editor.lsp.Positions;

import java.util.*;

/**
 * Holds the latest server-pushed diagnostics for this document and rebuilds the squiggle overlay
 * (pushed into {@link StyleCompositor}) plus the per-line gutter severity map. Diagnostic ranges are
 * stored in SOURCE coordinates and re-mapped to DISPLAY offsets on every rebuild, so this is re-run
 * after inlay-hint segments shift offsets (see {@link EditorTab#runHintMutation}).
 */
final class DiagnosticsController {
    private final MTypeCodeArea codeArea;
    private final StyleCompositor compositor;
    private final Runnable gutterRefresh;

    private List<Diagnostic> lastDiagnostics = Collections.emptyList();
    private final Map<Integer, DiagnosticSeverity> diagnosticsByLine = new HashMap<>();

    DiagnosticsController(MTypeCodeArea codeArea, StyleCompositor compositor, Runnable gutterRefresh) {
        this.codeArea = codeArea;
        this.compositor = compositor;
        this.gutterRefresh = gutterRefresh;
    }

    /** Store the latest raw diagnostics and (re)build display-mapped spans + gutter markers. */
    void applyDiagnostics(List<Diagnostic> diags) {
        lastDiagnostics = diags == null ? Collections.emptyList() : new ArrayList<>(diags);
        rebuildDiagnosticStyles();
    }

    /** Worst diagnostic severity on the given source line, or null if none. Read by the gutter. */
    DiagnosticSeverity severityAt(int line) {
        return diagnosticsByLine.get(line);
    }

    /**
     * Rebuild diagnostic spans from the stored diagnostics, mapping LSP source ranges to current
     * display offsets. Called on each server push and again after inlay-hint segments shift offsets.
     */
    void rebuildDiagnosticStyles() {
        String source = codeArea.getSourceText();
        int len = codeArea.getLength(); // display length (spans overlay the hint-augmented document)
        List<Diagnostic> diags = new ArrayList<>(lastDiagnostics);
        if (len == 0 || diags.isEmpty()) {
            StyleSpansBuilder<Collection<String>> empty = new StyleSpansBuilder<>();
            empty.add(Collections.emptyList(), Math.max(len, 1));
            compositor.setDiagnostic(empty.create());
            applyDiagnosticLines(Collections.emptyMap());
            return;
        }
        diags.sort(Comparator
                .comparingInt((Diagnostic d) -> d.getRange().getStart().getLine())
                .thenComparingInt(d -> d.getRange().getStart().getCharacter()));
        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        int cursor = 0;
        Map<Integer, DiagnosticSeverity> linesBySeverity = new HashMap<>();
        for (Diagnostic d : diags) {
            int start = codeArea.sourceToDisplay(Positions.offset(source,
                    d.getRange().getStart().getLine(), d.getRange().getStart().getCharacter()));
            int end = codeArea.sourceToDisplay(Positions.offset(source,
                    d.getRange().getEnd().getLine(), d.getRange().getEnd().getCharacter()));
            if (end <= start) end = Math.min(start + 1, len);
            start = Math.max(0, Math.min(start, len));
            end = Math.max(start, Math.min(end, len));
            int startLine = d.getRange().getStart().getLine();
            int endLine = d.getRange().getEnd().getLine();
            DiagnosticSeverity sev = d.getSeverity() == null ? DiagnosticSeverity.Error : d.getSeverity();
            for (int line = startLine; line <= endLine; line++) {
                linesBySeverity.merge(line, sev, DiagnosticsRenderer::worse);
            }
            if (start < cursor) continue; // skip overlapping spans (overlay can't handle them)
            b.add(Collections.emptyList(), start - cursor);
            b.add(Collections.singleton(DiagnosticsRenderer.styleFor(sev)), end - start);
            cursor = end;
        }
        b.add(Collections.emptyList(), len - cursor);
        compositor.setDiagnostic(b.create());
        applyDiagnosticLines(linesBySeverity);
    }

    private void applyDiagnosticLines(Map<Integer, DiagnosticSeverity> nextLines) {
        if (nextLines == null) nextLines = Collections.emptyMap();
        if (nextLines.equals(diagnosticsByLine)) return;
        diagnosticsByLine.clear();
        diagnosticsByLine.putAll(nextLines);
        gutterRefresh.run();
    }
}
