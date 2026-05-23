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
import java.util.function.Consumer;
import java.util.stream.Collectors;

final class InlayHintsController {
    private final CodeArea area;
    private final Pane layer;
    private final Consumer<List<Position>> anchorListener;
    private final List<RenderedHint> rendered = new ArrayList<>();
    private final Subscription viewportSubscription;

    InlayHintsController(CodeArea area, Pane layer, Consumer<List<Position>> anchorListener) {
        this.area = area;
        this.layer = layer;
        this.anchorListener = anchorListener;
        this.layer.setMouseTransparent(true);
        this.layer.getStyleClass().add("mt-inlay-hints-layer");
        this.viewportSubscription = area.viewportDirtyEvents().subscribe(ignored ->
                Platform.runLater(this::layoutHints));
        this.layer.layoutBoundsProperty().addListener((_, _, _) -> layoutHints());
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
            rendered.add(new RenderedHint(renderPosition(hint, text), label));
            layer.getChildren().add(label);
        }
        publishAnchors();
        layoutHints();
    }

    void clear() {
        rendered.clear();
        layer.getChildren().clear();
        publishAnchors();
    }

    void dispose() {
        viewportSubscription.unsubscribe();
        clear();
    }

    void refreshLayout() {
        layoutHints();
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
                int start = Math.max(0, Math.min(offset - 1, area.getLength()));
                int end = Math.max(start + 1, Math.min(offset, area.getLength()));
                maybeBounds = area.getCharacterBoundsOnScreen(start, end);
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
            Point2D local = layer.screenToLocal(charBounds.getMaxX(), charBounds.getMinY());
            label.applyCss();
            label.autosize();
            double width = Math.ceil(label.prefWidth(-1)) + 1.0;
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
                if (length == 0) continue;
                int end = start + length;
                double shift = shiftForSegment(start, lineGaps);
                child.setTranslateX(shift);
                start = end;
            }
        }
    }

    private double shiftForSegment(int start, List<LineGap> gaps) {
        double shift = 0.0;
        for (LineGap gap : gaps) {
            int ch = gap.character();
            if (ch <= start) {
                shift += gap.width();
            }
        }
        return shift;
    }

    private void publishAnchors() {
        if (anchorListener == null) return;
        anchorListener.accept(rendered.stream()
                .map(RenderedHint::position)
                .toList());
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

    private Position renderPosition(InlayHint hint, String labelText) {
        Position p = hint.getPosition();
        if (!isTypeHint(hint, labelText) || p == null) return p;

        String text = area.getText();
        int offset;
        try {
            offset = Positions.offset(text, p.getLine(), p.getCharacter());
        } catch (Exception ignored) {
            return p;
        }

        if (offset < 0 || offset > text.length()) {
            return p;
        }

        Position bareLambdaPosition = bareLambdaParamEndOnLine(text, offset);
        if (bareLambdaPosition != null) {
            return bareLambdaPosition;
        }

        if (offset == text.length() || !isIdentifierChar(text.charAt(offset))) {
            int next = skipHorizontalWhitespace(text, offset);
            if (next >= text.length() || !isIdentifierChar(text.charAt(next))) {
                return p;
            }
            int candidateEnd = identifierEnd(text, next);
            int afterCandidate = skipHorizontalWhitespace(text, candidateEnd);
            if (afterCandidate + 1 >= text.length()
                    || text.charAt(afterCandidate) != '-'
                    || text.charAt(afterCandidate + 1) != '>') {
                return p;
            }
            return positionAtOffset(text, candidateEnd);
        }

        return positionAtOffset(text, identifierEnd(text, offset));
    }

    private static Position bareLambdaParamEndOnLine(String text, int offset) {
        int safeOffset = Math.max(0, Math.min(offset, text.length()));
        int lineStart = text.lastIndexOf('\n', Math.max(0, safeOffset - 1)) + 1;
        int lineEnd = text.indexOf('\n', safeOffset);
        if (lineEnd < 0) lineEnd = text.length();

        int arrow = text.indexOf("->", lineStart);
        while (arrow >= 0 && arrow < lineEnd) {
            int beforeArrow = skipHorizontalWhitespaceBackward(text, arrow - 1, lineStart);
            if (beforeArrow >= lineStart && isIdentifierChar(text.charAt(beforeArrow))) {
                int idEnd = beforeArrow + 1;
                int idStart = beforeArrow;
                while (idStart > lineStart && isIdentifierChar(text.charAt(idStart - 1))) {
                    idStart--;
                }
                if (safeOffset <= idEnd) {
                    return positionAtOffset(text, idEnd);
                }
            }
            arrow = text.indexOf("->", arrow + 2);
        }
        return null;
    }

    private static int skipHorizontalWhitespaceBackward(String text, int offset, int minInclusive) {
        int i = Math.min(offset, text.length() - 1);
        while (i >= minInclusive) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t') break;
            i--;
        }
        return i;
    }

    private static int skipHorizontalWhitespace(String text, int offset) {
        int i = Math.max(0, Math.min(offset, text.length()));
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\t') break;
            i++;
        }
        return i;
    }

    private static int identifierEnd(String text, int offset) {
        int end = offset;
        while (end < text.length() && isIdentifierChar(text.charAt(end))) {
            end++;
        }
        return end;
    }

    private static boolean isTypeHint(InlayHint hint, String labelText) {
        if (hint.getKind() == InlayHintKind.Type) return true;
        return labelText != null && labelText.trim().startsWith(":");
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static Position positionAtOffset(String text, int offset) {
        int safeOffset = Math.max(0, Math.min(offset, text.length()));
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < safeOffset; i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new Position(line, safeOffset - lineStart);
    }

    private record RenderedHint(Position position, Label label) {}
    private record LineGap(int line, int character, double width) {}
}
