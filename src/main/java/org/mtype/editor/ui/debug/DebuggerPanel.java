package org.mtype.editor.ui.debug;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.debug.BreakpointService;
import org.mtype.editor.debug.DebuggerBridge;
import org.mtype.editor.debug.DebuggerEventBus;
import org.mtype.editor.debug.Frame;
import org.mtype.editor.debug.Variable;
import org.mtype.editor.ui.dialogs.Dialogs;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** VS Code-style debug side panel: toolbar + Variables / Watch / Call Stack / Breakpoints. */
public class DebuggerPanel extends VBox {

    private final AppContext ctx;
    private final DebuggerBridge bridge;
    private final DebuggerEventBus events;
    private final BreakpointService breakpoints;

    private final Button startContinueBtn = new Button();
    private final Button stopBtn = new Button();
    private final Button stepOverBtn = new Button();
    private final Button stepIntoBtn = new Button();
    private final Button stepOutBtn = new Button();
    private final Button restartBtn = new Button();
    private final Button attachBtn = new Button("Attach");
    private final Label statusLabel = new Label("Idle");
    private final StringProperty statusText = new SimpleStringProperty("Idle");

    private final TreeItem<VarNode> varRoot = new TreeItem<>(VarNode.label("root"));
    private final TreeItem<VarNode> localRoot = new TreeItem<>(VarNode.label("Local"));
    private final TreeItem<VarNode> globalRoot = new TreeItem<>(VarNode.label("Global"));
    private final TreeView<VarNode> variablesTree = new TreeView<>(varRoot);
    private final Map<Long, TreeItem<VarNode>> pendingExpansions = new HashMap<>();

    private final ListView<WatchEntry> watchList = new ListView<>();
    private final TextField watchInput = new TextField();
    private final Deque<WatchEntry> evalQueue = new ArrayDeque<>();
    private final AtomicLong watchKeySeq = new AtomicLong();
    private WatchEntry inFlightWatch;

    private final ListView<Frame> stackList = new ListView<>();
    private final ListView<BreakpointEntry> breakpointList = new ListView<>();

    private int currentFrame = 0;
    private DebuggerEventBus.State state = DebuggerEventBus.State.IDLE;

    public DebuggerPanel(AppContext ctx) {
        this.ctx = ctx;
        this.bridge = ctx.getDebuggerBridge();
        this.events = ctx.getDebuggerEventBus();
        this.breakpoints = ctx.getBreakpointService();
        getStyleClass().add("mt-debug-panel");
        setSpacing(0);

        buildToolbar();
        buildSections();
        wireEvents();
        applyState(DebuggerEventBus.State.IDLE);
    }

    /* ============================== toolbar ============================== */

