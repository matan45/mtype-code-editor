package org.mtype.editor.ui.tree;

import javafx.application.Platform;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.workspace.Workspace;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public class WorkspaceTreeView extends TreeView<Path> {
    private static final String[] ALLOWED_EXTENSIONS = {".mt", ".mtc", ".mtproj", ".mtworkspace"};
    private final AppContext ctx;

    public WorkspaceTreeView(AppContext ctx) {
        this.ctx = ctx;
        setShowRoot(true);
        setCellFactory(tv -> new FileTreeCell());
        getStyleClass().add("mt-tree");

        setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                TreeItem<Path> sel = getSelectionModel().getSelectedItem();
                if (sel == null) return;
                Path p = sel.getValue();
                if (p != null && Files.isRegularFile(p)) {
                    ctx.getTabPane().openFile(p);
                }
            }
        });
    }

    public void setWorkspace(Workspace ws) {
        Platform.runLater(() -> {
            LazyTreeItem root = new LazyTreeItem(ws.getRoot());
            root.setExpanded(true);
            setRoot(root);
        });
    }

    private static boolean acceptFile(Path p) {
        String name = p.getFileName().toString().toLowerCase();
        for (String ext : ALLOWED_EXTENSIONS) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    private static class LazyTreeItem extends TreeItem<Path> {
        private boolean loaded;
        private final boolean isDir;

        LazyTreeItem(Path path) {
            super(path);
            this.isDir = Files.isDirectory(path);
        }

        @Override
        public boolean isLeaf() {
            return !isDir;
        }

        @Override
        public javafx.collections.ObservableList<TreeItem<Path>> getChildren() {
            if (!loaded && isDir) {
                loaded = true;
                loadChildren();
            }
            return super.getChildren();
        }

        private void loadChildren() {
            Path path = getValue();
            if (path == null || !Files.isDirectory(path)) return;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(path)) {
                java.util.List<Path> children = new java.util.ArrayList<>();
                for (Path child : ds) {
                    String name = child.getFileName().toString();
                    if (name.startsWith(".")) continue;
                    if (Files.isDirectory(child) || acceptFile(child)) {
                        children.add(child);
                    }
                }
                children.sort(Comparator
                        .comparing((Path p) -> !Files.isDirectory(p))
                        .thenComparing(p -> p.getFileName().toString().toLowerCase()));
                for (Path c : children) {
                    super.getChildren().add(new LazyTreeItem(c));
                }
            } catch (IOException ignored) {
            }
        }
    }

    private static class FileTreeCell extends TreeCell<Path> {
        @Override
        protected void updateItem(Path item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            String name = item.getFileName() == null ? item.toString() : item.getFileName().toString();
            String icon = Files.isDirectory(item) ? "📁 " : "📄 ";
            setText(icon + name);
        }
    }
}
