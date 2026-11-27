package org.mtype.editor.ui.editor;

import javafx.application.Platform;
import org.eclipse.lsp4j.CompletionItem;
import org.fxmisc.richtext.model.PlainTextChange;
import org.fxmisc.richtext.model.TwoDimensional;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.lsp.LspBridge;
import org.reactfx.Subscription;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

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
 *
 * <p><b>Triggers (MYT-424).</b> Every user edit (from {@code multiPlainChanges}) goes through
 * {@link CompletionTriggerPolicy}: the server's trigger characters, {@code :} only as {@code ::}, and no
 * auto-popup in comments or plain strings ({@link LexicalContext}); Ctrl+Space works everywhere. Each
 * request reports what caused it: TriggerCharacter for the trigger just typed, Invoked otherwise.
 *
 * <p><b>Snippets.</b> The {@link SnippetStack} sees every edit, so completing inside a placeholder keeps
 * the snippet and its later stops; a snippet accepted inside a snippet nests.
 */
final class CompletionController {
    private static final long DEBOUNCE_MS = 180;

    private final MTypeCodeArea codeArea;
    private final Path path;
    private final AppContext ctx;
    private final StyleCompositor compositor;
    private final SemanticTokensController semanticTokens;
    private final boolean lspManaged;
    /** Sends the tab's pending didChange, so the server completes on the text the user sees. */
    private final Runnable flushDidChange;
    /** True while the tab applies an internal edit (inlay hints, external reload): not the user typing. */
    private final BooleanSupplier internalEdit;
    private final CompletionView view;
    private final LatestRequest<List<CompletionItem>> inFlight = new LatestRequest<>();
    private final SnippetStack snippets = new SnippetStack();
    private final Subscription textChanges;

    /** The word being completed; null when idle (then no timer, no request, popup hidden). */
    private Session session;
    private ScheduledFuture<?> timer;
    /** Bumped by every arm/cancel, so a timer tick already queued on the FX thread can't fire late. */
    private long timerSerial;
    /** Our own accept edit is being applied: the edit listener must not treat it as typing. */
    private boolean applying;
    private boolean reconcileQueued;
    private boolean disposed;

    private static final class Session {
        final CompletionAnchor anchor;
        List<CompletionItem> items = List.of();
        /** The caret column the server answered for (-1 until a reply arrives). */
        int requestCol = -1;
        /** The trigger character the next request reports (TriggerCharacter); null means Invoked. */
        String nextTrigger;

        Session(CompletionAnchor anchor) {
            this.anchor = anchor;
        }
    }

    /** The caret in SOURCE coordinates (paragraph index == source line). */
    private record Caret(int line, int col, String lineText) {}

    CompletionController(MTypeCodeArea codeArea, Path path, AppContext ctx,
                         StyleCompositor compositor, SemanticTokensController semanticTokens, boolean lspManaged,
                         Runnable flushDidChange, BooleanSupplier internalEdit, CompletionView view) {
        this.codeArea = codeArea;
        this.path = path;
        this.ctx = ctx;
        this.compositor = compositor;
        this.semanticTokens = semanticTokens;
        this.lspManaged = lspManaged;
        this.flushDidChange = flushDidChange;
        this.internalEdit = internalEdit;
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
        textChanges = codeArea.multiPlainChanges().subscribe(this::onTextChanges);
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
        return snippets.isActive();
    }

