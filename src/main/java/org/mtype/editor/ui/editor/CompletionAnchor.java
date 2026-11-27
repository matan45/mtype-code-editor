package org.mtype.editor.ui.editor;

/**
 * The word a completion is for: the SOURCE line and the column (UTF-16, inlay hints stripped) where the
 * word under the caret starts (MYT-423). A completion reply, the list shown and an accept only apply
 * while the caret is still inside that word: typing, backspacing or moving within the word keeps the
 * anchor; moving before its start, past a non-word character or to another line leaves it.
 *
 * <p>Pure (one line of source text in, no JavaFX), so it is unit-tested directly.
 */
record CompletionAnchor(int line, int wordStartCol) {

    /** The anchor of the word ending at {@code col} on {@code line} (an empty word right after {@code .}). */
    static CompletionAnchor at(String lineText, int line, int col) {
        return new CompletionAnchor(line, wordStart(lineText, col));
    }

    /** Start column of the run of word characters ending at {@code col} ({@code col} itself if none). */
    static int wordStart(String lineText, int col) {
        int start = clamp(lineText, col);
        while (start > 0 && Words.isWordChar(lineText.charAt(start - 1))) start--;
        return start;
    }

    /** End column of the run of word characters starting at {@code col} ({@code col} itself if none). */
    static int wordEnd(String lineText, int col) {
        int end = clamp(lineText, col);
        while (end < lineText.length() && Words.isWordChar(lineText.charAt(end))) end++;
        return end;
    }

    /** Whether a caret at ({@code line}, {@code col}) is still in the word this anchor starts. */
    boolean contains(String lineText, int line, int col) {
        return line == this.line
                && col >= wordStartCol
                && col <= lineText.length()
                && wordStart(lineText, col) == wordStartCol;
    }

    /** What was typed of the word: from its start to {@code col}. Meaningful only when {@link #contains}. */
    String prefix(String lineText, int col) {
        int end = clamp(lineText, col);
        return lineText.substring(Math.min(wordStartCol, end), end);
    }

    /** {@code "."} or {@code ":"} when that character sits right before the word (member access), else null. */
    String triggerCharacter(String lineText) {
        if (wordStartCol <= 0 || wordStartCol > lineText.length()) return null;
        char prior = lineText.charAt(wordStartCol - 1);
        return prior == '.' || prior == ':' ? String.valueOf(prior) : null;
    }

    private static int clamp(String lineText, int col) {
        return Math.max(0, Math.min(col, lineText.length()));
    }
}
