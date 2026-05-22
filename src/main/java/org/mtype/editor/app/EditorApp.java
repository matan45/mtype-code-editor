package org.mtype.editor.app;

import javafx.application.Application;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.text.Font;
import javafx.scene.control.Button;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToolBar;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.mtype.editor.lsp.LspBridge;
import org.mtype.editor.process.RunController;
import org.mtype.editor.ui.editor.EditorTabPane;
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

        StatusBar status = new StatusBar();
        ctx.setStatusBar(status);

        OutputPane output = new OutputPane();
        ctx.setOutputPane(output);

        EditorTabPane tabPane = new EditorTabPane(ctx);
        ctx.setTabPane(tabPane);

        WorkspaceTreeView tree = new WorkspaceTreeView(ctx);
        ctx.setTreeView(tree);

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

        SplitPane mainSplit = new SplitPane(tree, verticalSplit);
        mainSplit.setOrientation(Orientation.HORIZONTAL);
        mainSplit.setDividerPositions(0.2);

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
        MenuItem openFolder = new MenuItem("Open Folder…");
        openFolder.setAccelerator(new KeyCodeCombination(KeyCode.O,
                KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        openFolder.setOnAction(e -> openFolder());

        MenuItem save = new MenuItem("Save");
        save.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN));
        save.setOnAction(e -> ctx.getTabPane().saveActive());

        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(e -> { shutdown(); stage.close(); });

        file.getItems().addAll(openFolder, save, new SeparatorMenuItem(), exit);

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

    private void openFolder() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Open Folder");
        File chosen = dc.showDialog(stage);
        if (chosen == null) return;
        Path root = chosen.toPath();
        WorkspaceSettings settings = SettingsStore.load(root);
        Workspace ws = new Workspace(root, settings);
        ctx.openWorkspace(ws);
        stage.setTitle("mType Editor — " + root.getFileName());
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
