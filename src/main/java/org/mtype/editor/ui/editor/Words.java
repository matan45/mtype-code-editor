package org.mtype.editor.ui.editor;

import org.mtype.editor.lsp.Positions;

/**
 * Identifier/word scanning shared by completion, hover-link detection, and the LSP commands.
 * A "word" character is a letter, digit, or underscore — the predicate the editor uses everywhere.
 */
final class Words {
    private Words() {}

    static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** Word around a SOURCE (line, character) position. Returns "" if none / on error. */
    static String wordAt(MTypeCodeArea area, int line, int col) {
        try {
            String text = area.getSourceText();
            int offset = Positions.offset(text, line, col);
            int start = offset;
            while (start > 0 && isWordChar(text.charAt(start - 1))) start--;
            int end = offset;
            while (end < text.length() && isWordChar(text.charAt(end))) end++;
            return text.substring(start, end);
        } catch (Exception ignored) {
            return "";
        }
    }

    /** Whole word straddling the caret in DISPLAY text, or null if the caret isn't on a word. */
    static String wordAroundCaret(MTypeCodeArea area) {
        int caret = area.getCaretPosition();
        String text = area.getText();
        int start = caret;
        int end = caret;
        while (start > 0 && isWordChar(text.charAt(start - 1))) start--;
        while (end < text.length() && isWordChar(text.charAt(end))) end++;
        if (start == end) return null;
        return text.substring(start, end);
    }

    /** The word characters immediately before the caret in DISPLAY text (the completion prefix). */
    static String currentWordPrefix(MTypeCodeArea area) {
        int caret = area.getCaretPosition();
        String text = area.getText();
        int start = caret;
        while (start > 0 && isWordChar(text.charAt(start - 1))) start--;
        return text.substring(start, caret);
    }

    /**
     * DISPLAY-offset range {@code [start, end)} of the identifier at {@code offset}, or null when the
     * offset isn't inside an identifier (the first char must be a letter or underscore, not a digit).
     */
    static int[] identifierRangeAt(MTypeCodeArea area, int offset) {
        String text = area.getText();
        int length = text.length();
        if (offset < 0 || offset > length) return null;
        int start = offset;
        while (start > 0 && isWordChar(text.charAt(start - 1))) start--;
        int end = offset;
        while (end < length && isWordChar(text.charAt(end))) end++;
        if (start == end) return null;
        char first = text.charAt(start);
        if (!Character.isLetter(first) && first != '_') return null;
        return new int[]{start, end};
    }
}
