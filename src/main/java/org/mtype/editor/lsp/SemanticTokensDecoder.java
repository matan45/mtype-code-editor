package org.mtype.editor.lsp;

import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.mtype.editor.ui.editor.MTypeCodeArea;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public final class SemanticTokensDecoder {
    private SemanticTokensDecoder() {}

    /**
     * Decode delta-encoded semantic tokens. {@code text} is the SOURCE document (no inlay hints); the
     * resulting spans are mapped to {@code area}'s DISPLAY offsets so they overlay the hint-augmented
     * document correctly.
     */
    public static StyleSpans<Collection<String>> decode(
            String text, SemanticTokens tokens, SemanticTokensLegend legend, MTypeCodeArea area) {
        if (text == null || text.isEmpty() || tokens == null || legend == null || area == null) return null;
        List<Integer> data = tokens.getData();
        if (data == null || data.size() < 5) return null;
        List<String> tokenTypes = legend.getTokenTypes();
        List<String> tokenModifiers = legend.getTokenModifiers();
        if (tokenTypes == null) tokenTypes = Collections.emptyList();
        if (tokenModifiers == null) tokenModifiers = Collections.emptyList();

        int textLen = text.length();              // source length (token offsets are in source space)
        int displayLen = area.getLength();         // spans overlay the displayed document
        List<int[]> ranges = new ArrayList<>(data.size() / 5);
        List<Collection<String>> classes = new ArrayList<>(data.size() / 5);

        int line = 0;
        int character = 0;
        int lineStartOffset = 0;
        int lastLine = 0;
        for (int i = 0; i + 4 < data.size(); i += 5) {
            int deltaLine = data.get(i);
            int deltaStart = data.get(i + 1);
            int length = data.get(i + 2);
            int typeId = data.get(i + 3);
            int modBits = data.get(i + 4);
            if (deltaLine > 0) {
                line += deltaLine;
                character = deltaStart;
                lineStartOffset = advanceLines(text, lineStartOffset, line - lastLine);
                lastLine = line;
            } else {
                character += deltaStart;
            }
            int offset = lineStartOffset + character;
            if (offset < 0 || offset >= textLen || length <= 0) continue;
            int end = Math.min(offset + length, textLen);
            // Map source span -> display span (preserves alignment when inlay-hint chars sit between tokens).
            int dStart = area.sourceToDisplay(offset);
            int dEnd = area.sourceToDisplay(end);
            int effectiveLen = dEnd - dStart;
            if (effectiveLen <= 0) continue;
            offset = dStart;
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
            ranges.add(new int[]{offset, effectiveLen});
            classes.add(cls);
        }
        if (ranges.isEmpty()) return null;

        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        int cursor = 0;
        for (int i = 0; i < ranges.size(); i++) {
            int start = ranges.get(i)[0];
            int len = ranges.get(i)[1];
            if (start < cursor) continue;
            if (start > cursor) builder.add(Collections.emptyList(), start - cursor);
            builder.add(classes.get(i), len);
            cursor = start + len;
        }
        if (cursor < displayLen) builder.add(Collections.emptyList(), displayLen - cursor);
        return builder.create();
    }

    private static int advanceLines(String text, int from, int linesToAdvance) {
        int idx = from;
        int remaining = linesToAdvance;
        while (remaining > 0 && idx < text.length()) {
            if (text.charAt(idx) == '\n') remaining--;
            idx++;
        }
        return idx;
    }
}
