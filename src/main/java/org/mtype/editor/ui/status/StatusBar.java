package org.mtype.editor.ui.status;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

public class StatusBar extends HBox {
    private final Label branchLabel = new Label("");
    private final Label messageLabel = new Label("Ready");
    private final Label lspLabel = new Label("LSP: idle");
    private final Label caretLabel = new Label("");
    private Runnable onBranchClick;

    public StatusBar() {
        setPadding(new Insets(4, 8, 4, 8));
        setSpacing(12);
        setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        branchLabel.getStyleClass().add("mt-status-branch");
        branchLabel.setVisible(false);
        branchLabel.setManaged(false);
        branchLabel.setOnMouseClicked(_ -> {
            if (onBranchClick != null) onBranchClick.run();
        });

        getChildren().addAll(branchLabel, messageLabel, spacer, caretLabel, lspLabel);
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

    public void setBranch(String branch, int ahead, int behind, boolean hasUpstream) {
        Runnable r = () -> {
            if (branch == null || branch.isBlank()) {
                branchLabel.setVisible(false);
                branchLabel.setManaged(false);
                branchLabel.setText("");
                branchLabel.setTooltip(null);
                return;
            }
            StringBuilder sb = new StringBuilder("⎇ ").append(branch);
            if (hasUpstream) {
                if (ahead > 0) sb.append("  ↑").append(ahead);
                if (behind > 0) sb.append("  ↓").append(behind);
            }
            branchLabel.setText(sb.toString());
            branchLabel.setVisible(true);
            branchLabel.setManaged(true);
            branchLabel.setTooltip(new Tooltip(hasUpstream
                    ? "On " + branch + " (click to switch)"
                    : "On " + branch + " (no upstream — click to switch)"));
        };
        if (Platform.isFxApplicationThread()) r.run();
        else Platform.runLater(r);
    }

    public void setOnBranchClick(Runnable r) {
        this.onBranchClick = r;
    }
}
