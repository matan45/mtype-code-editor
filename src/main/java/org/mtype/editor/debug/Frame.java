package org.mtype.editor.debug;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Represents one call-stack frame: "main@C:\path\to\script.mt:42". */
public record Frame(String function, String file, int line) {
    public Path filePath() {
        try { return Paths.get(file); } catch (Exception e) { return null; }
    }

    public static Frame parse(String encoded) {
        if (encoded == null || encoded.isEmpty()) return new Frame("?", "", 0);
        int at = encoded.lastIndexOf('@');
        if (at < 0) return new Frame(encoded, "", 0);
        String fn = encoded.substring(0, at);
        String rest = encoded.substring(at + 1);
        int colon = rest.lastIndexOf(':');
        if (colon < 0) return new Frame(fn, rest, 0);
        String file = rest.substring(0, colon);
        int line = 0;
        try { line = Integer.parseInt(rest.substring(colon + 1).trim()); } catch (NumberFormatException ignored) {}
        return new Frame(fn, file, line);
    }

    public String displayName() {
        Path p = filePath();
        String fname = p != null && p.getFileName() != null ? p.getFileName().toString() : file;
        return function + "  (" + fname + ":" + line + ")";
    }
}
