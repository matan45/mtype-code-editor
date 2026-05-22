package org.mtype.editor.syntax;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class XmlTokenizer {

    private static final Pattern PATTERN = Pattern.compile(
            "(?<XMLCOMMENT><!--[\\s\\S]*?-->)" +
            "|(?<XMLSTRING>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')" +
            "|(?<XMLATTR>[A-Za-z_][\\w-]*(?=\\s*=))" +
            "|(?<XMLTAG>(?<=<[?/]?)[A-Za-z_][\\w-]*)" +
            "|(?<XMLPUNCT><\\?|\\?>|</|/>|<|>|=)"
    );

    private XmlTokenizer() {}

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
        if (m.group("XMLCOMMENT") != null) return MTypeStyles.COMMENT;
        if (m.group("XMLSTRING") != null) return MTypeStyles.STRING;
        if (m.group("XMLATTR") != null) return MTypeStyles.NUMBER;
        if (m.group("XMLTAG") != null) return MTypeStyles.MEMBER;
        if (m.group("XMLPUNCT") != null) return MTypeStyles.BRACKET;
        return null;
    }
}
