package org.mtype.editor.ui.dialogs;

import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.VBox;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import org.mtype.editor.ui.chrome.DialogTitleBar;

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
     * Apply the dark theme + replace the native window chrome with our
     * borderless title bar so popups match the editor's main window.
     *
     * Alert/Input dialogs that set a headerText keep it visible in a
     * sub-row below the title bar so we don't lose any explanatory copy.
     */
    public static <T> void theme(Dialog<T> dlg) {
        try { dlg.initStyle(StageStyle.UNDECORATED); } catch (IllegalStateException ignored) {}
        dlg.setGraphic(null);
        DialogPane pane = dlg.getDialogPane();
        if (!pane.getStyleClass().contains("mt-dialog")) {
            pane.getStyleClass().add("mt-dialog");
        }

        // setHeader() replaces any headerText layout, so we extract it
        // first and re-render it below the chrome.
        String existingHeaderText = pane.getHeaderText();
        pane.setHeaderText(null);
        DialogTitleBar bar = new DialogTitleBar(dlg);
        if (existingHeaderText != null && !existingHeaderText.isEmpty()) {
            Label sub = new Label(existingHeaderText);
            sub.getStyleClass().add("mt-dialog-subheader");
            VBox box = new VBox(bar, sub);
            pane.setHeader(box);
        } else {
            pane.setHeader(bar);
        }

        var cssUrl = Dialogs.class.getResource("/css/mtype-dark.css");
        if (cssUrl != null) {
            String url = cssUrl.toExternalForm();
            if (!pane.getStylesheets().contains(url)) {
                pane.getStylesheets().add(url);
            }
        }
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
