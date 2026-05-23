package org.mtype.editor.ui.chrome;

import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;

/**
 * Drives edge-resize behavior for an {@link javafx.stage.StageStyle#UNDECORATED}
 * stage. Without the native chrome the OS no longer handles resize, so we
 * detect cursor proximity to the scene's outer edges and translate
 * mouse-drag deltas into stage size changes.
 *
 * Maximized state is treated as non-resizable here — Windows handles
 * un-maximize on drag from the title bar; resizing the edges of a
 * maximized window has no useful meaning.
 */
public final class WindowResizer {
    private static final double EDGE = 6;

    private final Stage stage;
    private final Scene scene;
    private Edge active;
    private double pressScreenX;
    private double pressScreenY;
    private double pressStageX;
    private double pressStageY;
    private double pressStageW;
    private double pressStageH;

    private enum Edge {
        N, S, E, W, NE, NW, SE, SW
    }

    private WindowResizer(Stage stage, Scene scene) {
        this.stage = stage;
        this.scene = scene;
    }

    public static void install(Stage stage, Scene scene) {
        WindowResizer wr = new WindowResizer(stage, scene);
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, wr::onMoved);
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, wr::onPressed);
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, wr::onDragged);
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, wr::onReleased);
        scene.addEventFilter(MouseEvent.MOUSE_EXITED, _ -> wr.resetCursorIfIdle());
    }

    private void onMoved(MouseEvent e) {
        if (stage.isMaximized() || stage.isFullScreen()) {
            resetCursorIfIdle();
            return;
        }
        Edge edge = detectEdge(e.getSceneX(), e.getSceneY());
        if (edge == null) {
            resetCursorIfIdle();
        } else {
            scene.setCursor(cursorFor(edge));
        }
    }

    private void onPressed(MouseEvent e) {
        if (e.getButton() != MouseButton.PRIMARY) return;
        if (stage.isMaximized() || stage.isFullScreen()) return;
        Edge edge = detectEdge(e.getSceneX(), e.getSceneY());
        if (edge == null) return;
        active = edge;
        pressScreenX = e.getScreenX();
        pressScreenY = e.getScreenY();
        pressStageX = stage.getX();
        pressStageY = stage.getY();
        pressStageW = stage.getWidth();
        pressStageH = stage.getHeight();
        e.consume();
    }

    private void onDragged(MouseEvent e) {
        if (active == null) return;
        double dx = e.getScreenX() - pressScreenX;
        double dy = e.getScreenY() - pressScreenY;
        double minW = stage.getMinWidth() > 0 ? stage.getMinWidth() : 200;
        double minH = stage.getMinHeight() > 0 ? stage.getMinHeight() : 120;

        switch (active) {
            case E -> setWidthClamped(pressStageW + dx, minW);
            case S -> setHeightClamped(pressStageH + dy, minH);
            case SE -> {
                setWidthClamped(pressStageW + dx, minW);
                setHeightClamped(pressStageH + dy, minH);
            }
            case W -> resizeFromLeft(dx, minW);
            case N -> resizeFromTop(dy, minH);
            case NW -> {
                resizeFromLeft(dx, minW);
                resizeFromTop(dy, minH);
            }
            case NE -> {
                setWidthClamped(pressStageW + dx, minW);
                resizeFromTop(dy, minH);
            }
            case SW -> {
                resizeFromLeft(dx, minW);
                setHeightClamped(pressStageH + dy, minH);
            }
        }
        e.consume();
    }

    private void onReleased(MouseEvent e) {
        if (active == null) return;
        active = null;
        resetCursorIfIdle();
    }

    private void setWidthClamped(double w, double min) {
        stage.setWidth(Math.max(min, w));
    }

    private void setHeightClamped(double h, double min) {
        stage.setHeight(Math.max(min, h));
    }

    private void resizeFromLeft(double dx, double minW) {
        double newW = Math.max(minW, pressStageW - dx);
        double newX = pressStageX + (pressStageW - newW);
        stage.setX(newX);
        stage.setWidth(newW);
    }

    private void resizeFromTop(double dy, double minH) {
        double newH = Math.max(minH, pressStageH - dy);
        double newY = pressStageY + (pressStageH - newH);
        stage.setY(newY);
        stage.setHeight(newH);
    }

    private Edge detectEdge(double x, double y) {
        double w = scene.getWidth();
        double h = scene.getHeight();
        boolean left = x <= EDGE;
        boolean right = x >= w - EDGE;
        boolean top = y <= EDGE;
        boolean bottom = y >= h - EDGE;
        if (top && left) return Edge.NW;
        if (top && right) return Edge.NE;
        if (bottom && left) return Edge.SW;
        if (bottom && right) return Edge.SE;
        if (left) return Edge.W;
        if (right) return Edge.E;
        if (top) return Edge.N;
        if (bottom) return Edge.S;
        return null;
    }

    private Cursor cursorFor(Edge edge) {
        return switch (edge) {
            case N -> Cursor.N_RESIZE;
            case S -> Cursor.S_RESIZE;
            case E -> Cursor.E_RESIZE;
            case W -> Cursor.W_RESIZE;
            case NE -> Cursor.NE_RESIZE;
            case NW -> Cursor.NW_RESIZE;
            case SE -> Cursor.SE_RESIZE;
            case SW -> Cursor.SW_RESIZE;
        };
    }

    private void resetCursorIfIdle() {
        if (active == null) scene.setCursor(Cursor.DEFAULT);
    }
}
