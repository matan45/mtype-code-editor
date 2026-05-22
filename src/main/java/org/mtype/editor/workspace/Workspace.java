package org.mtype.editor.workspace;

import java.nio.file.Path;

public class Workspace {
    private final Path root;
    private final WorkspaceSettings settings;

    public Workspace(Path root, WorkspaceSettings settings) {
        this.root = root;
        this.settings = settings;
    }

    public Path getRoot() { return root; }
    public WorkspaceSettings getSettings() { return settings; }
}
