package org.mtype.editor.ui.editor;

import javafx.application.Platform;
import org.eclipse.lsp4j.DocumentSymbol;
import org.fxmisc.richtext.model.Paragraph;
import org.fxmisc.richtext.model.RichTextChange;
import org.fxmisc.richtext.model.TwoDimensional;
import org.reactfx.util.Either;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Function/method/constructor body folding driven by document symbols. Folding is VISUAL ONLY.
 *
 * <p>A fold hides paragraphs through RichTextFX's built-in paragraph folding (the
 * {@link MTypeCodeArea#COLLAPSE} paragraph style class): the document text is never modified, so the
 * language server, the file on disk, undo, line numbers, breakpoints and diagnostics always see the real
 * source (MYT-444). A fold is a visible paragraph (its header) followed by a maximal run of collapsed
 * paragraphs; that state lives in the paragraph styles and moves with the text when lines are inserted
 * or deleted. VS Code style: the header and the closing-brace line stay visible, only the body is hidden.
 * Unfolding an outer fold also unfolds folds nested in it.
 *
 * <p>Maintenance rules, which keep the user's text from ever disappearing into a fold:
 * <ul>
 *   <li><b>M1 (touch):</b> an edit that touches a hidden line, or adds/removes a line break directly above
 *       one, opens that fold (see {@link FoldRegions#touchesFold}). Without it, Backspace at column 0 under
 *       a fold would merge the line into the hidden text, and Enter on a folded header would leave the
 *       fold detached from its header.</li>
 *   <li><b>M2 (caret):</b> the caret never rests in hidden text; the fold containing it is opened (e.g.
 *       Ctrl+End into a folded last line, where RichTextFX's own caret skip gives up).</li>
 *   <li><b>M3 (navigation):</b> {@link EditorTab#revealPosition} / {@link EditorTab#revealLine} open the
 *       target's fold first; RichTextFX would otherwise push a {@code moveTo} out of the fold.</li>
 * </ul>
 * M1/M2 unfolds run in one deferred {@code Platform.runLater}: changing paragraph styles while RichTextFX
 * is still dispatching the change would interleave with its caret and undo bookkeeping.
 */
final class CodeFoldingController {
    private final MTypeCodeArea area;
    private final EditorTab tab;
    private final Runnable gutterRefresh;

    /** Foldable regions from the last document symbols, keyed by header line. */
    private Map<Integer, FoldRegions.Region> regions = Collections.emptyMap();
    /** {@link EditorTab#getVersion()} the regions were computed for; folding is refused when stale. */
    private int regionsVersion = Integer.MIN_VALUE;

    /** Paragraph windows [from, to] whose folds M1 must open, applied by {@link #reconcile()}. */
    private final List<int[]> pendingWindows = new ArrayList<>();
    private boolean reconcileQueued;

    CodeFoldingController(MTypeCodeArea area, EditorTab tab, Runnable gutterRefresh) {
        this.area = area;
        this.tab = tab;
        this.gutterRefresh = gutterRefresh;
        area.richChanges().subscribe(this::onChange);
        area.currentParagraphProperty().addListener((_, _, _) -> queueReconcile());
    }

    void setDocumentSymbols(List<DocumentSymbol> symbols) {
        // Only the ▼ arrows come from symbols. Existing folds are NOT reconciled against them: symbols
        // computed while the file has a parse error can be partial and would open everything.
        regions = FoldRegions.compute(symbols, area.getParagraphs().size(), area::getText);
        regionsVersion = tab.getVersion();
        gutterRefresh.run();
    }

    /* ----- gutter queries ----- */

    /** Whether paragraph {@code p} is hidden by a fold (the gutter draws nothing for it). */
    boolean isHidden(int p) {
        return area.isParagraphFolded(p);
    }

    /** Whether {@code p} is the visible header of a fold (gutter ▶). */
    boolean isFoldedAt(int p) {
        return p >= 0 && !isHidden(p) && isHidden(p + 1);
    }

    /** Whether the gutter shows a fold arrow on {@code p}: ▶ for a fold, ▼ for a foldable region. */
    boolean isFoldable(int p) {
        return isFoldedAt(p) || regions.containsKey(p);
    }

    void toggleFoldAt(int p) {
        if (isFoldedAt(p)) {
            unfold(p);
            return;
        }
        FoldRegions.Region r = regions.get(p);
        // Regions from an older text would hide the wrong lines; the next symbol refresh brings fresh ones.
        if (r == null || regionsVersion != tab.getVersion()) return;
        if (r.lastHidden() >= area.getParagraphs().size() || isHidden(r.header())) return;
        int caret = area.getCaretPosition();
        int anchor = area.getAnchor();
        int bodyStart = area.getAbsolutePosition(r.header() + 1, 0);
        int bodyEnd = area.getAbsolutePosition(r.lastHidden(), area.getParagraphLength(r.lastHidden()));
        area.foldLines(r.header(), r.lastHidden());
        if (within(caret, bodyStart, bodyEnd) || within(anchor, bodyStart, bodyEnd)) {
            area.moveTo(r.header(), area.getParagraphLength(r.header()));
        } else {
            area.selectRange(anchor, caret);
        }
        // Hidden rows must drop their gutter graphics: re-query the factory for every row.
        gutterRefresh.run();
    }

    /** Open the fold that hides paragraph {@code p}; no-op when {@code p} is visible or out of range. */
    void unfoldContaining(int p) {
        if (!isHidden(p)) return;
        int header = p;
        while (header > 0 && isHidden(header)) header--;
        unfold(header);
    }

    /** Open every fold (bottom-up). */
    void unfoldAll() {
        for (int p = area.getParagraphs().size() - 1; p >= 0; p--) {
            if (isHidden(p)) unfoldContaining(p);
        }
    }

    private void unfold(int header) {
        int caret = area.getCaretPosition();
        int anchor = area.getAnchor();
        // A run can only start at paragraph 0 when its header line was deleted; un-hide that line too.
        if (isHidden(header)) area.updateParagraphClasses(header, null, MTypeCodeArea.COLLAPSE);
        area.unfoldRunAfter(header);
        area.selectRange(anchor, caret); // the text is unchanged, so the old offsets are still valid
        gutterRefresh.run();
    }

    private static boolean within(int offset, int start, int end) {
        return offset >= start && offset <= end;
    }

    /* ----- maintenance (M1 / M2) ----- */

    private void onChange(RichTextChange<Collection<String>, Either<String, InlayHintSeg>, Collection<String>> change) {
        if (change.isPlainTextIdentity()) return; // fold/unfold themselves and style-only changes
        int p0 = area.offsetToPosition(change.getPosition(), TwoDimensional.Bias.Forward).getMajor();
        int inserted = change.getInserted().getParagraphs().size();
        int removed = change.getRemoved().getParagraphs().size();
        int lineDelta = inserted - removed;
        if (lineDelta != 0) { // keep queued windows on the same lines
            for (int[] w : pendingWindows) {
                for (int i = 0; i < 2; i++) {
                    if (w[i] > p0) w[i] = Math.max(p0, w[i] + lineDelta);
                }
            }
        }
        if (tab.isInternalEditActive()) return; // inlay-hint mutations, external reload
        int p1 = p0 + inserted - 1;
        // Removed paragraphs (even empty slices) keep their original paragraph styles.
        boolean removedHidden = false;
        for (Paragraph<Collection<String>, Either<String, InlayHintSeg>, Collection<String>> par
                : change.getRemoved().getParagraphs()) {
            if (par.getParagraphStyle().contains(MTypeCodeArea.COLLAPSE)) {
                removedHidden = true;
                break;
            }
        }
        boolean structural = inserted > 1 || removed > 1;
        if (FoldRegions.touchesFold(removedHidden, structural, p0, p1, this::isHidden)) {
            pendingWindows.add(new int[]{p0 - 1, p1 + 1});
            queueReconcile();
        }
    }

    private void queueReconcile() {
        if (reconcileQueued) return;
        reconcileQueued = true;
        Platform.runLater(this::reconcile);
    }

    private void reconcile() {
        reconcileQueued = false;
        List<int[]> windows = new ArrayList<>(pendingWindows);
        pendingWindows.clear();
        for (int[] w : windows) {
            for (int p = Math.max(0, w[0]); p <= w[1]; p++) unfoldContaining(p); // M1
        }
        unfoldContaining(area.getCurrentParagraph()); // M2
    }
}
