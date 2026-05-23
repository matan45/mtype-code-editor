package org.mtype.editor.ui.output;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.InlineCssTextArea;
import org.mtype.editor.app.AppContext;

import java.util.List;

public class OutputPane extends TabPane {
    private final TextArea runArea = new TextArea();
    private final TextArea lspArea = new TextArea();
    private final InlineCssTextArea compileArea = new InlineCssTextArea();
    private final InlineCssTextArea gitArea = new InlineCssTextArea();
    private final Tab runTab;
    private final Tab lspTab;
    private final Tab compileTab;
    private final Tab gitTab;
    private final Tab callHierarchyTab;
    private final Tab problemsTab;
    private CallHierarchyPane callHierarchyPane;
    private ProblemsPane problemsPane;

    public OutputPane() {
        runArea.setEditable(false);
        runArea.getStyleClass().add("mt-output");
        lspArea.setEditable(false);
        lspArea.getStyleClass().add("mt-output");
        compileArea.setEditable(false);
        compileArea.getStyleClass().add("mt-output");
        gitArea.setEditable(false);
        gitArea.getStyleClass().add("mt-output");

        runTab = new Tab("Run", runArea);
        runTab.setClosable(false);

        Button clearLspButton = new Button("Clear");
        clearLspButton.getStyleClass().add("mt-output-toolbar-button");
        clearLspButton.setOnAction(e -> lspArea.clear());
        HBox lspToolbar = new HBox(clearLspButton);
        lspToolbar.setAlignment(Pos.CENTER_RIGHT);
        lspToolbar.setPadding(new Insets(4, 6, 4, 6));
        lspToolbar.getStyleClass().add("mt-output-toolbar");
        BorderPane lspContent = new BorderPane(lspArea);
        lspContent.setTop(lspToolbar);
        lspTab = new Tab("LSP Log", lspContent);
        lspTab.setClosable(false);

        Button clearCompileButton = new Button("Clear");
        clearCompileButton.getStyleClass().add("mt-output-toolbar-button");
        clearCompileButton.setOnAction(e -> compileArea.clear());
        HBox compileToolbar = new HBox(clearCompileButton);
        compileToolbar.setAlignment(Pos.CENTER_RIGHT);
        compileToolbar.setPadding(new Insets(4, 6, 4, 6));
        compileToolbar.getStyleClass().add("mt-output-toolbar");
        BorderPane compileContent = new BorderPane(new VirtualizedScrollPane<>(compileArea));
        compileContent.setTop(compileToolbar);
        compileTab = new Tab("Compile", compileContent);
        compileTab.setClosable(false);

        Button clearGitButton = new Button("Clear");
        clearGitButton.getStyleClass().add("mt-output-toolbar-button");
        clearGitButton.setOnAction(e -> gitArea.clear());
        HBox gitToolbar = new HBox(clearGitButton);
        gitToolbar.setAlignment(Pos.CENTER_RIGHT);
        gitToolbar.setPadding(new Insets(4, 6, 4, 6));
        gitToolbar.getStyleClass().add("mt-output-toolbar");
        BorderPane gitContent = new BorderPane(new VirtualizedScrollPane<>(gitArea));
        gitContent.setTop(gitToolbar);
        gitTab = new Tab("Git", gitContent);
        gitTab.setClosable(false);

        callHierarchyTab = new Tab("Call Hierarchy");
        callHierarchyTab.setClosable(false);
        problemsTab = new Tab("Problems");
        problemsTab.setClosable(false);

        getTabs().addAll(problemsTab, runTab, compileTab, lspTab, gitTab, callHierarchyTab);
        getStyleClass().add("mt-output-pane");
        setTabClosingPolicy(TabClosingPolicy.UNAVAILABLE);
    }

    private List<Tab> canonicalOrder() {
        return List.of(problemsTab, runTab, compileTab, lspTab, gitTab, callHierarchyTab);
    }

    private Tab tabByName(String name) {
        for (Tab t : canonicalOrder()) {
            if (t.getText() != null && t.getText().startsWith(name)) return t;
        }
        return null;
    }

    public List<String> tabNames() {
        return List.of("Problems", "Run", "Compile", "LSP Log", "Git", "Call Hierarchy");
    }

    public boolean isTabVisible(String name) {
        Tab t = tabByName(name);
        return t != null && getTabs().contains(t);
    }

    public void setTabVisible(String name, boolean visible) {
        Tab target = tabByName(name);
        if (target == null) return;
        boolean present = getTabs().contains(target);
        if (visible == present) return;
        if (!visible) {
            getTabs().remove(target);
            return;
        }
        int insertAt = 0;
        for (Tab t : canonicalOrder()) {
            if (t == target) break;
            if (getTabs().contains(t)) insertAt++;
        }
        if (insertAt > getTabs().size()) insertAt = getTabs().size();
        getTabs().add(insertAt, target);
    }

    public void attachCallHierarchy(AppContext ctx) {
        callHierarchyPane = new CallHierarchyPane(ctx);
        callHierarchyTab.setContent(callHierarchyPane);
    }

    public void attachProblems(AppContext ctx) {
        problemsPane = new ProblemsPane(ctx);
        problemsTab.setContent(problemsPane);
        // Bind the tab label to the visible row count so the Clear button
        // (which clears rows but not the bus) also resets the label.
        problemsPane.rowCountBinding().addListener((obs, oldV, newV) -> {
            int n = newV.intValue();
            Platform.runLater(() ->
                    problemsTab.setText(n > 0 ? "Problems (" + n + ")" : "Problems"));
        });
    }

    public void showCallHierarchy(CallHierarchyItem item) {
        if (callHierarchyPane == null) return;
        Platform.runLater(() -> {
            getSelectionModel().select(callHierarchyTab);
            callHierarchyPane.show(item);
        });
    }

    public void appendRun(String line, boolean stderr) {
        String text = (stderr ? "[err] " : "") + line + System.lineSeparator();
        Platform.runLater(() -> {
            runArea.appendText(text);
        });
    }

    public void appendLspLog(String line) {
        String text = line + System.lineSeparator();
        Platform.runLater(() -> lspArea.appendText(text));
    }

    public void appendCompile(String line, boolean stderr) {
        String text = line + System.lineSeparator();
        String style = stderr ? "-fx-fill: #e06c75;" : "";
        Platform.runLater(() -> {
            int start = compileArea.getLength();
            compileArea.appendText(text);
            int end = compileArea.getLength();
            compileArea.setStyle(start, end, style);
            compileArea.moveTo(end);
            compileArea.requestFollowCaret();
        });
    }

    public void appendGit(String line, boolean stderr) {
        String text = line + System.lineSeparator();
        String style = stderr ? "-fx-fill: #e06c75;" : "";
        Platform.runLater(() -> {
            int start = gitArea.getLength();
            gitArea.appendText(text);
            int end = gitArea.getLength();
            gitArea.setStyle(start, end, style);
            gitArea.moveTo(end);
            gitArea.requestFollowCaret();
        });
    }

    public void clearRun() {
        Platform.runLater(runArea::clear);
    }

    public void clearCompile() {
        Platform.runLater(compileArea::clear);
    }

    public void clearGit() {
        Platform.runLater(gitArea::clear);
    }

    public void focusRun() {
        Platform.runLater(() -> getSelectionModel().select(runTab));
    }

    public void focusCompile() {
        Platform.runLater(() -> getSelectionModel().select(compileTab));
    }

    public void focusLsp() {
        Platform.runLater(() -> getSelectionModel().select(lspTab));
    }

    public void focusGit() {
        Platform.runLater(() -> getSelectionModel().select(gitTab));
    }
}
