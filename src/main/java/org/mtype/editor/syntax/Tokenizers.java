package org.mtype.editor.syntax;

import org.fxmisc.richtext.model.StyleSpans;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;

public final class Tokenizers {

    private Tokenizers() {}

    public static StyleSpans<Collection<String>> computeFor(Path path, String text) {
        if (path == null) return MTypeTokenizer.compute(text);
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.equals("mtproj.lock")) return JsonTokenizer.compute(text);
        if (name.endsWith(".mtproj")) return XmlTokenizer.compute(text);
        if (name.endsWith(".mtworkspace")) return XmlTokenizer.compute(text);
        return MTypeTokenizer.compute(text);
    }
}
