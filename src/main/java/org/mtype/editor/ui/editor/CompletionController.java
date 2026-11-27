package org.mtype.editor.ui.editor;

import javafx.application.Platform;
import org.eclipse.lsp4j.CompletionItem;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.lsp.LspBridge;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Auto-completion and snippet placeholder navigation, "honest" (MYT-423): Enter only ever accepts an
 * item that matches what was typed, on the word it was requested for.
 *
 * <p>A <b>session</b> is the completion of one word, identified by its {@link CompletionAnchor}. The
 * debounce timer, the request in flight and the list shown all belong to it, and {@link #cancel()} drops
 * all three at once: Esc, a mouse press, focus loss, the popup closing itself, a non-word character, the
 * caret leaving the word, accepting. A reply is applied only while it is the current request (the
 * {@link LatestRequest} serial) and the caret is still in its anchor word. The list is ranked locally by
 * {@link CompletionFilter} on every keystroke and hidden when nothing matches. Accepting applies the
 * item at once — no resolve round-trip — with {@link CompletionEditPlanner}: the import edits and the
 * main edit go into one multi-change, i.e. one undo step.
 */
final class CompletionController {
    private static final long DEBOUNCE_MS = 180;
    /**
     * Typed while a completion was about to fire, these re-arm it at the new caret (as the leftover timer
     * used to), so {@code Foo::} and {@code "./} typed quickly still complete. Temporary: MYT-424 replaces
     * it with the server's trigger characters and lexical context.
     */
    private static final String BRIDGE_TRIGGERS = ":\"/@";

    private final MTypeCodeArea codeArea;
    private final Path path;
    private final AppContext ctx;
    private final StyleCompositor compositor;
    private final SemanticTokensController semanticTokens;
    private final boolean lspManaged;
    /** Sends the tab's pending didChange, so the server completes on the text the user sees. */
    private final Runnable flushDidChange;
    private final CompletionView view;
    private final LatestRequest<List<CompletionItem>> inFlight = new LatestRequest<>();

    /** The word being completed; null when idle (then no timer, no request, popup hidden). */
    private Session session;
    private ScheduledFuture<?> timer;
    /** Bumped by every arm/cancel, so a timer tick already queued on the FX thread can't fire late. */
    private long timerSerial;
    private boolean timerArmed;
    /** Our own accept edit is being applied: the text listener must not treat it as typing. */
    private boolean applying;
    private boolean reconcileQueued;
    private boolean disposed;
    private SnippetSession activeSnippet;

    private static final class Session {
        final CompletionAnchor anchor;
        List<CompletionItem> items = List.of();
        /** The caret column the server answered for (-1 until a reply arrives). */
        int requestCol = -1;

        Session(CompletionAnchor anchor) {
            this.anchor = anchor;
        }
    }

    /** The caret in SOURCE coordinates (paragraph index == source line). */
    private record Caret(int line, int col, String lineText) {}

    CompletionController(MTypeCodeArea codeArea, Path path, AppContext ctx,
                         StyleCompositor compositor, SemanticTokensController semanticTokens, boolean lspManaged,
                         Runnable flushDidChange, CompletionView view) {
        this.codeArea = codeArea;
        this.path = path;
        this.ctx = ctx;
        this.compositor = compositor;
        this.semanticTokens = semanticTokens;
        this.lspManaged = lspManaged;
        this.flushDidChange = flushDidChange;
        this.view = view;
        view.setListener(new CompletionView.Listener() {
            @Override
            public void onAccept(CompletionItem item) {
                accept(item);
            }

            @Override
            public void onDismissed() {
                cancel();
            }
        });
        codeArea.caretPositionProperty().addListener((_, _, _) -> queueReconcile());
        codeArea.focusedProperty().addListener((_, _, focused) -> {
            // Deferred: focus can move into the popup's own window, which is not a dismissal.
            if (!focused) Platform.runLater(this::cancelIfUnfocused);
        });
    }

    /* ----- key-handling surface (called from EditorTab.handleKey) ----- */

    boolean isMenuShowing() {
        return view.isShowing();
    }

    boolean hasActiveSnippet() {
        return activeSnippet != null;
    }

    /** Drop the session: the debounce timer, the request in flight (the server request too) and the popup. */
    void cancel() {
        timerSerial++;
        timerArmed = false;
        if (timer != null) {
            timer.cancel(false);
            timer = null;
        }
        inFlight.cancel();
        session = null;
        view.hide();
    }

    /**
     * Enter / Tab with the popup open: accept the highlighted (or first) item if it still matches the
     * word under the caret. Returns false — so the key does its normal thing — when nothing is accepted.
     */
    boolean acceptSelected() {
        if (session == null) return false;
        reconcileNow();
        if (!view.isShowing()) return false;
        return accept(view.selected());
    }

    /** Ctrl+Space: complete the word at the caret now. */
    void invoke() {
        if (!lspManaged || disposed) return;
        ensureSession(caret());
        disarm();
        requestNow();
    }

    /* ----- triggering ----- */

    /** Called by EditorTab's text listener for every edit that isn't internal. */
    void maybeAutoCompletion(String oldText, String newText) {
        if (applying || disposed || !lspManaged) return;
        if (activeSnippet != null) {
            cancel(); // no auto-completion during a snippet session (until MYT-424)
            return;
        }
        int delta = newText.length() - oldText.length();
        int caretPos = codeArea.getCaretPosition();
        if (delta == 1 && caretPos > 0 && caretPos <= newText.length()) {
            char typed = newText.charAt(caretPos - 1);
            if (Words.isWordChar(typed)) {
                Caret now = caret();
                ensureSession(now);
                refilter(now); // now, not on the next pulse: Enter right after this sees the new list
                arm();
            } else if (typed == '.') {
                cancel();
                startSession(caret());
                arm();
            } else {
                boolean wasArmed = timerArmed;
                cancel();
                if (wasArmed && BRIDGE_TRIGGERS.indexOf(typed) >= 0) {
                    startSession(caret());
                    arm();
                }
            }
            return;
        }
        if (delta == -1 && session != null) {
            Caret now = caret();
            if (session.anchor.contains(now.lineText(), now.line(), now.col())) {
                refilter(now);
                arm(); // refresh from the server for the shorter prefix
            } else {
                cancel();
            }
            return;
        }
        cancel(); // paste, undo, a multi-character edit, ...
    }

    private void ensureSession(Caret now) {
        CompletionAnchor anchor = CompletionAnchor.at(now.lineText(), now.line(), now.col());
        if (session != null && session.anchor.equals(anchor)) return;
        cancel();
        session = new Session(anchor);
    }

    private void startSession(Caret now) {
        session = new Session(CompletionAnchor.at(now.lineText(), now.line(), now.col()));
    }

    private void arm() {
        if (session == null) return;
        if (timer != null) timer.cancel(false);
        long serial = ++timerSerial;
        timerArmed = true;
        timer = EditorTab.BG_EXEC.schedule(
                () -> Platform.runLater(() -> onTimer(serial)),
                DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private void disarm() {
        timerSerial++;
        timerArmed = false;
        if (timer != null) {
            timer.cancel(false);
            timer = null;
        }
    }

    private void onTimer(long serial) {
        if (serial != timerSerial || disposed) return;
        timerArmed = false;
        timer = null;
        requestNow();
    }

    private void requestNow() {
        if (!lspManaged || disposed || session == null) return;
        LspBridge lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) return;
        // Put the text the user sees on the wire first: the server answers requests in order, so this
        // completion is computed on exactly that text (MYT-422).
        flushDidChange.run();
        Caret now = caret();
        CompletionAnchor anchor = session.anchor;
        if (!anchor.contains(now.lineText(), now.line(), now.col())) {
            cancel();
            return;
        }
        int requestCol = now.col();
        CompletableFuture<List<CompletionItem>> request =
                lsp.completion(path, now.line(), requestCol, anchor.triggerCharacter(now.lineText()));
        long token = inFlight.begin(request);
        request.thenAcceptAsync(items -> onReply(token, anchor, requestCol, items), Platform::runLater);
    }

    private void onReply(long token, CompletionAnchor anchor, int requestCol, List<CompletionItem> items) {
        if (disposed || !inFlight.isCurrent(token)) return; // superseded or cancelled: never reopens
        Caret now = caret();
        if (session == null || !session.anchor.equals(anchor)
                || !anchor.contains(now.lineText(), now.line(), now.col())) {
            cancel(); // the caret moved off the word the reply is for
            return;
        }
        session.items = items == null ? List.of() : items;
        session.requestCol = requestCol;
        refilter(now);
    }

    /* ----- keeping the popup honest between keystrokes ----- */

    /**
     * The caret moved. Checked once the edit has fully settled (one coalesced runLater), because RichTextFX
     * moves the caret before or after the text listener depending on the edit's shape. Everything that
     * opens or accepts re-checks the anchor itself, so a deferred check can only close too late, never
     * open or accept at the wrong place.
     */
    private void queueReconcile() {
        if (session == null || reconcileQueued || disposed) return;
        reconcileQueued = true;
        Platform.runLater(() -> {
            reconcileQueued = false;
            reconcileNow();
        });
    }

    private void reconcileNow() {
        if (session == null || disposed) return;
        Caret now = caret();
        if (codeArea.getSelection().getLength() > 0
                || !session.anchor.contains(now.lineText(), now.line(), now.col())) {
            cancel();
            return;
        }
        refilter(now);
    }

    /** Re-rank the session's items for what is typed now; no match hides the popup but keeps the session. */
    private void refilter(Caret now) {
        if (session == null) return;
        List<CompletionItem> ranked = CompletionFilter.rank(
                session.items, session.anchor.prefix(now.lineText(), now.col()));
        if (ranked.isEmpty()) {
            view.hide();
        } else {
            view.show(ranked);
        }
    }

    private void cancelIfUnfocused() {
        if (disposed) return;
        if (!codeArea.isFocused() && !view.ownsFocus()) cancel();
    }

    private Caret caret() {
        int paragraph = codeArea.getCurrentParagraph();
        int col = codeArea.displayToSourceColumn(paragraph, codeArea.getCaretColumn());
        return new Caret(paragraph, col, codeArea.sourceParagraphText(paragraph));
    }

    /* ----- accepting ----- */

    private boolean accept(CompletionItem item) {
        Session current = session;
        if (item == null || current == null || disposed) {
            cancel();
            return false;
        }
        Caret now = caret();
        String prefix = current.anchor.prefix(now.lineText(), now.col());
        if (!current.anchor.contains(now.lineText(), now.line(), now.col())
                || CompletionFilter.match(CompletionFilter.key(item), prefix) == null) {
            cancel();
            return false;
        }
        CompletionAnchor anchor = current.anchor;
        int requestCol = current.requestCol;
        cancel(); // first: no timer or late reply can reopen the popup after the edit

        int caretDisplay = codeArea.getCaretPosition();
        CompletionEditPlanner.Plan plan = CompletionEditPlanner.plan(
                codeArea.getSourceText(), codeArea.displayToSource(caretDisplay), anchor, requestCol, item);
        if (plan == null) return false;
        applyPlan(plan, caretDisplay);

        if (item.getCommand() != null) {
            LspBridge lsp = ctx.getLspBridge();
            if (lsp != null) {
                flushDidChange.run(); // the command runs on the text with the completion in it
                lsp.executeCommand(item.getCommand().getCommand(), item.getCommand().getArguments());
            }
        }
        if (plan.hasAdditionalEdits()) {
            compositor.invalidateOverlays();
            compositor.applyHighlightingNow();
            semanticTokens.schedule();
        }
        return true;
    }

    /**
     * Apply the plan as ONE multi-change (one undo step), then place the caret / first tab stop: a
     * multi-change commit doesn't place it. Display offsets are all computed on the text before the commit;
     * the changes come in descending order, so each one's original offsets are still exact when applied.
     */
    private void applyPlan(CompletionEditPlanner.Plan plan, int caretDisplay) {
        List<CompletionEditPlanner.Change> changes = plan.changes();
        int caretSource = codeArea.displayToSource(caretDisplay);
        int[] starts = new int[changes.size()];
        int[] ends = new int[changes.size()];
        int mainIndex = 0;
        for (int i = 0; i < changes.size(); i++) {
            CompletionEditPlanner.Change change = changes.get(i);
            if (change.main()) mainIndex = i;
            if (change.main() && change.end() == caretSource) {
                // Ends at the caret: keep it exactly there. The start lands after any hint before the word.
                ends[i] = caretDisplay;
                starts[i] = change.start() == change.end()
                        ? caretDisplay : Math.min(codeArea.sourceToDisplay(change.start()), caretDisplay);
            } else if (change.start() == change.end()) {
                starts[i] = ends[i] = codeArea.sourceToDisplayBefore(change.start());
            } else {
                starts[i] = codeArea.sourceToDisplay(change.start());
                ends[i] = Math.max(starts[i], codeArea.sourceToDisplayBefore(change.end()));
            }
        }

        activeSnippet = null;
        var undo = codeArea.getUndoManager();
        undo.preventMerge(); // don't merge into the prefix typed just before
        applying = true;
        try {
            var multiChange = codeArea.createMultiChange(changes.size());
            for (int i = 0; i < changes.size(); i++) {
                multiChange.replaceTextAbsolutely(starts[i], ends[i], changes.get(i).text());
            }
            multiChange.commit();
        } finally {
            applying = false;
        }
        undo.preventMerge(); // nor the next keystroke into this

        // Where the main text begins now: the changes after it in the list lie at or before it.
        int displayInsertStart = starts[mainIndex];
        for (int i = mainIndex + 1; i < changes.size(); i++) {
            displayInsertStart += changes.get(i).text().length() - (ends[i] - starts[i]);
        }
        int delta = displayInsertStart - plan.insertStart(); // hints before the inserted text
        int length = codeArea.getLength();
        int selectionStart = Math.max(0, Math.min(plan.selectionStart() + delta, length));
        int selectionEnd = Math.max(0, Math.min(plan.selectionEnd() + delta, length));
        codeArea.selectRange(selectionStart, selectionEnd);
        codeArea.requestFollowCaret();
        if (plan.snippet() != null) activeSnippet = plan.snippet().shift(delta);
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

    void dispose() {
        disposed = true;
        cancel();
        view.dispose();
    }
}
