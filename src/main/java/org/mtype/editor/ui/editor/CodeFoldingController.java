package org.mtype.editor.ui.editor;

import javafx.application.Platform;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;
import org.fxmisc.richtext.model.StyledDocument;
import org.mtype.editor.lsp.Positions;
import org.reactfx.util.Either;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Function/method/constructor body folding driven by document symbols.
 *
 * <p>Folding is tricky because both folds <i>and</i> inlay-hint segments shift display offsets, so a
 * region tracks both its ORIGINAL (source) line numbers and its CURRENT (display) line numbers. Folded
 * bodies are stashed as a rich {@link StyledDocument} (not plain text) so embedded inlay-hint segments
 * survive the fold/unfold round-trip. All document mutations go through
 * {@link EditorTab#runInternalEdit(Runnable)} to suppress LSP/dirty reactions, after which highlighting
 * and overlays are rebuilt for the new offsets.
 */
final class CodeFoldingController {
    private static final String FOLD_PLACEHOLDER = " … }";

    private final MTypeCodeArea codeArea;
    private final EditorTab tab;
    private final StyleCompositor compositor;
    private final SemanticTokensController semanticTokens;
    private final Runnable gutterRefresh;

    private List<DocumentSymbol> documentSymbols = Collections.emptyList();
    private final Map<Integer, FoldRegion> foldableByCurrentLine = new java.util.HashMap<>();
    private final Map<Integer, FoldedRegion> foldedByOriginalLine = new LinkedHashMap<>();

    private record FoldRegion(int currentSigLine, int currentOpenBraceLine, int openBracePos, int currentEndLine,
                              int currentEndLineLen, int originalSigLine, int originalOpenBraceLine, int originalEndLine) {}
    private record FoldedRegion(int originalSigLine, int originalOpenBraceLine, int originalEndLine,
                                StyledDocument<Collection<String>, Either<String, InlayHintSeg>, Collection<String>> stashedDoc) {}

    CodeFoldingController(MTypeCodeArea codeArea, EditorTab tab, StyleCompositor compositor,
                          SemanticTokensController semanticTokens, Runnable gutterRefresh) {
        this.codeArea = codeArea;
        this.tab = tab;
        this.compositor = compositor;
        this.semanticTokens = semanticTokens;
        this.gutterRefresh = gutterRefresh;
    }

    void setDocumentSymbols(List<DocumentSymbol> symbols) {
        this.documentSymbols = symbols == null ? Collections.emptyList() : symbols;
        rebuildFoldableRegions();
    }

    /* ----- gutter queries ----- */

    boolean isFoldable(int currentLine) {
        return foldableByCurrentLine.containsKey(currentLine);
    }

    boolean isFoldedAt(int currentLine) {
        FoldRegion fr = foldableByCurrentLine.get(currentLine);
        return fr != null && foldedByOriginalLine.containsKey(fr.originalSigLine());
    }

    void toggleFoldAt(int currentLine) {
        FoldRegion fr = foldableByCurrentLine.get(currentLine);
        if (fr != null) toggleFold(fr.originalSigLine());
    }

    /* ----- region computation ----- */

    private void rebuildFoldableRegions() {
        foldableByCurrentLine.clear();
        if (documentSymbols == null || documentSymbols.isEmpty()) {
            gutterRefresh.run();
            return;
        }
        List<int[]> raw = new ArrayList<>();
        collectFoldable(documentSymbols, raw);
        if (raw.isEmpty()) {
            gutterRefresh.run();
            return;
        }
        // For each currently-folded region, how many lines it absorbed from the original.
        // Walk in ascending origSigLine order — that's how LinkedHashMap iterates if we
        // inserted in order, but we re-sort to be safe.
        List<FoldedRegion> activeFolds = new ArrayList<>(foldedByOriginalLine.values());
        activeFolds.sort(Comparator.comparingInt(FoldedRegion::originalSigLine));

        int linesInBuffer = codeArea.getParagraphs().size();
        for (int[] r : raw) {
            int origSig = r[0], origEnd = r[1];
            int absorbedBefore = 0;
            boolean nestedInsideOuterFold = false;
            for (FoldedRegion f : activeFolds) {
                if (f.originalSigLine() == origSig) continue;
                if (f.originalSigLine() < origSig && f.originalEndLine() >= origSig) {
                    nestedInsideOuterFold = true; break;
                }
                if (f.originalEndLine() < origSig) {
                    absorbedBefore += f.originalEndLine() - f.originalOpenBraceLine();
                }
            }
            if (nestedInsideOuterFold) continue;
            int currentSig = origSig - absorbedBefore;
            FoldedRegion folded = foldedByOriginalLine.get(origSig);
            int currentEnd = folded != null
                    ? currentSig          // this region is itself folded — collapses onto signature line
                    : origEnd - absorbedBefore;
            int currentVisibleEnd = folded != null
                    ? folded.originalOpenBraceLine() - absorbedBefore
                    : currentEnd;
            if (currentSig < 0 || currentSig >= linesInBuffer) continue;
            if (currentVisibleEnd >= linesInBuffer) continue;
            int braceLine = -1;
            int brace = -1;
            for (int line = currentSig; line <= currentVisibleEnd; line++) {
                brace = codeArea.getText(line).indexOf('{');
                if (brace >= 0) {
                    braceLine = line;
                    break;
                }
            }
            if (braceLine < 0) continue;
            int originalBraceLine = folded != null ? folded.originalOpenBraceLine() : braceLine + absorbedBefore;
            int endLineLen = codeArea.getText(currentVisibleEnd).length();
            foldableByCurrentLine.put(currentSig,
                    new FoldRegion(currentSig, braceLine, brace, currentVisibleEnd, endLineLen, origSig, originalBraceLine, origEnd));
        }
        gutterRefresh.run();
    }

    private static void collectFoldable(List<DocumentSymbol> symbols, List<int[]> out) {
        if (symbols == null) return;
        for (DocumentSymbol s : symbols) {
            if (s == null) continue;
            Range r = s.getRange();
            if (r != null && r.getStart() != null && r.getEnd() != null) {
                SymbolKind k = s.getKind();
                if (k == SymbolKind.Function || k == SymbolKind.Method || k == SymbolKind.Constructor) {
                    int startLine = r.getStart().getLine();
                    int endLine = r.getEnd().getLine();
                    if (endLine > startLine) out.add(new int[]{startLine, endLine});
                }
            }
            collectFoldable(s.getChildren(), out);
        }
    }

    /* ----- fold / unfold ----- */

    private void toggleFold(int originalSigLine) {
        if (foldedByOriginalLine.containsKey(originalSigLine)) unfoldByOriginal(originalSigLine);
        else foldByOriginal(originalSigLine);
    }

    private void foldByOriginal(int originalSigLine) {
        FoldRegion fr = findFoldableByOriginal(originalSigLine);
        if (fr == null) return;
        int start = Positions.offset(codeArea.getText(), fr.currentOpenBraceLine(), fr.openBracePos() + 1);
        int end = Positions.offset(codeArea.getText(), fr.currentEndLine(), fr.currentEndLineLen());
        if (end <= start) return;
        // Stash the rich sub-document (not plain text) so embedded inlay-hint segments survive the
        // fold/unfold round-trip instead of becoming literal ￼ characters.
        var stash = codeArea.subDocument(start, end);
        // Folding an outer region wipes any inner folds (they're inside the collapsed text).
        foldedByOriginalLine.keySet().removeIf(o -> o > fr.originalSigLine() && o <= fr.originalEndLine());
        foldedByOriginalLine.put(originalSigLine, new FoldedRegion(originalSigLine, fr.originalOpenBraceLine(), fr.originalEndLine(), stash));
        double savedScrollX = codeArea.estimatedScrollXProperty().getValue();
        double savedScrollY = codeArea.estimatedScrollYProperty().getValue();
        int savedCaret = codeArea.getCaretPosition();
        int caretClamped = savedCaret <= start ? savedCaret : (savedCaret >= end ? savedCaret - (end - start) + FOLD_PLACEHOLDER.length() : start);
        tab.runInternalEdit(() -> codeArea.replaceText(start, end, FOLD_PLACEHOLDER));
        compositor.invalidateOverlays();
        compositor.applyHighlightingNow();
        semanticTokens.schedule();
        rebuildFoldableRegions();
        codeArea.moveTo(Math.min(caretClamped, codeArea.getLength()));
        Platform.runLater(() -> {
            codeArea.scrollXToPixel(savedScrollX);
            codeArea.scrollYToPixel(savedScrollY);
        });
    }

    private void unfoldByOriginal(int originalSigLine) {
        FoldedRegion folded = foldedByOriginalLine.remove(originalSigLine);
        if (folded == null) { rebuildFoldableRegions(); return; }
        FoldRegion fr = findFoldableByOriginal(originalSigLine);
        if (fr == null) { rebuildFoldableRegions(); return; }
        int start = Positions.offset(codeArea.getText(), fr.currentOpenBraceLine(), fr.openBracePos() + 1);
        int placeholderEnd = start + FOLD_PLACEHOLDER.length();
        if (placeholderEnd > codeArea.getLength()) { rebuildFoldableRegions(); return; }
        double savedScrollX = codeArea.estimatedScrollXProperty().getValue();
        double savedScrollY = codeArea.estimatedScrollYProperty().getValue();
        int savedCaret = codeArea.getCaretPosition();
        int caretClamped = savedCaret <= start ? savedCaret
                : (savedCaret >= placeholderEnd ? savedCaret + folded.stashedDoc().length() - FOLD_PLACEHOLDER.length() : start);
        tab.runInternalEdit(() -> codeArea.replace(start, placeholderEnd, folded.stashedDoc()));
        compositor.invalidateOverlays();
        compositor.applyHighlightingNow();
        semanticTokens.schedule();
        rebuildFoldableRegions();
        codeArea.moveTo(Math.min(caretClamped, codeArea.getLength()));
        Platform.runLater(() -> {
            codeArea.scrollXToPixel(savedScrollX);
            codeArea.scrollYToPixel(savedScrollY);
        });
    }

    private FoldRegion findFoldableByOriginal(int originalSigLine) {
        for (FoldRegion fr : foldableByCurrentLine.values()) {
            if (fr.originalSigLine() == originalSigLine) return fr;
        }
        return null;
    }

    void unfoldAll() {
        if (foldedByOriginalLine.isEmpty()) return;
        List<Integer> keys = new ArrayList<>(foldedByOriginalLine.keySet());
        keys.sort(Comparator.reverseOrder());
        for (Integer k : keys) unfoldByOriginal(k);
    }
}
