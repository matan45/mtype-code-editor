package org.mtype.editor.ui.chrome;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;

/**
 * Builders for the three traffic-light chrome circles used in both the
 * main window's title bar and dialog popups.
 *
 * Each circle is a {@link StackPane} hosting a coloured {@link Circle} with
 * a small glyph centred on top. Built from shape primitives rather than
 * JavaFX {@link javafx.scene.control.Button} so the JavaFX default button
 * styling (modena padding, pill background) can't compete for specificity
 * and accidentally make the buttons look like wide pills.
 */
public final class ChromeCircles {
    private static final double DIAMETER = 14;
    private static final double RADIUS = DIAMETER / 2.0;
    private static final Color MIN = Color.web("#febc2e");
    private static final Color MAX = Color.web("#28c840");
    private static final Color CLOSE = Color.web("#ff5f57");
    private static final Color GLYPH = Color.color(0, 0, 0, 0.65);

    private ChromeCircles() {}

    public static StackPane minimize(String tooltip, Runnable onClick) {
        return circle(MIN, minGlyph(), tooltip, onClick);
    }

    public static StackPane maximize(String tooltip, Runnable onClick) {
        return circle(MAX, maxGlyph(), tooltip, onClick);
    }

    public static StackPane close(String tooltip, Runnable onClick) {
        return circle(CLOSE, closeGlyph(), tooltip, onClick);
    }

    private static StackPane circle(Color color, Node glyph, String tooltip, Runnable onClick) {
        Circle disc = new Circle(RADIUS, color);
        StackPane sp = new StackPane(disc, glyph);
        sp.setMinSize(DIAMETER, DIAMETER);
        sp.setPrefSize(DIAMETER, DIAMETER);
        sp.setMaxSize(DIAMETER, DIAMETER);
        sp.getStyleClass().add("mt-chrome-circle");
        Tooltip.install(sp, new Tooltip(tooltip));
        sp.setOnMouseEntered(_ -> disc.setFill(color.brighter()));
        sp.setOnMouseExited(_ -> disc.setFill(color));
        sp.setOnMousePressed(_ -> disc.setFill(color.darker()));
        sp.setOnMouseReleased(e -> {
            disc.setFill(color);
            if (e.getButton() == MouseButton.PRIMARY
                    && sp.contains(e.getX(), e.getY())) {
                onClick.run();
            }
        });
        return sp;
    }

    private static Node minGlyph() {
        Line l = new Line(0, 0, 6, 0);
        l.setStroke(GLYPH);
        l.setStrokeWidth(1.2);
        return l;
    }

    private static Node maxGlyph() {
        Rectangle r = new Rectangle(6, 6);
        r.setFill(null);
        r.setStroke(GLYPH);
        r.setStrokeWidth(1.1);
        return r;
    }

    private static Node closeGlyph() {
        Line a = new Line(0, 0, 6, 6);
        Line b = new Line(0, 6, 6, 0);
        a.setStroke(GLYPH);
        b.setStroke(GLYPH);
        a.setStrokeWidth(1.2);
        b.setStrokeWidth(1.2);
        return new Group(a, b);
    }
}
