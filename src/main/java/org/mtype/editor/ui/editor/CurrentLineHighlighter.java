package org.mtype.editor.ui.editor;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import org.fxmisc.richtext.CodeArea;
import org.reactfx.Subscription;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Draws the current (caret) line highlight as a single full-width translucent band in an overlay
 * {@code layer} that sits on top of the {@link CodeArea} text but below the inlay-hint labels.
 *
 * <p>RichTextFX sizes each {@code .paragraph-box} cell to its content width (wrapping is off), so a
 * {@code .paragraph-box:has-caret} background only ever reaches end-of-text — and inlay hints, which
 * shift glyph runs rightward via {@code translateX} without growing the cell bounds, push the tail
 * outside that background. Drawing the highlight as a full-width overlay band decouples it from text
 * geometry entirely, so it spans the whole row regardless of inlay-hint shifts. Mirrors the overlay /
 * debounced-relayout pattern used by {@link InlayHintsController}.
 */
final class CurrentLineHighlighter {
    private final CodeArea area;
    private final Pane layer;
    private final Region band;
    private final Subscription viewportSubscription;
    private boolean layoutScheduled;

    CurrentLineHighlighter(CodeArea area, Pane layer) {
        this.area = area;
        this.layer = layer;
        this.layer.setMouseTransparent(true);
        this.layer.getStyleClass().add("mt-current-line-layer");

        this.band = new Region();
        this.band.getStyleClass().add("mt-current-line-band");
        this.band.setManaged(false);
        this.band.setVisible(false);
        this.layer.getChildren().add(band);

        this.viewportSubscription = area.viewportDirtyEvents().subscribe(ignored -> requestLayout());
        this.layer.layoutBoundsProperty().addListener((_, _, _) -> requestLayout());
        area.currentParagraphProperty().addListener((_, _, _) -> requestLayout());
    }

    void dispose() {
        viewportSubscription.unsubscribe();
        band.setVisible(false);
    }

    private void requestLayout() {
        if (layoutScheduled) return;
        layoutScheduled = true;
        Platform.runLater(() -> {
            layoutScheduled = false;
            layoutBand();
        });
    }

    private void layoutBand() {
        if (layer.getScene() == null) {
            band.setVisible(false);
            return;
        }
        int line = area.getCurrentParagraph();

        List<Node> boxes = sortedByScreenY(".paragraph-box");
        int visibleCount = Math.min(boxes.size(), area.getVisibleParagraphs().size());
        if (visibleCount <= 0) {
            band.setVisible(false);
            return;
        }

        int target = -1;
        for (int visible = 0; visible < visibleCount; visible++) {
            try {
                if (area.visibleParToAllParIndex(visible) == line) {
                    target = visible;
                    break;
                }
            } catch (Exception ignored) {}
        }
        if (target < 0) {
            band.setVisible(false);
            return;
        }

        Bounds boxScreen = boxes.get(target).localToScreen(boxes.get(target).getBoundsInLocal());
        if (boxScreen == null) {
            band.setVisible(false);
            return;
        }

        // Full-row extent from the paragraph box (left edge = gutter edge, covers code-lens top
        // padding), matching the original .paragraph-box:has-caret highlight but full-width.
        Point2D topLeft = layer.screenToLocal(boxScreen.getMinX(), boxScreen.getMinY());
        if (topLeft == null) {
            band.setVisible(false);
            return;
        }
        double leftX = topLeft.getX();

        // Right edge = the editor's full viewport width. Several candidate nodes can each report
        // either content width or viewport width depending on layout state, so take the largest
        // right edge across all of them (in layer-local coords). The scene edge is the reliable
        // backstop: the editor is the right-most element, so the window's right edge == editor's.
        double layerW = layer.getWidth();
        double parentR = localRightEdge(layer.getParent());
        double areaR = localRightEdge(area);
        double sceneR = Double.NEGATIVE_INFINITY;
        javafx.scene.Scene scene = layer.getScene();
        if (scene != null) {
            Point2D r = layer.sceneToLocal(scene.getWidth(), 0);
            if (r != null) sceneR = r.getX();
        }
        double rightX = max(layerW, parentR, areaR, sceneR);
        double width = Math.max(0, rightX - leftX);

        band.relocate(leftX, topLeft.getY());
        band.resize(width, boxScreen.getHeight());
        band.setVisible(true);
    }

    /** Right edge of {@code node} expressed in this layer's local coordinate space, or -inf. */
    private double localRightEdge(Node node) {
        if (node == null) return Double.NEGATIVE_INFINITY;
        Bounds screen = node.localToScreen(node.getBoundsInLocal());
        if (screen == null) return Double.NEGATIVE_INFINITY;
        Point2D local = layer.screenToLocal(screen.getMaxX(), screen.getMinY());
        return local == null ? Double.NEGATIVE_INFINITY : local.getX();
    }

    private static double max(double a, double b, double c, double d) {
        return Math.max(Math.max(a, b), Math.max(c, d));
    }

    private List<Node> sortedByScreenY(String selector) {
        return area.lookupAll(selector).stream()
                .sorted(Comparator.comparingDouble(this::screenMinY))
                .collect(Collectors.toList());
    }

    private double screenMinY(Node node) {
        Bounds b = node.localToScreen(node.getBoundsInLocal());
        return b == null ? 0.0 : b.getMinY();
    }
}
