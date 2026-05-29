package org.mtype.editor.ui.chrome;

import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class WindowMaximizer {
    private static final Map<Stage, SavedBounds> SAVED_BOUNDS = new WeakHashMap<>();

    private WindowMaximizer() {
    }

    public static boolean isMaximized(Stage stage) {
        return stage != null && (stage.isMaximized() || SAVED_BOUNDS.containsKey(stage));
    }

    public static void toggle(Stage stage) {
        if (isMaximized(stage)) restore(stage);
        else maximize(stage);
    }

    public static void maximize(Stage stage) {
        if (stage == null || SAVED_BOUNDS.containsKey(stage)) return;
        Rectangle2D visual = visualBoundsFor(stage);
        double savedWidth = finitePositive(stage.getWidth(), stage.getScene() == null ? 1280 : stage.getScene().getWidth());
        double savedHeight = finitePositive(stage.getHeight(), stage.getScene() == null ? 800 : stage.getScene().getHeight());
        savedWidth = Math.min(savedWidth, visual.getWidth());
        savedHeight = Math.min(savedHeight, visual.getHeight());
        double savedX = Double.isFinite(stage.getX())
                ? stage.getX()
                : visual.getMinX() + Math.max(0, (visual.getWidth() - savedWidth) / 2.0);
        double savedY = Double.isFinite(stage.getY())
                ? stage.getY()
                : visual.getMinY() + Math.max(0, (visual.getHeight() - savedHeight) / 2.0);

        SAVED_BOUNDS.put(stage, new SavedBounds(savedX, savedY, savedWidth, savedHeight));
        if (stage.isMaximized()) stage.setMaximized(false);

        stage.setX(visual.getMinX());
        stage.setY(visual.getMinY());
        stage.setWidth(visual.getWidth());
        stage.setHeight(visual.getHeight());
    }

    public static void restore(Stage stage) {
        if (stage == null) return;
        if (stage.isMaximized()) stage.setMaximized(false);
        SavedBounds saved = SAVED_BOUNDS.remove(stage);
        if (saved == null) return;

        if (Double.isFinite(saved.x)) stage.setX(saved.x);
        if (Double.isFinite(saved.y)) stage.setY(saved.y);
        if (Double.isFinite(saved.width) && saved.width > 0) stage.setWidth(saved.width);
        if (Double.isFinite(saved.height) && saved.height > 0) stage.setHeight(saved.height);
    }

    private static Rectangle2D visualBoundsFor(Stage stage) {
        double x = Double.isFinite(stage.getX()) ? stage.getX() : 0;
        double y = Double.isFinite(stage.getY()) ? stage.getY() : 0;
        double width = Double.isFinite(stage.getWidth()) && stage.getWidth() > 0 ? stage.getWidth() : 1;
        double height = Double.isFinite(stage.getHeight()) && stage.getHeight() > 0 ? stage.getHeight() : 1;

        List<Screen> screens = Screen.getScreensForRectangle(x, y, width, height);
        Screen screen = screens.isEmpty() ? Screen.getPrimary() : screens.getFirst();
        return screen.getVisualBounds();
    }

    private static double finitePositive(double value, double fallback) {
        if (Double.isFinite(value) && value > 0) return value;
        if (Double.isFinite(fallback) && fallback > 0) return fallback;
        return 1;
    }

    private record SavedBounds(double x, double y, double width, double height) {
    }
}
