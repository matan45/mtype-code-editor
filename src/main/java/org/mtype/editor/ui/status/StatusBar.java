package org.mtype.editor.ui.status;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

public class StatusBar extends HBox {
    private final Label messageLabel = new Label("Ready");
    private final Label lspLabel = new Label("LSP: idle");
    private final Label caretLabel = new Label("");

    public StatusBar() {
        setPadding(new Insets(4, 8, 4, 8));
        setSpacing(12);
        setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        getChildren().addAll(messageLabel, spacer, caretLabel, lspLabel);
        getStyleClass().add("mt-status-bar");
    }

    public void setMessage(String msg) {
        if (Platform.isFxApplicationThread()) {
            messageLabel.setText(msg);
        } else {
            Platform.runLater(() -> messageLabel.setText(msg));
        }
    }

    public void setLspState(String state) {
        if (Platform.isFxApplicationThread()) {
            lspLabel.setText(state);
        } else {
            Platform.runLater(() -> lspLabel.setText(state));
        }
    }

    public void setCaret(int line, int col) {
        String text = "Ln " + (line + 1) + ", Col " + (col + 1);
        if (Platform.isFxApplicationThread()) {
            caretLabel.setText(text);
        } else {
            Platform.runLater(() -> caretLabel.setText(text));
        }
    }
}
