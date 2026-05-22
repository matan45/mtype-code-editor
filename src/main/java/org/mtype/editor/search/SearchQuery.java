package org.mtype.editor.search;

import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public record SearchQuery(
        String text,
        boolean caseSensitive,
        boolean wholeWord,
        boolean regex,
        String fileMask) {

    public boolean isBlank() {
        return text == null || text.isEmpty();
    }

    public Pattern compile() throws PatternSyntaxException {
        String body = regex ? text : Pattern.quote(text);
        if (wholeWord) body = "\\b" + body + "\\b";
        int flags = caseSensitive ? 0 : (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return Pattern.compile(body, flags);
    }

    public PathMatcher pathMatcher() {
        if (fileMask == null) return null;
        String mask = fileMask.trim();
        if (mask.isEmpty()) return null;
        if (!mask.contains("/") && !mask.contains("\\")) {
            mask = "**/" + mask;
        }
        return FileSystems.getDefault().getPathMatcher("glob:" + mask);
    }
}
