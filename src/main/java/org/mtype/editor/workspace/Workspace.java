package org.mtype.editor.workspace;

import java.nio.file.Path;

public class Workspace {
    private final Path root;

    public Workspace(Path root) {
        this.root = root;
    }

    public Path getRoot() { return root; }
}
