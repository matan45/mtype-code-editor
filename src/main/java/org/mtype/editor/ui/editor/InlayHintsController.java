package org.mtype.editor.ui.editor;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.text.Text;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintKind;
import org.eclipse.lsp4j.InlayHintLabelPart;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.fxmisc.richtext.CodeArea;
import org.mtype.editor.lsp.Positions;
import org.reactfx.Subscription;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

final class InlayHintsController {
    private final CodeArea area;
    private final Pane layer;
    private final List<RenderedHint> rendered = new ArrayList<>();
    private final Subscription viewportSubscription;

    InlayHintsController(CodeArea area, Pane layer) {
        this.area = area;
        this.layer = layer;
        this.layer.setMouseTransparent(true);
        this.layer.getStyleClass().add("mt-inlay-hints-layer");
        this.viewportSubscription = area.viewportDirtyEvents().subscribe(ignored ->
                Platform.runLater(this::layoutHints));
        this.layer.layoutBoundsProperty().addListener((obs, oldBounds, newBounds) -> layoutHints());
    }

    void setHints(List<InlayHint> hints) {
        rendered.clear();
        layer.getChildren().clear();
        if (hints == null || hints.isEmpty()) return;

        for (InlayHint hint : hints) {
            if (hint == null || hint.getPosition() == null) continue;
            String text = labelText(hint);
            if (text.isBlank()) continue;

            Label label = new Label(paddedText(hint, text));
            label.getStyleClass().add("mt-inlay-hint");
            InlayHintKind kind = hint.getKind();
            if (kind == InlayHintKind.Parameter) {
                label.getStyleClass().add("mt-inlay-hint-param");
            } else if (kind == InlayHintKind.Type) {
                label.getStyleClass().add("mt-inlay-hint-type");
            }
            label.setMouseTransparent(true);
            rendered.add(new RenderedHint(hint.getPosition(), label));
            layer.getChildren().add(label);
        }
        layoutHints();
    }

    void clear() {
        rendered.clear();
        layer.getChildren().clear();
    }

    void dispose() {
        viewportSubscription.unsubscribe();
        clear();
    }

    private void layoutHints() {
        resetInlineGaps();
        if (rendered.isEmpty() || layer.getScene() == null) return;

        List<RenderedHint> ordered = rendered.stream()
                .sorted(Comparator
                        .comparingInt((RenderedHint h) -> h.position().getLine())
                        .thenComparingInt(h -> h.position().getCharacter()))
                .toList();
        List<LineGap> gaps = new ArrayList<>();
        int currentLine = -1;
        double currentLineShift = 0.0;

        for (RenderedHint hint : ordered) {
            Position p = hint.position();
            Label label = hint.label();
            Optional<Bounds> maybeBounds;
            try {
                int offset = Positions.offset(area.getText(), p.getLine(), p.getCharacter());
                int end = Math.min(area.getLength(), offset + 1);
                maybeBounds = area.getCharacterBoundsOnScreen(offset, end);
            } catch (Exception ignored) {
                maybeBounds = Optional.empty();
            }

            if (maybeBounds.isEmpty()) {
                label.setVisible(false);
                continue;
            }

            if (p.getLine() != currentLine) {
                currentLine = p.getLine();
                currentLineShift = 0.0;
            }

            Bounds charBounds = maybeBounds.get();
            Point2D local = layer.screenToLocal(charBounds.getMinX(), charBounds.getMinY());
            label.applyCss();
            label.autosize();
            double width = label.prefWidth(-1);
            double y = local.getY() + Math.max(0.0,
                    (charBounds.getHeight() - label.prefHeight(-1)) * 0.5);
            label.relocate(Math.max(0, local.getX() + currentLineShift), Math.max(0, y));
            label.setVisible(true);
            gaps.add(new LineGap(p.getLine(), p.getCharacter(), width));
            currentLineShift += width;
        }

        applyInlineGaps(gaps);
    }

    private void resetInlineGaps() {
        for (Node paragraphText : paragraphTextNodes()) {
            if (paragraphText instanceof Pane pane) {
                for (Node child : pane.getChildren()) {
                    if (child instanceof Text) {
                        child.setTranslateX(0.0);
                    }
                }
            }
        }
    }

    private void applyInlineGaps(List<LineGap> gaps) {
        if (gaps.isEmpty()) return;
        List<Node> paragraphTexts = paragraphTextNodes();
        int visibleCount = Math.min(paragraphTexts.size(), area.getVisibleParagraphs().size());
        for (int visible = 0; visible < visibleCount; visible++) {
            int paragraphIndex;
            try {
                paragraphIndex = area.visibleParToAllParIndex(visible);
            } catch (Exception ignored) {
                continue;
            }
            List<LineGap> lineGaps = gaps.stream()
                    .filter(gap -> gap.line() == paragraphIndex)
                    .sorted(Comparator.comparingInt(LineGap::character))
                    .toList();
            if (lineGaps.isEmpty()) continue;

            Node paragraphText = paragraphTexts.get(visible);
            if (!(paragraphText instanceof Pane pane)) continue;

            int start = 0;
            for (Node child : pane.getChildren()) {
                if (!(child instanceof Text text)) continue;
                int length = text.getText() == null ? 0 : text.getText().length();
                int end = start + length;
                double shift = shiftForSegment(start, end, lineGaps);
                child.setTranslateX(shift);
                start = end;
            }
        }
    }

    private double shiftForSegment(int start, int end, List<LineGap> gaps) {
        double shift = 0.0;
        for (LineGap gap : gaps) {
            int ch = gap.character();
            if (ch <= start || (ch > start && ch < end)) {
                shift += gap.width();
            }
        }
        return shift;
    }

    private List<Node> paragraphTextNodes() {
        Set<Node> nodes = area.lookupAll(".paragraph-text");
        return nodes.stream()
                .filter(node -> node instanceof Pane)
                .sorted(Comparator.comparingDouble(this::screenMinY))
                .collect(Collectors.toList());
    }

    private double screenMinY(Node node) {
        Bounds b = node.localToScreen(node.getBoundsInLocal());
        return b == null ? 0.0 : b.getMinY();
    }

    private static String labelText(InlayHint hint) {
        Either<String, List<InlayHintLabelPart>> label = hint.getLabel();
        if (label == null) return "";
        if (label.isLeft()) return label.getLeft() == null ? "" : label.getLeft();
        List<InlayHintLabelPart> parts = label.getRight();
        if (parts == null || parts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (InlayHintLabelPart part : parts) {
            if (part != null && part.getValue() != null) sb.append(part.getValue());
        }
        return sb.toString();
    }

    private static String paddedText(InlayHint hint, String text) {
        boolean left = Boolean.TRUE.equals(hint.getPaddingLeft());
        boolean right = Boolean.TRUE.equals(hint.getPaddingRight());
        return (left ? " " : "") + text + (right ? " " : "");
    }

    private record RenderedHint(Position position, Label label) {}
    private record LineGap(int line, int character, double width) {}
}
