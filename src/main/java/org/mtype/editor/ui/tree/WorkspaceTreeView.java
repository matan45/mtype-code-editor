package org.mtype.editor.ui.tree;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.stage.Window;
import javafx.util.Duration;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.process.PackageController;
import org.mtype.editor.ui.dialogs.AddPackageDialog;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

public class WorkspaceTreeView extends TreeView<Path> {
    private enum ClipboardOp { COPY, CUT }
    private static List<Path> clipboardPaths = List.of();
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
    private List<Path> draggingPaths = List.of();

    public WorkspaceTreeView(AppContext ctx) {
        this.ctx = ctx;
        setShowRoot(true);
        setCellFactory(_ -> new FileTreeCell());
        getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        getStyleClass().add("mt-tree");
        filterDebounce.setOnFinished(_ -> applyFilter(currentQuery));

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
            LazyTreeItem root = new LazyTreeItem(ws.root());
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
            LazyTreeItem root = new LazyTreeItem(ws.root());
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

    /** Reveal the active editor's file: clear filter, expand ancestors, select, scroll. */
    public void revealActiveFile() {
        Path active = ctx.getTabPane() == null ? null : ctx.getTabPane().activePath();
        if (active == null) {
            ctx.getStatusBar().setMessage("No file open");
            return;
        }
        // The filtered tree uses a non-lazy root and a different parent chain,
        // so clear the filter before walking the lazy tree.
        if (!currentQuery.isEmpty()) {
            currentQuery = "";
            filterDebounce.stop();
            cancelActiveWalk();
            if (originalRoot != null) setRoot(originalRoot);
            if (onFilterReset != null) onFilterReset.run();
        }
        LazyTreeItem item = findItem(active);
        if (item == null) {
            ctx.getStatusBar().setMessage("File not in workspace tree");
            return;
        }
        TreeItem<Path> p = item.getParent();
        while (p != null) { p.setExpanded(true); p = p.getParent(); }
        getSelectionModel().clearSelection();
        getSelectionModel().select(item);
        int row = getRow(item);
        if (row >= 0) scrollTo(row);
        requestFocus();
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
        newFile.setOnAction(_ -> newFileAction());

        MenuItem newMt = new MenuItem("New .mt File...");
        newMt.setOnAction(_ -> newFileWithExtAction());

        MenuItem newFolder = new MenuItem("New Folder...");
        newFolder.setOnAction(_ -> newFolderAction());

        MenuItem rename = new MenuItem("Rename...");
        rename.setAccelerator(new KeyCodeCombination(KeyCode.F2));
        rename.setOnAction(_ -> renameAction());

        MenuItem delete = new MenuItem("Delete");
        delete.setAccelerator(new KeyCodeCombination(KeyCode.DELETE));
        delete.setOnAction(_ -> deleteAction());

        MenuItem copy = new MenuItem("Copy");
        copy.setAccelerator(new KeyCodeCombination(KeyCode.C, KeyCombination.CONTROL_DOWN));
        copy.setOnAction(_ -> clipAction(ClipboardOp.COPY));

        MenuItem cut = new MenuItem("Cut");
        cut.setAccelerator(new KeyCodeCombination(KeyCode.X, KeyCombination.CONTROL_DOWN));
        cut.setOnAction(_ -> clipAction(ClipboardOp.CUT));

        MenuItem paste = new MenuItem("Paste");
        paste.setAccelerator(new KeyCodeCombination(KeyCode.V, KeyCombination.CONTROL_DOWN));
        paste.setOnAction(_ -> pasteAction());

        MenuItem copyPath = new MenuItem("Copy Path");
        copyPath.setOnAction(_ -> copyPathAction());

        MenuItem revealInOs = new MenuItem("Reveal in File Explorer");
        revealInOs.setOnAction(_ -> revealInOsAction());

        MenuItem refreshItem = new MenuItem("Refresh");
        refreshItem.setOnAction(_ -> refresh());

        MenuItem pkgInstall = new MenuItem("Install");
        pkgInstall.setOnAction(_ -> runMtpmFromTree(pc -> pc.install(selectedPath())));

        MenuItem pkgAdd = new MenuItem("Add Package...");
        pkgAdd.setOnAction(_ -> {
            Path mtproj = selectedPath();
            if (mtproj == null) return;
            AddPackageDialog dlg = new AddPackageDialog(ownerWindow(), mtproj);
            dlg.showAndWait().ifPresent(spec -> runMtpmFromTree(pc -> pc.add(mtproj, spec)));
        });

        MenuItem pkgRemove = new MenuItem("Remove Package...");
        pkgRemove.setOnAction(_ -> {
            Path mtproj = selectedPath();
            if (mtproj == null) return;
            var prompt = Dialogs.prompt(ownerWindow(), "Remove Package",
                    "Remove package from " + mtproj.getFileName(),
                    "Package name:", "");
            prompt.showAndWait().map(String::trim).filter(s -> !s.isEmpty()).ifPresent(name -> {
                var confirm = Dialogs.confirm(ownerWindow(), "Remove " + name + "?",
                        "Remove " + name + " from " + mtproj.getFileName() + " and clean mt_modules?");
                var result = confirm.showAndWait();
                if (result.isPresent() && result.get() == ButtonType.OK) {
                    runMtpmFromTree(pc -> pc.remove(mtproj, name));
                }
            });
        });

        MenuItem pkgList = new MenuItem("List Packages");
        pkgList.setOnAction(_ -> runMtpmFromTree(pc -> pc.list(selectedPath())));

        Menu pkgMenu = new Menu("Package");
        pkgMenu.getItems().addAll(pkgInstall, pkgAdd, pkgRemove,
                new SeparatorMenuItem(), pkgList);

        ContextMenu menu = new ContextMenu(
                newFile, newMt, newFolder,
                new SeparatorMenuItem(),
                rename, delete,
                new SeparatorMenuItem(),
                cut, copy, paste,
                new SeparatorMenuItem(),
                copyPath, revealInOs, refreshItem,
                new SeparatorMenuItem(),
                pkgMenu);

        menu.setOnShowing(_ -> {
            List<Path> selected = selectedMutablePaths();
            Path sel = selectedPath();
            boolean hasSel = sel != null;
            boolean hasMutableSel = !selected.isEmpty();
            boolean singleMutableSel = selected.size() == 1;
            rename.setDisable(!singleMutableSel);
            delete.setDisable(!hasMutableSel);
            copy.setDisable(!hasSel);
            cut.setDisable(!hasMutableSel);
            paste.setDisable(clipboardPaths.isEmpty() || targetDirectory() == null);
            copyPath.setDisable(!hasSel);
            revealInOs.setDisable(!singleMutableSel);

            boolean isMtproj = sel != null && sel.getFileName() != null
                    && sel.getFileName().toString().toLowerCase().endsWith(".mtproj");
            PackageController pkgCtrl = ctx.getPackageController();
            boolean pkgBusy = pkgCtrl != null && pkgCtrl.runningProperty().get();
            pkgMenu.setDisable(!isMtproj || pkgBusy);
        });

        return menu;
    }

    private void runMtpmFromTree(java.util.function.Consumer<PackageController> action) {
        PackageController pc = ctx.getPackageController();
        if (pc == null) return;
        action.accept(pc);
    }

    private Path selectedPath() {
        TreeItem<Path> sel = getSelectionModel().getSelectedItem();
        return sel == null ? null : sel.getValue();
    }

    private List<Path> selectedPaths() {
        List<Path> paths = new ArrayList<>();
        for (TreeItem<Path> item : getSelectionModel().getSelectedItems()) {
            if (item != null && item.getValue() != null) {
                paths.add(item.getValue());
            }
        }
        return paths;
    }

    private List<Path> selectedMutablePaths() {
        return topLevelPaths(selectedPaths(), true);
    }

    private List<Path> topLevelPaths(List<Path> paths, boolean excludeRoot) {
        Path rootPath = getRoot() == null ? null : getRoot().getValue();
        List<Path> unique = new ArrayList<>(new LinkedHashSet<>(paths));
        unique.removeIf(p -> p == null || (excludeRoot && rootPath != null && p.equals(rootPath)));
        unique.sort(Comparator.comparingInt(Path::getNameCount));

        List<Path> topLevel = new ArrayList<>();
        for (Path path : unique) {
            boolean coveredByParent = false;
            for (Path parent : topLevel) {
                if (path.startsWith(parent)) {
                    coveredByParent = true;
                    break;
                }
            }
            if (!coveredByParent) {
                topLevel.add(path);
            }
        }
        return topLevel;
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
            if (validateName(name) || existsCheck(target)) return;
            try { Files.createFile(target); }
            catch (IOException ex) { errorAlert("Could not create file", ex); return; }
            refreshDirectory(dir);
            ctx.getTabPane().openFile(target);
        });
    }

    private void newFileWithExtAction() {
        Path dir = targetDirectory();
        if (dir == null) return;
        TextInputDialog d = Dialogs.prompt(ownerWindow(), "New " + ".mt" + " File",
                "Create a " + ".mt" + " file in " + dir.getFileName(),
                "Name (without extension):", null);
        d.showAndWait().map(String::trim).filter(s -> !s.isEmpty()).ifPresent(name -> {
            String full = name.toLowerCase().endsWith(".mt") ? name : name + ".mt";
            Path target = dir.resolve(full);
            if (validateName(full) || existsCheck(target)) return;
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
            if (validateName(name) || existsCheck(target)) return;
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
            if (validateName(name) || existsCheck(target)) return;
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
        List<Path> selected = selectedMutablePaths();
        if (selected.isEmpty()) return;
        Alert confirm = Dialogs.confirm(ownerWindow(), null,
                deleteConfirmationText(selected));
        Optional<ButtonType> r = confirm.showAndWait();
        if (r.isEmpty() || r.get() != ButtonType.OK) return;
        try {
            for (Path path : selected) {
                if (Files.isDirectory(path)) {
                    ctx.getTabPane().closeUnder(path);
                    deleteRecursive(path);
                } else {
                    ctx.getTabPane().closeByPath(path);
                    Files.delete(path);
                }
            }
        } catch (IOException ex) { errorAlert("Delete failed", ex); return; }
        if (clipboardPaths.stream().anyMatch(source ->
                selected.stream().anyMatch(deleted -> source.startsWith(deleted)))) {
            clipboardPaths = List.of();
            clipboardOp = null;
        }
        refreshParents(selected);
        ctx.refreshHasProjectFile();
    }

    private void clipAction(ClipboardOp op) {
        List<Path> selected = op == ClipboardOp.CUT
                ? selectedMutablePaths()
                : topLevelPaths(selectedPaths(), false);
        if (selected.isEmpty()) return;
        clipboardPaths = List.copyOf(selected);
        clipboardOp = op;
        ctx.getStatusBar().setMessage((op == ClipboardOp.COPY ? "Copied " : "Cut ") + describePaths(selected));
    }

    private void pasteAction() {
        if (clipboardPaths.isEmpty()) return;
        Path dir = targetDirectory();
        if (dir == null) return;
        if (clipboardPaths.stream().anyMatch(path -> !Files.exists(path))) {
            ctx.getStatusBar().setMessage("Clipboard source no longer exists");
            clipboardPaths = List.of(); clipboardOp = null;
            return;
        }
        Optional<Path> invalidNestedPaste = clipboardPaths.stream()
                .filter(source -> Files.isDirectory(source) && dir.startsWith(source))
                .findFirst();
        if (invalidNestedPaste.isPresent()) {
            String action = clipboardOp == ClipboardOp.CUT ? "move" : "copy";
            Dialogs.error(ownerWindow(), "Paste failed",
                    "Cannot " + action + " " + invalidNestedPaste.get().getFileName()
                            + " into itself.").showAndWait();
            return;
        }

        Set<Path> sourceParents = new LinkedHashSet<>();
        for (Path source : clipboardPaths) {
            if (source.getParent() != null) sourceParents.add(source.getParent());
        }
        boolean wasCut = clipboardOp == ClipboardOp.CUT;
        try {
            for (Path source : clipboardPaths) {
                Path target = uniquify(dir.resolve(source.getFileName().toString()));
                if (wasCut) {
                    if (Files.isDirectory(source)) ctx.getTabPane().closeUnder(source);
                    else ctx.getTabPane().closeByPath(source);
                    Files.move(source, target);
                } else {
                    if (Files.isDirectory(source)) copyRecursive(source, target);
                    else Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            if (wasCut) {
                clipboardPaths = List.of(); clipboardOp = null;
            }
        } catch (IOException ex) { errorAlert("Paste failed", ex); return; }
        refreshDirectory(dir);
        if (wasCut) {
            for (Path sourceParent : sourceParents) {
                if (!sourceParent.equals(dir)) refreshDirectory(sourceParent);
            }
        }
        ctx.refreshHasProjectFile();
    }

    private List<Path> dragSourcePaths(Path draggedPath) {
        if (draggedPath == null) return List.of();
        Path rootPath = getRoot() == null ? null : getRoot().getValue();
        if (rootPath != null && draggedPath.equals(rootPath)) return List.of();

        List<Path> selected = selectedPaths();
        if (selected.contains(draggedPath)) {
            return selectedMutablePaths();
        }
        return List.of(draggedPath);
    }

    private boolean canDropOnDirectory(Path targetDir, List<Path> sources) {
        if (targetDir == null || !Files.isDirectory(targetDir) || sources == null || sources.isEmpty()) {
            return false;
        }
        boolean hasMove = false;
        for (Path source : sources) {
            if (source == null || source.equals(targetDir)) return false;
            if (Files.isDirectory(source) && targetDir.startsWith(source)) return false;
            if (!targetDir.equals(source.getParent())) hasMove = true;
        }
        return hasMove;
    }

    private boolean moveDraggedPathsTo(Path targetDir) {
        List<Path> sources = draggingPaths;
        if (!canDropOnDirectory(targetDir, sources)) return false;

        List<Path> moved = new ArrayList<>();
        Set<Path> sourceParents = new LinkedHashSet<>();
        try {
            for (Path source : sources) {
                Path sourceParent = source.getParent();
                if (sourceParent != null) sourceParents.add(sourceParent);
                if (targetDir.equals(sourceParent)) continue;

                Path target = uniquify(targetDir.resolve(source.getFileName().toString()));
                if (Files.isDirectory(source)) ctx.getTabPane().closeUnder(source);
                else ctx.getTabPane().closeByPath(source);
                Files.move(source, target);
                moved.add(source);
            }
        } catch (IOException ex) {
            errorAlert("Move failed", ex);
            return false;
        }

        if (moved.isEmpty()) return false;
        if (clipboardPaths.stream().anyMatch(source ->
                moved.stream().anyMatch(movedPath -> source.startsWith(movedPath)))) {
            clipboardPaths = List.of();
            clipboardOp = null;
        }
        refreshDirectory(targetDir);
        for (Path sourceParent : sourceParents) {
            if (!sourceParent.equals(targetDir)) refreshDirectory(sourceParent);
        }
        ctx.refreshHasProjectFile();
        ctx.getStatusBar().setMessage("Moved " + describePaths(moved));
        return true;
    }

    private void copyPathAction() {
        Path sel = selectedPath();
        if (sel == null) return;
        ClipboardContent cc = new ClipboardContent();
        cc.putString(sel.toAbsolutePath().toString());
        Clipboard.getSystemClipboard().setContent(cc);
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
            return true;
        }
        return false;
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

    private String deleteConfirmationText(List<Path> paths) {
        if (paths.size() == 1) {
            return "Delete " + paths.get(0).getFileName() + "? This cannot be undone.";
        }
        return "Delete " + paths.size() + " selected items? This cannot be undone.";
    }

    private String describePaths(List<Path> paths) {
        if (paths.size() == 1) {
            return paths.get(0).getFileName().toString();
        }
        return paths.size() + " items";
    }

    private void refreshParents(List<Path> paths) {
        Set<Path> parents = new LinkedHashSet<>();
        for (Path path : paths) {
            Path parent = path.getParent();
            if (parent != null && Files.exists(parent)) parents.add(parent);
        }
        if (parents.isEmpty()) {
            refresh();
            return;
        }
        for (Path parent : parents) {
            refreshDirectory(parent);
        }
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

    private class FileTreeCell extends TreeCell<Path> {
        private final javafx.beans.value.ChangeListener<Boolean> expandListener =
                (_, _, _) -> refreshIcon();
        private TreeItem<Path> watchedItem;

        FileTreeCell() {
            setOnDragDetected(event -> {
                List<Path> sources = dragSourcePaths(getItem());
                if (sources.isEmpty()) return;
                draggingPaths = sources;

                Dragboard dragboard = startDragAndDrop(TransferMode.MOVE);
                ClipboardContent content = new ClipboardContent();
                content.putString(describePaths(sources));
                dragboard.setContent(content);
                event.consume();
            });

            setOnDragOver(event -> {
                Dragboard dragboard = event.getDragboard();
                if (dragboard.hasString() && canDropOnDirectory(getItem(), draggingPaths)) {
                    event.acceptTransferModes(TransferMode.MOVE);
                    event.consume();
                }
            });

            setOnDragDropped(event -> {
                boolean moved = moveDraggedPathsTo(getItem());
                event.setDropCompleted(moved);
                event.consume();
            });

            setOnDragDone(_ -> draggingPaths = List.of());
        }

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
