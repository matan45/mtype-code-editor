package org.mtype.editor.ui.editor;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import org.mtype.editor.app.AppContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class EditorTabPane extends TabPane {
    private static final DataFormat TAB_DRAG_FORMAT = new DataFormat("application/x-mtype-tab-drag");
    private static final String DRAG_INSTALLED_KEY = "mt-drag-installed";

    private final AppContext ctx;
    private final Map<Path, EditorTab> open = new HashMap<>();
    private final Map<Path, DiffTab> diffs = new HashMap<>();
    private boolean reordering = false;
    private boolean closingAll = false;

    public EditorTabPane(AppContext ctx) {
        this.ctx = ctx;
        setTabClosingPolicy(TabClosingPolicy.ALL_TABS);
        getTabs().addListener((ListChangeListener<Tab>) c -> {
            while (c.next()) {
                if (c.wasRemoved() && !reordering && !closingAll) {
                    for (Tab t : c.getRemoved()) {
                        if (t instanceof EditorTab et) {
                            open.remove(et.getPath());
                            et.onClosed();
                        } else if (t instanceof DiffTab dt) {
                            diffs.remove(dt.getPath());
                        }
                    }
                }
            }
            Platform.runLater(this::wireTabHeaderDragHandlers);
        });
        skinProperty().addListener((obs, old, sk) -> {
            if (sk != null) Platform.runLater(this::wireTabHeaderDragHandlers);
        });
    }

    public void openDiff(Path file, String leftText, String rightText, String title, boolean binary) {
        DiffTab existing = diffs.get(file);
        if (existing != null) {
            getTabs().remove(existing);
            diffs.remove(file);
        }
        DiffTab tab = new DiffTab(ctx, file, title, leftText, rightText, binary);
        diffs.put(file, tab);
        getTabs().add(tab);
        attachTabContextMenu(tab);
        getSelectionModel().select(tab);
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
        attachTabContextMenu(tab);
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
        diffs.clear();
        closingAll = true;
        try {
            getTabs().clear();
        } finally {
            closingAll = false;
        }
    }

    private boolean canClose(Tab t) {
        return !(t instanceof EditorTab et) || et.canClose();
    }

    public void closeTab(Tab t) {
        if (t != null && canClose(t)) getTabs().remove(t);
    }

    public void closeAllInteractive() {
        for (Tab t : new ArrayList<>(getTabs())) {
            if (canClose(t)) getTabs().remove(t);
        }
    }

    public void closeAllButThis(Tab keep) {
        for (Tab t : new ArrayList<>(getTabs())) {
            if (t == keep) continue;
            if (canClose(t)) getTabs().remove(t);
        }
    }

    public void closeTabsToLeftOf(Tab anchor) {
        int idx = getTabs().indexOf(anchor);
        if (idx <= 0) return;
        for (Tab t : new ArrayList<>(getTabs().subList(0, idx))) {
            if (canClose(t)) getTabs().remove(t);
        }
    }

    public void closeTabsToRightOf(Tab anchor) {
        int idx = getTabs().indexOf(anchor);
        if (idx < 0 || idx >= getTabs().size() - 1) return;
        for (Tab t : new ArrayList<>(getTabs().subList(idx + 1, getTabs().size()))) {
            if (canClose(t)) getTabs().remove(t);
        }
    }

    private void attachTabContextMenu(Tab t) {
        ContextMenu menu = new ContextMenu();
        MenuItem close = new MenuItem("Close");
        MenuItem closeOthers = new MenuItem("Close All But This");
        MenuItem closeAll = new MenuItem("Close All");
        MenuItem closeLeft = new MenuItem("Close All To The Left");
        MenuItem closeRight = new MenuItem("Close All To The Right");
        MenuItem copyPath = new MenuItem("Copy Path");

        close.setOnAction(e -> closeTab(t));
        closeOthers.setOnAction(e -> closeAllButThis(t));
        closeAll.setOnAction(e -> closeAllInteractive());
        closeLeft.setOnAction(e -> closeTabsToLeftOf(t));
        closeRight.setOnAction(e -> closeTabsToRightOf(t));
        copyPath.setOnAction(e -> {
            Path p = pathOf(t);
            if (p != null) copyToClipboard(p.toAbsolutePath().toString());
        });

        menu.getItems().addAll(
                close,
                new SeparatorMenuItem(),
                closeOthers,
                closeAll,
                new SeparatorMenuItem(),
                closeLeft,
                closeRight,
                new SeparatorMenuItem(),
                copyPath
        );

        menu.setOnShowing(ev -> {
            int idx = getTabs().indexOf(t);
            int size = getTabs().size();
            closeOthers.setDisable(size <= 1);
            closeLeft.setDisable(idx <= 0);
            closeRight.setDisable(idx < 0 || idx >= size - 1);
            copyPath.setDisable(pathOf(t) == null);
        });

        t.setContextMenu(menu);
    }

    private static Path pathOf(Tab t) {
        if (t instanceof EditorTab et) return et.getPath();
        if (t instanceof DiffTab dt) return dt.getPath();
        return null;
    }

    private static void copyToClipboard(String s) {
        ClipboardContent cc = new ClipboardContent();
        cc.putString(s);
        Clipboard.getSystemClipboard().setContent(cc);
    }

    private void wireTabHeaderDragHandlers() {
        List<Node> headers = orderedTabHeaders();
        for (Node header : headers) {
            if (header.getProperties().containsKey(DRAG_INSTALLED_KEY)) continue;
            header.getProperties().put(DRAG_INSTALLED_KEY, Boolean.TRUE);
            installDragHandlersOn(header);
        }
    }

    private List<Node> orderedTabHeaders() {
        Set<Node> raw = lookupAll(".tab");
        List<Node> ordered = new ArrayList<>(raw);
        ordered.sort(Comparator.comparingDouble(n -> n.localToScene(n.getBoundsInLocal()).getMinX()));
        return ordered;
    }

    private void installDragHandlersOn(Node header) {
        header.setOnDragDetected(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            int fromIdx = orderedTabHeaders().indexOf(header);
            if (fromIdx < 0 || fromIdx >= getTabs().size()) return;
            Dragboard db = header.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            content.put(TAB_DRAG_FORMAT, fromIdx);
            db.setContent(content);
            db.setDragView(header.snapshot(null, null));
            e.consume();
        });
        header.setOnDragOver(e -> {
            if (e.getGestureSource() != header && e.getDragboard().hasContent(TAB_DRAG_FORMAT)) {
                e.acceptTransferModes(TransferMode.MOVE);
            }
            e.consume();
        });
        header.setOnDragDropped(e -> {
            Dragboard db = e.getDragboard();
            boolean success = false;
            if (db.hasContent(TAB_DRAG_FORMAT)) {
                Object payload = db.getContent(TAB_DRAG_FORMAT);
                int fromIdx = payload instanceof Integer i ? i : -1;
                int toIdx = orderedTabHeaders().indexOf(header);
                if (fromIdx >= 0 && fromIdx < getTabs().size()
                        && toIdx >= 0 && toIdx < getTabs().size()
                        && fromIdx != toIdx) {
                    reordering = true;
                    try {
                        Tab moving = getTabs().remove(fromIdx);
                        getTabs().add(toIdx, moving);
                        getSelectionModel().select(moving);
                    } finally {
                        reordering = false;
                    }
                    success = true;
                }
            }
            e.setDropCompleted(success);
            e.consume();
        });
    }

    public EditorTab findByPath(Path p) {
        return open.get(p);
    }

    public void closeByPath(Path p) {
        EditorTab t = open.get(p);
        if (t != null) getTabs().remove(t);
    }

    public void closeUnder(Path dir) {
        for (EditorTab t : new java.util.ArrayList<>(open.values())) {
            if (t.getPath().startsWith(dir)) getTabs().remove(t);
        }
    }

    /** Snapshot of all open tabs — used by services that need to scan tabs (e.g. diagnostics router). */
    public java.util.Collection<EditorTab> openTabs() {
        return new java.util.ArrayList<>(open.values());
    }

    public void syncOpenDocumentsWithLsp() {
        for (EditorTab tab : new java.util.ArrayList<>(open.values())) {
            tab.onLspReady();
        }
    }

}
