package org.mtype.editor.app;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.control.ToolBar;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.mtype.editor.git.GitService;
import org.mtype.editor.lsp.LspBridge;
import org.mtype.editor.process.BuildController;
import org.mtype.editor.process.RunController;
import org.mtype.editor.ui.dialogs.NewProjectDialog;
import org.mtype.editor.ui.dialogs.NewWorkspaceDialog;
import org.mtype.editor.ui.editor.EditorTabPane;
import org.mtype.editor.ui.git.GitChangesView;
import org.mtype.editor.ui.output.OutputPane;
import org.mtype.editor.ui.search.FindInFilesWindow;
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
    private boolean shutdownStarted;
    private TextField explorerFilterField;
    private ToggleButton explorerActivityButton;
    private SplitPane verticalSplit;
    private OutputPane outputPane;
    private double lastBottomDivider = 0.72;

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
        applyPersistedBottomTabVisibility(output);

        EditorTabPane tabPane = new EditorTabPane(ctx);
        ctx.setTabPane(tabPane);

        WorkspaceTreeView tree = new WorkspaceTreeView(ctx);
        ctx.setTreeView(tree);

        GitService gitService = new GitService(ctx);
        ctx.setGitService(gitService);

        GitChangesView gitChanges = new GitChangesView(ctx);
        ctx.setGitChangesView(gitChanges);

        LspBridge lsp = new LspBridge(ctx);
        ctx.setLspBridge(lsp);

        output.attachCallHierarchy(ctx);
        output.attachProblems(ctx);

        RunController runController = new RunController(ctx);
        ctx.setRunController(runController);

        BuildController buildController = new BuildController(ctx);
        ctx.setBuildController(buildController);

        Button runBtn = new Button("Run");
        Button stopBtn = new Button("Stop");
        runBtn.disableProperty().bind(runController.runningProperty());
        stopBtn.disableProperty().bind(runController.runningProperty().not());
        runBtn.setOnAction(_ -> {
            Path active = tabPane.activePath();
            if (active != null) runController.run(active);
        });
        stopBtn.setOnAction(e -> runController.stop());

        Button buildBtn = new Button("Build");
        Button stopBuildBtn = new Button("Stop Build");
        buildBtn.disableProperty().bind(
                buildController.buildingProperty().or(ctx.hasProjectFileProperty().not()));
        stopBuildBtn.disableProperty().bind(buildController.buildingProperty().not());
        buildBtn.setOnAction(_ -> buildController.build());
        stopBuildBtn.setOnAction(_ -> buildController.stop());

        ToolBar toolbar = new ToolBar(runBtn, stopBtn, buildBtn, stopBuildBtn);

        MenuBar menuBar = buildMenuBar();
        BorderPane topBar = new BorderPane();
        topBar.setTop(menuBar);
        topBar.setCenter(toolbar);

        SplitPane verticalSplit = new SplitPane(tabPane, output);
        verticalSplit.setOrientation(Orientation.VERTICAL);
        verticalSplit.setDividerPositions(lastBottomDivider);
        this.verticalSplit = verticalSplit;
        this.outputPane = output;

        Node sidePanel = buildSidePanel(tree, gitChanges);

        SplitPane mainSplit = new SplitPane(sidePanel, verticalSplit);
        mainSplit.setOrientation(Orientation.HORIZONTAL);
        mainSplit.setDividerPositions(0.10);

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
        stage.setMaximized(true);
        stage.show();
    }

    private MenuBar buildMenuBar() {
        MenuBar mb = new MenuBar();
        Menu file = new Menu("File");
        MenuItem openFolder = new MenuItem("Open Folder...");
        openFolder.setAccelerator(new KeyCodeCombination(KeyCode.O,
                KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        openFolder.setOnAction(e -> openFolder());

        MenuItem newProject = new MenuItem("New Project...");
        newProject.setOnAction(e -> openNewProjectDialog());

        MenuItem newWorkspace = new MenuItem("New Workspace...");
        newWorkspace.setOnAction(e -> openNewWorkspaceDialog());

        MenuItem save = new MenuItem("Save");
        save.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN));
        save.setOnAction(e -> ctx.getTabPane().saveActive());

        MenuItem settings = new MenuItem("Settings...");
        settings.setAccelerator(new KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN));
        settings.setOnAction(e -> openSettings());

        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(e -> { shutdown(); Platform.exit(); });

        file.getItems().addAll(openFolder, new SeparatorMenuItem(), newProject, newWorkspace,
                new SeparatorMenuItem(), save, new SeparatorMenuItem(), settings,
                new SeparatorMenuItem(), exit);

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

        MenuItem findInFiles = new MenuItem("Find in Files...");
        findInFiles.setAccelerator(new KeyCodeCombination(KeyCode.F,
                KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        findInFiles.setOnAction(e -> openFindInFiles());

        code.getItems().addAll(format, goToDef, rename, callHierarchy,
                new SeparatorMenuItem(), findInFiles);

        Menu build = new Menu("Build");
        BuildController bc = ctx.getBuildController();
        javafx.beans.binding.BooleanBinding noBuild =
                bc.buildingProperty().or(ctx.hasProjectFileProperty().not());

        MenuItem buildItem = new MenuItem("Build");
        buildItem.setAccelerator(new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN));
        buildItem.setOnAction(e -> bc.build());
        buildItem.disableProperty().bind(noBuild);

        MenuItem buildLib = new MenuItem("Build Library");
        buildLib.setOnAction(e -> bc.buildLibrary());
        buildLib.disableProperty().bind(noBuild);

        MenuItem buildExe = new MenuItem("Build Executable");
        buildExe.setOnAction(e -> bc.buildExecutable());
        buildExe.disableProperty().bind(noBuild);

        MenuItem buildGui = new MenuItem("Build GUI Executable");
        buildGui.setOnAction(e -> bc.buildGuiExecutable());
        buildGui.disableProperty().bind(noBuild);

        MenuItem depsTree = new MenuItem("Show Dependency Tree");
        depsTree.setOnAction(e -> bc.showDepsTree());
        depsTree.disableProperty().bind(noBuild);

        MenuItem depsForFile = new MenuItem("Show Dependencies for Current File");
        depsForFile.setAccelerator(new KeyCodeCombination(KeyCode.D,
                KeyCombination.CONTROL_DOWN, KeyCombination.ALT_DOWN));
        depsForFile.setOnAction(e -> bc.showDepsForFile(ctx.getTabPane().activePath()));
        depsForFile.disableProperty().bind(noBuild);

        MenuItem stopBuild = new MenuItem("Stop Build");
        stopBuild.setOnAction(e -> bc.stop());
        stopBuild.disableProperty().bind(bc.buildingProperty().not());

        build.getItems().addAll(buildItem, buildLib, buildExe, buildGui,
                new SeparatorMenuItem(), depsTree, depsForFile,
                new SeparatorMenuItem(), stopBuild);

        Menu view = new Menu("View");
        MenuItem filterFiles = new MenuItem("Filter Files in Explorer");
        filterFiles.setAccelerator(new KeyCodeCombination(KeyCode.E,
                KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        filterFiles.setOnAction(e -> toggleExplorerFilter());

        MenuItem toggleBottom = new MenuItem("Toggle Bottom Panel");
        toggleBottom.setAccelerator(new KeyCodeCombination(KeyCode.J, KeyCombination.SHORTCUT_DOWN));
        toggleBottom.setOnAction(e -> toggleBottomPanel());

        Menu bottomTabs = new Menu("Bottom Tabs");
        OutputPane outputForMenu = ctx.getOutputPane();
        for (String name : outputForMenu.tabNames()) {
            CheckMenuItem item = new CheckMenuItem(name);
            item.setSelected(outputForMenu.isTabVisible(name));
            item.setOnAction(e -> {
                outputForMenu.setTabVisible(name, item.isSelected());
                persistBottomTabVisibility(name, item.isSelected());
            });
            bottomTabs.getItems().add(item);
        }

        view.getItems().addAll(filterFiles, toggleBottom, new SeparatorMenuItem(), bottomTabs);

        mb.getMenus().addAll(file, code, build, view);
        return mb;
    }

    private void openFindInFiles() {
        FindInFilesWindow w = ctx.getFindInFilesWindow();
        if (w == null) {
            w = new FindInFilesWindow(ctx, stage);
            ctx.setFindInFilesWindow(w);
        }
        w.showOrFocus();
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

    private void openNewProjectDialog() {
        Path root = resolveTargetRoot("Choose location for new project");
        if (root == null) return;
        boolean wasOpen = ctx.getWorkspace() != null
                && ctx.getWorkspace().getRoot().equals(root);
        NewProjectDialog dlg = new NewProjectDialog(stage, root);
        dlg.showAndWait().ifPresent(p -> afterScaffoldWritten(p, root, wasOpen));
    }

    private void openNewWorkspaceDialog() {
        Path root = resolveTargetRoot("Choose location for new workspace");
        if (root == null) return;
        boolean wasOpen = ctx.getWorkspace() != null
                && ctx.getWorkspace().getRoot().equals(root);
        NewWorkspaceDialog dlg = new NewWorkspaceDialog(stage, root);
        dlg.showAndWait().ifPresent(p -> afterScaffoldWritten(p, root, wasOpen));
    }

    private Path resolveTargetRoot(String chooserTitle) {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle(chooserTitle);
        if (ctx.getWorkspace() != null) {
            File initial = ctx.getWorkspace().getRoot().toFile();
            if (initial.isDirectory()) dc.setInitialDirectory(initial);
        }
        File chosen = dc.showDialog(stage);
        return chosen == null ? null : chosen.toPath();
    }

    private void afterScaffoldWritten(Path writtenFile, Path root, boolean wasAlreadyOpen) {
        if (!wasAlreadyOpen) {
            ctx.openWorkspace(new Workspace(root));
            stage.setTitle("mType Editor - " + root.getFileName());
        } else {
            ctx.getTreeView().refresh();
            ctx.refreshHasProjectFile();
        }
        ctx.getTabPane().openFile(writtenFile);
        ctx.getStatusBar().setMessage("Created " + writtenFile.getFileName());
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
        TextField filterField = new TextField();
        filterField.setPromptText("Filter files...");
        filterField.getStyleClass().add("mt-tree-filter");
        filterField.setVisible(false);
        filterField.setManaged(false);
        filterField.textProperty().addListener((obs, oldV, newV) -> tree.setFilter(newV));
        filterField.setOnKeyPressed(ev -> {
            if (ev.getCode() == KeyCode.ESCAPE) {
                hideExplorerFilter(filterField, tree);
                ev.consume();
            }
        });
        tree.setOnFilterReset(filterField::clear);
        this.explorerFilterField = filterField;

        VBox explorerPane = new VBox(filterField, tree);
        VBox.setVgrow(tree, Priority.ALWAYS);

        StackPane content = new StackPane(explorerPane, gitChanges);
        gitChanges.setVisible(false);
        gitChanges.setManaged(false);

        ToggleGroup group = new ToggleGroup();
        ToggleButton explorerButton = activityButton("Explorer", explorerIcon());
        ToggleButton gitButton = activityButton("Git", gitIcon());
        explorerButton.setToggleGroup(group);
        gitButton.setToggleGroup(group);
        explorerButton.setSelected(true);
        this.explorerActivityButton = explorerButton;

        explorerButton.setOnAction(e -> {
            explorerButton.setSelected(true);
            showPanel(content, explorerPane);
        });
        gitButton.setOnAction(e -> {
            gitButton.setSelected(true);
            hideExplorerFilter(filterField, tree);
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

    private void hideExplorerFilter(TextField filterField, WorkspaceTreeView tree) {
        filterField.clear();
        filterField.setVisible(false);
        filterField.setManaged(false);
        tree.requestFocus();
    }

    private void applyPersistedBottomTabVisibility(OutputPane output) {
        var prefs = ctx.getSettings().view;
        if (prefs == null || prefs.hiddenBottomTabs == null) return;
        for (String name : output.tabNames()) {
            if (prefs.hiddenBottomTabs.contains(name)) output.setTabVisible(name, false);
        }
    }

    private void persistBottomTabVisibility(String name, boolean visible) {
        var settings = ctx.getSettings();
        if (settings.view == null) settings.view = new org.mtype.editor.workspace.WorkspaceSettings.ViewPrefs();
        if (settings.view.hiddenBottomTabs == null) settings.view.hiddenBottomTabs = new java.util.LinkedHashSet<>();
        if (visible) settings.view.hiddenBottomTabs.remove(name);
        else settings.view.hiddenBottomTabs.add(name);
        try {
            SettingsStore.save(settings);
        } catch (java.io.IOException ex) {
            ctx.getStatusBar().setMessage("Could not save view prefs: " + ex.getMessage());
        }
    }

    private void toggleBottomPanel() {
        if (verticalSplit == null || outputPane == null) return;
        if (verticalSplit.getItems().contains(outputPane)) {
            double[] positions = verticalSplit.getDividerPositions();
            if (positions.length > 0 && positions[0] > 0.05 && positions[0] < 0.95) {
                lastBottomDivider = positions[0];
            }
            verticalSplit.getItems().remove(outputPane);
        } else {
            verticalSplit.getItems().add(outputPane);
            verticalSplit.setDividerPositions(lastBottomDivider);
        }
    }

    private void toggleExplorerFilter() {
        if (explorerFilterField == null) return;
        // Make sure the Explorer panel is showing.
        if (explorerActivityButton != null && !explorerActivityButton.isSelected()) {
            explorerActivityButton.fire();
        }
        WorkspaceTreeView tree = ctx.getTreeView();
        if (explorerFilterField.isVisible()) {
            hideExplorerFilter(explorerFilterField, tree);
        } else {
            explorerFilterField.setVisible(true);
            explorerFilterField.setManaged(true);
            explorerFilterField.requestFocus();
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

    @Override
    public void stop() {
        shutdown();
    }

    private synchronized void shutdown() {
        if (shutdownStarted) return;
        shutdownStarted = true;
        try { if (ctx.getRunController() != null) ctx.getRunController().stop(); } catch (Exception ignored) {}
        try { if (ctx.getBuildController() != null) ctx.getBuildController().stop(); } catch (Exception ignored) {}
        try { if (ctx.getLspBridge() != null) ctx.getLspBridge().stop(); } catch (Exception ignored) {}
        try { if (ctx.getFindInFilesWindow() != null) ctx.getFindInFilesWindow().cancelSearch(); } catch (Exception ignored) {}
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
