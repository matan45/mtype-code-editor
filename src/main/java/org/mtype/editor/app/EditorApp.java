package org.mtype.editor.app;

import javafx.application.Application;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.control.ToolBar;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.mtype.editor.lsp.LspBridge;
import org.mtype.editor.process.RunController;
import org.mtype.editor.ui.editor.EditorTabPane;
import org.mtype.editor.ui.git.GitChangesView;
import org.mtype.editor.ui.output.OutputPane;
import org.mtype.editor.ui.status.StatusBar;
import org.mtype.editor.ui.tree.WorkspaceTreeView;
import org.mtype.editor.workspace.SettingsStore;
import org.mtype.editor.workspace.Workspace;
import org.mtype.editor.workspace.WorkspaceSettings;

import java.io.File;
import java.nio.file.Path;

public class EditorApp extends Application {
    private AppContext ctx;
    private Stage stage;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        loadBundledFonts();
        ctx = new AppContext();
        ctx.setSettings(SettingsStore.load());

        StatusBar status = new StatusBar();
        ctx.setStatusBar(status);

        OutputPane output = new OutputPane();
        ctx.setOutputPane(output);

        EditorTabPane tabPane = new EditorTabPane(ctx);
        ctx.setTabPane(tabPane);

        WorkspaceTreeView tree = new WorkspaceTreeView(ctx);
        ctx.setTreeView(tree);

        GitChangesView gitChanges = new GitChangesView(ctx);
        ctx.setGitChangesView(gitChanges);

        LspBridge lsp = new LspBridge(ctx);
        ctx.setLspBridge(lsp);

        output.attachCallHierarchy(ctx);

        RunController runController = new RunController(ctx);
        ctx.setRunController(runController);

        Button runBtn = new Button("Run");
        Button stopBtn = new Button("Stop");
        runBtn.disableProperty().bind(runController.runningProperty());
        stopBtn.disableProperty().bind(runController.runningProperty().not());
        runBtn.setOnAction(e -> {
            Path active = tabPane.activePath();
            if (active != null) runController.run(active);
        });
        stopBtn.setOnAction(e -> runController.stop());

        ToolBar toolbar = new ToolBar(runBtn, stopBtn);

        MenuBar menuBar = buildMenuBar();
        BorderPane topBar = new BorderPane();
        topBar.setTop(menuBar);
        topBar.setCenter(toolbar);

        SplitPane verticalSplit = new SplitPane(tabPane, output);
        verticalSplit.setOrientation(Orientation.VERTICAL);
        verticalSplit.setDividerPositions(0.72);

        Node sidePanel = buildSidePanel(tree, gitChanges);

        SplitPane mainSplit = new SplitPane(sidePanel, verticalSplit);
        mainSplit.setOrientation(Orientation.HORIZONTAL);
        mainSplit.setDividerPositions(0.22);

        BorderPane root = new BorderPane();
        root.setTop(topBar);
        root.setCenter(mainSplit);
        root.setBottom(status);

