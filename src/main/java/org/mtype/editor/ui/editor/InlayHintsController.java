package org.mtype.editor.ui.editor;

import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.InlayHintLabelPart;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.mtype.editor.lsp.Positions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Renders inlay hints as REAL length-1 segments embedded in the {@link MTypeCodeArea} document (each
 * a {@code ￼} placeholder rendered as a pill node). Because the hints are genuine layout participants,
 * RichTextFX computes correct selection/caret/wrap geometry around them — no overlay labels or
 * {@code translateX} tricks, and the previous {@code correctedHit} compensation is no longer needed.
 *
 * <p>The hint characters never reach the language server / disk: {@link MTypeCodeArea} maps display
 * offsets (with hints) to source offsets (without). All segment mutation goes through
 * {@link EditorTab#runHintMutation(Runnable)}, which suppresses LSP/dirty reactions, preserves the
 * caret's source position, and re-applies highlighting + diagnostics for the new offsets.
 */
final class InlayHintsController {
    private final MTypeCodeArea area;
    private final EditorTab tab;
    private List<InlayHint> current = new ArrayList<>();
    private String renderedSignature = "";

    InlayHintsController(MTypeCodeArea area, EditorTab tab) {
        this.area = area;
        this.tab = tab;
    }

    void setHints(List<InlayHint> hints) {
        current = hints == null ? new ArrayList<>() : new ArrayList<>(hints);
        String signature = signatureOf(current);
        if (signature.equals(renderedSignature) && area.hasHints() == !current.isEmpty()) return;
        renderedSignature = signature;
        tab.runHintMutation(this::rebuildSegments);
    }

    void clear() {
        current = new ArrayList<>();
        if (!"".equals(renderedSignature) || area.hasHints()) {
            renderedSignature = "";
            tab.runHintMutation(this::removeAllHintSegments);
        }
    }

    void dispose() {
        // The tab is closing; leave the document untouched.
    }

    /** Remove every existing hint segment, then (re)insert the current hints as length-1 segments. */
    private void rebuildSegments() {
        removeAllHintSegments();
        // The document now equals the source text (no ￼ chars), so source offset == display offset.
        String text = area.getText();
        List<Insertion> inserts = new ArrayList<>();
        for (InlayHint hint : current) {
            if (hint == null || hint.getPosition() == null) continue;
            String label = labelText(hint);
            if (label.isBlank()) continue;
            int offset;
            try {
                offset = Positions.offset(text, hint.getPosition().getLine(), hint.getPosition().getCharacter());
            } catch (Exception ex) {
                continue;
            }
            if (offset < 0 || offset > text.length()) continue;
            inserts.add(new Insertion(offset, new InlayHintSeg(paddedText(hint, label), hint.getKind())));
        }
        // Insert descending so earlier offsets stay valid as the document grows.
        inserts.sort(Comparator.comparingInt(Insertion::offset).reversed());
        for (Insertion ins : inserts) {
            area.insert(ins.offset(),
                    org.reactfx.util.Either.<String, InlayHintSeg>right(ins.seg()),
                    Collections.<String>emptyList());
        }
    }

    private void removeAllHintSegments() {
        String text = area.getText();
        for (int i = text.length() - 1; i >= 0; i--) {
            if (text.charAt(i) == MTypeCodeArea.HINT_CHAR) {
                area.deleteText(i, i + 1);
            }
        }
    }

    private static String signatureOf(List<InlayHint> hints) {
        StringBuilder sb = new StringBuilder();
        for (InlayHint hint : hints) {
            if (hint == null || hint.getPosition() == null) continue;
            sb.append(hint.getPosition().getLine()).append(':')
                    .append(hint.getPosition().getCharacter()).append(':')
                    .append(paddedText(hint, labelText(hint))).append('|');
        }
        return sb.toString();
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

    private record Insertion(int offset, InlayHintSeg seg) {}
}
