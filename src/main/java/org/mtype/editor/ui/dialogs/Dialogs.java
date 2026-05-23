package org.mtype.editor.ui.dialogs;

import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextInputDialog;
import javafx.stage.StageStyle;
import javafx.stage.Window;

public final class Dialogs {
    private Dialogs() {}

    public static TextInputDialog prompt(Window owner, String title, String header, String content, String defaultValue) {
        TextInputDialog d = defaultValue == null ? new TextInputDialog() : new TextInputDialog(defaultValue);
        if (owner != null) d.initOwner(owner);
        d.setTitle(title);
        d.setHeaderText(header);
        d.setContentText(content);
        theme(d);
        return d;
    }

    public static Alert error(Window owner, String header, String message) {
        Alert a = new Alert(AlertType.ERROR, message);
        if (owner != null) a.initOwner(owner);
        a.setHeaderText(header);
        theme(a);
        return a;
    }

    public static Alert confirm(Window owner, String header, String message) {
        Alert a = new Alert(AlertType.CONFIRMATION, message, ButtonType.OK, ButtonType.CANCEL);
        if (owner != null) a.initOwner(owner);
        a.setHeaderText(header);
        theme(a);
        return a;
    }

    /**
     * Apply the dark theme + remove the default JavaFX ?-graphic header icon.
     */
    public static <T> void theme(Dialog<T> dlg) {
        try { dlg.initStyle(StageStyle.UTILITY); } catch (IllegalStateException ignored) {}
        dlg.setGraphic(null);
        DialogPane pane = dlg.getDialogPane();
        if (!pane.getStyleClass().contains("mt-dialog")) {
            pane.getStyleClass().add("mt-dialog");
        }
        var cssUrl = Dialogs.class.getResource("/css/mtype-dark.css");
        if (cssUrl != null) {
            String url = cssUrl.toExternalForm();
            if (!pane.getStylesheets().contains(url)) {
                pane.getStylesheets().add(url);
            }
        }
    }

    /** Same as theme(), but for callers that hold the DialogPane directly. */
    public static DialogPane themePane(DialogPane pane) {
        if (!pane.getStyleClass().contains("mt-dialog")) {
            pane.getStyleClass().add("mt-dialog");
        }
        var cssUrl = Dialogs.class.getResource("/css/mtype-dark.css");
        if (cssUrl != null) {
            String url = cssUrl.toExternalForm();
            if (!pane.getStylesheets().contains(url)) {
                pane.getStylesheets().add(url);
            }
        }
        return pane;
    }

    /**
     * Inject a sibling Node (anything created outside the DialogPane) into the .mt-dialog scope so its CSS rules win.
     */
    public static void themeNode(Node node) {
        if (!node.getStyleClass().contains("mt-dialog")) {
            node.getStyleClass().add("mt-dialog");
        }
    }
}
