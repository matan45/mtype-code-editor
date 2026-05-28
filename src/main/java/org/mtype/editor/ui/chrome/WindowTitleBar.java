package org.mtype.editor.ui.chrome;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.MenuBar;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.io.InputStream;

/**
 * Custom window title bar for UNDECORATED stages: hosts the menu bar inline
 * with the app title and the standard min / max / close buttons, and makes
 * the empty surface draggable so the user can still move the window around.
 *
 * Pairs with {@link WindowResizer} to restore the edge-resize behavior we
 * lose when dropping the native chrome.
 */
public class WindowTitleBar extends HBox {
    private final Stage stage;
    private double dragOffsetX;
    private double dragOffsetY;
    private boolean dragging;

    /** Main-window mode: logo + inline menu bar + min/max/close. */
    public WindowTitleBar(Stage stage, MenuBar menuBar) {
        this(stage, menuBar, /*leadingLabel=*/null);
    }

    /**
     * Auxiliary-window mode: a plain title label on the left (no logo,
     * no menu) bound to the stage title — matches the look of our dialog
     * popups (Switch Branch etc.) so the Find in Files window doesn't
     * read as the main editor at a glance.
     */
    public WindowTitleBar(Stage stage) {
        this(stage, /*menuBar=*/null, /*leadingLabel=*/buildTitleLabel(stage));
    }

    private WindowTitleBar(Stage stage, MenuBar menuBar, Label leadingLabel) {
        this.stage = stage;
        getStyleClass().add("mt-title-bar");
        setAlignment(Pos.CENTER_LEFT);
        setMinHeight(30);
        setPrefHeight(30);
        setMaxHeight(30);
        setFillHeight(false);
        setPadding(new Insets(0, 0, 0, 10));

        // Choose the leading node: a Label for auxiliary windows, the
        // bundled mType logo (cropped to the m-glyph) for the main window.
        Node leading;
        if (leadingLabel != null) {
            leading = leadingLabel;
        } else {
            ImageView logo = loadLogo();
            logo.getStyleClass().add("mt-title-bar-logo");
            Tooltip logoTooltip = new Tooltip();
            logoTooltip.textProperty().bind(stage.titleProperty());
            Tooltip.install(logo, logoTooltip);
            HBox.setMargin(logo, new Insets(0, 10, 0, 0));
            leading = logo;
        }

        // MenuBar default minHeight/prefHeight are sized for the OS menu; in
        // an inline title bar we want it flush with the chrome height so the
        // hover background doesn't visibly overhang.
        if (menuBar != null) {
            menuBar.getStyleClass().add("mt-title-bar-menus");
            menuBar.setMinHeight(USE_PREF_SIZE);
        }

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        StackPane minBtn = ChromeCircles.minimize("Minimize",
                () -> stage.setIconified(true));
        StackPane maxBtn = ChromeCircles.maximize("Maximize",
                () -> WindowMaximizer.toggle(stage));
        StackPane closeBtn = ChromeCircles.close("Close", stage::close);

        // Spacing between traffic-light circles + right margin on the row.
        HBox.setMargin(minBtn, new Insets(0, 6, 0, 0));
        HBox.setMargin(maxBtn, new Insets(0, 6, 0, 0));
        HBox.setMargin(closeBtn, new Insets(0, 12, 0, 0));

        if (menuBar != null) {
            getChildren().addAll(leading, menuBar, spacer, minBtn, maxBtn, closeBtn);
        } else {
            getChildren().addAll(leading, spacer, minBtn, maxBtn, closeBtn);
        }

        installDragHandlers();
    }

    private static Label buildTitleLabel(Stage stage) {
        Label label = new Label();
        label.textProperty().bind(stage.titleProperty());
        label.getStyleClass().add("mt-dialog-title");
        return label;
    }

    /**
     * Drag-to-move is gated to clicks landing directly on the title bar
     * surface, the title label, or the spacer — pressing a menu or window
     * button must not also start a drag.
     */
    private void installDragHandlers() {
        setOnMousePressed(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (!isDragSurface(e.getTarget())) return;
            dragging = true;
            // Track cursor offset within the stage so the window doesn't
            // jump when the drag begins.
            dragOffsetX = e.getScreenX() - stage.getX();
            dragOffsetY = e.getScreenY() - stage.getY();
        });

        setOnMouseDragged(e -> {
            if (!dragging || e.getButton() != MouseButton.PRIMARY) return;
            // If the user starts dragging a maximized window, restore it
            // first and align the cursor to roughly the same relative
            // position in the restored window.
            if (WindowMaximizer.isMaximized(stage)) {
                double prevW = stage.getWidth();
                double ratio = prevW > 0 ? dragOffsetX / prevW : 0.5;
                WindowMaximizer.restore(stage);
                double newW = stage.getWidth();
                dragOffsetX = ratio * newW;
                dragOffsetY = Math.min(dragOffsetY, getHeight());
            }
            stage.setX(e.getScreenX() - dragOffsetX);
            stage.setY(e.getScreenY() - dragOffsetY);
        });

        setOnMouseReleased(_ -> dragging = false);

        setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2
                    && isDragSurface(e.getTarget())) {
                WindowMaximizer.toggle(stage);
            }
        });
    }

    private boolean isDragSurface(Object target) {
        if (target == this) return true;
        if (target instanceof javafx.scene.Node node) {
            // Title label or unstyled spacer Region count; menus and chrome
            // circles (and anything inside them) do not.
            javafx.scene.Node n = node;
            while (n != null && n != this) {
                if (n.getStyleClass().contains("mt-title-bar-logo")) return true;
                if (n.getStyleClass().contains("mt-chrome-circle")) return false;
                if (n.getStyleClass().contains("mt-title-bar-menus")) return false;
                if (n instanceof javafx.scene.control.MenuBar) return false;
                n = n.getParent();
            }
            return true;
        }
        return false;
    }

    /**
     * Bundled mType logo, cropped to just the top "m" icon (the source
     * PNG also contains the "mType" wordmark and "PROGRAMMING LANGUAGE"
     * subtitle, which look squashed at title-bar height). The crop is a
     * centered square taken from the upper portion of the image.
     */
    public static ImageView loadLogo() {
        ImageView view = new ImageView();
        try (InputStream in = WindowTitleBar.class.getResourceAsStream("/icons/mtype-logo.png")) {
            if (in != null) {
                Image img = new Image(in);
                double w = img.getWidth();
                double h = img.getHeight();
                // The icon occupies roughly the top 50% of the source image,
                // horizontally centered. Use a square viewport that just
                // covers the m-glyph.
                double side = Math.min(w, h * 0.5);
                double x = (w - side) / 2.0;
                double y = h * 0.05;
                view.setImage(img);
                view.setViewport(new javafx.geometry.Rectangle2D(x, y, side, side));
            }
        } catch (Exception ignored) {
        }
        view.setPreserveRatio(true);
        view.setFitHeight(20);
        view.setSmooth(true);
        return view;
    }
}
