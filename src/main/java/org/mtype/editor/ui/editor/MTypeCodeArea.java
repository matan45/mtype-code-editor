package org.mtype.editor.ui.editor;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.text.TextFlow;
import org.eclipse.lsp4j.InlayHintKind;
import org.fxmisc.richtext.GenericStyledArea;
import org.fxmisc.richtext.StyledTextArea;
import org.fxmisc.richtext.model.NodeSegmentOpsBase;
import org.fxmisc.richtext.model.ReadOnlyStyledDocument;
import org.fxmisc.richtext.model.SegmentOps;
import org.fxmisc.richtext.model.StyledDocument;
import org.fxmisc.richtext.model.StyledSegment;
import org.fxmisc.richtext.model.TextOps;
import org.reactfx.util.Either;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;

/**
 * A {@link CodeArea}-equivalent built on {@link GenericStyledArea} whose segments are
 * {@code Either<String, InlayHintSeg>}. Text runs render exactly like {@code CodeArea} (a
 * {@code TextExt} carrying the {@code text} style class plus the per-token style classes); inlay-hint
 * segments render as a pill {@link Label} and occupy exactly one character ({@code ￼}) in the
 * document model so RichTextFX gives them correct selection/caret/wrap geometry.
 *
 * <p>Because each embedded hint adds a character that the real source file / language server must NOT
 * see, this class also owns the display↔source offset mapping. "Display" = offsets/text in this area
 * (with {@code ￼} chars); "source" = the real document (hints stripped). All LSP, save, diagnostic,
 * and tokenizer code must convert through these helpers.
 */
public final class MTypeCodeArea
        extends GenericStyledArea<Collection<String>, Either<String, InlayHintSeg>, Collection<String>> {

    /** Unicode OBJECT REPLACEMENT CHARACTER — what an inlay-hint segment contributes to the text. */
    static final char HINT_CHAR = '￼';

    private static final TextOps<Either<String, InlayHintSeg>, Collection<String>> SEGMENT_OPS = buildSegmentOps();

    MTypeCodeArea() {
        super(Collections.<String>emptyList(),
                (TextFlow tf, Collection<String> styleClasses) -> tf.getStyleClass().addAll(styleClasses),
                Collections.<String>emptyList(),
                SEGMENT_OPS,
                MTypeCodeArea::createNode);
        getStyleClass().add("code-area");
        setUseInitialStyleForInsertion(true);
    }

    static TextOps<Either<String, InlayHintSeg>, Collection<String>> segmentOps() {
        return SEGMENT_OPS;
    }

    private static TextOps<Either<String, InlayHintSeg>, Collection<String>> buildSegmentOps() {
        TextOps<String, Collection<String>> textOps = SegmentOps.styledTextOps();
        SegmentOps<InlayHintSeg, Collection<String>> hintOps = new InlayHintSegmentOps();
        return textOps._or(hintOps, (a, b) -> Optional.empty());
    }

    private static Node createNode(StyledSegment<Either<String, InlayHintSeg>, Collection<String>> styledSeg) {
        return styledSeg.getSegment().unify(
                str -> StyledTextArea.createStyledTextNode(str, styledSeg.getStyle(),
                        (textExt, classes) -> textExt.getStyleClass().addAll(classes)),
                MTypeCodeArea::buildHintNode);
    }

    private static Node buildHintNode(InlayHintSeg hint) {
        Label label = new Label(hint.text());
        label.getStyleClass().add("mt-inlay-hint");
        if (hint.kind() == InlayHintKind.Parameter) {
            label.getStyleClass().add("mt-inlay-hint-param");
        } else if (hint.kind() == InlayHintKind.Type) {
            label.getStyleClass().add("mt-inlay-hint-type");
        }
        return label;
    }

    /** Builds a one-segment styled document wrapping a hint, for {@code replace}/{@code insert}. */
    static StyledDocument<Collection<String>, Either<String, InlayHintSeg>, Collection<String>> hintDocument(
            InlayHintSeg hint) {
        return ReadOnlyStyledDocument.fromSegment(
                Either.right(hint),
                Collections.<String>emptyList(),
                Collections.singletonList("mt-inlay-hint"),
                SEGMENT_OPS);
    }

    // ---- display↔source offset mapping -------------------------------------------------------

    public boolean hasHints() {
        return getText().indexOf(HINT_CHAR) >= 0;
    }

    /** The real document text the language server / disk see — display text with all {@code ￼} removed. */
    public String getSourceText() {
        String t = getText();
        if (t.indexOf(HINT_CHAR) < 0) return t;
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c != HINT_CHAR) sb.append(c);
        }
        return sb.toString();
    }

    /** Convert an offset in this area (display) to the corresponding offset in {@link #getSourceText()}. */
    public int displayToSource(int displayOffset) {
        String t = getText();
        int lim = Math.max(0, Math.min(displayOffset, t.length()));
        int hints = 0;
        for (int i = 0; i < lim; i++) {
            if (t.charAt(i) == HINT_CHAR) hints++;
        }
        return displayOffset - hints;
    }

    /** Convert a source offset to the offset in this area (display), landing after any hint at that gap. */
    public int sourceToDisplay(int sourceOffset) {
        String t = getText();
        int d = Math.max(0, sourceOffset);
        for (int i = 0; i < t.length() && i <= d; i++) {
            if (t.charAt(i) == HINT_CHAR) d++;
        }
        return Math.min(d, t.length());
    }

    /** (line, character) in the SOURCE document for a display offset (lines are identical; only column differs). */
    public int[] displayToSourceLineChar(int displayOffset) {
        int src = displayToSource(displayOffset);
        String s = getSourceText();
        int lim = Math.max(0, Math.min(src, s.length()));
        int line = 0, lineStart = 0;
        for (int i = 0; i < lim; i++) {
            if (s.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new int[]{line, src - lineStart};
    }

    /** Display offset for a SOURCE (line, character) position. */
    public int sourceLineCharToDisplay(int line, int character) {
        String s = getSourceText();
        int i = 0, ln = 0;
        while (ln < line && i < s.length()) {
            if (s.charAt(i) == '\n') ln++;
            i++;
        }
        int lineEnd = s.indexOf('\n', i);
        if (lineEnd < 0) lineEnd = s.length();
        int srcOffset = Math.min(i + Math.max(0, character), lineEnd);
        return sourceToDisplay(srcOffset);
    }

    /** SegmentOps for the embedded inlay-hint segments: one character, never merges, renders as {@code ￼}. */
    private static final class InlayHintSegmentOps extends NodeSegmentOpsBase<InlayHintSeg, Collection<String>> {
        InlayHintSegmentOps() {
            super(InlayHintSeg.EMPTY);
        }

        @Override
        public int length(InlayHintSeg seg) {
            return 1;
        }
    }
}
