package org.mtype.editor.syntax;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JsonTokenizer {

    private static final Pattern PATTERN = Pattern.compile(
            "(?<JSONKEY>\"(?:\\\\.|[^\"\\\\])*\"(?=\\s*:))" +
            "|(?<JSONVALUE>\"(?:\\\\.|[^\"\\\\])*\")" +
            "|(?<JSONCONST>\\b(?:true|false|null)\\b)" +
            "|(?<JSONNUMBER>-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b)" +
            "|(?<JSONBRACE>[\\[\\]{}])" +
            "|(?<JSONPUNCT>[,:])"
    );

    private JsonTokenizer() {}

    public static StyleSpans<Collection<String>> compute(String text) {
        Matcher matcher = PATTERN.matcher(text);
        int lastEnd = 0;
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        while (matcher.find()) {
            String style = matchedStyle(matcher);
            if (style == null) continue;
            builder.add(Collections.emptyList(), matcher.start() - lastEnd);
            builder.add(Collections.singleton(style), matcher.end() - matcher.start());
            lastEnd = matcher.end();
        }
        builder.add(Collections.emptyList(), text.length() - lastEnd);
        return builder.create();
    }

    private static String matchedStyle(Matcher m) {
        if (m.group("JSONKEY") != null) return MTypeStyles.MEMBER;
        if (m.group("JSONVALUE") != null) return MTypeStyles.STRING;
        if (m.group("JSONCONST") != null) return MTypeStyles.CONSTANT;
        if (m.group("JSONNUMBER") != null) return MTypeStyles.NUMBER;
        if (m.group("JSONBRACE") != null) return MTypeStyles.BRACKET;
        if (m.group("JSONPUNCT") != null) return MTypeStyles.OPERATOR;
        return null;
    }
}