        Scene scene = new Scene(root, 1280, 800);
        var cssUrl = EditorApp.class.getResource("/css/mtype-dark.css");
        if (cssUrl != null) scene.getStylesheets().add(cssUrl.toExternalForm());

        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN),
                tabPane::saveActive);

        stage.setTitle("mType Editor");
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> shutdown());
        stage.show();
    }

    private MenuBar buildMenuBar() {
        MenuBar mb = new MenuBar();
        Menu file = new Menu("File");
        MenuItem openFolder = new MenuItem("Open Folder...");
        openFolder.setAccelerator(new KeyCodeCombination(KeyCode.O,
                KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        openFolder.setOnAction(e -> openFolder());

        MenuItem save = new MenuItem("Save");
        save.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN));
        save.setOnAction(e -> ctx.getTabPane().saveActive());

        MenuItem settings = new MenuItem("Settings...");
        settings.setAccelerator(new KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN));
        settings.setOnAction(e -> openSettings());

        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(e -> { shutdown(); stage.close(); });

        file.getItems().addAll(openFolder, save, new SeparatorMenuItem(), settings, new SeparatorMenuItem(), exit);

        Menu code = new Menu("Code");
        MenuItem format = new MenuItem("Format Document");
        format.setAccelerator(new KeyCodeCombination(KeyCode.F,
                KeyCombination.SHIFT_DOWN, KeyCombination.ALT_DOWN));
        format.setOnAction(e -> ctx.getTabPane().formatActive());

        MenuItem goToDef = new MenuItem("Go to Definition");
        goToDef.setAccelerator(new KeyCodeCombination(KeyCode.F12));
        goToDef.setOnAction(e -> ctx.getTabPane().goToDefinitionActive());

        MenuItem rename = new MenuItem("Rename Symbol");
        rename.setAccelerator(new KeyCodeCombination(KeyCode.F2));
        rename.setOnAction(e -> ctx.getTabPane().renameActive());

        MenuItem callHierarchy = new MenuItem("Show Call Hierarchy");
        callHierarchy.setAccelerator(new KeyCodeCombination(KeyCode.H,
                KeyCombination.CONTROL_DOWN, KeyCombination.ALT_DOWN));
        callHierarchy.setOnAction(e -> ctx.getTabPane().callHierarchyActive());

        code.getItems().addAll(format, goToDef, rename, callHierarchy);

        mb.getMenus().addAll(file, code);
        return mb;
    }

    private void openSettings() {
        org.mtype.editor.ui.settings.SettingsDialog dlg =
                new org.mtype.editor.ui.settings.SettingsDialog(stage, ctx.getSettings());
        dlg.showAndWait().ifPresent(updated -> {
            ctx.setSettings(updated);
            try {
                SettingsStore.save(updated);
                ctx.getStatusBar().setMessage("Settings saved — restart to apply fonts/theme/LSP path");
            } catch (java.io.IOException ex) {
                ctx.getStatusBar().setMessage("Save failed: " + ex.getMessage());
            }
        });
    }

    private void openFolder() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Open Folder");
        File chosen = dc.showDialog(stage);
        if (chosen == null) return;
        Path root = chosen.toPath();
        Workspace ws = new Workspace(root);
        ctx.openWorkspace(ws);
        stage.setTitle("mType Editor - " + root.getFileName());
    }

    private Node buildSidePanel(WorkspaceTreeView tree, GitChangesView gitChanges) {
        StackPane content = new StackPane(tree, gitChanges);
        gitChanges.setVisible(false);
        gitChanges.setManaged(false);

        ToggleGroup group = new ToggleGroup();
        ToggleButton explorerButton = activityButton("Explorer", explorerIcon());
        ToggleButton gitButton = activityButton("Git", gitIcon());
        explorerButton.setToggleGroup(group);
        gitButton.setToggleGroup(group);
        explorerButton.setSelected(true);

        explorerButton.setOnAction(e -> {
            explorerButton.setSelected(true);
            showPanel(content, tree);
        });
        gitButton.setOnAction(e -> {
            gitButton.setSelected(true);
            showPanel(content, gitChanges);
        });

        VBox activityBar = new VBox(explorerButton, gitButton);
        activityBar.getStyleClass().add("mt-activity-bar");

        BorderPane sidePanel = new BorderPane(content);
        sidePanel.setLeft(activityBar);
        sidePanel.getStyleClass().add("mt-side-panel");
        sidePanel.setMinWidth(220);
        sidePanel.setPrefWidth(280);
        return sidePanel;
    }

    private ToggleButton activityButton(String tooltip, Node graphic) {
        ToggleButton button = new ToggleButton();
        button.setGraphic(graphic);
        button.setTooltip(new Tooltip(tooltip));
        button.getStyleClass().add("mt-activity-button");
        return button;
    }

    private void showPanel(StackPane content, Node active) {
        for (Node child : content.getChildren()) {
            boolean show = child == active;
            child.setVisible(show);
            child.setManaged(show);
        }
    }

    private Node explorerIcon() {
        SVGPath file = new SVGPath();
        file.setContent("M6 3 H18 L24 9 V29 H6 Z M18 3 V9 H24");
        file.getStyleClass().add("mt-activity-icon");

        SVGPath back = new SVGPath();
        back.setContent("M3 8 V32 H20");
        back.getStyleClass().add("mt-activity-icon-muted");

        Group group = new Group(back, file);
        group.getStyleClass().add("mt-activity-graphic");
        return group;
    }

    private Node gitIcon() {
        Circle top = activityCircle(16, 6);
        Circle middle = activityCircle(24, 15);
        Circle bottom = activityCircle(11, 27);
        Line main = activityLine(16, 8, 11, 25);
        Line branch = activityLine(17, 8, 23, 14);

        Group group = new Group(main, branch, top, middle, bottom);
        group.getStyleClass().add("mt-activity-graphic");
        return group;
    }

    private Circle activityCircle(double x, double y) {
        Circle circle = new Circle(x, y, 4);
        circle.getStyleClass().add("mt-activity-icon");
        return circle;
    }

    private Line activityLine(double x1, double y1, double x2, double y2) {
        Line line = new Line(x1, y1, x2, y2);
        line.getStyleClass().add("mt-activity-icon");
        return line;
    }

    private void shutdown() {
        try { if (ctx.getRunController() != null) ctx.getRunController().stop(); } catch (Exception ignored) {}
        try { if (ctx.getLspBridge() != null) ctx.getLspBridge().stop(); } catch (Exception ignored) {}
    }

    private void loadBundledFonts() {
        String[] files = {
                "/fonts/JetBrainsMono-Regular.ttf",
                "/fonts/JetBrainsMono-Italic.ttf",
                "/fonts/JetBrainsMono-Bold.ttf",
                "/fonts/JetBrainsMono-BoldItalic.ttf",
        };
        for (String f : files) {
            try (var in = EditorApp.class.getResourceAsStream(f)) {
                if (in != null) Font.loadFont(in, 14);
            } catch (Exception ignored) {
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
