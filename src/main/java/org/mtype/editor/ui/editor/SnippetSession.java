package org.mtype.editor.ui.editor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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

    static SnippetSession parse(String snippet, int insertionOffset) {
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
                    placeholders.add(new Placeholder(index, insertionOffset + out.length(), insertionOffset + out.length()));
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

                int start = insertionOffset + out.length();
                out.append(defaultValue);
                int end = insertionOffset + out.length();
                if (index == 0) {
                    finalRelativeOffset = end - insertionOffset;
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
        int finalOffset = insertionOffset + (finalRelativeOffset >= 0 ? finalRelativeOffset : out.length());
        return new SnippetSession(out.toString(), placeholders, finalOffset);
    }

    String text() {
        return text;
    }

    Selection firstSelection() {
        if (placeholders.isEmpty()) {
            return new Selection(finalOffset, finalOffset, true);
        }
        Placeholder p = placeholders.get(0);
        return new Selection(p.start, p.end, false);
    }

    Selection advance(int caretPosition, int selectionStart, int selectionEnd) {
        if (currentPlaceholder >= placeholders.size()) {
            return new Selection(finalOffset, finalOffset, true);
        }

        Placeholder current = placeholders.get(currentPlaceholder);
        int actualEnd = selectionStart != selectionEnd
                ? Math.max(selectionStart, selectionEnd)
                : caretPosition;
        if (actualEnd < current.start) {
            return null;
        }

        int delta = actualEnd - current.end;
        current.end = actualEnd;
        if (delta != 0) {
            for (int i = currentPlaceholder + 1; i < placeholders.size(); i++) {
                placeholders.get(i).shift(delta);
            }
            finalOffset += delta;
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
