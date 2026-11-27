package org.mtype.editor.ui.editor;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.InsertReplaceEdit;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.mtype.editor.lsp.Positions;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns an accepted completion item into the edits to apply, at once and on the text the user sees — no
 * resolve round-trip (MYT-423). All offsets are SOURCE offsets (inlay hints stripped). Pure.
 *
 * <ul>
 *   <li><b>Main edit</b>: replaces the anchor word from its start to the caret. The server's range start
 *       is not used: it can be narrower than the typed prefix (the mType server counts columns in UTF-8
 *       bytes on lines with non-ASCII text) or stale. Its end only reaches past the caret by as much as it
 *       reached past the server's own request column, and never past the word, so a stale range never
 *       deletes text after the word. The rest of a word the caret is in the middle of is kept.</li>
 *   <li><b>Additional edits</b> (auto-import lines) go into the same change; one overlapping the main edit
 *       is dropped (the LSP spec forbids that).</li>
 *   <li>{@link Plan#changes()} is in apply order, each with its ORIGINAL offsets: descending start, and at
 *       the same offset the main edit first, then the additional edits in reverse — so an import inserted
 *       where the word starts ends up above it, and several imports keep the server's order.</li>
 * </ul>
 */
final class CompletionEditPlanner {
    private CompletionEditPlanner() {}

    /** One replacement in ORIGINAL source offsets; {@code main} marks the completion itself. */
    record Change(int start, int end, String text, boolean main) {}

    /**
     * @param changes            apply in this order, each with its original offsets
     * @param insertStart        where the main text begins in the RESULT text
     * @param insertText         the main text, snippet syntax expanded
     * @param selectionStart     selection start (caret when equal to the end) in the RESULT text
     * @param selectionEnd       selection end in the RESULT text
     * @param snippet            tab stops in RESULT offsets; null when there is nothing to tab through
     * @param hasAdditionalEdits whether any additional edit is applied
     */
    record Plan(List<Change> changes, int insertStart, String insertText,
                int selectionStart, int selectionEnd, SnippetSession snippet, boolean hasAdditionalEdits) {}

    /**
     * @param source     the source text now
     * @param caret      the caret, as a source offset
     * @param anchor     the word being completed
     * @param requestCol the caret column the server answered for (negative when unknown)
     * @return null when there is nothing to apply: no text, or the caret is no longer in the anchor word
     */
    static Plan plan(String source, int caret, CompletionAnchor anchor, int requestCol, CompletionItem item) {
        if (source == null || anchor == null || item == null) return null;
        if (caret < 0 || caret > source.length()) return null;

        int lineStart = source.lastIndexOf('\n', caret - 1) + 1;
        int lineEnd = source.indexOf('\n', caret);
        if (lineEnd < 0) lineEnd = source.length();
        String lineText = source.substring(lineStart, lineEnd);
        int line = lineNumber(source, lineStart);
        int col = caret - lineStart;
        if (!anchor.contains(lineText, line, col)) return null;

        String newText = null;
        Range serverRange = null;
        if (item.getTextEdit() != null) {
            if (item.getTextEdit().isLeft()) {
                TextEdit edit = item.getTextEdit().getLeft();
                newText = edit.getNewText();
                serverRange = edit.getRange();
            } else {
                InsertReplaceEdit edit = item.getTextEdit().getRight();
                newText = edit.getNewText();
                serverRange = edit.getInsert();
            }
        }
        if (newText == null) newText = item.getInsertText();
        if (newText == null) newText = item.getLabel();
        if (newText == null) return null;

        int start = lineStart + anchor.wordStartCol();
        int extra = 0;
        if (requestCol >= 0 && onLine(serverRange, anchor.line())) {
            extra = serverRange.getEnd().getCharacter() - requestCol;
        }
        int wordEnd = CompletionAnchor.wordEnd(lineText, col);
        int end = caret + Math.max(0, Math.min(extra, wordEnd - col));

        boolean isSnippet = item.getInsertTextFormat() == InsertTextFormat.Snippet;
        SnippetSession parsed = isSnippet ? SnippetSession.parse(newText) : null;
        String insertText = parsed != null ? parsed.text() : newText;

        List<Change> additional = new ArrayList<>();
        int shiftBefore = 0;
        if (item.getAdditionalTextEdits() != null) {
            for (TextEdit edit : item.getAdditionalTextEdits()) {
                if (edit == null || edit.getRange() == null
                        || edit.getRange().getStart() == null || edit.getRange().getEnd() == null) continue;
                int a = Positions.offset(source, edit.getRange().getStart());
                int b = Positions.offset(source, edit.getRange().getEnd());
                if (a > b) { int t = a; a = b; b = t; }
                if (a < end && b > start) continue; // overlaps the main edit (zero-width at its ends is fine)
                String text = edit.getNewText() == null ? "" : edit.getNewText();
                additional.add(new Change(a, b, text, false));
                if (b <= start) shiftBefore += text.length() - (b - a);
            }
        }

        List<Change> changes = new ArrayList<>(additional.size() + 1);
        changes.add(new Change(start, end, insertText, true));
        for (int i = additional.size() - 1; i >= 0; i--) changes.add(additional.get(i));
        changes.sort((x, y) -> Integer.compare(y.start(), x.start())); // stable: ties keep the order above

        int insertStart = start + shiftBefore;
        int selectionStart = insertStart + insertText.length();
        int selectionEnd = selectionStart;
        SnippetSession snippet = null;
        if (parsed != null) {
            parsed.shift(insertStart);
            SnippetSession.Selection first = parsed.firstSelection();
            selectionStart = first.start();
            selectionEnd = first.end();
            if (!first.finalCaret()) snippet = parsed;
        }
        return new Plan(List.copyOf(changes), insertStart, insertText,
                selectionStart, selectionEnd, snippet, !additional.isEmpty());
    }

    private static boolean onLine(Range range, int line) {
        return range != null && range.getStart() != null && range.getEnd() != null
                && range.getStart().getLine() == line && range.getEnd().getLine() == line;
    }

    private static int lineNumber(String source, int offset) {
        int line = 0;
        for (int i = 0; i < offset; i++) {
            if (source.charAt(i) == '\n') line++;
        }
        return line;
    }
}