    private void buildToolbar() {
        configToolbarButton(startContinueBtn, DebuggerIcons.playIcon(), "Start / Continue (F5)");
        configToolbarButton(stopBtn, DebuggerIcons.stopIcon(), "Stop (Shift+F5)");
        configToolbarButton(stepOverBtn, DebuggerIcons.stepOverIcon(), "Step Over (F10)");
        configToolbarButton(stepIntoBtn, DebuggerIcons.stepIntoIcon(), "Step Into (F11)");
        configToolbarButton(stepOutBtn, DebuggerIcons.stepOutIcon(), "Step Out (Shift+F11)");
        configToolbarButton(restartBtn, DebuggerIcons.restartIcon(), "Restart");

        attachBtn.setContentDisplay(ContentDisplay.TEXT_ONLY);
        attachBtn.setTooltip(new Tooltip("Attach to a running mType debug host over TCP"));
        attachBtn.getStyleClass().add("mt-debug-toolbar-button");

        startContinueBtn.setOnAction(_ -> onStartOrContinue());
        stopBtn.setOnAction(_ -> bridge.stop());
        stepOverBtn.setOnAction(_ -> bridge.stepOver());
        stepIntoBtn.setOnAction(_ -> bridge.stepInto());
        stepOutBtn.setOnAction(_ -> bridge.stepOut());
        restartBtn.setOnAction(_ -> onRestart());
        attachBtn.setOnAction(_ -> onAttach());

        statusLabel.textProperty().bind(statusText);
        statusLabel.getStyleClass().add("mt-debug-status");

        HBox spacer = new HBox();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox toolbar = new HBox(4, startContinueBtn, stepOverBtn, stepIntoBtn,
                stepOutBtn, restartBtn, stopBtn, attachBtn, spacer, statusLabel);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(6, 8, 6, 8));
        toolbar.getStyleClass().add("mt-debug-toolbar");
        getChildren().add(toolbar);
    }

    private static void configToolbarButton(Button b, Node graphic, String tooltip) {
        b.setGraphic(graphic);
        b.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        b.setTooltip(new Tooltip(tooltip));
        b.getStyleClass().add("mt-debug-toolbar-button");
    }

    public void onStartOrContinue() {
        if (state == DebuggerEventBus.State.PAUSED) {
            bridge.cont();
            return;
        }
        Path active = ctx.getTabPane() != null ? ctx.getTabPane().activePath() : null;
        if (active == null) {
            statusText.set("Open a file to debug");
            return;
        }
        try {
            ctx.getOutputPane().clearDebugConsole();
            ctx.getOutputPane().focusDebugConsole();
            bridge.start(active);
        } catch (Exception ex) {
            statusText.set("Start failed: " + ex.getMessage());
            ctx.getOutputPane().appendDebugConsole("[error] " + ex.getMessage(), "stderr");
        }
    }

    public void onRestart() {
        Path last = bridge.getLastFile();
        if (last == null) {
            onStartOrContinue();
            return;
        }
        bridge.stop();
        try {
            ctx.getOutputPane().clearDebugConsole();
            bridge.start(last);
        } catch (Exception ex) {
            statusText.set("Restart failed: " + ex.getMessage());
        }
    }

    public void onAttach() {
        Window owner = getScene() != null ? getScene().getWindow() : null;
        var dlg = Dialogs.prompt(owner, "Attach to mType Debug Host",
                "Connect to a running mType host (e.g. VertexForge started with --debug-port)",
                "host:port", "localhost:5005");
        var result = dlg.showAndWait();
        if (result.isEmpty()) return;
        String addr = result.get().trim();
        if (addr.isEmpty()) return;

        String host = "localhost";
        int port;
        int colon = addr.lastIndexOf(':');
        try {
            if (colon >= 0) {
                String h = addr.substring(0, colon).trim();
                if (!h.isEmpty()) host = h;
                port = Integer.parseInt(addr.substring(colon + 1).trim());
            } else {
                port = Integer.parseInt(addr);
            }
        } catch (NumberFormatException ex) {
            statusText.set("Invalid address: " + addr);
            return;
        }

        try {
            ctx.getOutputPane().clearDebugConsole();
            ctx.getOutputPane().focusDebugConsole();
            bridge.attach(host, port);
        } catch (Exception ex) {
            statusText.set("Attach failed: " + ex.getMessage());
            ctx.getOutputPane().appendDebugConsole("[error] " + ex.getMessage(), "stderr");
        }
    }

    /* ============================== sections ============================== */

    private void buildSections() {
        varRoot.getChildren().setAll(localRoot, globalRoot);
        varRoot.setExpanded(true);
        localRoot.setExpanded(true);
        globalRoot.setExpanded(false);
        variablesTree.setShowRoot(false);
        variablesTree.getStyleClass().add("mt-debug-tree");
        variablesTree.setCellFactory(_ -> new VarCell());

        watchList.getStyleClass().add("mt-debug-list");
        watchList.setCellFactory(_ -> new WatchCell());
        watchInput.setPromptText("Add expression and press Enter");
        watchInput.getStyleClass().add("mt-debug-watch-input");
        watchInput.setOnAction(_ -> addWatchFromInput());
        VBox watchBox = new VBox(4, watchInput, watchList);
        VBox.setVgrow(watchList, Priority.ALWAYS);

        stackList.getStyleClass().add("mt-debug-list");
        stackList.setCellFactory(_ -> new ListCell<>() {
            @Override protected void updateItem(Frame item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayName());
            }
        });
        stackList.getSelectionModel().selectedItemProperty().addListener((_, _, sel) -> {
            if (sel == null) return;
            currentFrame = stackList.getSelectionModel().getSelectedIndex();
            jumpTo(sel);
        });

        breakpointList.getStyleClass().add("mt-debug-list");
        breakpointList.setCellFactory(_ -> new BreakpointCell());

        getChildren().addAll(
                section("VARIABLES", variablesTree, true),
                section("WATCH", watchBox, false),
                section("CALL STACK", stackList, false),
                section("BREAKPOINTS", breakpointList, false));
    }

    private TitledPane section(String title, Node body, boolean expanded) {
        TitledPane pane = new TitledPane(title, body);
        pane.setExpanded(expanded);
        pane.setAnimated(false);
        pane.getStyleClass().add("mt-debug-section");
        VBox.setVgrow(pane, Priority.ALWAYS);
        return pane;
    }

    /* ============================== events ============================== */

    private void wireEvents() {
        events.onStateChanged(e -> applyState(e.state()));
        events.onStarted(_ -> {
            clearVariablesAndStack();
            statusText.set("Running");
        });
        events.onStopped(e -> {
            String name = e.file() != null && e.file().getFileName() != null
                    ? e.file().getFileName().toString() : "?";
            statusText.set("Paused at " + name + ":" + (e.line() + 1) + " (" + e.reason() + ")");
            if (e.message() != null && !e.message().isEmpty()) {
                ctx.getOutputPane().appendDebugConsole("[stop] " + e.message(), "stderr");
            }
            currentFrame = 0;
            // Bridge auto-fetches stack + locals + globals. Kick watches once the next
            // VARIABLES (local) lands — but watches are sticky-evaluable now too.
            kickWatchQueue();
        });
        events.onResumed(() -> {
            statusText.set("Running");
            clearVariablesAndStack();
        });
        events.onTerminated(() -> {
            statusText.set("Terminated");
            clearVariablesAndStack();
            evalQueue.clear();
            inFlightWatch = null;
        });
        events.onStack(e -> stackList.getItems().setAll(e.frames()));
        events.onVariables(e -> {
            TreeItem<VarNode> root = "global".equals(e.scope()) ? globalRoot : localRoot;
            root.getChildren().clear();
            for (Variable v : e.variables()) root.getChildren().add(makeNode(v));
            root.setExpanded(true);
        });
        events.onExpandedVar(e -> {
            TreeItem<VarNode> parent = pendingExpansions.remove(e.reference());
            if (parent == null) return;
            parent.getChildren().clear();
            for (Variable v : e.children()) parent.getChildren().add(makeNode(v));
        });
        events.onEvaluate(e -> {
            WatchEntry target = inFlightWatch;
            inFlightWatch = null;
            if (target != null) {
                target.value.set(e.value() == null ? "" : e.value());
                target.type.set(e.type() == null ? "" : e.type());
                watchList.refresh();
            }
            kickWatchQueue();
        });
        events.onError(e -> ctx.getOutputPane().appendDebugConsole("[debugger error] " + e.message(), "stderr"));
        events.onOutput(e -> {
            String text = e.text() == null ? "" : e.text();
            ctx.getOutputPane().appendDebugConsole(text, e.category());
        });

        breakpoints.addListener((_, _) -> rebuildBreakpointList());
        rebuildBreakpointList();
    }

    private TreeItem<VarNode> makeNode(Variable v) {
        TreeItem<VarNode> item = new TreeItem<>(VarNode.variable(v));
        if (v.expandable()) {
            item.getChildren().add(new TreeItem<>(VarNode.label("Loading...")));
            item.expandedProperty().addListener((_, _, expanded) -> {
                if (expanded && item.getChildren().size() == 1
                        && item.getChildren().getFirst().getValue().placeholder()) {
                    pendingExpansions.put(v.reference(), item);
                    bridge.expandVariable(v.reference());
                }
            });
        }
        return item;
    }

    private void clearVariablesAndStack() {
        localRoot.getChildren().clear();
        globalRoot.getChildren().clear();
        stackList.getItems().clear();
        pendingExpansions.clear();
    }

    private void applyState(DebuggerEventBus.State next) {
        this.state = next;
        boolean live = next == DebuggerEventBus.State.RUNNING || next == DebuggerEventBus.State.PAUSED;
        boolean paused = next == DebuggerEventBus.State.PAUSED;
        startContinueBtn.setDisable(next == DebuggerEventBus.State.RUNNING);
        attachBtn.setDisable(live);
        stopBtn.setDisable(!live);
        stepOverBtn.setDisable(!paused);
        stepIntoBtn.setDisable(!paused);
        stepOutBtn.setDisable(!paused);
        restartBtn.setDisable(next == DebuggerEventBus.State.IDLE && bridge.getLastFile() == null);
        startContinueBtn.setTooltip(new Tooltip(
                paused ? "Continue (F5)" :
                next == DebuggerEventBus.State.RUNNING ? "Running..." :
                "Start Debugging (F5)"));
        if (next == DebuggerEventBus.State.IDLE) statusText.set("Idle");
    }

    /* ============================== watches ============================== */

    private void addWatchFromInput() {
        String expr = watchInput.getText() == null ? "" : watchInput.getText().trim();
        if (expr.isEmpty()) return;
        WatchEntry entry = new WatchEntry(expr);
        watchList.getItems().add(entry);
        watchInput.clear();
        if (state == DebuggerEventBus.State.PAUSED) {
            evalQueue.add(entry);
            kickWatchQueue();
        }
    }

    private void kickWatchQueue() {
        if (state != DebuggerEventBus.State.PAUSED) return;
        if (inFlightWatch != null) return;
        if (evalQueue.isEmpty()) {
            // After a stop, re-evaluate all known watches once
            evalQueue.addAll(new ArrayList<>(watchList.getItems()));
            if (evalQueue.isEmpty()) return;
        }
        WatchEntry next = evalQueue.poll();
        inFlightWatch = next;
        String key = "watch-" + watchKeySeq.incrementAndGet();
        bridge.evaluate(key, next.expression, currentFrame);
    }

    /* ============================== breakpoints view ============================== */

    private void rebuildBreakpointList() {
        Map<Path, Set<Integer>> snap = breakpoints.snapshot();
        List<BreakpointEntry> rows = new ArrayList<>();
        for (Map.Entry<Path, Set<Integer>> e : snap.entrySet()) {
            for (int line0 : e.getValue()) rows.add(new BreakpointEntry(e.getKey(), line0));
        }
        breakpointList.getItems().setAll(rows);
    }

    private void jumpTo(Frame frame) {
        Path p = frame.filePath();
        if (p == null || ctx.getTabPane() == null) return;
        Platform.runLater(() -> {
            try { ctx.getTabPane().openFile(p); } catch (Exception ignored) {}
            try {
                var tab = ctx.getTabPane().openTabs().stream()
                        .filter(t -> t.getPath() != null && t.getPath().equals(p))
                        .findFirst().orElse(null);
                if (tab != null && frame.line() > 0) {
                    int line0 = Math.max(0, frame.line() - 1);
                    var ca = tab.getCodeArea();
                    if (line0 < ca.getParagraphs().size()) {
                        ca.moveTo(line0, 0);
                        ca.requestFollowCaret();
                    }
                }
            } catch (Exception ignored) {}
        });
    }

    /* ============================== data + cells ============================== */

    private record VarNode(String label, Variable variable) {
        static VarNode label(String s) { return new VarNode(s, null); }
        static VarNode variable(Variable v) { return new VarNode(null, v); }
        boolean isVariable() { return variable != null; }
        boolean placeholder() { return !isVariable() && "Loading...".equals(label); }
    }

    private static final class WatchEntry {
        final String expression;
        final SimpleStringProperty value = new SimpleStringProperty("(not evaluated)");
        final SimpleStringProperty type = new SimpleStringProperty("");
        WatchEntry(String expression) { this.expression = expression; }
    }

    private record BreakpointEntry(Path file, int line0) {}

    private static final class VarCell extends TreeCell<VarNode> {
        @Override protected void updateItem(VarNode item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) { setText(null); setGraphic(null); return; }
            if (item.isVariable()) {
                Variable v = item.variable();
                Label name = new Label(v.name() + ":");
                name.getStyleClass().add("mt-debug-var-name");
                Label value = new Label(v.value());
                value.getStyleClass().add("mt-debug-var-value");
                Label type = new Label(v.type() == null || v.type().isEmpty() ? "" : "  " + v.type());
                type.getStyleClass().add("mt-debug-var-type");
                HBox row = new HBox(4, name, value, type);
                row.setAlignment(Pos.CENTER_LEFT);
                setText(null);
                setGraphic(row);
            } else {
                setText(item.label());
                setGraphic(null);
            }
        }
    }

    private final class WatchCell extends ListCell<WatchEntry> {
        @Override protected void updateItem(WatchEntry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) { setText(null); setGraphic(null); setContextMenu(null); return; }
            Label name = new Label(item.expression + ":");
            name.getStyleClass().add("mt-debug-var-name");
            Label value = new Label();
            value.textProperty().bind(item.value);
            value.getStyleClass().add("mt-debug-var-value");
            HBox row = new HBox(4, name, value);
            row.setAlignment(Pos.CENTER_LEFT);
            setText(null);
            setGraphic(row);

            ContextMenu menu = new ContextMenu();
            MenuItem remove = new MenuItem("Remove");
            remove.setOnAction(_ -> watchList.getItems().remove(item));
            menu.getItems().add(remove);
            setContextMenu(menu);
        }
    }

    private final class BreakpointCell extends ListCell<BreakpointEntry> {
        @Override protected void updateItem(BreakpointEntry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) { setText(null); setGraphic(null); return; }
            Region dot = new Region();
            dot.getStyleClass().addAll("mt-gutter-breakpoint", "mt-gutter-breakpoint-on");
            dot.setPrefSize(10, 10);
            dot.setMinSize(10, 10);
            dot.setMaxSize(10, 10);
            String fname = item.file().getFileName() == null ? item.file().toString()
                    : item.file().getFileName().toString();
            Label label = new Label(fname + ":" + (item.line0() + 1));
            label.getStyleClass().add("mt-debug-bp-label");
            HBox spacer = new HBox();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Button remove = new Button("✕");
            remove.getStyleClass().add("mt-debug-bp-remove");
            remove.setOnAction(_ -> breakpoints.toggle(item.file(), item.line0()));
            HBox row = new HBox(6, dot, label, spacer, remove);
            row.setAlignment(Pos.CENTER_LEFT);
            setText(null);
            setGraphic(row);
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                    Path p = item.file();
                    if (ctx.getTabPane() != null) {
                        ctx.getTabPane().openFile(p);
                        try {
                            var tab = ctx.getTabPane().openTabs().stream()
                                    .filter(t -> t.getPath() != null && t.getPath().equals(p))
                                    .findFirst().orElse(null);
                            if (tab != null && item.line0() < tab.getCodeArea().getParagraphs().size()) {
                                tab.getCodeArea().moveTo(item.line0(), 0);
                                tab.getCodeArea().requestFollowCaret();
                            }
                        } catch (Exception ignored) {}
                    }
                }
            });
        }
    }
}
