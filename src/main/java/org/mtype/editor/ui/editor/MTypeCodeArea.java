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
import org.fxmisc.richtext.model.StyledSegment;
import org.fxmisc.richtext.model.TextOps;
import org.fxmisc.richtext.model.TwoDimensional;
import org.fxmisc.richtext.util.UndoUtils;
import org.reactfx.util.Either;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
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

    /**
     * Paragraph style class of a folded (hidden) line. RichTextFX's bundled stylesheet hides it
     * ({@code .styled-text-area .collapse { visibility: collapse; }}) and its paragraph box then has zero
     * height; the text itself is untouched.
     */
    static final String COLLAPSE = "collapse";

    private static final TextOps<Either<String, InlayHintSeg>, Collection<String>> SEGMENT_OPS = buildSegmentOps();

    MTypeCodeArea() {
        super(Collections.emptyList(),
                (TextFlow tf, Collection<String> styleClasses) -> tf.getStyleClass().addAll(styleClasses),
                Collections.emptyList(),
                SEGMENT_OPS,
                MTypeCodeArea::createNode);
        getStyleClass().add("code-area");
        setUseInitialStyleForInsertion(true);
        // Plain-text undo, like CodeArea. The default for this constructor (preserveStyle=true) is the
        // rich-text undo manager, which records every style-only change — token highlighting, code-lens
        // padding, folds — as an undo step, so Ctrl+Z would replay highlighting and unfold code.
        setUndoManager(UndoUtils.plainTextUndoManager(this));
    }

    private static TextOps<Either<String, InlayHintSeg>, Collection<String>> buildSegmentOps() {
        TextOps<String, Collection<String>> textOps = SegmentOps.styledTextOps();
        SegmentOps<InlayHintSeg, Collection<String>> hintOps = new InlayHintSegmentOps();
        return textOps._or(hintOps, (_, _) -> Optional.empty());
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

    // ---- folding & paragraph style classes -------------------------------------------------------

    /** Whether paragraph {@code p} is hidden by a fold (false when out of range). */
    boolean isParagraphFolded(int p) {
        return p >= 0 && p < getParagraphs().size() && getParagraph(p).getParagraphStyle().contains(COLLAPSE);
    }

    /**
     * Hide paragraphs {@code header+1 .. lastHidden}; {@code header} stays visible. Only paragraph styles
     * change (the replace RichTextFX does internally is text-identical), but the caret is moved to the
     * header — callers restore it.
     */
    void foldLines(int header, int lastHidden) {
        foldParagraphs(header, lastHidden, ps -> withClass(ps, COLLAPSE));
    }

    /** Un-hide the run of folded paragraphs directly below {@code header}. Moves the caret — callers restore it. */
    void unfoldRunAfter(int header) {
        unfoldParagraphs(header, ps -> ps.contains(COLLAPSE), ps -> withoutClass(ps, COLLAPSE));
    }

    /**
     * Add and/or remove one style class on paragraph {@code p}, keeping its other classes. Paragraph style
     * classes are shared state (folds, code-lens padding, the execution line): never replace the whole
     * collection. Out-of-range paragraphs are ignored.
     */
    void updateParagraphClasses(int p, String add, String remove) {
        if (p < 0 || p >= getParagraphs().size()) return;
        Collection<String> current = getParagraph(p).getParagraphStyle();
        Collection<String> next = current;
        if (remove != null) next = withoutClass(next, remove);
        if (add != null) next = withClass(next, add);
        if (next != current) setParagraphStyle(p, next);
    }

    /**
     * Insert an inlay-hint segment at display {@code offset}. The segment carries the paragraph's own
     * style: inserted text normally gets the INITIAL (empty) paragraph style, which would un-hide a folded
     * line when the hint lands in column 0.
     */
    void insertHint(int offset, InlayHintSeg seg) {
        int paragraph = offsetToPosition(offset, TwoDimensional.Bias.Forward).getMajor();
        replace(offset, offset, ReadOnlyStyledDocument.fromSegment(
                Either.<String, InlayHintSeg>right(seg),
                getParagraph(paragraph).getParagraphStyle(),
                Collections.<String>emptyList(),
                SEGMENT_OPS));
    }

    /** {@code ps} plus {@code c}, copied (paragraph styles can be immutable, e.g. the initial empty list). */
    private static Collection<String> withClass(Collection<String> ps, String c) {
        if (ps.contains(c)) return ps;
        List<String> copy = new ArrayList<>(ps);
        copy.add(c);
        return copy;
    }

    private static Collection<String> withoutClass(Collection<String> ps, String c) {
        if (!ps.contains(c)) return ps;
        List<String> copy = new ArrayList<>(ps);
        copy.removeIf(c::equals);
        return copy;
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
