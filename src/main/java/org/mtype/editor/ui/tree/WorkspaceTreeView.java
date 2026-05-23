package org.mtype.editor.ui.tree;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.stage.Window;
import javafx.util.Duration;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.ui.dialogs.Dialogs;
import org.mtype.editor.workspace.Workspace;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

public class WorkspaceTreeView extends TreeView<Path> {
    private enum ClipboardOp { COPY, CUT }
    private static Path clipboardPath;
    private static ClipboardOp clipboardOp;

    private static final ExecutorService FILTER_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "workspace-filter");
        t.setDaemon(true);
        return t;
    });

    private final AppContext ctx;
    private final PauseTransition filterDebounce = new PauseTransition(Duration.millis(200));
    private final AtomicLong walkGeneration = new AtomicLong();
    private TreeItem<Path> originalRoot;
    private String currentQuery = "";
    private Future<?> activeWalk;
    private Runnable onFilterReset;

    public WorkspaceTreeView(AppContext ctx) {
        this.ctx = ctx;
        setShowRoot(true);
        setCellFactory(tv -> new FileTreeCell());
        getStyleClass().add("mt-tree");
        filterDebounce.setOnFinished(e -> applyFilter(currentQuery));

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

        setContextMenu(buildContextMenu());

        addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.F2) { renameAction(); e.consume(); }
            else if (e.getCode() == KeyCode.DELETE) { deleteAction(); e.consume(); }
            else if (e.isControlDown() && e.getCode() == KeyCode.C) { clipAction(ClipboardOp.COPY); e.consume(); }
            else if (e.isControlDown() && e.getCode() == KeyCode.X) { clipAction(ClipboardOp.CUT); e.consume(); }
            else if (e.isControlDown() && e.getCode() == KeyCode.V) { pasteAction(); e.consume(); }
        });
    }

    public void setWorkspace(Workspace ws) {
        Platform.runLater(() -> {
            cancelActiveWalk();
            currentQuery = "";
            filterDebounce.stop();
            LazyTreeItem root = new LazyTreeItem(ws.getRoot());
            root.setExpanded(true);
            originalRoot = root;
            setRoot(root);
            if (onFilterReset != null) onFilterReset.run();
        });
    }

    public void refresh() {
        Workspace ws = ctx.getWorkspace();
        if (ws == null) return;
        if (currentQuery.isEmpty()) {
            setWorkspace(ws);
        } else {
            // Rebuild the lazy root in the background reference so a future filter-clear shows fresh state,
            // then re-run the walk.
            LazyTreeItem root = new LazyTreeItem(ws.getRoot());
            root.setExpanded(true);
            originalRoot = root;
            applyFilter(currentQuery);
        }
    }

    /** Reload only the given directory's subtree, preserving expansion state of unaffected branches. */
    public void refreshDirectory(Path dir) {
        if (dir == null) { refresh(); return; }
        if (!currentQuery.isEmpty()) {
            // While filtering, do a targeted reload of the original lazy tree (so it's fresh when the
            // filter clears), then re-run the walk to pick up the changes.
            if (originalRoot instanceof LazyTreeItem lazyRoot) {
                LazyTreeItem item = findItem(lazyRoot, dir);
                if (item != null) item.reload();
            }
            applyFilter(currentQuery);
            return;
        }
        LazyTreeItem item = findItem(dir);
        if (item == null) { refresh(); return; }
        item.reload();
    }

    public void setFilter(String query) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.equals(currentQuery)) return;
        currentQuery = normalized;
        filterDebounce.playFromStart();
    }

    public void setOnFilterReset(Runnable callback) {
        this.onFilterReset = callback;
    }

    private void applyFilter(String query) {
        cancelActiveWalk();
        if (query.isEmpty()) {
            if (originalRoot != null) Platform.runLater(() -> setRoot(originalRoot));
            return;
        }
        if (originalRoot == null) return;
        Path root = originalRoot.getValue();
        if (root == null) return;
        long generation = walkGeneration.incrementAndGet();
        activeWalk = FILTER_EXEC.submit(() -> runWalk(query, root, generation));
    }

    private void cancelActiveWalk() {
        if (activeWalk != null) {
            activeWalk.cancel(true);
            activeWalk = null;
        }
    }

    private void runWalk(String query, Path root, long generation) {
        String qLower = query.toLowerCase();
        List<Path> matches = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("cancelled");
                    if (!dir.equals(root)) {
                        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                        if (name.startsWith(".")) return FileVisitResult.SKIP_SUBTREE;
                        if (name.toLowerCase().contains(qLower)) matches.add(dir);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("cancelled");
                    String name = file.getFileName() == null ? "" : file.getFileName().toString();
                    if (name.startsWith(".")) return FileVisitResult.CONTINUE;
                    if (name.toLowerCase().contains(qLower)) matches.add(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // Cancellation or permission errors at the top level; fall through with whatever we collected.
        }
        if (Thread.currentThread().isInterrupted()) return;
        Platform.runLater(() -> {
            if (generation != walkGeneration.get()) return;
            if (!query.equals(currentQuery)) return;
            TreeItem<Path> filteredRoot = buildFilteredTree(matches, root);
            setRoot(filteredRoot);
        });
    }

    private TreeItem<Path> buildFilteredTree(List<Path> matches, Path root) {
        TreeItem<Path> rootItem = new TreeItem<>(root);
        rootItem.setExpanded(true);
        Map<Path, TreeItem<Path>> byPath = new HashMap<>();
        byPath.put(root, rootItem);
        for (Path match : matches) {
            ensureNode(match, root, byPath);
        }
        sortRecursive(rootItem);
        return rootItem;
    }

    private TreeItem<Path> ensureNode(Path path, Path root, Map<Path, TreeItem<Path>> byPath) {
        TreeItem<Path> existing = byPath.get(path);
        if (existing != null) return existing;
        Path parent = path.getParent();
        if (parent == null || !path.startsWith(root) || path.equals(root)) {
            return byPath.get(root);
        }
        TreeItem<Path> parentItem = ensureNode(parent, root, byPath);
        TreeItem<Path> node = new TreeItem<>(path);
        node.setExpanded(true);
        parentItem.getChildren().add(node);
        byPath.put(path, node);
        return node;
    }

    private static final Comparator<TreeItem<Path>> FILTER_CHILD_ORDER = Comparator
            .comparing((TreeItem<Path> ti) -> !Files.isDirectory(ti.getValue()))
            .thenComparing(ti -> ti.getValue().getFileName().toString().toLowerCase());

    private void sortRecursive(TreeItem<Path> node) {
        if (node.getChildren().isEmpty()) return;
        node.getChildren().sort(FILTER_CHILD_ORDER);
        for (TreeItem<Path> child : node.getChildren()) sortRecursive(child);
    }

    private LazyTreeItem findItem(LazyTreeItem root, Path target) {
        Path rootPath = root.getValue();
        if (rootPath == null || !target.startsWith(rootPath)) return null;
        if (target.equals(rootPath)) return root;
        Path rel = rootPath.relativize(target);
        LazyTreeItem current = root;
        for (Path segment : rel) {
            LazyTreeItem next = null;
            for (TreeItem<Path> child : current.getChildren()) {
                if (child instanceof LazyTreeItem lti
                        && Objects.equals(segment.toString(), lti.getValue().getFileName().toString())) {
                    next = lti;
                    break;
                }
            }
            if (next == null) return null;
            current = next;
        }
        return current;
    }

    private LazyTreeItem findItem(Path target) {
        if (!(getRoot() instanceof LazyTreeItem root)) return null;
        Path rootPath = root.getValue();
        if (rootPath == null || !target.startsWith(rootPath)) return null;
        if (target.equals(rootPath)) return root;
        Path rel = rootPath.relativize(target);
        LazyTreeItem current = root;
        for (Path segment : rel) {
            LazyTreeItem next = null;
            for (TreeItem<Path> child : current.getChildren()) {
                if (child instanceof LazyTreeItem lti
                        && segment.toString().equals(lti.getValue().getFileName().toString())) {
                    next = lti;
                    break;
                }
            }
            if (next == null) return null;
            current = next;
        }
        return current;
    }

    private ContextMenu buildContextMenu() {
        MenuItem newFile = new MenuItem("New File...");
        newFile.setOnAction(e -> newFileAction());

        MenuItem newMt = new MenuItem("New .mt File...");
        newMt.setOnAction(e -> newFileWithExtAction(".mt"));

        MenuItem newFolder = new MenuItem("New Folder...");
        newFolder.setOnAction(e -> newFolderAction());

        MenuItem rename = new MenuItem("Rename...");
        rename.setAccelerator(new KeyCodeCombination(KeyCode.F2));
        rename.setOnAction(e -> renameAction());

        MenuItem delete = new MenuItem("Delete");
        delete.setAccelerator(new KeyCodeCombination(KeyCode.DELETE));
        delete.setOnAction(e -> deleteAction());

        MenuItem copy = new MenuItem("Copy");
        copy.setAccelerator(new KeyCodeCombination(KeyCode.C, KeyCombination.CONTROL_DOWN));
        copy.setOnAction(e -> clipAction(ClipboardOp.COPY));

        MenuItem cut = new MenuItem("Cut");
        cut.setAccelerator(new KeyCodeCombination(KeyCode.X, KeyCombination.CONTROL_DOWN));
        cut.setOnAction(e -> clipAction(ClipboardOp.CUT));

        MenuItem paste = new MenuItem("Paste");
        paste.setAccelerator(new KeyCodeCombination(KeyCode.V, KeyCombination.CONTROL_DOWN));
        paste.setOnAction(e -> pasteAction());

        MenuItem revealInOs = new MenuItem("Reveal in File Explorer");
        revealInOs.setOnAction(e -> revealInOsAction());

        MenuItem refreshItem = new MenuItem("Refresh");
        refreshItem.setOnAction(e -> refresh());

        ContextMenu menu = new ContextMenu(
                newFile, newMt, newFolder,
                new SeparatorMenuItem(),
                rename, delete,
                new SeparatorMenuItem(),
                cut, copy, paste,
                new SeparatorMenuItem(),
                revealInOs, refreshItem);

        menu.setOnShowing(e -> {
            Path sel = selectedPath();
            boolean hasSel = sel != null;
            boolean isRoot = hasSel && getRoot() != null && sel.equals(getRoot().getValue());
            rename.setDisable(!hasSel || isRoot);
            delete.setDisable(!hasSel || isRoot);
            copy.setDisable(!hasSel);
            cut.setDisable(!hasSel || isRoot);
            paste.setDisable(clipboardPath == null || targetDirectory() == null);
            revealInOs.setDisable(!hasSel);
        });

        return menu;
    }

    private Path selectedPath() {
        TreeItem<Path> sel = getSelectionModel().getSelectedItem();
        return sel == null ? null : sel.getValue();
    }

    /** Where new files/folders or pasted items land. Folder selection → that folder; file → its parent; nothing → root. */
    private Path targetDirectory() {
        Path sel = selectedPath();
        if (sel != null) {
            if (Files.isDirectory(sel)) return sel;
            Path parent = sel.getParent();
            if (parent != null) return parent;
        }
        return getRoot() == null ? null : getRoot().getValue();
    }

    private Window ownerWindow() {
        return getScene() == null ? null : getScene().getWindow();
    }

    private void newFileAction() {
        Path dir = targetDirectory();
        if (dir == null) return;
        TextInputDialog d = Dialogs.prompt(ownerWindow(), "New File",
                "Create a file in " + dir.getFileName(), "Name:", null);
        d.showAndWait().map(String::trim).filter(s -> !s.isEmpty()).ifPresent(name -> {
            Path target = dir.resolve(name);
            if (!validateName(name) || existsCheck(target)) return;
            try { Files.createFile(target); }
            catch (IOException ex) { errorAlert("Could not create file", ex); return; }
            refreshDirectory(dir);
            ctx.getTabPane().openFile(target);
        });
    }

    private void newFileWithExtAction(String ext) {
        Path dir = targetDirectory();
        if (dir == null) return;
        TextInputDialog d = Dialogs.prompt(ownerWindow(), "New " + ext + " File",
                "Create a " + ext + " file in " + dir.getFileName(),
                "Name (without extension):", null);
        d.showAndWait().map(String::trim).filter(s -> !s.isEmpty()).ifPresent(name -> {
            String full = name.toLowerCase().endsWith(ext) ? name : name + ext;
            Path target = dir.resolve(full);
            if (!validateName(full) || existsCheck(target)) return;
            try { Files.createFile(target); }
            catch (IOException ex) { errorAlert("Could not create file", ex); return; }
            refreshDirectory(dir);
            ctx.getTabPane().openFile(target);
        });
    }

    private void newFolderAction() {
        Path dir = targetDirectory();
        if (dir == null) return;
        TextInputDialog d = Dialogs.prompt(ownerWindow(), "New Folder",
                "Create a folder in " + dir.getFileName(), "Name:", null);
        d.showAndWait().map(String::trim).filter(s -> !s.isEmpty()).ifPresent(name -> {
            Path target = dir.resolve(name);
            if (!validateName(name) || existsCheck(target)) return;
            try { Files.createDirectory(target); }
            catch (IOException ex) { errorAlert("Could not create folder", ex); return; }
            refreshDirectory(dir);
        });
    }

    private void renameAction() {
        Path sel = selectedPath();
        if (sel == null) return;
        if (getRoot() != null && sel.equals(getRoot().getValue())) return;
        String currentName = sel.getFileName().toString();
        TextInputDialog d = Dialogs.prompt(ownerWindow(), "Rename",
                "Rename " + currentName, "New name:", currentName);
        d.showAndWait().map(String::trim).filter(s -> !s.isEmpty() && !s.equals(currentName)).ifPresent(name -> {
            Path target = sel.resolveSibling(name);
            if (!validateName(name) || existsCheck(target)) return;
            try {
                if (Files.isDirectory(sel)) ctx.getTabPane().closeUnder(sel);
                else ctx.getTabPane().closeByPath(sel);
                Files.move(sel, target);
            } catch (IOException ex) { errorAlert("Rename failed", ex); return; }
            refreshDirectory(sel.getParent());
            ctx.refreshHasProjectFile();
            if (Files.isRegularFile(target)) ctx.getTabPane().openFile(target);
        });
    }

    private void deleteAction() {
        Path sel = selectedPath();
        if (sel == null) return;
        if (getRoot() != null && sel.equals(getRoot().getValue())) return;
        Alert confirm = Dialogs.confirm(ownerWindow(), null,
                "Delete " + sel.getFileName() + "? This cannot be undone.");
        Optional<ButtonType> r = confirm.showAndWait();
        if (r.isEmpty() || r.get() != ButtonType.OK) return;
        try {
            if (Files.isDirectory(sel)) {
                ctx.getTabPane().closeUnder(sel);
                deleteRecursive(sel);
            } else {
                ctx.getTabPane().closeByPath(sel);
                Files.delete(sel);
            }
        } catch (IOException ex) { errorAlert("Delete failed", ex); return; }
        if (clipboardPath != null && clipboardPath.startsWith(sel)) {
            clipboardPath = null;
            clipboardOp = null;
        }
        refreshDirectory(sel.getParent());
        ctx.refreshHasProjectFile();
    }

    private void clipAction(ClipboardOp op) {
        Path sel = selectedPath();
        if (sel == null) return;
        if (op == ClipboardOp.CUT && getRoot() != null && sel.equals(getRoot().getValue())) return;
        clipboardPath = sel;
        clipboardOp = op;
        ctx.getStatusBar().setMessage((op == ClipboardOp.COPY ? "Copied " : "Cut ") + sel.getFileName());
    }

    private void pasteAction() {
        if (clipboardPath == null) return;
        Path dir = targetDirectory();
        if (dir == null) return;
        if (!Files.exists(clipboardPath)) {
            ctx.getStatusBar().setMessage("Clipboard source no longer exists");
            clipboardPath = null; clipboardOp = null;
            return;
        }
        Path target = dir.resolve(clipboardPath.getFileName().toString());
        target = uniquify(target);
        Path sourceParent = clipboardPath.getParent();
        boolean wasCut = clipboardOp == ClipboardOp.CUT;
        try {
            if (wasCut) {
                if (Files.isDirectory(clipboardPath)) ctx.getTabPane().closeUnder(clipboardPath);
                else ctx.getTabPane().closeByPath(clipboardPath);
                Files.move(clipboardPath, target);
                clipboardPath = null; clipboardOp = null;
            } else {
                if (Files.isDirectory(clipboardPath)) copyRecursive(clipboardPath, target);
                else Files.copy(clipboardPath, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) { errorAlert("Paste failed", ex); return; }
        refreshDirectory(dir);
        if (wasCut && sourceParent != null && !sourceParent.equals(dir)) {
            refreshDirectory(sourceParent);
        }
        ctx.refreshHasProjectFile();
    }

    private void revealInOsAction() {
        Path sel = selectedPath();
        if (sel == null) return;
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                new ProcessBuilder("explorer.exe", "/select,", sel.toString()).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", "-R", sel.toString()).start();
            } else {
                new ProcessBuilder("xdg-open",
                        (Files.isDirectory(sel) ? sel : sel.getParent()).toString()).start();
            }
        } catch (IOException ex) { errorAlert("Reveal failed", ex); }
    }

    private boolean validateName(String name) {
        if (name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..")) {
            Dialogs.error(ownerWindow(), null, "Invalid name.").showAndWait();
            return false;
        }
        return true;
    }

    private boolean existsCheck(Path target) {
        if (Files.exists(target)) {
            Dialogs.error(ownerWindow(), null, target.getFileName() + " already exists.").showAndWait();
            return true;
        }
        return false;
    }

    private void errorAlert(String header, Exception ex) {
        Dialogs.error(ownerWindow(), header, ex.getMessage()).showAndWait();
    }

    private static Path uniquify(Path target) {
        if (!Files.exists(target)) return target;
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        Path parent = target.getParent();
        for (int i = 1; i < 1000; i++) {
            Path candidate = parent.resolve(base + " (" + i + ")" + ext);
            if (!Files.exists(candidate)) return candidate;
        }
        return target;
    }

    private static void copyRecursive(Path src, Path dst) throws IOException {
        if (Files.isDirectory(src)) {
            Files.createDirectories(dst);
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(src)) {
                for (Path c : ds) copyRecursive(c, dst.resolve(c.getFileName().toString()));
            }
        } else {
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteRecursive(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                for (Path c : ds) deleteRecursive(c);
            }
        }
        Files.delete(p);
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

        /** Reload children from disk, preserving expansion of subdirs that still exist. */
        void reload() {
            if (!isDir) return;
            java.util.Map<String, Boolean> wasExpanded = new java.util.HashMap<>();
            for (TreeItem<Path> child : super.getChildren()) {
                if (child instanceof LazyTreeItem lti && lti.getValue() != null) {
                    wasExpanded.put(lti.getValue().getFileName().toString(), lti.isExpanded());
                }
            }
            super.getChildren().clear();
            loaded = false;
            loadChildren();
            loaded = true;
            for (TreeItem<Path> child : super.getChildren()) {
                if (child instanceof LazyTreeItem lti && lti.getValue() != null) {
                    Boolean exp = wasExpanded.get(lti.getValue().getFileName().toString());
                    if (Boolean.TRUE.equals(exp)) lti.setExpanded(true);
                }
            }
        }

        private void loadChildren() {
            Path path = getValue();
            if (path == null || !Files.isDirectory(path)) return;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(path)) {
                java.util.List<Path> children = new java.util.ArrayList<>();
                for (Path child : ds) {
                    String name = child.getFileName().toString();
                    if (name.startsWith(".")) continue;
                    children.add(child);
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
        private final javafx.beans.value.ChangeListener<Boolean> expandListener =
                (obs, oldV, newV) -> refreshIcon();
        private TreeItem<Path> watchedItem;

        @Override
        protected void updateItem(Path item, boolean empty) {
            super.updateItem(item, empty);

            // Detach old expand listener if the item changed
            if (watchedItem != null) {
                watchedItem.expandedProperty().removeListener(expandListener);
                watchedItem = null;
            }

            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }

            TreeItem<Path> ti = getTreeItem();
            if (ti != null && Files.isDirectory(item)) {
                ti.expandedProperty().addListener(expandListener);
                watchedItem = ti;
            }

            String name = item.getFileName() == null ? item.toString() : item.getFileName().toString();
            setText(name);
            refreshIcon();
        }

        private void refreshIcon() {
            Path item = getItem();
            if (item == null) {
                setGraphic(null);
                return;
            }
            TreeItem<Path> ti = getTreeItem();
            boolean expanded = ti != null && ti.isExpanded();
            setGraphic(IconFactory.iconForPath(item, expanded));
        }
    }
}
