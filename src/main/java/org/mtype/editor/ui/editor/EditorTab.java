package org.mtype.editor.ui.editor;

import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tab;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CharacterHit;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.debug.BreakpointService;
import org.mtype.editor.debug.DebuggerEventBus;
import org.mtype.editor.lsp.LspBridge;
import org.mtype.editor.lsp.LspEdits;
import org.mtype.editor.syntax.Tokenizers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A single open document. {@code EditorTab} owns the {@link MTypeCodeArea} and wires up a set of
 * focused collaborators — one per editor feature — passing them the code area and the shared
 * {@link StyleCompositor}. It keeps only the cross-cutting concerns: document lifecycle (open/save/
 * close), the LSP document session, dirty/version bookkeeping, debugger hooks, inlay-hint scheduling,
 * event routing to the collaborators, and the public API other UI components call.
 *
 * @see StyleCompositor for the shared highlight composition the collaborators push into
 */
public class EditorTab extends Tab {
    static final ScheduledExecutorService BG_EXEC =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "mtype-tab-bg");
                t.setDaemon(true);
                return t;
            });

    private final AppContext ctx;
    private final Path path;
    private final boolean lspManaged;
    private final MTypeCodeArea codeArea = new MTypeCodeArea();
    private final SimpleBooleanProperty dirty = new SimpleBooleanProperty(false);
    private final AtomicInteger version = new AtomicInteger(1);

    // Feature collaborators.
    private final StyleCompositor compositor;
    private final DiagnosticsController diagnostics;
    private final SemanticTokensController semanticTokens;
    private final CompletionController completion;
    private final SignatureHelpController signatureHelp;
    private final HoverController hover;
    private final CodeLensController codeLens;
    private final CodeFoldingController folding;
    private final DocumentSymbolController documentSymbols;
    private final EditorCommands commands;
    private final GutterFactory gutter;
    private final InlayHintsController inlayHintsController;
    private final CurrentLineHighlighter currentLineHighlighter;

    // Cross-cutting state.
    private long openedLspSession = -1;
    private boolean suppressDirty = true;
    private boolean isInternalEdit = false;
    private int executionLine = -1;

    // Schedulers kept on the tab: LSP document sync and inlay-hint requests.
    private ScheduledFuture<?> pendingDidChange;
    private ScheduledFuture<?> pendingInlayHints;
    private int inlayHintRequestSerial;

    public EditorTab(AppContext ctx, Path path) {
        this.ctx = ctx;
        this.path = path;
        this.lspManaged = Tokenizers.isMTypeFile(path);
        setText(path.getFileName().toString());

        codeArea.getStyleClass().add("code-area");
        // VS-Code-style current-line highlight: the band is painted in currentLineLayer BEHIND a
        // transparent CodeArea (mt-main-editor) so the text stays crisp on top of it. The editor
        // background is painted by the StackPane (mt-editor-stack) instead of the area itself.
        codeArea.getStyleClass().add("mt-main-editor");
        applyFontFromSettings();

        VirtualizedScrollPane<MTypeCodeArea> scroll = new VirtualizedScrollPane<>(codeArea);
        scroll.getStyleClass().add("mt-main-editor-scroll");
        installScrollSensitivity();
        Pane currentLineLayer = new Pane();
        StackPane editorStack = new StackPane(currentLineLayer, scroll);
        editorStack.getStyleClass().add("mt-editor-stack");
        HBox breadcrumbBar = new HBox();
        breadcrumbBar.getStyleClass().add("mt-breadcrumb-bar");
        BorderPane wrap = new BorderPane(editorStack);
        wrap.setTop(breadcrumbBar);
        setContent(wrap);

        // Build the collaborators in dependency order. The gutter is consulted live by several of
        // them via the gutterRefresh hook (which re-sets the paragraph graphic factory).
        compositor = new StyleCompositor(codeArea, path);
        diagnostics = new DiagnosticsController(codeArea, compositor, this::refreshGutter);
        semanticTokens = new SemanticTokensController(codeArea, path, ctx, compositor, lspManaged);
        completion = new CompletionController(codeArea, path, ctx, compositor, semanticTokens, lspManaged);
        signatureHelp = new SignatureHelpController(codeArea, path, ctx, lspManaged);
        hover = new HoverController(codeArea, path, ctx, lspManaged, compositor);
        codeLens = new CodeLensController(codeArea, path, ctx, lspManaged, this::refreshGutter);
        folding = new CodeFoldingController(codeArea, this, compositor, semanticTokens, this::refreshGutter);
        documentSymbols = new DocumentSymbolController(codeArea, path, ctx, lspManaged, breadcrumbBar, folding, this);
        commands = new EditorCommands(codeArea, path, ctx, lspManaged, compositor, semanticTokens);
        gutter = new GutterFactory(path, ctx, diagnostics, codeLens, folding, () -> executionLine);
        inlayHintsController = new InlayHintsController(codeArea, this);
        currentLineHighlighter = new CurrentLineHighlighter(codeArea, currentLineLayer);

        codeArea.setParagraphGraphicFactory(gutter::paragraphGraphic);

        loadFile();
        documentSymbols.updateBreadcrumb();
        suppressDirty = false;

        codeArea.textProperty().addListener((_, oldText, newText) -> {
            if (isInternalEdit) return;
            if (!suppressDirty) markDirty();
            compositor.scheduleHighlight();
            scheduleDidChange();
            codeLens.schedule();
            scheduleInlayHintsRefresh();
            documentSymbols.schedule();
            semanticTokens.schedule();
            completion.maybeAutoCompletion(oldText, newText);
            signatureHelp.maybeSignatureHelp(oldText, newText);
        });

        codeArea.caretPositionProperty().addListener((_, _, n) -> {
            int[] lc = codeArea.displayToSourceLineChar(n);
            ctx.getStatusBar().setCaret(lc[0], lc[1]);
            documentSymbols.updateBreadcrumb();
        });

        codeArea.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKey);

        // Ctrl+Click -> go to definition. Filter (not handler) so we run before
        // RichTextFX's default caret placement, which uses raw hit() and lands
        // on the wrong source offset on lines shifted by inlay-hint overlays.
        codeArea.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.isControlDown()) {
                CharacterHit hit = codeArea.hit(e.getX(), e.getY());
                codeArea.moveTo(hit.getInsertionIndex());
                commands.goToDefinitionAtCaret();
                e.consume();
            }
        });

        // Right-click -> position caret, kick off the code-action request so the
        // submenu is already populated by the time the user hovers it. Plain left-click,
        // double-click word selection, and drag selection are left entirely to RichTextFX:
        // inlay hints are now real segments, so its native hit-testing is accurate and no
        // caret/selection "correction" is needed (correcting it actually broke selection).
        codeArea.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                CharacterHit hit = codeArea.hit(e.getX(), e.getY());
                int clickedOffset = hit.getInsertionIndex();
                if (!isOffsetInsideSelection(clickedOffset)) {
                    codeArea.moveTo(clickedOffset);
                }
                commands.requestQuickFix(clickedOffset);
            }
            if (completion.isMenuShowing()) completion.hideMenu();
        });

        codeArea.setContextMenu(commands.buildCodeContextMenu());

        setOnCloseRequest(ev -> {
            if (!confirmDiscardIfDirty()) ev.consume();
        });

        Platform.runLater(compositor::applyHighlightingNow);
        onLspReady();
        wireDebuggerHooks();
    }

    /** Re-render the gutter by re-setting the paragraph graphic factory (the shared refresh hook). */
    private void refreshGutter() {
        codeArea.setParagraphGraphicFactory(gutter::paragraphGraphic);
    }

    /* ----- debugger integration ----- */

    private void wireDebuggerHooks() {
        BreakpointService bs = ctx.getBreakpointService();
        if (bs != null) {
            bs.addListener((p, _) -> {
                if (p == null || !p.equals(path)) return;
                Platform.runLater(this::refreshGutter);
            });
        }
        DebuggerEventBus bus = ctx.getDebuggerEventBus();
        if (bus != null) {
            bus.onStopped(e -> {
                if (e.file() == null || !e.file().equals(path)) {
                    updateExecutionLine(-1);
                    return;
                }
                updateExecutionLine(e.line());
                int line0 = e.line();
                if (line0 >= 0 && line0 < codeArea.getParagraphs().size()) {
                    codeArea.moveTo(line0, 0);
                    codeArea.requestFollowCaret();
                }
            });
            bus.onResumed(() -> updateExecutionLine(-1));
            bus.onTerminated(() -> updateExecutionLine(-1));
        }
    }

    private void updateExecutionLine(int newLine) {
        int old = executionLine;
        executionLine = newLine;
        int paragraphCount = codeArea.getParagraphs().size();
        if (old >= 0 && old < paragraphCount && old != newLine) {
            codeArea.setParagraphStyle(old,
                    codeLens.isCodeLensParagraph(old)
                            ? Collections.singletonList("mt-code-lens-paragraph")
                            : Collections.emptyList());
        }
        if (newLine >= 0 && newLine < paragraphCount) {
            List<String> classes = new ArrayList<>(2);
            classes.add("mt-execution-line");
            if (codeLens.isCodeLensParagraph(newLine)) classes.add("mt-code-lens-paragraph");
            codeArea.setParagraphStyle(newLine, classes);
        }
        refreshGutter();
    }

    /* ----- accessors ----- */

    public Path getPath() { return path; }
    public MTypeCodeArea getCodeArea() { return codeArea; }
    public int getVersion() { return version.get(); }
    public boolean isDirty() { return dirty.get(); }
    public List<DocumentSymbol> getLastDocumentSymbols() { return documentSymbols.getLastDocumentSymbols(); }

    /* ----- file I/O ----- */

    public void save() {
        folding.unfoldAll();
        boolean formatFirst = lspManaged
                && ctx.getSettings() != null
                && ctx.getSettings().editor != null
                && ctx.getSettings().editor.formatOnSave;
        LspBridge lsp = ctx.getLspBridge();
        if (formatFirst && lsp != null && lsp.isReady()) {
            lsp.format(path, 4, true).thenAcceptAsync(edits -> {
                if (edits != null && !edits.isEmpty()) {
                    LspEdits.applyToCodeArea(codeArea, edits);
                    compositor.invalidateOverlays();
                    compositor.applyHighlightingNow();
                    semanticTokens.schedule();
                }
                writeToDisk();
            }, Platform::runLater);
            return;
        }
        writeToDisk();
    }

    private void applyFontFromSettings() {
        if (ctx.getSettings() == null || ctx.getSettings().editor == null) return;
        var prefs = ctx.getSettings().editor;
        StringBuilder sb = new StringBuilder();
        if (prefs.fontFamily != null && !prefs.fontFamily.isBlank()) {
            String family = prefs.fontFamily.replace("\"", "");
            sb.append("-fx-font-family: \"").append(family).append("\"; ");
        }
        if (prefs.fontSize > 0) {
            sb.append("-fx-font-size: ").append(prefs.fontSize).append("; ");
        }
        if (!sb.isEmpty()) codeArea.setStyle(sb.toString());
    }

    private void writeToDisk() {
        try {
            Files.writeString(path, codeArea.getSourceText(), StandardCharsets.UTF_8);
            dirty.set(false);
            setText(path.getFileName().toString());
            ctx.getStatusBar().setMessage("Saved " + path.getFileName());
            if (ctx.getGitChangesView() != null) ctx.getGitChangesView().refresh();
        } catch (Exception ex) {
            ctx.getStatusBar().setMessage("Save failed: " + ex.getMessage());
        }
    }

    private void loadFile() {
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            codeArea.replaceText(text);
            codeArea.moveTo(0);
        } catch (Exception ex) {
            codeArea.replaceText("");
            ctx.getStatusBar().setMessage("Open failed: " + ex.getMessage());
        }
        codeArea.getUndoManager().forgetHistory();
    }

    public void reloadFromDiskExternal() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::reloadFromDiskExternal);
            return;
        }

        String diskText;
        try {
            diskText = Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            ctx.getStatusBar().setMessage("Reload failed: " + ex.getMessage());
            return;
        }

        if (dirty.get()) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                    "Reload " + path.getFileName() + " from disk and discard unsaved changes?",
                    ButtonType.YES, ButtonType.NO);
            alert.setHeaderText(null);
            Optional<ButtonType> result = alert.showAndWait();
            if (result.isEmpty() || result.get() != ButtonType.YES) {
                ctx.getStatusBar().setMessage("Kept local changes for " + path.getFileName());
                return;
            }
        } else if (diskText.equals(codeArea.getSourceText())) {
            return;
        }

        applyExternalText(diskText);
        ctx.getStatusBar().setMessage("Reloaded " + path.getFileName());
    }

    public void handleExternalDelete() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::handleExternalDelete);
            return;
        }
        if (dirty.get()) {
            ctx.getStatusBar().setMessage("Backing file deleted; kept unsaved tab " + path.getFileName());
            return;
        }
        if (ctx.getTabPane() != null) ctx.getTabPane().closeByPath(path);
        ctx.getStatusBar().setMessage("Closed deleted file " + path.getFileName());
    }

    private void applyExternalText(String text) {
        folding.unfoldAll();
        int sourceCaret = codeArea.displayToSource(codeArea.getCaretPosition());
        double sx = codeArea.estimatedScrollXProperty().getValue();
        double sy = codeArea.estimatedScrollYProperty().getValue();

        runInternalEdit(() -> {
            codeArea.replaceText(text == null ? "" : text);
            int newCaret = Math.min(codeArea.sourceToDisplay(sourceCaret), codeArea.getLength());
            codeArea.moveTo(newCaret);
        });
        codeArea.getUndoManager().forgetHistory();
        dirty.set(false);
        setText(path.getFileName().toString());

        compositor.invalidateOverlays();
        diagnostics.applyDiagnostics(Collections.emptyList());
        compositor.applyHighlightingNow();
        sendDidChangeNow();
        codeLens.schedule();
        scheduleInlayHintsRefresh();
        documentSymbols.schedule();
        documentSymbols.updateBreadcrumb();
        semanticTokens.schedule();

        Platform.runLater(() -> {
            codeArea.scrollXToPixel(sx);
            codeArea.scrollYToPixel(sy);
        });
    }

    private void markDirty() {
        if (!dirty.get()) {
            dirty.set(true);
            setText("*" + path.getFileName().toString());
        }
    }

    /* ----- diagnostics push (from DiagnosticsRenderer) ----- */

    public void applyDiagnostics(List<org.eclipse.lsp4j.Diagnostic> diags) {
        diagnostics.applyDiagnostics(diags);
    }

    /* ----- LSP document lifecycle ----- */

    void onLspReady() {
        if (!lspManaged) return;
        LspBridge lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) return;
        long lspSession = lsp.getSession();
        if (openedLspSession == lspSession) return;
        lsp.didOpen(path, codeArea.getSourceText(), version.get());
        openedLspSession = lspSession;
        codeLens.schedule();
        scheduleInlayHintsRefresh();
        documentSymbols.schedule();
        semanticTokens.schedule();
    }

    void onClosed() {
        if (pendingDidChange != null) pendingDidChange.cancel(false);
        if (pendingInlayHints != null) pendingInlayHints.cancel(false);
        compositor.dispose();
        completion.dispose();
        signatureHelp.dispose();
        hover.dispose();
        codeLens.dispose();
        semanticTokens.dispose();
        documentSymbols.dispose();
        inlayHintsController.dispose();
        currentLineHighlighter.dispose();
        if (lspManaged && ctx.getLspBridge() != null) {
            try { ctx.getLspBridge().didClose(path); } catch (Exception ignored) {}
        }
    }

    private void scheduleDidChange() {
        if (!lspManaged) return;
        if (pendingDidChange != null) pendingDidChange.cancel(false);
        String snapshot = codeArea.getSourceText();
        int v = version.incrementAndGet();
        pendingDidChange = BG_EXEC.schedule(() -> {
            if (ctx.getLspBridge() != null) ctx.getLspBridge().didChange(path, snapshot, v);
        }, 200, TimeUnit.MILLISECONDS);
    }

    private void sendDidChangeNow() {
        if (!lspManaged) return;
        if (pendingDidChange != null) pendingDidChange.cancel(false);
        String snapshot = codeArea.getSourceText();
        int v = version.incrementAndGet();
        if (ctx.getLspBridge() != null) ctx.getLspBridge().didChange(path, snapshot, v);
    }

    /* ----- inlay hints (request scheduling; rendering lives in InlayHintsController) ----- */

    private void scheduleInlayHintsRefresh() {
        if (!lspManaged) return;
        if (pendingInlayHints != null) pendingInlayHints.cancel(false);
        pendingInlayHints = BG_EXEC.schedule(
                () -> Platform.runLater(this::requestInlayHintsNow),
                350,
                TimeUnit.MILLISECONDS);
    }

    public void refreshInlayHintsFromSettings() {
        requestInlayHintsNow();
    }

    private void requestInlayHintsNow() {
        if (!lspManaged) {
            inlayHintsController.clear();
            return;
        }
        if (ctx.getSettings() == null
                || ctx.getSettings().editor == null
                || !ctx.getSettings().editor.inlayHints) {
            inlayHintsController.clear();
            return;
        }
        LspBridge lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) {
            inlayHintsController.clear();
            return;
        }
        int request = ++inlayHintRequestSerial;
        Range range = wholeDocumentRange();
        lsp.inlayHints(path, range).thenAcceptAsync(hints -> {
            if (request != inlayHintRequestSerial) return;
            inlayHintsController.setHints(hints);
        }, Platform::runLater);
    }

    private Range wholeDocumentRange() {
        // Expressed in SOURCE coordinates (no inlay hints) since this range is sent to the LSP server.
        String source = codeArea.getSourceText();
        int lastLine = 0;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') lastLine++;
        }
        int lastCharacter = source.length() - (source.lastIndexOf('\n') + 1);
        return new Range(new Position(0, 0), new Position(lastLine, lastCharacter));
    }

    /**
     * Run an inlay-hint segment mutation (insert/remove {@code ￼} segments) while suppressing the
     * normal text-change reactions (LSP didChange, dirty flag, debounced refreshes). Preserves the
     * caret's SOURCE position across the offset shift, then re-tokenizes and re-maps diagnostics for
     * the new display offsets (semantic tokens are re-requested asynchronously).
     */
    void runHintMutation(Runnable mutation) {
        boolean prev = isInternalEdit;
        isInternalEdit = true;
        int sourceCaret = codeArea.displayToSource(codeArea.getCaretPosition());
        double sx = codeArea.estimatedScrollXProperty().getValue();
        double sy = codeArea.estimatedScrollYProperty().getValue();
        try {
            mutation.run();
        } finally {
            isInternalEdit = prev;
        }
        int newCaret = Math.min(codeArea.sourceToDisplay(sourceCaret), codeArea.getLength());
        codeArea.moveTo(newCaret);
        compositor.invalidateOverlays();
        compositor.applyHighlightingNow();   // re-tokenize over the new display text (length matches spans)
        diagnostics.rebuildDiagnosticStyles(); // re-map stored diagnostics to the new display offsets
        semanticTokens.schedule();
        Platform.runLater(() -> {
            codeArea.scrollXToPixel(sx);
            codeArea.scrollYToPixel(sy);
        });
    }

    /** Run a document edit with text-change reactions (dirty/LSP/refreshes) suppressed. */
    void runInternalEdit(Runnable edit) {
        boolean prev = isInternalEdit;
        isInternalEdit = true;
        try {
            edit.run();
        } finally {
            isInternalEdit = prev;
        }
    }

    /* ----- navigation (public API; also reachable via context menu + keys) ----- */

    public void formatDocument() { commands.formatDocument(); }
    public void goToDefinitionAtCaret() { commands.goToDefinitionAtCaret(); }
    public void renameAtCaret() { commands.renameAtCaret(); }
    public void showCallHierarchyAtCaret() { commands.showCallHierarchyAtCaret(); }

    /** Move the caret to a 0-based LSP position and ensure visible. */
    public void revealPosition(int line, int character) {
        int offset = codeArea.sourceLineCharToDisplay(line, character);
        codeArea.moveTo(offset);
        codeArea.requestFollowCaret();
        codeArea.requestFocus();
    }

    /* ----- key + mouse helpers ----- */

    private void handleKey(KeyEvent e) {
        if (completion.isMenuShowing()) {
            if (e.getCode() == KeyCode.ESCAPE) {
                completion.hideMenu();
                e.consume();
                return;
            }
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.TAB) {
                if (completion.fireFocusedItem()) {
                    e.consume();
                    return;
                }
            }
        }
        if (e.getCode() == KeyCode.TAB && completion.hasActiveSnippet()) {
            if (completion.advanceSnippet()) {
                e.consume();
                return;
            }
        }
        if (e.getCode() == KeyCode.SPACE && e.isControlDown() && e.isShiftDown()) {
            signatureHelp.requestSignatureHelpAtCaret();
            e.consume();
            return;
        }
        if (e.getCode() == KeyCode.SPACE && e.isControlDown()) {
            completion.requestCompletionNow();
            e.consume();
            return;
        }
        if (e.getCode() == KeyCode.F12) {
            commands.goToDefinitionAtCaret();
            e.consume();
            return;
        }
        if (e.getCode() == KeyCode.F2) {
            commands.renameAtCaret();
            e.consume();
            return;
        }
        if (e.getCode() == KeyCode.F && e.isShiftDown() && e.isAltDown()) {
            commands.formatDocument();
            e.consume();
            return;
        }
        if (e.getCode() == KeyCode.H && e.isControlDown() && e.isAltDown()) {
            commands.showCallHierarchyAtCaret();
            e.consume();
        }
    }

    private boolean isOffsetInsideSelection(int offset) {
        javafx.scene.control.IndexRange selection = codeArea.getSelection();
        return selection.getLength() > 0
                && offset >= selection.getStart()
                && offset <= selection.getEnd();
    }

    /* ----- scroll sensitivity ----- */

    private double pendingScrollX;
    private double pendingScrollY;
    private boolean scrollFlushScheduled;

    private void installScrollSensitivity() {
        final double sensitivity = 1.8;
        codeArea.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, event -> {
            if (event.isShortcutDown() || event.isInertia()) return;

            double deltaY = event.getDeltaY();
            double deltaX = event.getDeltaX();
            if (deltaY == 0 && deltaX == 0) return;

            pendingScrollY -= deltaY * sensitivity;
            pendingScrollX -= deltaX * sensitivity;
            scheduleScrollFlush();
            event.consume();
        });
    }

    private void scheduleScrollFlush() {
        if (scrollFlushScheduled) return;
        scrollFlushScheduled = true;
        Platform.runLater(() -> {
            scrollFlushScheduled = false;
            double y = pendingScrollY;
            double x = pendingScrollX;
            pendingScrollY = 0;
            pendingScrollX = 0;
            if (y != 0) codeArea.scrollYBy(y);
            if (x != 0) codeArea.scrollXBy(x);
        });
    }

    /* ----- close confirmation ----- */

    public boolean canClose() {
        return confirmDiscardIfDirty();
    }

    private boolean confirmDiscardIfDirty() {
        if (!dirty.get()) return true;
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Discard unsaved changes to " + path.getFileName() + "?",
                ButtonType.YES, ButtonType.NO);
        a.setHeaderText(null);
        Optional<ButtonType> r = a.showAndWait();
        return r.isPresent() && r.get() == ButtonType.YES;
    }
}
