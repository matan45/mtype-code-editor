package org.mtype.editor.search;

import java.nio.file.Path;

public record SearchMatch(
        Path path,
        int lineNumber,
        int columnStart,
        int columnEnd,
        String lineText) {
}
