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
import org.mtype.editor.app.AppContext;

public class OutputPane extends TabPane {
    private final TextArea runArea = new TextArea();
    private final TextArea lspArea = new TextArea();
    private final Tab runTab;
    private final Tab lspTab;
    private final Tab callHierarchyTab;
    private final Tab problemsTab;
    private CallHierarchyPane callHierarchyPane;
    private ProblemsPane problemsPane;

    public OutputPane() {
        runArea.setEditable(false);
        runArea.getStyleClass().add("mt-output");
        lspArea.setEditable(false);
        lspArea.getStyleClass().add("mt-output");

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
        callHierarchyTab = new Tab("Call Hierarchy");
        callHierarchyTab.setClosable(false);
        problemsTab = new Tab("Problems");
        problemsTab.setClosable(false);

        getTabs().addAll(problemsTab, runTab, lspTab, callHierarchyTab);
        getStyleClass().add("mt-output-pane");
        setTabClosingPolicy(TabClosingPolicy.UNAVAILABLE);
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

    public void clearRun() {
        Platform.runLater(runArea::clear);
    }

    public void focusRun() {
        Platform.runLater(() -> getSelectionModel().select(runTab));
    }

    public void focusLsp() {
        Platform.runLater(() -> getSelectionModel().select(lspTab));
    }
}
