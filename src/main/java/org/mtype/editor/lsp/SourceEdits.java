package org.mtype.editor.lsp;

import org.eclipse.lsp4j.TextEdit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Pure string-level helpers for applying LSP {@link TextEdit}s minimally (no JavaFX, so they are
 * unit-tested headless).
 *
 * <p>The mType server answers {@code textDocument/formatting} with ONE edit that replaces the whole
 * document. Applying it verbatim resets every paragraph style in the editor (folds, code-lens padding,
 * the debugger execution line), moves the caret, and reports a change even when the formatted text is
 * identical. {@link #minimize} shrinks each edit to the spans that really differ, so the editor is only
 * touched where the text actually changes.
 */
final class SourceEdits {
    private SourceEdits() {
    }

    /** Replace {@code [start, end)} with {@code text}. Offsets are SOURCE offsets (no inlay-hint chars). */
    record Edit(int start, int end, String text) {
    }

    /**
     * Resolve {@code edits} against {@code src}, shrink each one to the span(s) that actually change, and
     * drop no-ops. When the changed middle of an edit has the same (non-zero) number of line breaks before
     * and after, it is split into one edit per changed line, so unchanged lines between two changes are
     * never replaced. Input edits must not overlap (the LSP spec guarantees this).
     *
     * @return the edits sorted by start offset DESCENDING, which is the order to apply them in
     */
    static List<Edit> minimize(String src, List<? extends TextEdit> edits) {
        List<Edit> out = new ArrayList<>();
        if (edits == null) return out;
        for (TextEdit e : edits) {
            if (e == null || e.getRange() == null || e.getRange().getStart() == null || e.getRange().getEnd() == null) {
                continue;
            }
            int a = Positions.offset(src, e.getRange().getStart());
            int b = Positions.offset(src, e.getRange().getEnd());
            if (a > b) {
                int t = a;
                a = b;
                b = t;
            }
            addMinimal(src, a, b, e.getNewText() == null ? "" : e.getNewText(), out);
        }
        // Descending by start, then by end. Reversing first makes the stable sort put the LATER of two
        // edits at the same position first, so inserts at one position keep their array order in the
        // result (LSP spec: "the order in the array defines the order of the inserted strings").
        Collections.reverse(out);
        out.sort(Comparator.comparingInt(Edit::start).thenComparingInt(Edit::end).reversed());
        return out;
    }

    /** Map {@code offset} in the original text through {@code desc} (as returned by {@link #minimize}). */
    static int mapOffset(int offset, List<Edit> desc) {
        int delta = 0;
        for (int i = desc.size() - 1; i >= 0; i--) { // ascending
            Edit e = desc.get(i);
            if (e.start() >= offset) break; // at or after the offset (an insertion exactly here stays after it)
            if (e.end() <= offset) {
                delta += e.text().length() - (e.end() - e.start());
            } else { // offset strictly inside a replaced span: keep its relative position, clamped
                return e.start() + delta + Math.min(offset - e.start(), e.text().length());
            }
        }
        return offset + delta;
    }

    /** Apply {@code desc} (as returned by {@link #minimize}) to {@code src}. */
    static String apply(String src, List<Edit> desc) {
        StringBuilder sb = new StringBuilder(src);
        for (Edit e : desc) sb.replace(e.start(), e.end(), e.text());
        return sb.toString();
    }

    private static void addMinimal(String src, int a, int b, String text, List<Edit> out) {
        Edit trimmed = trim(src, a, b, text);
        if (trimmed == null) return;
        int breaks = countBreaks(src, trimmed.start(), trimmed.end());
        if (breaks == 0 || breaks != countBreaks(trimmed.text(), 0, trimmed.text().length())) {
            out.add(trimmed);
            return;
        }
        // Same line structure before and after: replace line by line, skipping unchanged lines.
        String mid = trimmed.text();
        int oldStart = trimmed.start();
        int newStart = 0;
        while (true) {
            int oldEnd = src.indexOf('\n', oldStart);
            if (oldEnd < 0 || oldEnd > trimmed.end()) oldEnd = trimmed.end();
            int newEnd = mid.indexOf('\n', newStart);
            if (newEnd < 0) newEnd = mid.length();
            Edit line = trim(src, oldStart, oldEnd, mid.substring(newStart, newEnd));
            if (line != null) out.add(line);
            if (oldEnd >= trimmed.end()) return;
            oldStart = oldEnd + 1;
            newStart = newEnd + 1;
        }
    }

    /** Strip the common prefix/suffix of {@code src[a,b)} and {@code text}; null when they are identical. */
    private static Edit trim(String src, int a, int b, String text) {
        int newLen = text.length();
        int max = Math.min(b - a, newLen);
        int p = 0;
        while (p < max && src.charAt(a + p) == text.charAt(p)) p++;
        int q = 0;
        while (q < max - p && src.charAt(b - 1 - q) == text.charAt(newLen - 1 - q)) q++;
        if (a + p == b - q && p + q == newLen) return null;
        return new Edit(a + p, b - q, text.substring(p, newLen - q));
    }

    private static int countBreaks(String s, int from, int to) {
        int n = 0;
        for (int i = from; i < to; i++) {
            if (s.charAt(i) == '\n') n++;
        }
        return n;
    }
}
