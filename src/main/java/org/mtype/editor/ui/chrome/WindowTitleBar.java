package org.mtype.editor.ui.chrome;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Button;
import javafx.scene.control.MenuBar;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

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

    public WindowTitleBar(Stage stage, MenuBar menuBar) {
        this.stage = stage;
        getStyleClass().add("mt-title-bar");
        setAlignment(Pos.CENTER_LEFT);
        setMinHeight(30);
        setPrefHeight(30);
        setPadding(new Insets(0, 0, 0, 8));

        ImageView logo = loadLogo();
        logo.getStyleClass().add("mt-title-bar-logo");
        Tooltip logoTooltip = new Tooltip();
        logoTooltip.textProperty().bind(stage.titleProperty());
        Tooltip.install(logo, logoTooltip);
        HBox.setMargin(logo, new Insets(0, 10, 0, 4));

        // MenuBar default minHeight/prefHeight are sized for the OS menu; in
        // an inline title bar we want it flush with the chrome height so the
        // hover background doesn't visibly overhang.
        menuBar.getStyleClass().add("mt-title-bar-menus");
        menuBar.setMinHeight(USE_PREF_SIZE);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button minBtn = chromeButton(minimizeGlyph(), "Minimize");
        minBtn.setOnAction(_ -> stage.setIconified(true));

        Button maxBtn = chromeButton(maximizeGlyph(), "Maximize");
        maxBtn.setOnAction(_ -> stage.setMaximized(!stage.isMaximized()));
        stage.maximizedProperty().addListener((_, _, isMax) ->
                maxBtn.setGraphic(isMax ? restoreGlyph() : maximizeGlyph()));

        Button closeBtn = chromeButton(closeGlyph(), "Close");
        closeBtn.getStyleClass().add("mt-title-bar-close");
        closeBtn.setOnAction(_ -> stage.fireEvent(
                new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST)));

        getChildren().addAll(logo, menuBar, spacer, minBtn, maxBtn, closeBtn);

        installDragHandlers();
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
            if (stage.isMaximized()) {
                double prevW = stage.getWidth();
                double ratio = prevW > 0 ? dragOffsetX / prevW : 0.5;
                stage.setMaximized(false);
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
                stage.setMaximized(!stage.isMaximized());
            }
        });
    }

    private boolean isDragSurface(Object target) {
        if (target == this) return true;
        if (target instanceof javafx.scene.Node node) {
            // Title label or unstyled spacer Region count; menus and chrome
            // buttons (and anything inside them) do not.
            javafx.scene.Node n = node;
            while (n != null && n != this) {
                if (n.getStyleClass().contains("mt-title-bar-logo")) return true;
                if (n.getStyleClass().contains("mt-title-bar-button")) return false;
                if (n.getStyleClass().contains("mt-title-bar-close")) return false;
                if (n.getStyleClass().contains("mt-title-bar-menus")) return false;
                if (n instanceof javafx.scene.control.MenuBar) return false;
                if (n instanceof Button) return false;
                n = n.getParent();
            }
            return true;
        }
        return false;
    }

    private Button chromeButton(javafx.scene.Node graphic, String tooltip) {
        Button b = new Button();
        b.setGraphic(graphic);
        b.getStyleClass().add("mt-title-bar-button");
        b.setTooltip(new Tooltip(tooltip));
        b.setFocusTraversable(false);
        b.setCursor(Cursor.DEFAULT);
        b.setMinSize(46, 30);
        b.setPrefSize(46, 30);
        return b;
    }

    /* Glyphs drawn as Shapes so they pick up theme colors via CSS and don't
     * depend on whichever Unicode glyph font is installed on the host. */

    private javafx.scene.Node minimizeGlyph() {
        Line l = new Line(0, 0, 10, 0);
        l.getStyleClass().add("mt-title-bar-glyph");
        return l;
    }

    private javafx.scene.Node maximizeGlyph() {
        Rectangle r = new Rectangle(10, 10);
        r.setFill(null);
        r.setStrokeWidth(1);
        r.getStyleClass().add("mt-title-bar-glyph");
        return r;
    }

    private javafx.scene.Node restoreGlyph() {
        Rectangle back = new Rectangle(2, 0, 8, 8);
        back.setFill(null);
        back.setStrokeWidth(1);
        back.getStyleClass().add("mt-title-bar-glyph");
        Rectangle front = new Rectangle(0, 2, 8, 8);
        front.setFill(null);
        front.setStrokeWidth(1);
        front.getStyleClass().add("mt-title-bar-glyph");
        javafx.scene.Group g = new javafx.scene.Group(back, front);
        return g;
    }

    /** Bundled mType logo, scaled to a 20-px-tall ImageView. */
    public static ImageView loadLogo() {
        ImageView view = new ImageView();
        try (InputStream in = WindowTitleBar.class.getResourceAsStream("/icons/mtype-logo.png")) {
            if (in != null) view.setImage(new Image(in));
        } catch (Exception ignored) {
        }
        view.setPreserveRatio(true);
        view.setFitHeight(20);
        view.setSmooth(true);
        return view;
    }

    private javafx.scene.Node closeGlyph() {
        Line a = new Line(0, 0, 10, 10);
        Line b = new Line(0, 10, 10, 0);
        a.getStyleClass().add("mt-title-bar-glyph");
        b.getStyleClass().add("mt-title-bar-glyph");
        return new javafx.scene.Group(a, b);
    }
}
