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

    public void onWorkspaceChanged(Workspace ws) {
        closeAll();
    }
}
