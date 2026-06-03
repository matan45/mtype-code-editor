package org.mtype.editor.app;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import org.mtype.editor.debug.BreakpointService;
import org.mtype.editor.debug.DebuggerBridge;
import org.mtype.editor.debug.DebuggerEventBus;
import org.mtype.editor.git.GitService;
import org.mtype.editor.lsp.LspBridge;
import org.mtype.editor.process.BuildController;
import org.mtype.editor.process.PackageController;
import org.mtype.editor.process.RunController;
import org.mtype.editor.terminal.TerminalController;
import org.mtype.editor.ui.debug.DebuggerIcons;
import org.mtype.editor.ui.debug.DebuggerPanel;
import org.mtype.editor.ui.dialogs.AddPackageDialog;
import org.mtype.editor.ui.dialogs.Dialogs;
import org.mtype.editor.ui.dialogs.NewProjectDialog;
import org.mtype.editor.ui.dialogs.NewWorkspaceDialog;
import org.mtype.editor.ui.dialogs.WorkspaceSymbolPicker;
import org.mtype.editor.ui.chrome.WindowMaximizer;
import org.mtype.editor.ui.chrome.WindowResizer;
import org.mtype.editor.ui.chrome.WindowTitleBar;
import org.mtype.editor.ui.editor.EditorTabPane;
import org.mtype.editor.ui.git.GitChangesView;
import org.mtype.editor.ui.output.OutputPane;
import org.mtype.editor.ui.search.FindInFilesWindow;
import org.mtype.editor.ui.status.StatusBar;
import org.mtype.editor.ui.tree.WorkspaceTreeView;
import org.mtype.editor.workspace.SettingsStore;
import org.mtype.editor.workspace.Workspace;

