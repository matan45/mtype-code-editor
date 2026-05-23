package org.mtype.editor.lsp;

import org.eclipse.lsp4j.Position;

public final class Positions {
    private Positions() {
    }

    /**
     * Convert an LSP (line, character) position to a 0-based offset in text.
     */
    public static int offset(String text, int line, int character) {
        int idx = 0, curLine = 0;
        while (curLine < line && idx < text.length()) {
            if (text.charAt(idx) == '\n') curLine++;
            idx++;
        }
        int end = Math.min(text.length(), idx + character);
        return Math.max(0, end);
    }

    public static int offset(String text, Position p) {
        return offset(text, p.getLine(), p.getCharacter());
    }
}
