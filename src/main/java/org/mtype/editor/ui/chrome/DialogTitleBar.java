package org.mtype.editor.ui.chrome;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

/**
 * Custom title bar for an undecorated {@link Dialog}: a title label bound
 * to the dialog's title, the same three traffic-light circles as the main
 * window (— □ ×), and drag-to-move on the empty surface.
 *
 * Install via {@link #install(Dialog)} which sets it as the
 * DialogPane's header.
 */
public final class DialogTitleBar extends HBox {
    private double dragOffsetX;
    private double dragOffsetY;
    private boolean dragging;

    public DialogTitleBar(Dialog<?> dialog) {
        getStyleClass().add("mt-dialog-title-bar");
        setAlignment(Pos.CENTER_LEFT);
        setMinHeight(30);
        setPrefHeight(30);
        setMaxHeight(30);
        setFillHeight(false);
        setPadding(new Insets(0, 0, 0, 10));

        Label title = new Label();
        title.textProperty().bind(dialog.titleProperty());
        title.getStyleClass().add("mt-dialog-title");
        HBox.setMargin(title, new Insets(0, 8, 0, 0));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        StackPane minBtn = ChromeCircles.minimize("Minimize", () -> {
            Stage s = currentStage();
            if (s != null) s.setIconified(true);
        });
        StackPane maxBtn = ChromeCircles.maximize("Maximize", () -> {
            Stage s = currentStage();
            if (s != null) s.setMaximized(!s.isMaximized());
        });
        StackPane closeBtn = ChromeCircles.close("Close", () -> {
            // Mirror the OS close button — fire WINDOW_CLOSE_REQUEST so the
            // dialog's setOnCloseRequest / resultConverter chain runs the
            // same way it would natively.
            Window w = currentWindow();
            if (w != null) {
                w.fireEvent(new WindowEvent(w, WindowEvent.WINDOW_CLOSE_REQUEST));
            }
        });

        HBox.setMargin(minBtn, new Insets(0, 6, 0, 0));
        HBox.setMargin(maxBtn, new Insets(0, 6, 0, 0));
        HBox.setMargin(closeBtn, new Insets(0, 12, 0, 0));

        getChildren().addAll(title, spacer, minBtn, maxBtn, closeBtn);

        installDrag();
    }

    public static void install(Dialog<?> dialog) {
        DialogTitleBar bar = new DialogTitleBar(dialog);
        dialog.getDialogPane().setHeader(bar);
    }

    private void installDrag() {
        setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (isOnCircle(e.getTarget())) return;
            Stage stage = currentStage();
            if (stage == null) return;
            dragging = true;
            dragOffsetX = e.getScreenX() - stage.getX();
            dragOffsetY = e.getScreenY() - stage.getY();
        });
        setOnMouseDragged(e -> {
            if (!dragging) return;
            Stage stage = currentStage();
            if (stage == null) return;
            stage.setX(e.getScreenX() - dragOffsetX);
            stage.setY(e.getScreenY() - dragOffsetY);
        });
        setOnMouseReleased(_ -> dragging = false);
    }

    private boolean isOnCircle(Object target) {
        if (!(target instanceof Node n)) return false;
        while (n != null && n != this) {
            if (n.getStyleClass().contains("mt-chrome-circle")) return true;
            n = n.getParent();
        }
        return false;
    }

    private Window currentWindow() {
        return getScene() == null ? null : getScene().getWindow();
    }

    private Stage currentStage() {
        Window w = currentWindow();
        return w instanceof Stage s ? s : null;
    }
}
