package org.mtype.editor.ui.editor;

import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/**
 * Pure fold-region arithmetic for {@link CodeFoldingController} (no JavaFX, so it is unit-tested headless).
 * Lines are 0-based paragraph indices, which equal LSP source lines because folding never edits the text.
 */
final class FoldRegions {
    private FoldRegions() {
    }

    /** A foldable body: {@code header} stays visible and {@code header+1 .. lastHidden} get hidden. */
    record Region(int header, int lastHidden) {
    }

    /**
     * Foldable regions for every function/method/constructor symbol (recursively), keyed by header line.
     * When two symbols share a header line, the outer one wins.
     */
    static Map<Integer, Region> compute(List<DocumentSymbol> symbols, int lineCount, IntFunction<String> lineText) {
        Map<Integer, Region> out = new HashMap<>();
        collect(symbols, lineCount, lineText, out);
        return out;
    }

    private static void collect(List<DocumentSymbol> symbols, int lineCount, IntFunction<String> lineText,
                                Map<Integer, Region> out) {
        if (symbols == null) return;
        for (DocumentSymbol s : symbols) {
            if (s == null) continue;
            Range r = s.getRange();
            SymbolKind k = s.getKind();
            if (r != null && r.getStart() != null && r.getEnd() != null
                    && (k == SymbolKind.Function || k == SymbolKind.Method || k == SymbolKind.Constructor)) {
                Region region = regionFor(r.getStart().getLine(), r.getEnd().getLine(), lineCount, lineText);
                if (region != null) out.putIfAbsent(region.header(), region);
            }
            collect(s.getChildren(), lineCount, lineText, out);
        }
    }

    /**
     * The region for a symbol spanning {@code startLine..endLine}, or null if there is nothing to hide.
     * The header is the first line holding an opening brace (the signature line in K&amp;R style, the
     * brace line in Allman style). VS Code style: a closing line that starts with a closing brace stays
     * visible, so only the body is hidden.
     */
    static Region regionFor(int startLine, int endLine, int lineCount, IntFunction<String> lineText) {
        if (startLine < 0 || endLine <= startLine || endLine >= lineCount) return null;
        int header = -1;
        for (int line = startLine; line <= endLine; line++) {
            String text = lineText.apply(line);
            if (text != null && text.indexOf('{') >= 0) {
                header = line;
                break;
            }
        }
        if (header < 0) return null;
        String endText = lineText.apply(endLine);
        String closing = endText == null ? "" : endText.replace(String.valueOf(MTypeCodeArea.HINT_CHAR), "").strip();
        int lastHidden = closing.startsWith("}") ? endLine - 1 : endLine;
        return lastHidden > header ? new Region(header, lastHidden) : null;
    }

    /**
     * Rule M1: does an edit touch a fold, so that the fold must be opened? True when the edit removed
     * hidden text, when it now covers a hidden line ({@code p0..p1} = paragraphs holding the inserted text),
     * or when it added/removed a line break directly above a hidden line (Enter on a folded header).
     * Line-count changes BELOW a fold (e.g. Enter at column 0 of the closing brace) leave the fold valid.
     */
    static boolean touchesFold(boolean removedHadCollapsed, boolean structural, int p0, int p1, IntPredicate collapsed) {
        if (removedHadCollapsed) return true;
        for (int p = p0; p <= p1; p++) {
            if (collapsed.test(p)) return true;
        }
        return structural && collapsed.test(p1 + 1);
    }
}
