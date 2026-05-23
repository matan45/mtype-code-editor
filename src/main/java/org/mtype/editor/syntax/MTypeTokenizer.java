package org.mtype.editor.syntax;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MTypeTokenizer {

    private static final String KEYWORD_PATTERN =
            "\\b(import|from|as|class|interface|annotation|extends|implements|new|" +
            "function|constructor|" +
            "if|else|while|for|do|switch|case|default|break|continue|return|" +
            "try|catch|finally|throw|match|await)\\b";

    private static final String MODIFIER_PATTERN =
            "\\b(public|private|protected|static|final|const|abstract|async|value)\\b";

    private static final String CONSTANT_PATTERN =
            "\\b(true|false|null)\\b";

    private static final String MEMBER_PATTERN =
            "\\b(this|super)\\b";

    private static final String PRIMITIVE_PATTERN =
            "\\b(int|float|string|bool|void|object|Object|" +
            "Array|List|ArrayList|LinkedList|Set|HashSet|Map|HashMap|Stack|Queue)\\b";

    private static final Pattern PATTERN = Pattern.compile(
            "(?<BLOCKCOMMENT>/\\*[\\s\\S]*?\\*/)" +
            "|(?<LINECOMMENT>//[^\\n]*)" +
            "|(?<INTERPSTRING>\\$\"(?:\\\\.|[^\"\\\\])*\")" +
            "|(?<STRING>\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')" +
            "|(?<ANNOTATION>@[A-Za-z_][A-Za-z0-9_]*)" +
            "|(?<CONSTANT>" + CONSTANT_PATTERN + ")" +
            "|(?<MEMBER>" + MEMBER_PATTERN + ")" +
            "|(?<KEYWORD>" + KEYWORD_PATTERN + ")" +
            "|(?<MODIFIER>" + MODIFIER_PATTERN + ")" +
            "|(?<PRIMITIVE>" + PRIMITIVE_PATTERN + ")" +
            "|(?<FUNCTION>\\b[a-z_][A-Za-z0-9_]*(?=\\s*\\())" +
            "|(?<TYPE>\\b[A-Z][A-Za-z0-9_]*\\b)" +
            "|(?<NUMBER>\\b(?:0[xX][0-9a-fA-F]+|\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\b)" +
                    "|(?<BRACKET>[\\[\\](){}])" +
            "|(?<OPERATOR>->|=>|==|!=|<=|>=|&&|\\|\\||<<|>>|\\+\\+|--|[+\\-*/%=<>!&|^~?])" +
            "|(?<PUNCT>[.,;:])"
    );

    private MTypeTokenizer() {}

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
        if (m.group("BLOCKCOMMENT") != null) return MTypeStyles.COMMENT;
        if (m.group("LINECOMMENT") != null) return MTypeStyles.COMMENT;
        if (m.group("INTERPSTRING") != null) return MTypeStyles.STRING;
        if (m.group("STRING") != null) return MTypeStyles.STRING;
        if (m.group("ANNOTATION") != null) return MTypeStyles.ANNOTATION;
        if (m.group("CONSTANT") != null) return MTypeStyles.CONSTANT;
        if (m.group("MEMBER") != null) return MTypeStyles.MEMBER;
        if (m.group("KEYWORD") != null) return MTypeStyles.KEYWORD;
        if (m.group("MODIFIER") != null) return MTypeStyles.MODIFIER;
        if (m.group("PRIMITIVE") != null) return MTypeStyles.PRIMITIVE;
        if (m.group("FUNCTION") != null) return MTypeStyles.FUNCTION;
        if (m.group("TYPE") != null) return MTypeStyles.TYPE;
        if (m.group("NUMBER") != null) return MTypeStyles.NUMBER;
        if (m.group("BRACKET") != null) return MTypeStyles.BRACKET;
        if (m.group("OPERATOR") != null) return MTypeStyles.OPERATOR;
        if (m.group("PUNCT") != null) return MTypeStyles.PUNCT;
        return null;
    }
}
