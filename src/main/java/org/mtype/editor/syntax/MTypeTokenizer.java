package org.mtype.editor.syntax;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

    private static final String TYPE_NAME_PATTERN =
            "(?:[A-Z][A-Za-z0-9_]*|int|float|string|bool|object|Object|" +
            "Array|List|ArrayList|LinkedList|Set|HashSet|Map|HashMap|Stack|Queue)" +
            "(?:\\s*<[^\\n<>]*>)?\\??";

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

    private static final Pattern DECLARATION_IDENTIFIER_PATTERN = Pattern.compile(
            "(?m)^\\s*(?:(?:public|private|protected|static|final|const|value)\\s+)*" +
            TYPE_NAME_PATTERN +
            "\\s+([a-z_][A-Za-z0-9_]*)(?=\\s*(?:=|;|,))"
    );

    private static final Pattern MEMBER_ACCESS_PATTERN = Pattern.compile(
            "(?:::|\\bthis\\s*\\.|\\bsuper\\s*\\.)\\s*([a-z_][A-Za-z0-9_]*)(?![A-Za-z0-9_])(?!\\s*\\()"
    );

    private MTypeTokenizer() {}

    public static StyleSpans<Collection<String>> compute(String text) {
        StyleSpans<Collection<String>> base = computeBase(text);
        StyleSpans<Collection<String>> fallback = computeSemanticFallback(text);
        return base.overlay(fallback, MTypeTokenizer::mergeStyles);
    }

    private static StyleSpans<Collection<String>> computeBase(String text) {
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

    private static StyleSpans<Collection<String>> computeSemanticFallback(String text) {
        List<Range> protectedRanges = protectedRanges(text);
        List<Range> memberRanges = new ArrayList<>();
        addDeclarationIdentifiers(text, protectedRanges, memberRanges);
        addMemberAccessIdentifiers(text, protectedRanges, memberRanges);
        return rangesToSpans(text.length(), memberRanges);
    }

    private static void addDeclarationIdentifiers(String text, List<Range> protectedRanges, List<Range> out) {
        Matcher matcher = DECLARATION_IDENTIFIER_PATTERN.matcher(text);
        while (matcher.find()) {
            addMemberRange(matcher.start(1), matcher.end(1), protectedRanges, out);
        }
    }

    private static void addMemberAccessIdentifiers(String text, List<Range> protectedRanges, List<Range> out) {
        Matcher matcher = MEMBER_ACCESS_PATTERN.matcher(text);
        while (matcher.find()) {
            addMemberRange(matcher.start(1), matcher.end(1), protectedRanges, out);
        }
    }

    private static void addMemberRange(int start, int end, List<Range> protectedRanges, List<Range> out) {
        if (start >= end || isProtected(start, protectedRanges)) return;
        out.add(new Range(start, end));
    }

    private static List<Range> protectedRanges(String text) {
        List<Range> ranges = new ArrayList<>();
        Matcher matcher = PATTERN.matcher(text);
        while (matcher.find()) {
            if (matcher.group("BLOCKCOMMENT") != null
                    || matcher.group("LINECOMMENT") != null
                    || matcher.group("INTERPSTRING") != null
                    || matcher.group("STRING") != null) {
                ranges.add(new Range(matcher.start(), matcher.end()));
            }
        }
        return ranges;
    }

    private static boolean isProtected(int offset, List<Range> ranges) {
        for (Range range : ranges) {
            if (offset >= range.start && offset < range.end) return true;
            if (offset < range.start) return false;
        }
        return false;
    }

    private static StyleSpans<Collection<String>> rangesToSpans(int textLength, List<Range> ranges) {
        ranges.sort((a, b) -> Integer.compare(a.start, b.start));
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        int lastEnd = 0;
        for (Range range : ranges) {
            int start = Math.max(lastEnd, Math.min(range.start, textLength));
            int end = Math.max(start, Math.min(range.end, textLength));
            if (start > lastEnd) builder.add(Collections.emptyList(), start - lastEnd);
            if (end > start) builder.add(Collections.singleton(MTypeStyles.MEMBER), end - start);
            lastEnd = end;
        }
        if (lastEnd < textLength) {
            builder.add(Collections.emptyList(), textLength - lastEnd);
        } else if (textLength == 0) {
            builder.add(Collections.emptyList(), 0);
        }
        return builder.create();
    }

    private static Collection<String> mergeStyles(Collection<String> a, Collection<String> b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        Set<String> merged = new LinkedHashSet<>(a);
        merged.addAll(b);
        return merged;
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

    private static final class Range {
        private final int start;
        private final int end;

        private Range(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }
}