    /** Drop the session: the debounce timer, the request in flight (the server request too) and the popup. */
    void cancel() {
        disarm();
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

    /** Ctrl+Space: complete the word at the caret now, wherever it is (comments and strings too). */
    void invoke() {
        if (!lspManaged || disposed) return;
        ensureSession(caret());
        session.nextTrigger = null; // Invoked, even right after a trigger character
        disarm();
        requestNow();
    }

    /* ----- triggering ----- */

    /**
     * One edit of the text: a single change, or the changes of one multi-change (our accept, or its undo /
     * redo), in the order applied, each position relative to the text after the ones before it. RichTextFX
     * applies all of them before notifying. The snippet stops are mapped through every change; only a
     * single-change edit by the user can trigger completion.
     */
    private void onTextChanges(List<PlainTextChange> changes) {
        if (disposed) return;
        for (PlainTextChange change : changes) {
            if (change.isIdentity()) continue;
            String removed = change.getRemoved();
            String inserted = change.getInserted();
            snippets.onEdit(change.getPosition(), removed.length(), inserted.length(),
                    isHintsOnly(removed) && isHintsOnly(inserted));
        }
        if (applying || internalEdit.getAsBoolean() || !lspManaged) return;
        if (changes.size() != 1) {
            cancel(); // undo / redo of an accept: never typing
            return;
        }
        PlainTextChange change = changes.getFirst();
        onUserEdit(change.getPosition() + change.getInserted().length(), change.getRemoved(), change.getInserted());
    }

    /**
     * A user edit that leaves the caret at DISPLAY offset {@code caretDisplay}. The caret is derived from
     * the change, not read from the area, because RichTextFX may move it after this runs.
     */
    private void onUserEdit(int caretDisplay, String removed, String inserted) {
        Caret now = caretAt(caretDisplay);
        boolean inSession = session != null && session.anchor.contains(now.lineText(), now.line(), now.col());
        int beforeCol = now.col() - 2; // the source character before the one just typed
        char before = beforeCol >= 0 && beforeCol < now.lineText().length() ? now.lineText().charAt(beforeCol) : '\0';
        Supplier<LexicalContext.Kind> context =
                () -> LexicalContext.at(codeArea.getSourceText(), codeArea.displayToSource(caretDisplay));
        LspBridge lsp = ctx.getLspBridge();
        Set<String> triggers = lsp == null ? Set.of() : lsp.getCompletionTriggerCharacters();

        CompletionTriggerPolicy.Decision decision =
                CompletionTriggerPolicy.onEdit(removed, inserted, before, context, triggers, inSession);
        switch (decision.action()) {
            case CONTINUE -> {
                ensureSession(now);
                refilter(now); // now, not on the next pulse: Enter right after this sees the new list
                arm(null);
            }
            case START -> {
                cancel();
                startSession(now);
                arm(decision.triggerCharacter());
            }
            case RERANK -> {
                refilter(now);
                arm(null); // refresh from the server for the shorter prefix
            }
            case CANCEL -> cancel(); // paste, undo, a multi-character edit, no completion here, ...
        }
    }

    private static boolean isHintsOnly(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != MTypeCodeArea.HINT_CHAR) return false;
        }
        return true;
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

    /** (Re)start the debounce; the request then reports {@code trigger} (null: Invoked). */
    private void arm(String trigger) {
        if (session == null) return;
        session.nextTrigger = trigger;
        if (timer != null) timer.cancel(false);
        long serial = ++timerSerial;
        timer = EditorTab.BG_EXEC.schedule(
                () -> Platform.runLater(() -> onTimer(serial)),
                DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private void disarm() {
        timerSerial++;
        if (timer != null) {
            timer.cancel(false);
            timer = null;
        }
    }

    private void onTimer(long serial) {
        if (serial != timerSerial || disposed) return;
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
        String trigger = session.nextTrigger;
        session.nextTrigger = null; // a re-request for the same word is Invoked
        CompletableFuture<List<CompletionItem>> request = lsp.completion(path, now.line(), requestCol, trigger);
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

    /** The caret a DISPLAY offset stands for, in SOURCE coordinates. */
    private Caret caretAt(int displayOffset) {
        int offset = Math.max(0, Math.min(displayOffset, codeArea.getLength()));
        TwoDimensional.Position position = codeArea.offsetToPosition(offset, TwoDimensional.Bias.Forward);
        int paragraph = position.getMajor();
        int col = codeArea.displayToSourceColumn(paragraph, position.getMinor());
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
     * A snippet already running stays: the commit's changes reach it through {@link #onTextChanges}.
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
        // Pushed after the commit: its offsets already describe the text with the completion in it.
        if (plan.snippet() != null) snippets.push(plan.snippet().shift(delta));
    }

    /* ----- snippets ----- */

    /** Tab with no popup: the next tab stop of the innermost snippet; false when there is none. */
    boolean advanceSnippet() {
        if (!snippets.isActive()) return false;
        SnippetSession.Selection next = snippets.advance(codeArea.getCaretPosition());
        if (next == null) return false;
        applySnippetSelection(next);
        return true;
    }

    private void applySnippetSelection(SnippetSession.Selection selection) {
        int length = codeArea.getLength();
        int start = Math.max(0, Math.min(selection.start(), length));
        int end = Math.max(0, Math.min(selection.end(), length));
        codeArea.selectRange(start, end);
    }

    void dispose() {
        disposed = true;
        cancel();
        textChanges.unsubscribe();
        snippets.clear();
        view.dispose();
    }
}
