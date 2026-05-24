package org.mtype.editor.ui.debug;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;

/**
 * Small SVG/shape factories for debugger UI icons. All icons render at roughly
 * 16x16 (debug toolbar) or 32x32 (activity bar). Each icon is fillable via CSS
 * style classes "mt-activity-icon" / "mt-debug-icon".
 */
public final class DebuggerIcons {
    private DebuggerIcons() {}

    /** Activity-bar "debug" icon: a bug with an arrow tail, VS Code style. */
    public static Node activityBugIcon() {
        SVGPath body = new SVGPath();
        // simplified bug body: a rounded oval with legs
        body.setContent("M11 11 H21 V25 Q21 28 16 28 Q11 28 11 25 Z");
        body.getStyleClass().add("mt-activity-icon");

        SVGPath head = new SVGPath();
        head.setContent("M13 11 Q13 6 16 6 Q19 6 19 11");
        head.getStyleClass().add("mt-activity-icon");

        SVGPath antennaL = new SVGPath();
        antennaL.setContent("M13 8 L9 4");
        antennaL.getStyleClass().add("mt-activity-icon-muted");
        SVGPath antennaR = new SVGPath();
        antennaR.setContent("M19 8 L23 4");
        antennaR.getStyleClass().add("mt-activity-icon-muted");

        SVGPath legs = new SVGPath();
        legs.setContent("M11 16 L7 14 M11 19 L7 21 M21 16 L25 14 M21 19 L25 21 M16 28 L16 32");
        legs.getStyleClass().add("mt-activity-icon-muted");

        Group g = new Group(legs, antennaL, antennaR, body, head);
        g.getStyleClass().add("mt-activity-graphic");
        return g;
    }

    /** Triangle play arrow. Used for Continue / Start. */
    public static Node playIcon() {
        Polygon p = new Polygon(3, 2, 13, 8, 3, 14);
        p.getStyleClass().add("mt-debug-icon");
        return wrap(p);
    }

    /** Filled square. Used for Stop. */
    public static Node stopIcon() {
        Rectangle r = new Rectangle(3, 3, 10, 10);
        r.getStyleClass().add("mt-debug-icon-danger");
        return wrap(r);
    }

    /** Step Over: curved arrow over a dot. */
    public static Node stepOverIcon() {
        SVGPath arc = new SVGPath();
        arc.setContent("M3 9 Q3 3 8 3 Q13 3 13 9 L11 7 M13 9 L15 7");
        arc.getStyleClass().add("mt-debug-icon");
        Circle dot = new Circle(8, 13, 1.6);
        dot.getStyleClass().add("mt-debug-icon-fill");
        return wrap(arc, dot);
    }

    /** Step Into: down-arrow into a dot. */
    public static Node stepIntoIcon() {
        Line shaft = new Line(8, 2, 8, 9);
        shaft.getStyleClass().add("mt-debug-icon");
        Polygon head = new Polygon(5, 7, 11, 7, 8, 11);
        head.getStyleClass().add("mt-debug-icon-fill");
        Circle dot = new Circle(8, 13.5, 1.6);
        dot.getStyleClass().add("mt-debug-icon-fill");
        return wrap(shaft, head, dot);
    }

    /** Step Out: up-arrow out of a dot. */
    public static Node stepOutIcon() {
        Line shaft = new Line(8, 5, 8, 12);
        shaft.getStyleClass().add("mt-debug-icon");
        Polygon head = new Polygon(5, 7, 11, 7, 8, 3);
        head.getStyleClass().add("mt-debug-icon-fill");
        Circle dot = new Circle(8, 14, 1.6);
        dot.getStyleClass().add("mt-debug-icon-fill");
        return wrap(shaft, head, dot);
    }

    /** Circular arrow for Restart. */
    public static Node restartIcon() {
        SVGPath arc = new SVGPath();
        arc.setContent("M3 8 A5 5 0 1 1 8 13");
        arc.getStyleClass().add("mt-debug-icon");
        Polygon head = new Polygon(6, 12, 10, 13, 8, 9);
        head.getStyleClass().add("mt-debug-icon-fill");
        return wrap(arc, head);
    }

    private static Group wrap(Node... children) {
        Group g = new Group(children);
        g.getStyleClass().add("mt-debug-graphic");
        return g;
    }
}