import java.io.File;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
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
        TerminalController terminalController = new TerminalController(ctx);
        ctx.setTerminalController(terminalController);
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

        DebuggerEventBus debugEvents = new DebuggerEventBus();
        ctx.setDebuggerEventBus(debugEvents);
        BreakpointService breakpoints = new BreakpointService();
        ctx.setBreakpointService(breakpoints);
        DebuggerBridge debugger = new DebuggerBridge(ctx, debugEvents, breakpoints);
        ctx.setDebuggerBridge(debugger);
        breakpoints.attachBridge(debugger);
        DebuggerPanel dp = new DebuggerPanel(ctx);
        ctx.setDebuggerPanel();

        output.attachCallHierarchy(ctx);
        output.attachReferences(ctx);
        output.attachProblems(ctx);
        output.attachTerminal(ctx);
        output.attachOutline(ctx);

        RunController runController = new RunController(ctx);
        ctx.setRunController(runController);

        BuildController buildController = new BuildController(ctx);
        ctx.setBuildController(buildController);

        PackageController packageController = new PackageController(ctx);
        ctx.setPackageController(packageController);

        Button runBtn = toolbarIconButton(DebuggerIcons.playIcon(), "Run");
        runBtn.getStyleClass().add("mt-run-button");
        Button stopBtn = toolbarIconButton(DebuggerIcons.stopIcon(), "Stop");
        runBtn.disableProperty().bind(runController.runningProperty());
        stopBtn.disableProperty().bind(runController.runningProperty().not());
        runBtn.setOnAction(_ -> {
            Path active = tabPane.activePath();
            if (active != null) runController.run(active);
        });
        stopBtn.setOnAction(_ -> runController.stop());

        Button buildBtn = toolbarIconButton(DebuggerIcons.buildIcon(), "Build");
        buildBtn.getStyleClass().add("mt-build-button");
        buildBtn.disableProperty().bind(
                buildController.buildingProperty().or(ctx.hasProjectFileProperty().not()));
        buildBtn.setOnAction(_ -> buildController.build());

        ToolBar toolbar = new ToolBar(runBtn, stopBtn, buildBtn);

        MenuBar menuBar = buildMenuBar();
        WindowTitleBar titleBar = new WindowTitleBar(stage, menuBar);
        VBox topBar = new VBox(titleBar, toolbar);

        SplitPane verticalSplit = new SplitPane(tabPane, output);
        verticalSplit.setOrientation(Orientation.VERTICAL);
        verticalSplit.setDividerPositions(lastBottomDivider);
        this.verticalSplit = verticalSplit;
        this.outputPane = output;

        Node sidePanel = buildSidePanel(tree, gitChanges, dp);

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

        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F5), dp::onStartOrContinue);
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.F5, KeyCombination.SHIFT_DOWN),
                () -> ctx.getDebuggerBridge().stop());
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F10), () -> {
            if (ctx.getDebuggerBridge().getState() == DebuggerEventBus.State.PAUSED) {
                ctx.getDebuggerBridge().stepOver();
            }
        });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F11), () -> {
            if (ctx.getDebuggerBridge().getState() == DebuggerEventBus.State.PAUSED) {
                ctx.getDebuggerBridge().stepInto();
            }
        });
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.F11, KeyCombination.SHIFT_DOWN),
                () -> {
                    if (ctx.getDebuggerBridge().getState() == DebuggerEventBus.State.PAUSED) {
                        ctx.getDebuggerBridge().stepOut();
                    }
                });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.F9), () -> {
            EditorTabPane tp = ctx.getTabPane();
            if (tp == null) return;
            var tab = tp.openTabs().stream()
                    .filter(t -> t.getPath() != null && t.getPath().equals(tp.activePath()))
                    .findFirst().orElse(null);
            if (tab == null) return;
            int line0 = tab.getCodeArea().getCurrentParagraph();
            ctx.getBreakpointService().toggle(tab.getPath(), line0);
        });

        stage.initStyle(StageStyle.UNDECORATED);
        stage.setTitle("mType Editor");
        try (var in = EditorApp.class.getResourceAsStream("/icons/mtype-logo.png")) {
            if (in != null) stage.getIcons().add(new javafx.scene.image.Image(in));
        } catch (Exception ignored) {}
        stage.setScene(scene);
        stage.setOnCloseRequest(_ -> shutdown());
        WindowResizer.install(stage, scene);
        WindowMaximizer.maximize(stage);
        stage.show();
        openStartupFileArgument();
    }

    private MenuBar buildMenuBar() {
        MenuBar mb = new MenuBar();
        Menu file = new Menu("File");
        MenuItem newWindow = new MenuItem("New Window");
        newWindow.setAccelerator(new KeyCodeCombination(KeyCode.N,
                KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        newWindow.setOnAction(_ -> openNewWindow());

        MenuItem openFolder = new MenuItem("Open Folder...");
        openFolder.setAccelerator(new KeyCodeCombination(KeyCode.O,
                KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        openFolder.setOnAction(_ -> openFolder());

        MenuItem newProject = new MenuItem("New Project...");
        newProject.setOnAction(_ -> openNewProjectDialog());

        MenuItem newWorkspace = new MenuItem("New Workspace...");
        newWorkspace.setOnAction(_ -> openNewWorkspaceDialog());

        MenuItem save = new MenuItem("Save");
        save.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN));
        save.setOnAction(_ -> ctx.getTabPane().saveActive());

        MenuItem settings = new MenuItem("Settings...");
        settings.setAccelerator(new KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN));
        settings.setOnAction(_ -> openSettings());

        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(_ -> { shutdown(); Platform.exit(); });

        file.getItems().addAll(newWindow, new SeparatorMenuItem(),
                openFolder, new SeparatorMenuItem(), newProject, newWorkspace,
                new SeparatorMenuItem(), save, new SeparatorMenuItem(), settings,
                new SeparatorMenuItem(), exit);

        Menu code = new Menu("Code");
        MenuItem format = new MenuItem("Format Document");
        format.setAccelerator(new KeyCodeCombination(KeyCode.F,
                KeyCombination.SHIFT_DOWN, KeyCombination.ALT_DOWN));
        format.setOnAction(_ -> ctx.getTabPane().formatActive());

        MenuItem goToDef = new MenuItem("Go to Definition");
        goToDef.setAccelerator(new KeyCodeCombination(KeyCode.F12));
        goToDef.setOnAction(_ -> ctx.getTabPane().goToDefinitionActive());

        MenuItem rename = new MenuItem("Rename Symbol");
        rename.setAccelerator(new KeyCodeCombination(KeyCode.F2));
        rename.setOnAction(_ -> ctx.getTabPane().renameActive());

        MenuItem callHierarchy = new MenuItem("Show Call Hierarchy");
        callHierarchy.setAccelerator(new KeyCodeCombination(KeyCode.H,
                KeyCombination.CONTROL_DOWN, KeyCombination.ALT_DOWN));
        callHierarchy.setOnAction(_ -> ctx.getTabPane().callHierarchyActive());

        MenuItem findInFiles = new MenuItem("Find in Files...");
        findInFiles.setAccelerator(new KeyCodeCombination(KeyCode.F,
                KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        findInFiles.setOnAction(_ -> openFindInFiles());

        MenuItem gotoSymbol = new MenuItem("Go to Symbol in Workspace...");
        gotoSymbol.setAccelerator(new KeyCodeCombination(KeyCode.T,
                KeyCombination.SHORTCUT_DOWN));
        gotoSymbol.setOnAction(_ -> WorkspaceSymbolPicker.open(ctx, stage));

        code.getItems().addAll(format, goToDef, rename, callHierarchy,
                new SeparatorMenuItem(), gotoSymbol, findInFiles);

        Menu build = new Menu("Build");
        BuildController bc = ctx.getBuildController();
        javafx.beans.binding.BooleanBinding noBuild =
                bc.buildingProperty().or(ctx.hasProjectFileProperty().not());

        MenuItem buildItem = new MenuItem("Build");
        buildItem.setAccelerator(new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN));
        buildItem.setOnAction(_ -> bc.build());
        buildItem.disableProperty().bind(noBuild);

        MenuItem buildLib = new MenuItem("Build Library");
        buildLib.setOnAction(_ -> bc.buildLibrary());
        buildLib.disableProperty().bind(noBuild);

        MenuItem buildExe = new MenuItem("Build Executable");
        buildExe.setOnAction(_ -> bc.buildExecutable());
        buildExe.disableProperty().bind(noBuild);

        MenuItem buildGui = new MenuItem("Build GUI Executable");
        buildGui.setOnAction(_ -> bc.buildGuiExecutable());
        buildGui.disableProperty().bind(noBuild);

        MenuItem depsTree = new MenuItem("Show Dependency Tree");
        depsTree.setOnAction(_ -> bc.showDepsTree());
        depsTree.disableProperty().bind(noBuild);

        MenuItem depsForFile = new MenuItem("Show Dependencies for Current File");
        depsForFile.setAccelerator(new KeyCodeCombination(KeyCode.D,
                KeyCombination.CONTROL_DOWN, KeyCombination.ALT_DOWN));
        depsForFile.setOnAction(_ -> bc.showDepsForFile(ctx.getTabPane().activePath()));
        depsForFile.disableProperty().bind(noBuild);

        MenuItem stopBuild = new MenuItem("Stop Build");
        stopBuild.setOnAction(_ -> bc.stop());
        stopBuild.disableProperty().bind(bc.buildingProperty().not());

        build.getItems().addAll(buildItem, buildLib, buildExe, buildGui,
                new SeparatorMenuItem(), depsTree, depsForFile,
                new SeparatorMenuItem(), stopBuild);

        Menu pkg = new Menu("Package");
        PackageController pc = ctx.getPackageController();
        javafx.beans.binding.BooleanBinding noPkg =
                pc.runningProperty().or(ctx.hasProjectFileProperty().not());

        MenuItem pkgInstall = new MenuItem("Install");
        pkgInstall.setOnAction(_ -> pc.install(findSingleMtproj()));
        pkgInstall.disableProperty().bind(noPkg);

        MenuItem pkgAdd = new MenuItem("Add Package...");
        pkgAdd.setOnAction(_ -> {
            Path mtproj = findSingleMtproj();
            AddPackageDialog dlg = new AddPackageDialog(stage, mtproj);
            dlg.showAndWait().ifPresent(spec -> pc.add(mtproj, spec));
        });
        pkgAdd.disableProperty().bind(noPkg);

        MenuItem pkgRemove = new MenuItem("Remove Package...");
        pkgRemove.setOnAction(_ -> {
            Path mtproj = findSingleMtproj();
            var prompt = Dialogs.prompt(stage, "Remove Package",
                    mtproj != null ? "Remove package from " + mtproj.getFileName() : "Remove package",
                    "Package name:", "");
            prompt.showAndWait().map(String::trim).filter(s -> !s.isEmpty()).ifPresent(name -> {
                var confirm = Dialogs.confirm(stage, "Remove " + name + "?",
                        mtproj != null
                                ? "Remove " + name + " from " + mtproj.getFileName() + " and clean mt_modules?"
                                : "Remove " + name + " and clean mt_modules?");
                var result = confirm.showAndWait();
                if (result.isPresent() && result.get() == javafx.scene.control.ButtonType.OK) {
                    pc.remove(mtproj, name);
                }
            });
        });
        pkgRemove.disableProperty().bind(noPkg);

        MenuItem pkgList = new MenuItem("List Packages");
        pkgList.setOnAction(_ -> pc.list(findSingleMtproj()));
        pkgList.disableProperty().bind(noPkg);

        MenuItem pkgStop = new MenuItem("Stop");
        pkgStop.setOnAction(_ -> pc.stop());
        pkgStop.disableProperty().bind(pc.runningProperty().not());

        pkg.getItems().addAll(pkgInstall, pkgAdd, pkgRemove,
                new SeparatorMenuItem(), pkgList,
                new SeparatorMenuItem(), pkgStop);

        Menu view = new Menu("View");
        MenuItem filterFiles = new MenuItem("Filter Files in Explorer");
        filterFiles.setAccelerator(new KeyCodeCombination(KeyCode.E,
                KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        filterFiles.setOnAction(_ -> toggleExplorerFilter());

        MenuItem toggleBottom = new MenuItem("Toggle Bottom Panel");
        toggleBottom.setAccelerator(new KeyCodeCombination(KeyCode.J, KeyCombination.SHORTCUT_DOWN));
        toggleBottom.setOnAction(_ -> toggleBottomPanel());

        Menu bottomTabs = new Menu("Bottom Tabs");
        OutputPane outputForMenu = ctx.getOutputPane();
        for (String name : outputForMenu.tabNames()) {
            CheckMenuItem item = new CheckMenuItem(name);
            item.setSelected(outputForMenu.isTabVisible(name));
            item.setOnAction(_ -> {
                outputForMenu.setTabVisible(name, item.isSelected());
                persistBottomTabVisibility(name, item.isSelected());
            });
            bottomTabs.getItems().add(item);
        }

        view.getItems().addAll(filterFiles, toggleBottom, new SeparatorMenuItem(), bottomTabs);

        Menu terminal = new Menu("Terminal");
        MenuItem newTerminal = new MenuItem("New Terminal");
        newTerminal.setAccelerator(new KeyCodeCombination(KeyCode.BACK_QUOTE,
                KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        newTerminal.setOnAction(_ -> openNewTerminal());

        MenuItem focusTerminal = new MenuItem("Focus Terminal");
        focusTerminal.setAccelerator(new KeyCodeCombination(KeyCode.BACK_QUOTE,
                KeyCombination.SHORTCUT_DOWN));
        focusTerminal.setOnAction(_ -> focusTerminal());

        terminal.getItems().addAll(newTerminal, focusTerminal);

        mb.getMenus().addAll(file, code, pkg, build, view, terminal);
        return mb;
    }

    /** Return the single .mtproj at the workspace root, or null if zero or multiple exist. */
    private Path findSingleMtproj() {
        if (ctx.getWorkspace() == null) return null;
        Path root = ctx.getWorkspace().root();
        Path single = null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root, "*.mtproj")) {
            for (Path p : ds) {
                if (single != null) return null;
                single = p;
            }
        } catch (java.io.IOException ignored) {
            return null;
        }
        return single;
    }

    private void openNewTerminal() {
        ensureBottomPanelVisible();
        OutputPane output = ctx.getOutputPane();
        output.setTabVisible("Terminal", true);
        persistBottomTabVisibility("Terminal", true);
        output.newTerminal();
    }

    private void focusTerminal() {
        ensureBottomPanelVisible();
        OutputPane output = ctx.getOutputPane();
        output.setTabVisible("Terminal", true);
        persistBottomTabVisibility("Terminal", true);
        output.focusTerminal();
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
            if (ctx.getTabPane() != null) {
                for (var tab : ctx.getTabPane().openTabs()) {
                    tab.refreshInlayHintsFromSettings();
                }
            }
        });
    }

    private void openNewProjectDialog() {
        Path root = resolveTargetRoot("Choose location for new project");
        if (root == null) return;
        boolean wasOpen = ctx.getWorkspace() != null
                && ctx.getWorkspace().root().equals(root);
        NewProjectDialog dlg = new NewProjectDialog(stage, root);
        dlg.showAndWait().ifPresent(p -> afterScaffoldWritten(p, root, wasOpen));
    }

    private void openNewWorkspaceDialog() {
        Path root = resolveTargetRoot("Choose location for new workspace");
        if (root == null) return;
        boolean wasOpen = ctx.getWorkspace() != null
                && ctx.getWorkspace().root().equals(root);
        NewWorkspaceDialog dlg = new NewWorkspaceDialog(stage, root);
        dlg.showAndWait().ifPresent(p -> afterScaffoldWritten(p, root, wasOpen));
    }

    private Path resolveTargetRoot(String chooserTitle) {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle(chooserTitle);
        if (ctx.getWorkspace() != null) {
            File initial = ctx.getWorkspace().root().toFile();
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

    private void openStartupFileArgument() {
        var params = getParameters();
        if (params == null || params.getRaw().isEmpty()) return;

        String rawPath = params.getRaw().getFirst();
        Path file;
        try {
            file = Path.of(rawPath).toAbsolutePath().normalize();
        } catch (InvalidPathException ex) {
            ctx.getStatusBar().setMessage("Could not open startup file: " + rawPath);
            return;
        }

        if (!Files.isRegularFile(file)) {
            ctx.getStatusBar().setMessage("Startup path is not a file: " + file);
            return;
        }

        Path root = file.getParent();
        if (root == null) {
            ctx.getStatusBar().setMessage("Could not resolve startup file folder: " + file);
            return;
        }

        ctx.openWorkspace(new Workspace(root));
        stage.setTitle("mType Editor - " + root.getFileName());
        ctx.getTabPane().openFile(file);
        ctx.getStatusBar().setMessage("Opened " + file.getFileName());
    }

    private void openNewWindow() {
        try {
            new EditorApp().start(new Stage());
        } catch (Exception ex) {
            ctx.getStatusBar().setMessage("Failed to open new window: " + ex.getMessage());
        }
    }

    private Node buildSidePanel(WorkspaceTreeView tree, GitChangesView gitChanges, DebuggerPanel debuggerPanel) {
        TextField filterField = new TextField();
        filterField.setPromptText("Filter files...");
        filterField.getStyleClass().add("mt-tree-filter");
        filterField.setVisible(false);
        filterField.setManaged(false);
        filterField.textProperty().addListener((_, _, newV) -> tree.setFilter(newV));
        filterField.setOnKeyPressed(ev -> {
            if (ev.getCode() == KeyCode.ESCAPE) {
                hideExplorerFilter(filterField, tree);
                ev.consume();
            }
        });
        tree.setOnFilterReset(filterField::clear);
        this.explorerFilterField = filterField;

        Button selectOpenedBtn = new Button();
        selectOpenedBtn.setGraphic(selectOpenedFileIcon());
        selectOpenedBtn.setTooltip(new Tooltip("Select Opened File"));
        selectOpenedBtn.getStyleClass().add("mt-explorer-toolbar-button");
        selectOpenedBtn.setOnAction(_ -> tree.revealActiveFile());

        Button refreshBtn = new Button();
        refreshBtn.setGraphic(refreshIcon());
        refreshBtn.setTooltip(new Tooltip("Refresh Explorer"));
        refreshBtn.getStyleClass().add("mt-explorer-toolbar-button");
        refreshBtn.setOnAction(_ -> refreshExplorer(tree));

        HBox explorerToolbar = new HBox(selectOpenedBtn, refreshBtn);
        explorerToolbar.getStyleClass().add("mt-explorer-toolbar");

        VBox explorerPane = new VBox(explorerToolbar, filterField, tree);
        VBox.setVgrow(tree, Priority.ALWAYS);

        StackPane content = new StackPane(explorerPane, gitChanges, debuggerPanel);
        gitChanges.setVisible(false);
        gitChanges.setManaged(false);
        debuggerPanel.setVisible(false);
        debuggerPanel.setManaged(false);

        ToggleGroup group = new ToggleGroup();
        ToggleButton explorerButton = activityButton("Explorer", explorerIcon());
        ToggleButton gitButton = activityButton("Git", gitIcon());
        ToggleButton debugButton = activityButton("Debug", DebuggerIcons.activityBugIcon());
        explorerButton.setToggleGroup(group);
        gitButton.setToggleGroup(group);
        debugButton.setToggleGroup(group);
        explorerButton.setSelected(true);
        this.explorerActivityButton = explorerButton;

        explorerButton.setOnAction(_ -> {
            explorerButton.setSelected(true);
            showPanel(content, explorerPane);
        });
        gitButton.setOnAction(_ -> {
            gitButton.setSelected(true);
            hideExplorerFilter(filterField, tree);
            showPanel(content, gitChanges);
        });
        debugButton.setOnAction(_ -> {
            debugButton.setSelected(true);
            hideExplorerFilter(filterField, tree);
            showPanel(content, debuggerPanel);
        });

        VBox activityBar = new VBox(explorerButton, gitButton, debugButton);
        activityBar.getStyleClass().add("mt-activity-bar");

        BorderPane sidePanel = new BorderPane(content);
        sidePanel.setLeft(activityBar);
        sidePanel.getStyleClass().add("mt-side-panel");
        sidePanel.setMinWidth(220);
        sidePanel.setPrefWidth(280);
        return sidePanel;
    }

    private Button toolbarIconButton(Node graphic, String tooltip) {
        Button b = new Button();
        b.setGraphic(graphic);
        b.setTooltip(new Tooltip(tooltip));
        b.getStyleClass().add("mt-toolbar-icon-button");
        return b;
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

    private void ensureBottomPanelVisible() {
        if (verticalSplit == null || outputPane == null) return;
        if (!verticalSplit.getItems().contains(outputPane)) {
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

    private void refreshExplorer(WorkspaceTreeView tree) {
        tree.refresh();
        ctx.refreshHasProjectFile();
        if (ctx.getLspBridge() != null) {
            ctx.getLspBridge().refreshWatchedMtFiles();
        }
        ctx.getStatusBar().setMessage("Explorer refreshed");
    }

    private Node selectOpenedFileIcon() {
        Circle outer = new Circle(8, 8, 6);
        Circle middle = new Circle(8, 8, 3);
        Circle center = new Circle(8, 8, 1);
        outer.getStyleClass().add("mt-activity-icon");
        middle.getStyleClass().add("mt-activity-icon");
        center.getStyleClass().add("mt-activity-icon-fill");
        Group g = new Group(outer, middle, center);
        g.getStyleClass().add("mt-activity-graphic");
        return g;
    }

    private Node refreshIcon() {
        SVGPath arc = new SVGPath();
        arc.setContent("M12.5 5.3 A5.8 5.8 0 1 0 13.2 11.1");
        arc.getStyleClass().add("mt-activity-icon");

        SVGPath arrow = new SVGPath();
        arrow.setContent("M12.5 2.6 V5.3 H9.8");
        arrow.getStyleClass().add("mt-activity-icon");

        Group g = new Group(arc, arrow);
        g.getStyleClass().add("mt-activity-graphic");
        return g;
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
        Line main = activityLine(16, 11, 25);
        Line branch = activityLine(17, 23, 14);

        Group group = new Group(main, branch, top, middle, bottom);
        group.getStyleClass().add("mt-activity-graphic");
        return group;
    }

    private Circle activityCircle(double x, double y) {
        Circle circle = new Circle(x, y, 4);
        circle.getStyleClass().add("mt-activity-icon");
        return circle;
    }

    private Line activityLine(double x1, double x2, double y2) {
        Line line = new Line(x1, 8, x2, y2);
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
        if (ctx == null) return;
        try { if (ctx.getDebuggerBridge() != null) ctx.getDebuggerBridge().stop(); } catch (Exception ignored) {}
        try { if (ctx.getRunController() != null) ctx.getRunController().stop(); } catch (Exception ignored) {}
        try { if (ctx.getBuildController() != null) ctx.getBuildController().stop(); } catch (Exception ignored) {}
        try { if (ctx.getPackageController() != null) ctx.getPackageController().stop(); } catch (Exception ignored) {}
        try { if (ctx.getTerminalController() != null) ctx.getTerminalController().shutdownAll(); } catch (Exception ignored) {}
        try { ctx.stopFileWatcher(); } catch (Exception ignored) {}
        try { if (ctx.getTabPane() != null) ctx.getTabPane().closeAll(); } catch (Exception ignored) {}
        try { if (ctx.getLspBridge() != null) ctx.getLspBridge().stop(); } catch (Exception ignored) {}
        try { if (ctx.getFindInFilesWindow() != null) ctx.getFindInFilesWindow().dispose(); } catch (Exception ignored) {}
        try { if (ctx.getGitService() != null) ctx.getGitService().shutdown(); } catch (Exception ignored) {}
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
