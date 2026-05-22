package org.mtype.editor.ui.output;

import javafx.application.Platform;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;

public class OutputPane extends TabPane {
    private final TextArea runArea = new TextArea();
    private final TextArea lspArea = new TextArea();
    private final Tab runTab;
    private final Tab lspTab;

    public OutputPane() {
        runArea.setEditable(false);
        runArea.getStyleClass().add("mt-output");
        lspArea.setEditable(false);
        lspArea.getStyleClass().add("mt-output");

        runTab = new Tab("Run", runArea);
        runTab.setClosable(false);
        lspTab = new Tab("LSP Log", lspArea);
        lspTab.setClosable(false);

        getTabs().addAll(runTab, lspTab);
        getStyleClass().add("mt-output-pane");
        setTabClosingPolicy(TabClosingPolicy.UNAVAILABLE);
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
