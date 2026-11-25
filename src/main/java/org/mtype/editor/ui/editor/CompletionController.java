package org.mtype.editor.ui.editor;

import javafx.application.Platform;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.TextEdit;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.lsp.LspBridge;
import org.mtype.editor.lsp.LspEdits;
import org.mtype.editor.lsp.Positions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Auto-completion popup and snippet placeholder navigation. Triggers on identifier / {@code .} input,
 * on Ctrl+Space, and refreshes on backspace while open. Applying an item widens the server's textEdit
 * to the word under the caret, applies any additional edits (imports) first, and starts a
 * {@link SnippetSession} when the item is a snippet.
 */
final class CompletionController {
    private final MTypeCodeArea codeArea;
    private final Path path;
    private final AppContext ctx;
    private final StyleCompositor compositor;
    private final SemanticTokensController semanticTokens;
    private final boolean lspManaged;
    /** Sends the tab's pending didChange, so the server completes on the text the user sees. */
    private final Runnable flushDidChange;
    private final ContextMenu completionMenu = new ContextMenu();
    private final LatestRequest<List<CompletionItem>> inFlight = new LatestRequest<>();

    private SnippetSession activeSnippet;
    private ScheduledFuture<?> pendingCompletion;
    private boolean disposed;

    CompletionController(MTypeCodeArea codeArea, Path path, AppContext ctx,
                         StyleCompositor compositor, SemanticTokensController semanticTokens, boolean lspManaged,
                         Runnable flushDidChange) {
        this.codeArea = codeArea;
        this.path = path;
        this.ctx = ctx;
        this.compositor = compositor;
        this.semanticTokens = semanticTokens;
        this.lspManaged = lspManaged;
        this.flushDidChange = flushDidChange;
        completionMenu.getStyleClass().add("mt-completion");
    }

    /* ----- key-handling surface (called from EditorTab.handleKey) ----- */

    boolean isMenuShowing() {
        return completionMenu.isShowing();
    }

    /** Hide the menu and drop the pending/in-flight request, so it can't pop the menu back up. */
    void hideMenu() {
        completionMenu.hide();
        cancelRequest();
    }

    /** Cancel the debounced request and the request in flight (the server request too). */
    void cancelRequest() {
        if (pendingCompletion != null) pendingCompletion.cancel(false);
        inFlight.cancel();
    }

    boolean hasActiveSnippet() {
        return activeSnippet != null;
    }

    /** Fire the focused (or first) completion item. Returns true if an item was fired. */
    boolean fireFocusedItem() {
        MenuItem item = focusedMenuItem();
        if (item == null) return false;
        item.fire();
        return true;
    }

    /* ----- triggering ----- */

    void maybeAutoCompletion(String oldText, String newText) {
        if (activeSnippet != null) {
            hideMenu();
            return;
        }
        int caret = codeArea.getCaretPosition();
        int delta = newText.length() - oldText.length();
        if (delta != 1) {
            if (completionMenu.isShowing() && delta == -1) {
                scheduleCompletion(); // user deleted a char while popup open — refresh
            } else if (completionMenu.isShowing()) {
                hideMenu();
            }
            return;
        }
        if (caret == 0) return;
        char c = newText.charAt(caret - 1);
        if (Character.isLetterOrDigit(c) || c == '_' || c == '.') {
            scheduleCompletion();
        } else {
            // Hide, and drop a response still in flight for the text before this character. A pending
            // timer is left alone: it then completes at the new caret, which is how `Foo::` or `"./`
            // typed quickly get completions today (real trigger characters are MYT-424).
            completionMenu.hide();
            inFlight.cancel();
        }
    }

    private void scheduleCompletion() {
        if (!lspManaged) return;
        if (pendingCompletion != null) pendingCompletion.cancel(false);
        pendingCompletion = EditorTab.BG_EXEC.schedule(
                () -> Platform.runLater(this::requestCompletionNow),
                180, TimeUnit.MILLISECONDS);
    }

