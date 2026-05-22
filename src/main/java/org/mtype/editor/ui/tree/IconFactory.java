package org.mtype.editor.ui.tree;

import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds tree-view icons that mirror the SVGs in
 * C:\matan\mType\mtype-vscode-extension\icons (which JavaFX can't render directly).
 */
public final class IconFactory {

    private static final double ICON_SIZE = 16;

    private IconFactory() {}

    public static Node iconForPath(Path p, boolean expanded) {
        if (Files.isDirectory(p)) {
            return expanded ? folderOpen() : folderClosed();
        }
        String name = p.getFileName() == null ? "" : p.getFileName().toString().toLowerCase();
        if (name.endsWith(".mtproj.lock")) return lockedM("#42A5F5", "#1565C0");
        if (name.endsWith(".mt"))          return letterM("#FFD93D", "#F39C12", null);
        if (name.endsWith(".mtc"))         return letterM("#4A90E2", "#2E5C8A", null);
        if (name.endsWith(".mtproj"))      return letterM("#66BB6A", "#2E7D32", "PROJ");
        if (name.endsWith(".mtworkspace")) return letterM("#AB47BC", "#6A1B9A", "WS");
        return genericFile();
    }

    /* ============================== File icons ============================== */

    private static StackPane letterM(String c1, String c2, String subLabel) {
        Text m = new Text("M");
        m.setFont(Font.font("Arial Black", FontWeight.BLACK, subLabel == null ? 14 : 12));
        m.setFill(new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web(c1)),
                new Stop(1, Color.web(c2))));

        StackPane pane = sized();
        StackPane.setAlignment(m, subLabel == null ? Pos.CENTER : Pos.TOP_CENTER);
        if (subLabel == null) {
            pane.getChildren().add(m);
        } else {
            Text tag = new Text(subLabel);
            tag.setFont(Font.font("Arial", FontWeight.BOLD, 5));
            tag.setFill(Color.web(c2));
            StackPane.setAlignment(tag, Pos.BOTTOM_CENTER);
            pane.getChildren().addAll(m, tag);
        }
        return pane;
    }

    private static StackPane lockedM(String c1, String c2) {
        StackPane base = letterM(c1, c2, "LOCK");
        // tiny padlock badge in top-right
        SVGPath shackle = new SVGPath();
        shackle.setContent("M1,3 Q1,0 2.5,0 Q4,0 4,3");
        shackle.setStroke(Color.web("#F57F17"));
        shackle.setStrokeWidth(1.0);
        shackle.setFill(Color.TRANSPARENT);

        SVGPath body = new SVGPath();
        body.setContent("M0,3 H5 V7 H0 Z");
        body.setFill(Color.web("#FBC02D"));

        Group lock = new Group(body, shackle);
        StackPane.setAlignment(lock, Pos.TOP_RIGHT);
        lock.setTranslateX(-1);
        lock.setTranslateY(1);
        base.getChildren().add(lock);
        return base;
    }

    private static StackPane genericFile() {
        SVGPath outline = new SVGPath();
        outline.setContent("M3 1.5 V14.5 H13 V4.5 L10 1.5 Z M10 1.5 V4.5 H13");
        outline.setFill(Color.TRANSPARENT);
        outline.setStroke(Color.web("#8a8a8a"));
        outline.setStrokeWidth(1.0);
        return wrap(outline);
    }

    /* ============================== Folder icons ============================== */

    private static StackPane folderClosed() {
        SVGPath folder = new SVGPath();
        folder.setContent("M14.5 3H7.71L6.85 2.15C6.54 1.84 6.28 1.5 5.79 1.5H2.5C1.84 1.5 1.5 1.84 1.5 2.5V12.5C1.5 13.16 1.84 13.5 2.5 13.5H14.5C15.16 13.5 15.5 13.16 15.5 12.5V4C15.5 3.34 15.16 3 14.5 3Z");
        folder.setFill(Color.web("#8a8a8a"));
        folder.setStroke(Color.web("#8a8a8a"));
        folder.setStrokeWidth(0.5);
        return wrap(folder);
    }

    private static StackPane folderOpen() {
        SVGPath tab = new SVGPath();
        tab.setContent("M14.5 4H7.71L6.85 3.15C6.54 2.84 6.28 2.5 5.79 2.5H2.5C1.84 2.5 1.5 2.84 1.5 3.5V4H14.5Z");
        tab.setFill(Color.web("#dcb67a"));
        tab.setStroke(Color.web("#dcb67a"));
        tab.setStrokeWidth(0.5);

        SVGPath back = new SVGPath();
        back.setContent("M1.5 4V12.5C1.5 13.16 1.84 13.5 2.5 13.5H14.5C15.16 13.5 15.5 13.16 15.5 12.5V5C15.5 4.34 15.16 4 14.5 4H1.5Z");
        back.setFill(Color.web("#e8c547"));
        back.setStroke(Color.web("#e8c547"));
        back.setStrokeWidth(0.5);

        SVGPath front = new SVGPath();
        front.setContent("M2 5H14.5C15.16 5 15.5 5.34 15.5 6V12C15.5 12.66 15.16 13 14.5 13H2C1.34 13 1 12.66 1 12V6C1 5.34 1.34 5 2 5Z");
        front.setFill(Color.web("#f4d03f"));
        front.setStroke(Color.web("#f4d03f"));
        front.setStrokeWidth(0.3);

        Group g = new Group(tab, back, front);
        return wrap(g);
    }

    /* ============================== helpers ============================== */

    private static StackPane sized() {
        StackPane pane = new StackPane();
        pane.setMinSize(ICON_SIZE, ICON_SIZE);
        pane.setPrefSize(ICON_SIZE, ICON_SIZE);
        pane.setMaxSize(ICON_SIZE, ICON_SIZE);
        return pane;
    }

    private static StackPane wrap(Node n) {
        StackPane pane = sized();
        pane.getChildren().add(n);
        return pane;
    }
}
