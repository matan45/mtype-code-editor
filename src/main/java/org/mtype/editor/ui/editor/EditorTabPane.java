package org.mtype.editor.ui.editor;

import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.workspace.Workspace;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class EditorTabPane extends TabPane {
    private final AppContext ctx;
    private final Map<Path, EditorTab> open = new HashMap<>();

    public EditorTabPane(AppContext ctx) {
        this.ctx = ctx;
        setTabClosingPolicy(TabClosingPolicy.ALL_TABS);
        getTabs().addListener((javafx.collections.ListChangeListener<Tab>) c -> {
            while (c.next()) {
                if (c.wasRemoved()) {
                    for (Tab t : c.getRemoved()) {
                        if (t instanceof EditorTab et) {
                            open.remove(et.getPath());
                            et.onClosed();
                        }
                    }
                }
            }
        });
    }

    public void openFile(Path path) {
        EditorTab existing = open.get(path);
        if (existing != null) {
            getSelectionModel().select(existing);
            return;
        }
        EditorTab tab = new EditorTab(ctx, path);
        open.put(path, tab);
        getTabs().add(tab);
        getSelectionModel().select(tab);
    }

    /** Open file (if needed) and reveal an LSP 0-based (line, column) position. */
    public void openAt(Path path, int line, int column) {
        openFile(path);
        EditorTab tab = open.get(path);
        if (tab != null) {
            javafx.application.Platform.runLater(() -> tab.revealPosition(line, column));
        }
    }

    public void formatActive() {
        EditorTab t = activeTab();
        if (t != null) t.formatDocument();
    }

    public void renameActive() {
        EditorTab t = activeTab();
        if (t != null) t.renameAtCaret();
    }

    public void goToDefinitionActive() {
        EditorTab t = activeTab();
        if (t != null) t.goToDefinitionAtCaret();
    }

    public void callHierarchyActive() {
        EditorTab t = activeTab();
        if (t != null) t.showCallHierarchyAtCaret();
    }

    public EditorTab activeTab() {
        Tab t = getSelectionModel().getSelectedItem();
        return (t instanceof EditorTab et) ? et : null;
    }

    public Path activePath() {
        EditorTab t = activeTab();
        return t == null ? null : t.getPath();
    }

    public void saveActive() {
        EditorTab t = activeTab();
        if (t != null) t.save();
    }

    public void closeAll() {
        for (EditorTab t : new java.util.ArrayList<>(open.values())) {
            t.onClosed();
        }
        open.clear();
        getTabs().clear();
    }

    public EditorTab findByPath(Path p) {
        return open.get(p);
    }

    public void syncOpenDocumentsWithLsp() {
        for (EditorTab tab : new java.util.ArrayList<>(open.values())) {
            tab.onLspReady();
        }
    }

    public void onWorkspaceChanged(Workspace ws) {
        closeAll();
    }
}
