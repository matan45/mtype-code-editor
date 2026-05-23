package org.mtype.editor.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public record Workspace(Path root) {

    public boolean hasProjectFile() {
        if (root == null || !Files.isDirectory(root)) return false;
        try (Stream<Path> s = Files.list(root)) {
            return s.anyMatch(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.endsWith(".mtproj") || n.endsWith(".mtworkspace");
            });
        } catch (IOException e) {
            return false;
        }
    }
}
