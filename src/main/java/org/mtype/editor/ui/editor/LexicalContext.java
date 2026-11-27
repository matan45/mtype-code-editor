package org.mtype.editor.ui.editor;

import java.util.Arrays;

/**
 * What a caret is lexically inside (MYT-424): code, a comment, a string literal, or the path string of an
 * import statement. Completion auto-triggers only in {@link Kind#CODE} and {@link Kind#IMPORT_PATH}.
 *
 * <p>Computed on demand by scanning the SOURCE text (inlay hints stripped) from the start up to the caret,
 * so it is never stale and it sees literals the user is still typing: {@code "d.} and {@code /* d.} are
 * unterminated, which the highlighter's regex does not recognize. The rules mirror the server's
 * {@code SourceSegmenter} (languageserver/src/utils/SourceSegmenter.cpp), which mirrors the core lexer:
 * <ul>
 *   <li>{@code "..."} strings, where a backslash escapes the next character; a string is not ended by a
 *       newline;</li>
 *   <li>{@code $"..."} interpolated strings whose {@code {...}} holes are code that may hold nested
 *       strings, comments and braces;</li>
 *   <li>{@code //} line comments and {@code /* *}{@code /} block comments, which do not nest;</li>
 *   <li>no single-quoted literals.</li>
 * </ul>
 * One difference: the segmenter reports an interpolated string, holes included, as one string; here the
 * innermost construct decides, so a caret in a hole ({@code $"{d.}) is {@link Kind#CODE}.
 *
 * <p>An import path is a plain {@code "} string opened in top-level code right after the {@code from}
 * keyword or after {@code import lib}: {@code import { A } from "./A.mt"}, {@code import * from "x.mt"},
 * {@code import lib "Name"}. Whitespace and comments between the keyword and the quote don't matter.
 *
 * <p>Pure (text in, no JavaFX), so it is unit-tested directly.
 */
final class LexicalContext {

    enum Kind { CODE, COMMENT, STRING, IMPORT_PATH }

    private LexicalContext() {}

    /** The context of a caret at SOURCE offset {@code offset} (clamped to the text) in {@code source}. */
    static Kind at(CharSequence source, int offset) {
        Scanner scanner = new Scanner(source, Math.max(0, Math.min(offset, source.length())));
        scanner.run();
        return scanner.kind();
    }

    /** One pass over {@code [0, end)}; two-character tokens never look past {@code end}. */
    private static final class Scanner {
        private static final int STRING = 0;
        private static final int IMPORT_STRING = 1;
        private static final int INTERP_TEXT = 2;
        private static final int INTERP_EXPR = 3;
        private static final int BLOCK_COMMENT = 4;

        // Top-level code tokens, as far as import-path detection cares.
        private static final int OTHER = 0;
        private static final int IMPORT = 1;
        private static final int LIB = 2;
        private static final int FROM = 3;

        private final CharSequence text;
        private final int end;
        /** Open constructs, innermost last; empty == top-level code. */
        private int[] frames = new int[8];
        /** Unclosed {@code {} inside each INTERP_EXPR hole (parallel to {@link #frames}). */
        private int[] braces = new int[8];
        private int depth;
        /** In a {@code //} comment (at top level or inside a hole) until the next newline. */
        private boolean lineComment;
        /** The last two top-level code tokens, newest first. */
        private int previous = OTHER;
        private int beforePrevious = OTHER;
        /** Start of the top-level identifier being scanned, or -1. */
        private int wordStart = -1;

        Scanner(CharSequence text, int end) {
            this.text = text;
            this.end = end;
        }

        void run() {
            int i = 0;
            while (i < end) {
                if (lineComment) {
                    if (text.charAt(i) == '\n') lineComment = false;
                    i++;
                } else if (depth == 0) {
                    i = stepTop(i);
                } else {
                    i = switch (frames[depth - 1]) {
                        case BLOCK_COMMENT -> startsWith(i, '*', '/') ? popAt(i + 2) : i + 1;
                        case INTERP_EXPR -> stepHole(i);
                        default -> stepString(i);
                    };
                }
            }
        }

        Kind kind() {
            if (lineComment) return Kind.COMMENT;
            if (depth == 0) return Kind.CODE;
            return switch (frames[depth - 1]) {
                case BLOCK_COMMENT -> Kind.COMMENT;
                case INTERP_EXPR -> Kind.CODE;
                case IMPORT_STRING -> Kind.IMPORT_PATH;
                default -> Kind.STRING;
            };
        }

        private int stepTop(int i) {
            char c = text.charAt(i);
            if (Words.isWordChar(c)) {
                if (wordStart < 0) wordStart = i;
                return i + 1;
            }
            endWord(i);
            if (startsWith(i, '/', '/')) {
                lineComment = true;
                return i + 2;
            }
            if (startsWith(i, '/', '*')) {
                push(BLOCK_COMMENT);
                return i + 2;
            }
            if (startsWith(i, '$', '"')) {
                token(OTHER);
                push(INTERP_TEXT);
                return i + 2;
            }
            if (c == '"') {
                boolean importPath = previous == FROM || (previous == LIB && beforePrevious == IMPORT);
                token(OTHER);
                push(importPath ? IMPORT_STRING : STRING);
                return i + 1;
            }
            if (!Character.isWhitespace(c)) token(OTHER);
            return i + 1;
        }

        private int stepString(int i) {
            char c = text.charAt(i);
            if (c == '\\') return i + 2; // escapes the next character, a line break included
            if (c == '"') return popAt(i + 1);
            if (c == '{' && frames[depth - 1] == INTERP_TEXT) push(INTERP_EXPR);
            return i + 1;
        }

        /** Inside {@code {...}} of an interpolated string the lexer reads normal code. */
        private int stepHole(int i) {
            if (startsWith(i, '/', '/')) {
                lineComment = true;
                return i + 2;
            }
            if (startsWith(i, '/', '*')) {
                push(BLOCK_COMMENT);
                return i + 2;
            }
            if (startsWith(i, '$', '"')) {
                push(INTERP_TEXT);
                return i + 2;
            }
            char c = text.charAt(i);
            if (c == '"') {
                push(STRING);
            } else if (c == '{') {
                braces[depth - 1]++;
            } else if (c == '}') {
                if (braces[depth - 1] == 0) depth--;
                else braces[depth - 1]--;
            }
            return i + 1;
        }

        private void endWord(int i) {
            if (wordStart < 0) return;
            token(keyword(wordStart, i));
            wordStart = -1;
        }

        private int keyword(int start, int stop) {
            if (matches(start, stop, "from")) return FROM;
            if (matches(start, stop, "lib")) return LIB;
            if (matches(start, stop, "import")) return IMPORT;
            return OTHER;
        }

        private boolean matches(int start, int stop, String word) {
            if (stop - start != word.length()) return false;
            for (int k = 0; k < word.length(); k++) {
                if (text.charAt(start + k) != word.charAt(k)) return false;
            }
            return true;
        }

        private void token(int kind) {
            beforePrevious = previous;
            previous = kind;
        }

        private boolean startsWith(int i, char first, char second) {
            return i + 1 < end && text.charAt(i) == first && text.charAt(i + 1) == second;
        }

        private void push(int frame) {
            if (depth == frames.length) {
                frames = Arrays.copyOf(frames, depth * 2);
                braces = Arrays.copyOf(braces, depth * 2);
            }
            frames[depth] = frame;
            braces[depth] = 0;
            depth++;
        }

        private int popAt(int next) {
            depth--;
            return next;
        }
    }
}
