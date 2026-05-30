package org.mtype.editor.ui.editor;

import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Decodes delta-encoded LSP semantic tokens into style spans for the two panes of a {@link DiffTab}.
 *
 * <p>Tokens arrive in SOURCE (real-file line/char) coordinates of the working-tree document. The diff
 * panes insert blank padding rows where the other side has content, so a real-file line does not map to
 * the same line index in either pane. We map each token to a padded-pane character offset using the
 * per-row offset table built the same way {@link DiffTab} concatenates row text.
 *
 * <p>The server only has tokens for the current working-tree content, so:
 * <ul>
 *   <li><b>right (Working Tree)</b> pane — every token maps exactly.</li>
 *   <li><b>left (HEAD)</b> pane — only {@code EQUAL} rows are colored, by reusing the token (the row's
 *       text is character-identical to the right side). Changed/deleted rows stay regex-only.</li>
 * </ul>
 */
final class DiffSemanticDecoder {
    private DiffSemanticDecoder() {}

    record DiffSemantic(StyleSpans<Collection<String>> left, StyleSpans<Collection<String>> right) {}

    /** One decoded token, expressed in diff-row coordinates so both panes can place it. */
    private record Tok(int rowIndex, int intraChar, int length, Collection<String> classes) {}

    static DiffSemantic decode(List<DiffComputer.Row> rows, Integer[] leftLn, Integer[] rightLn,
                               SemanticTokens tokens, SemanticTokensLegend legend,
                               int leftLen, int rightLen) {
        if (rows == null || tokens == null || legend == null) return null;
        List<Integer> data = tokens.getData();
        if (data == null || data.size() < 5) return null;
        List<String> tokenTypes = legend.getTokenTypes();
        List<String> tokenModifiers = legend.getTokenModifiers();
        if (tokenTypes == null) tokenTypes = Collections.emptyList();
        if (tokenModifiers == null) tokenModifiers = Collections.emptyList();

        int n = rows.size();
        int[] leftRowStart = new int[n];
        int[] rightRowStart = new int[n];
        int[] leftCellLen = new int[n];
        int[] rightCellLen = new int[n];
        // realToRow indexed by 1-based real line; size n+1 (more lines than rows is impossible).
        int[] realToRow = new int[n + 1];
        java.util.Arrays.fill(realToRow, -1);

        int leftOff = 0;
        int rightOff = 0;
        for (int i = 0; i < n; i++) {
            DiffComputer.Row r = rows.get(i);
            String l = r.left() == null ? "" : r.left();
            String rt = r.right() == null ? "" : r.right();
            leftRowStart[i] = leftOff;
            rightRowStart[i] = rightOff;
            leftCellLen[i] = l.length();
            rightCellLen[i] = rt.length();
            leftOff += l.length();
            rightOff += rt.length();
            if (i < n - 1) {
                leftOff += 1;   // the '\n' joiner — matches DiffTab's build loop exactly
                rightOff += 1;
            }
            // Tokens are in working-tree coordinates, so map by the right (working-tree) line number.
            Integer real = rightLn[i];
            if (real != null && real >= 1 && real <= n) realToRow[real] = i;
        }

        List<Tok> toks = new ArrayList<>(data.size() / 5);
        int line = 0;
        int character = 0;
        for (int i = 0; i + 4 < data.size(); i += 5) {
            int deltaLine = data.get(i);
            int deltaStart = data.get(i + 1);
            int length = data.get(i + 2);
            int typeId = data.get(i + 3);
            int modBits = data.get(i + 4);
            if (deltaLine > 0) {
                line += deltaLine;
                character = deltaStart;
            } else {
                character += deltaStart;
            }
            if (length <= 0) continue;
            int rowIdx = (line + 1 <= n) ? realToRow[line + 1] : -1;
            if (rowIdx < 0) continue;

            List<String> cls = new ArrayList<>(2);
            if (typeId >= 0 && typeId < tokenTypes.size()) {
                cls.add("mt-sem-" + tokenTypes.get(typeId));
            }
            for (int b = 0; b < tokenModifiers.size(); b++) {
                if ((modBits & (1 << b)) != 0) {
                    cls.add("mt-sem-mod-" + tokenModifiers.get(b));
                }
            }
            if (cls.isEmpty()) continue;
            toks.add(new Tok(rowIdx, character, length, cls));
        }
        if (toks.isEmpty()) return null;

        StyleSpans<Collection<String>> right = build(toks, rightRowStart, rightCellLen, rightLen, false, rows);
        StyleSpans<Collection<String>> left = build(toks, leftRowStart, leftCellLen, leftLen, true, rows);
        return new DiffSemantic(left, right);
    }

    /**
     * Build padded style spans for one pane. {@code equalOnly} restricts coloring to EQUAL rows (the
     * left/HEAD pane), whose text is character-identical to the working tree.
     */
    private static StyleSpans<Collection<String>> build(List<Tok> toks, int[] rowStart, int[] cellLen,
                                                        int paneLen, boolean equalOnly,
                                                        List<DiffComputer.Row> rows) {
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        int cursor = 0;
        for (Tok t : toks) {
            if (equalOnly && rows.get(t.rowIndex()).op() != DiffComputer.Op.EQUAL) continue;
            int start = rowStart[t.rowIndex()] + t.intraChar();
            int end = Math.min(start + t.length(), rowStart[t.rowIndex()] + cellLen[t.rowIndex()]);
            if (start < 0 || start >= paneLen || end <= start) continue;
            if (start < cursor) continue;   // tokens are produced in document order; skip overlaps
            if (start > cursor) builder.add(Collections.emptyList(), start - cursor);
            builder.add(t.classes(), end - start);
            cursor = end;
        }
        if (cursor < paneLen) builder.add(Collections.emptyList(), paneLen - cursor);
        // StyleSpansBuilder.create() throws on a totally-empty builder; guarantee at least one span.
        if (cursor == 0 && paneLen == 0) builder.add(Collections.emptyList(), 0);
        return builder.create();
    }
}
