package org.mtype.editor.ui.editor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The tab stops of one inserted snippet, in DISPLAY offsets of the code area.
 *
 * <p>Every document edit is mapped through {@link #onEdit} while the session lives (MYT-424), so the stops
 * stay exact whatever changes: typing in a placeholder, an accepted completion (and its auto-import above),
 * an inlay hint appearing. Only the current placeholder grows when text is inserted at its edges, and never
 * from a hint-only edit; an edit across a placeholder boundary ends the session.
 *
 * <p>Pure (no JavaFX), so it is unit-tested directly.
 */
final class SnippetSession {
    private final String text;
    private final List<Placeholder> placeholders;
    private int finalOffset;
    private int currentPlaceholder;

    private SnippetSession(String text, List<Placeholder> placeholders, int finalOffset) {
        this.text = text;
        this.placeholders = placeholders;
        this.finalOffset = finalOffset;
    }

    static SnippetSession parse(String snippet) {
        StringBuilder out = new StringBuilder();
        List<Placeholder> placeholders = new ArrayList<>();
        int finalRelativeOffset = -1;

        for (int i = 0; i < snippet.length();) {
            char ch = snippet.charAt(i);
            if (ch == '\\' && i + 1 < snippet.length() && isEscapable(snippet.charAt(i + 1))) {
                out.append(snippet.charAt(i + 1));
                i += 2;
                continue;
            }
            if (ch != '$' || i + 1 >= snippet.length()) {
                out.append(ch);
                i++;
                continue;
            }

            char next = snippet.charAt(i + 1);
            if (Character.isDigit(next)) {
                int digitsEnd = i + 1;
                while (digitsEnd < snippet.length() && Character.isDigit(snippet.charAt(digitsEnd))) {
                    digitsEnd++;
                }
                int index = Integer.parseInt(snippet.substring(i + 1, digitsEnd));
                if (index == 0) {
                    finalRelativeOffset = out.length();
                } else {
                    placeholders.add(new Placeholder(index, out.length(), out.length()));
                }
                i = digitsEnd;
                continue;
            }

            if (next == '{') {
                int digitsStart = i + 2;
                int digitsEnd = digitsStart;
                while (digitsEnd < snippet.length() && Character.isDigit(snippet.charAt(digitsEnd))) {
                    digitsEnd++;
                }
                if (digitsEnd == digitsStart || digitsEnd >= snippet.length()) {
                    out.append(ch);
                    i++;
                    continue;
                }

                int index = Integer.parseInt(snippet.substring(digitsStart, digitsEnd));
                int close = -1;
                String defaultValue = "";
                if (snippet.charAt(digitsEnd) == '}') {
                    close = digitsEnd;
                } else if (snippet.charAt(digitsEnd) == ':') {
                    close = findClosingBrace(snippet, digitsEnd + 1);
                    if (close >= 0) {
                        defaultValue = unescape(snippet.substring(digitsEnd + 1, close));
                    }
                }
                if (close < 0) {
                    out.append(ch);
                    i++;
                    continue;
                }

                int start = out.length();
                out.append(defaultValue);
                int end = out.length();
                if (index == 0) {
                    finalRelativeOffset = end;
                } else {
                    placeholders.add(new Placeholder(index, start, end));
                }
                i = close + 1;
                continue;
            }

            out.append(ch);
            i++;
        }

        placeholders.sort(Comparator
                .comparingInt((Placeholder p) -> p.index)
                .thenComparingInt(p -> p.start));
        int finalOffset = (finalRelativeOffset >= 0 ? finalRelativeOffset : out.length());
        return new SnippetSession(out.toString(), placeholders, finalOffset);
    }

    String text() {
        return text;
    }

    /**
     * Moves every tab stop and the final caret by {@code delta} (e.g. from source to display offsets once
     * the text is in the document). Mutates this session and returns it.
     */
    SnippetSession shift(int delta) {
        if (delta == 0) return this;
        for (Placeholder placeholder : placeholders) placeholder.shift(delta);
        finalOffset += delta;
        return this;
    }

    Selection firstSelection() {
        if (placeholders.isEmpty()) {
            return new Selection(finalOffset, finalOffset, true);
        }
        Placeholder p = placeholders.getFirst();
        return new Selection(p.start, p.end, false);
    }

    /**
     * Map one document edit: {@code removedLength} characters at {@code start} were replaced by
     * {@code insertedLength} characters. {@code hintsOnly} when every character removed and inserted is an
     * inlay-hint character. Returns false when the edit crosses a remaining placeholder's boundary: the
     * session can no longer tell where its stops are and must end.
     */
    boolean onEdit(int start, int removedLength, int insertedLength, boolean hintsOnly) {
        int end = start + removedLength;
        int delta = insertedLength - removedLength;
        for (int i = currentPlaceholder; i < placeholders.size(); i++) {
            Placeholder p = placeholders.get(i);
            boolean grows = i == currentPlaceholder && !hintsOnly;
            if (grows && start >= p.start && end <= p.end) {
                p.end += delta; // typing (or a completion) in the current placeholder, at its edges included
            } else if (end <= p.start) {
                p.shift(delta); // wholly before it, an insert at its start included
            } else if (start >= p.end) {
                // wholly after it, an insert at its end included: unaffected
            } else if (start >= p.start && end <= p.end) {
                p.end += delta; // strictly inside it
            } else {
                return false;
            }
        }
        if (end <= finalOffset) {
            finalOffset += delta;
        } else if (start < finalOffset) {
            finalOffset = start + insertedLength; // the final caret was replaced: keep it after the new text
        }
        return true;
    }

    /** Whether {@code offset} lies in what is left of the snippet: from the current stop to its last stop. */
    boolean contains(int offset) {
        if (currentPlaceholder >= placeholders.size()) return offset == finalOffset;
        int from = placeholders.get(currentPlaceholder).start;
        int to = finalOffset;
        for (int i = currentPlaceholder; i < placeholders.size(); i++) {
            to = Math.max(to, placeholders.get(i).end);
        }
        return offset >= from && offset <= to;
    }

    /**
     * Tab: the next stop, or the final caret ({@link Selection#finalCaret}) after the last one. Null when
     * the caret has left the snippet ({@link #contains}), which ends the session.
     */
    Selection advance(int caret) {
        if (currentPlaceholder >= placeholders.size()) {
            return new Selection(finalOffset, finalOffset, true);
        }
        if (!contains(caret)) {
            return null;
        }
        currentPlaceholder++;
        if (currentPlaceholder >= placeholders.size()) {
            return new Selection(finalOffset, finalOffset, true);
        }
        Placeholder next = placeholders.get(currentPlaceholder);
        return new Selection(next.start, next.end, false);
    }

    private static int findClosingBrace(String snippet, int start) {
        for (int i = start; i < snippet.length(); i++) {
            char ch = snippet.charAt(i);
            if (ch == '\\' && i + 1 < snippet.length()) {
                i++;
                continue;
            }
            if (ch == '}') {
                return i;
            }
        }
        return -1;
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\\' && i + 1 < value.length() && isEscapable(value.charAt(i + 1))) {
                out.append(value.charAt(++i));
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    private static boolean isEscapable(char ch) {
        return ch == '$' || ch == '}' || ch == '\\';
    }

    record Selection(int start, int end, boolean finalCaret) {
    }

    private static final class Placeholder {
        private final int index;
        private int start;
        private int end;

        private Placeholder(int index, int start, int end) {
            this.index = index;
            this.start = start;
            this.end = end;
        }

        private void shift(int delta) {
            start += delta;
            end += delta;
        }
    }
}