    void requestCompletionNow() {
        if (!lspManaged || disposed) return;
        if (pendingCompletion != null) pendingCompletion.cancel(false);
        LspBridge lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) return;
        // Put the text the user sees on the wire first: the server answers requests in order, so this
        // completion is computed on exactly that text (MYT-422).
        flushDidChange.run();
        int caret = codeArea.getCaretPosition();
        int[] lc = codeArea.displayToSourceLineChar(caret);
        String trigger = null;
        String text = codeArea.getText();
        int scan = caret - 1;
        while (scan >= 0 && Words.isWordChar(text.charAt(scan))) scan--;
        if (scan >= 0) {
            char prior = text.charAt(scan);
            if (prior == '.' || prior == ':') trigger = String.valueOf(prior);
        }
        CompletableFuture<List<CompletionItem>> request = lsp.completion(path, lc[0], lc[1], trigger);
        long token = inFlight.begin(request);
        request.thenAcceptAsync(items -> {
            if (inFlight.isCurrent(token)) populateCompletionMenu(items);
        }, Platform::runLater);
    }

    private void populateCompletionMenu(List<CompletionItem> items) {
        if (items == null || items.isEmpty()) {
            completionMenu.hide();
            return;
        }
        String prefix = Words.currentWordPrefix(codeArea);
        List<CompletionItem> filtered = filter(items, prefix);
        if (filtered.isEmpty()) {
            completionMenu.hide();
            return;
        }
        completionMenu.getItems().clear();
        int shown = 0;
        for (CompletionItem ci : filtered) {
            MenuItem mi = buildCompletionItem(ci);
            completionMenu.getItems().add(mi);
            if (++shown >= 30) break;
        }
        if (!completionMenu.isShowing()) {
            Optional<javafx.geometry.Bounds> caretBounds = codeArea.getCaretBounds();
            caretBounds.ifPresent(b -> completionMenu.show(codeArea, b.getMinX(), b.getMaxY()));
        }
    }

    private MenuItem buildCompletionItem(CompletionItem ci) {
        String label = ci.getLabel() == null ? "" : ci.getLabel();
        String detail = ci.getDetail();
        Label kindBadge = new Label(kindShort(ci.getKind()));
        kindBadge.getStyleClass().add("mt-completion-kind");
        kindBadge.getStyleClass().add("mt-completion-kind-" + (ci.getKind() == null ? "Other" : ci.getKind().name()));

        MenuItem mi = new MenuItem(detail == null || detail.isBlank() ? label : (label + "   " + detail));
        mi.setGraphic(kindBadge);
        mi.setOnAction(_ -> applyCompletion(ci));
        return mi;
    }

    private void applyCompletion(CompletionItem ci) {
        if (ci == null) { completionMenu.hide(); return; }
        boolean hasExtras = (ci.getAdditionalTextEdits() != null && !ci.getAdditionalTextEdits().isEmpty())
                || ci.getCommand() != null;
        if (hasExtras) {
            applyResolvedCompletion(ci);
            return;
        }
        ctx.getLspBridge().resolveCompletion(ci)
                .thenAcceptAsync(resolved -> applyResolvedCompletion(resolved == null ? ci : resolved),
                        Platform::runLater);
    }

    private void applyResolvedCompletion(CompletionItem ci) {
        // The server's textEdit range can be narrower than the word under the caret: the user may
        // have typed more while the request (or the resolve) was in flight, and the server mixes
        // UTF-16 and UTF-8 columns on lines with non-ASCII text. So the replacement is widened to
        // the union of the server's range and the word currently under the caret.
        TextEdit te = ci.getTextEdit() != null && ci.getTextEdit().isLeft()
                ? ci.getTextEdit().getLeft() : null;
        String newText;
        int serverStart = -1, serverEnd = -1;
        String text = codeArea.getText();          // display text (used for the word scan below)
        String source = codeArea.getSourceText();  // for mapping LSP (source) ranges to display
        if (te != null) {
            newText = te.getNewText();
            serverStart = codeArea.sourceToDisplay(Positions.offset(source, te.getRange().getStart()));
            serverEnd = codeArea.sourceToDisplay(Positions.offset(source, te.getRange().getEnd()));
            if (serverStart > serverEnd) { int t = serverStart; serverStart = serverEnd; serverEnd = t; }
        } else {
            newText = ci.getInsertText() != null ? ci.getInsertText() : ci.getLabel();
        }
        if (newText == null) { completionMenu.hide(); return; }

        int caret = codeArea.getCaretPosition();
        int wordStart = caret;
        while (wordStart > 0 && Words.isWordChar(text.charAt(wordStart - 1))) wordStart--;
        int start = serverStart >= 0 ? Math.min(serverStart, wordStart) : wordStart;
        int end = serverEnd >= 0 ? Math.max(serverEnd, caret) : caret;

        // Apply additionalTextEdits (typically import statements above the caret) BEFORE
        // the main edit so snippet placeholder offsets land correctly. The shift is computed in
        // SOURCE offsets: applyToCodeArea trims edits to their changed spans, so inlay-hint chars
        // inside an edit's range may survive and display-offset arithmetic would drift.
        List<TextEdit> additional = ci.getAdditionalTextEdits();
        if (additional != null && !additional.isEmpty()) {
            int sourceStart = codeArea.displayToSource(start);
            int sourceEnd = codeArea.displayToSource(end);
            int sourceDelta = 0;
            for (TextEdit aedit : additional) {
                int aStart = Positions.offset(source, aedit.getRange().getStart());
                int aEnd = Positions.offset(source, aedit.getRange().getEnd());
                if (Math.max(aStart, aEnd) <= sourceStart) {
                    String anew = aedit.getNewText() == null ? "" : aedit.getNewText();
                    sourceDelta += anew.length() - Math.abs(aEnd - aStart);
                }
            }
            LspEdits.applyToCodeArea(codeArea, additional);
            start = codeArea.sourceToDisplay(sourceStart + sourceDelta);
            end = codeArea.sourceToDisplay(sourceEnd + sourceDelta);
        }

        activeSnippet = null;
        if (ci.getInsertTextFormat() == InsertTextFormat.Snippet) {
            SnippetSession snippet = SnippetSession.parse(newText, start);
            codeArea.replaceText(start, end, snippet.text());
            activeSnippet = snippet;
            applySnippetSelection(snippet.firstSelection());
        } else {
            codeArea.replaceText(start, end, newText);
        }

        if (ci.getCommand() != null) {
            ctx.getLspBridge().executeCommand(ci.getCommand().getCommand(), ci.getCommand().getArguments());
        }

        if (additional != null && !additional.isEmpty()) {
            compositor.invalidateOverlays();
            compositor.applyHighlightingNow();
            semanticTokens.schedule();
        }
        completionMenu.hide();
    }

    /* ----- snippets ----- */

    boolean advanceSnippet() {
        if (activeSnippet == null) return false;
        javafx.scene.control.IndexRange selection = codeArea.getSelection();
        SnippetSession.Selection next = activeSnippet.advance(
                codeArea.getCaretPosition(),
                selection.getStart(),
                selection.getEnd());
        if (next == null) {
            activeSnippet = null;
            return false;
        }
        applySnippetSelection(next);
        return true;
    }

    private void applySnippetSelection(SnippetSession.Selection selection) {
        int length = codeArea.getLength();
        int start = Math.max(0, Math.min(selection.start(), length));
        int end = Math.max(0, Math.min(selection.end(), length));
        codeArea.selectRange(start, end);
        if (selection.finalCaret()) {
            activeSnippet = null;
        }
    }

    private MenuItem focusedMenuItem() {
        for (MenuItem mi : completionMenu.getItems()) {
            if (mi.getStyleableNode() != null && mi.getStyleableNode().isFocused()) return mi;
        }
        return completionMenu.getItems().isEmpty() ? null : completionMenu.getItems().getFirst();
    }

    void dispose() {
        disposed = true;
        cancelRequest();
        completionMenu.hide();
    }

    private static List<CompletionItem> filter(List<CompletionItem> items, String prefix) {
        if (prefix == null || prefix.isEmpty()) return items;
        String low = prefix.toLowerCase();
        List<CompletionItem> out = new ArrayList<>();
        for (CompletionItem ci : items) {
            String key = ci.getFilterText() != null ? ci.getFilterText()
                    : (ci.getLabel() != null ? ci.getLabel() : "");
            if (key.toLowerCase().contains(low)) out.add(ci);
        }
        return out.isEmpty() ? items : out;
    }

    private static String kindShort(CompletionItemKind k) {
        if (k == null) return "·";
        return switch (k) {
            case Class -> "C";
            case Interface -> "I";
            case Struct -> "S";
            case Enum -> "E";
            case EnumMember -> "e";
            case Method, Function, Constructor -> "ƒ";
            case Property -> "p";
            case Field, Variable -> "v";
            case Constant -> "c";
            case Keyword -> "k";
            case Module -> "m";
            case Snippet -> "»";
            case File -> "□";
            case Folder -> "▥";
            case TypeParameter -> "T";
            case Text -> "a";
            default -> "·";
        };
    }
}
