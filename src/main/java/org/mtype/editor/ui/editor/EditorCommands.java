package org.mtype.editor.ui.editor;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.lsp.LspBridge;
import org.mtype.editor.lsp.LspEdits;
import org.mtype.editor.ui.dialogs.Dialogs;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The LSP-backed editor actions (format, go-to-definition, find-references, rename, call hierarchy),
 * the Quick Fix submenu, and the right-click context menu that wires them together. Edits that mutate
 * the document re-invalidate and rebuild the highlighting overlays via {@link StyleCompositor} and
 * re-request semantic tokens, matching the rest of the editor's stale-span handling.
 */
final class EditorCommands {
    private final MTypeCodeArea codeArea;
    private final Path path;
    private final AppContext ctx;
    private final boolean lspManaged;
    private final StyleCompositor compositor;
    private final SemanticTokensController semanticTokens;
    /** Sends this tab's pending didChange now ({@link EditorTab#flushPendingDidChange()}). */
    private final Runnable flushDidChange;

    private Menu quickFixMenu;
    /** The Ctrl+. popup at the caret; holds the same items the right-click submenu would. */
    private final ContextMenu quickFixPopup = new ContextMenu();
    private int quickFixRequestSerial;

    EditorCommands(MTypeCodeArea codeArea, Path path, AppContext ctx, boolean lspManaged,
                   StyleCompositor compositor, SemanticTokensController semanticTokens,
                   Runnable flushDidChange) {
        this.codeArea = codeArea;
        this.path = path;
        this.ctx = ctx;
        this.lspManaged = lspManaged;
        this.compositor = compositor;
        this.semanticTokens = semanticTokens;
        this.flushDidChange = flushDidChange;
        quickFixPopup.getStyleClass().add("mt-code-context");
    }

    /* ----- commands ----- */

    void formatDocument() {
        if (!lspManaged) return;
        LspBridge lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) {
            ctx.getStatusBar().setMessage("LSP not ready");
            return;
        }
        // Put the current text on the wire before the request, and drop the response if the text
        // changes while it is in flight (its edits would describe an older document).
        flushDidChange.run();
        String requested = codeArea.getSourceText();
        lsp.format(path, 4, true).thenAcceptAsync(edits -> {
            if (!requested.equals(codeArea.getSourceText())) {
                ctx.getStatusBar().setMessage("Document changed; format skipped");
                return;
            }
            int applied = LspEdits.applyToCodeArea(codeArea, edits);
            if (applied > 0) {
                // RichTextFX clears styles in replaced ranges, and any cached diagnostic spans
                // reference old offsets — so re-tokenize before showing the result.
                compositor.invalidateOverlays();
                compositor.applyHighlightingNow();
                semanticTokens.schedule();
            }
            ctx.getStatusBar().setMessage(applied > 0
                    ? "Formatted " + path.getFileName()
                    : "No formatting changes");
        }, Platform::runLater);
    }

    void goToDefinitionAtCaret() {
        if (!lspManaged) return;
        LspBridge lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) return;
        int[] lc = codeArea.displayToSourceLineChar(codeArea.getCaretPosition());
        lsp.definition(path, lc[0], lc[1]).thenAcceptAsync(loc -> {
            if (loc == null) {
                ctx.getStatusBar().setMessage("No definition");
                return;
            }
            Locations.openLocation(ctx, loc, "definition");
        }, Platform::runLater);
    }

    void showCallHierarchyAtCaret() {
        if (!lspManaged) return;
        LspBridge lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) {
            ctx.getStatusBar().setMessage("LSP not ready");
            return;
        }
        int[] lc = codeArea.displayToSourceLineChar(codeArea.getCaretPosition());
        lsp.prepareCallHierarchy(path, lc[0], lc[1])
                .thenAcceptAsync(items -> {
                    if (items == null || items.isEmpty()) {
                        ctx.getStatusBar().setMessage("No callable here");
                        return;
                    }
                    ctx.getOutputPane().showCallHierarchy(items.getFirst());
                }, Platform::runLater);
    }

    void findReferencesAtCaret() {
        if (!lspManaged) return;
        LspBridge lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) {
            ctx.getStatusBar().setMessage("LSP not ready");
            return;
        }
        int[] lc = codeArea.displayToSourceLineChar(codeArea.getCaretPosition());
        int line = lc[0];
        int col = lc[1];
        String word = Words.wordAt(codeArea, line, col);
        String label = word.isEmpty() ? "References" : word;
        lsp.references(path, line, col, true).thenAcceptAsync(locations -> {
            if (locations == null || locations.isEmpty()) {
                ctx.getStatusBar().setMessage("No references for " + label);
                ctx.getOutputPane().showReferences(label, java.util.Collections.emptyList());
                return;
            }
            ctx.getOutputPane().showReferences(label, locations);
        }, Platform::runLater);
    }

    void renameAtCaret() {
        if (!lspManaged) return;
        LspBridge lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) {
            ctx.getStatusBar().setMessage("LSP not ready");
            return;
        }
        int[] lc = codeArea.displayToSourceLineChar(codeArea.getCaretPosition());
        int line = lc[0];
        int col = lc[1];

        // The resulting WorkspaceEdit also rewrites OTHER open tabs from the server's copies, so every
        // open tab must have its pending changes on the wire, not just this one.
        flushAllOpenTabs();
        Map<EditorTab, String> snapshot = snapshotOpenTabs();
        lsp.prepareRename(path, line, col).thenAcceptAsync(info -> {
            String placeholder = info != null && info.placeholder() != null
                    ? info.placeholder() : Words.wordAroundCaret(codeArea);
            if (placeholder == null || placeholder.isBlank()) {
                ctx.getStatusBar().setMessage("Can't rename here");
                return;
            }
            TextInputDialog dlg = new TextInputDialog(placeholder);
            dlg.initOwner(codeArea.getScene() != null ? codeArea.getScene().getWindow() : null);
            dlg.setTitle("Rename Symbol");
            dlg.setHeaderText("Rename '" + placeholder + "'");
            dlg.setContentText("New name");
            Dialogs.theme(dlg);
            TextField field = dlg.getEditor();
            field.getStyleClass().add("mt-rename-field");
            Platform.runLater(() -> {
                field.selectAll();
                field.requestFocus();
            });
            Optional<String> result = dlg.showAndWait();
            if (result.isEmpty()) return;
            String newName = result.get().trim();
            if (newName.isEmpty() || newName.equals(placeholder)) return;

            // The dialog keeps the FX event loop running, so documents may have changed under it
            // (e.g. an external reload): (line, col) and the server's copies would then be stale.
            flushAllOpenTabs();
            if (unchanged(snapshot)) {
                ctx.getStatusBar().setMessage("Rename cancelled: a document changed");
                return;
            }
            lsp.rename(path, line, col, newName).thenAcceptAsync(edit -> {
                if (edit == null) {
                    ctx.getStatusBar().setMessage("Rename returned no edit");
                    return;
                }
                if (unchanged(snapshot)) {
                    ctx.getStatusBar().setMessage("Rename cancelled: a document changed during rename");
                    return;
                }
                int files = LspEdits.applyWorkspaceEdit(ctx, edit);
                compositor.invalidateOverlays();
                compositor.applyHighlightingNow();
                semanticTokens.schedule();
                ctx.getStatusBar().setMessage("Renamed in " + files + " file(s)");
            }, Platform::runLater);
        }, Platform::runLater);
    }

    /** Send every open tab's pending didChange (this tab included) so the server holds the current text. */
    private void flushAllOpenTabs() {
        if (ctx.getTabPane() == null) {
            flushDidChange.run();
            return;
        }
        for (EditorTab t : ctx.getTabPane().openTabs()) t.flushPendingDidChange();
    }

    /** Source text of every open tab (this one included), to detect edits while a request is in flight. */
    private Map<EditorTab, String> snapshotOpenTabs() {
        Map<EditorTab, String> texts = new HashMap<>();
        if (ctx.getTabPane() != null) {
            for (EditorTab t : ctx.getTabPane().openTabs()) texts.put(t, t.getCodeArea().getSourceText());
        }
        if (texts.isEmpty()) texts.put(null, codeArea.getSourceText());
        return texts;
    }

    private boolean unchanged(Map<EditorTab, String> snapshot) {
        for (Map.Entry<EditorTab, String> e : snapshot.entrySet()) {
            MTypeCodeArea area = e.getKey() == null ? codeArea : e.getKey().getCodeArea();
            if (!area.getSourceText().equals(e.getValue())) return true;
        }
        return false;
    }

    /* ----- context menu + quick fix ----- */

    ContextMenu buildCodeContextMenu() {
        ContextMenu menu = new ContextMenu();
        menu.getStyleClass().add("mt-code-context");

        Menu quickFix = new Menu("Quick Fix...");
        this.quickFixMenu = quickFix;
        // Populated on right-click (see EditorTab MOUSE_PRESSED handler). No setOnShowing —
        // that would overwrite the freshly-fetched actions with a new "Loading..."
        // every time the user hovers the submenu.
        quickFix.getItems().add(disabledItem("(right-click on a diagnostic)"));

        MenuItem goToDef = new MenuItem("Go to Definition");
        goToDef.setAccelerator(new KeyCodeCombination(KeyCode.F12));
        goToDef.setOnAction(_ -> goToDefinitionAtCaret());

        MenuItem rename = new MenuItem("Rename Symbol");
        rename.setAccelerator(new KeyCodeCombination(KeyCode.F2));
        rename.setOnAction(_ -> renameAtCaret());

        MenuItem callHier = new MenuItem("Show Call Hierarchy");
        callHier.setAccelerator(new KeyCodeCombination(KeyCode.H,
                KeyCombination.CONTROL_DOWN, KeyCombination.ALT_DOWN));
        callHier.setOnAction(_ -> showCallHierarchyAtCaret());

        MenuItem findRefs = new MenuItem("Find All References");
        findRefs.setAccelerator(new KeyCodeCombination(KeyCode.F12, KeyCombination.SHIFT_DOWN));
        findRefs.setOnAction(_ -> findReferencesAtCaret());

        MenuItem format = new MenuItem("Format Document");
        format.setAccelerator(new KeyCodeCombination(KeyCode.F,
                KeyCombination.SHIFT_DOWN, KeyCombination.ALT_DOWN));
        format.setOnAction(_ -> formatDocument());

        MenuItem cut = new MenuItem("Cut");
        cut.setAccelerator(new KeyCodeCombination(KeyCode.X, KeyCombination.SHORTCUT_DOWN));
        cut.setOnAction(_ -> codeArea.cut());

        MenuItem copy = new MenuItem("Copy");
        copy.setAccelerator(new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN));
        copy.setOnAction(_ -> codeArea.copy());

        MenuItem paste = new MenuItem("Paste");
        paste.setAccelerator(new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN));
        paste.setOnAction(_ -> codeArea.paste());

        menu.setOnShowing(_ -> {
            boolean hasSelection = codeArea.getSelection().getLength() > 0;
            cut.setDisable(!hasSelection);
            copy.setDisable(!hasSelection);
        });

        menu.getItems().addAll(
                quickFix,
                new SeparatorMenuItem(),
                goToDef, rename, callHier, findRefs,
                new SeparatorMenuItem(),
                format,
                new SeparatorMenuItem(),
                cut, copy, paste);
        return menu;
    }

    /** Kick off the code-action fetch for the right-clicked offset (no-op if the menu isn't built). */
    void requestQuickFix(int offset) {
        if (quickFixMenu == null) return;
        Menu menu = quickFixMenu;
        menu.getItems().setAll(disabledItem("Loading..."));
        fetchQuickFixes(offset, true,
                items -> replaceQuickFixItems(menu,
                        items.isEmpty() ? List.of(disabledItem("(no actions)")) : items),
                reason -> replaceQuickFixItems(menu, List.of(disabledItem(reason))));
    }

    /** Ctrl+. (MYT-445): the same quick fixes as the right-click submenu, in a popup at the caret. */
    void showQuickFixAtCaret() {
        hideQuickFixPopup();
        fetchQuickFixes(codeArea.getCaretPosition(), true, items -> {
            if (items.isEmpty()) {
                ctx.getStatusBar().setMessage("No quick fixes at caret");
                return;
            }
            showQuickFixPopup(items);
        }, reason -> ctx.getStatusBar().setMessage("Quick fix: " + reason));
    }

    boolean isQuickFixPopupShowing() {
        return quickFixPopup.isShowing();
    }

    void hideQuickFixPopup() {
        quickFixPopup.hide();
    }

    /** ENTER while the Ctrl+. popup is open: run the focused entry, or the first one. */
    boolean fireFocusedQuickFix() {
        MenuItem target = null;
        for (MenuItem mi : quickFixPopup.getItems()) {
            if (mi.getStyleableNode() != null && mi.getStyleableNode().isFocused()) {
                target = mi;
                break;
            }
        }
        if (target == null && !quickFixPopup.getItems().isEmpty()) target = quickFixPopup.getItems().getFirst();
        if (target == null || target.isDisable()) return false;
        quickFixPopup.hide();
        target.fire();
        return true;
    }

    void dispose() {
        quickFixPopup.hide();
    }

    private void showQuickFixPopup(List<MenuItem> items) {
        quickFixPopup.getItems().setAll(items);
        codeArea.requestFollowCaret();
        Optional<Bounds> caret = codeArea.getCaretBounds();
        if (caret.isPresent()) {
            quickFixPopup.show(codeArea, caret.get().getMinX(), caret.get().getMaxY());
            return;
        }
        // Caret not laid out (scrolled away): anchor at the editor's top-left corner instead.
        Bounds area = codeArea.localToScreen(codeArea.getBoundsInLocal());
        if (area != null) quickFixPopup.show(codeArea, area.getMinX(), area.getMinY());
    }

    /**
     * Requests the code actions at {@code offset} and hands the menu items to {@code onItems} on the FX
     * thread ({@code onUnavailable} gets a short reason instead). The pending edit is flushed first so
     * the server answers for the current text, and a superseded request is dropped. The flush makes the
     * server republish the line's diagnostics while the request is in flight; if they changed, ask once
     * more with the fresh ones, so typing a line and pressing Ctrl+. right away still offers its fix.
     */
    private void fetchQuickFixes(int offset, boolean allowRetry,
                                 Consumer<List<MenuItem>> onItems, Consumer<String> onUnavailable) {
        if (!lspManaged) {
            onUnavailable.accept("(not available)");
            return;
        }
        LspBridge lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) {
            onUnavailable.accept("LSP not ready");
            return;
        }
        flushDidChange.run();
        String requested = codeArea.getSourceText();
        int clampedOffset = Math.max(0, Math.min(offset, codeArea.getLength()));
        int[] lc = codeArea.displayToSourceLineChar(clampedOffset);
        Position pos = new Position(lc[0], lc[1]);
        List<Diagnostic> sent = diagnosticsContainingLine(lc[0]);

        final int serial = ++quickFixRequestSerial;
        ctx.getOutputPane().appendLspLog("[codeAction] " + (lc[0] + 1) + ":" + (lc[1] + 1)
                + " diagnostics=" + sent.size());

        lsp.codeAction(path, new Range(pos, pos), sent).thenAcceptAsync(actions -> {
            if (serial != quickFixRequestSerial) return; // a newer request has superseded
            if (allowRetry && QuickFixes.shouldRetry(sent, diagnosticsContainingLine(lc[0]))) {
                fetchQuickFixes(offset, false, onItems, onUnavailable);
                return;
            }
            int count = actions == null ? 0 : actions.size();
            ctx.getOutputPane().appendLspLog("[codeAction] returned " + count + " action(s)");
            onItems.accept(quickFixMenuItems(actions, requested));
        }, Platform::runLater).exceptionally(t -> {
            Platform.runLater(() -> {
                ctx.getOutputPane().appendLspLog("[codeAction error] " + t.getMessage());
                onUnavailable.accept("(error: " + t.getMessage() + ")");
            });
            return null;
        });
    }

    private List<MenuItem> quickFixMenuItems(List<Either<Command, CodeAction>> actions, String requested) {
        List<MenuItem> items = new ArrayList<>();
        if (actions == null) return items;
        for (var either : actions) {
            MenuItem mi = quickFixMenuItem(either, requested);
            if (mi != null) items.add(mi);
        }
        return items;
    }

    /** Replace submenu items. If it's already shown, force JavaFX to re-layout it. */
    private void replaceQuickFixItems(Menu quickFix, List<MenuItem> next) {
        boolean wasShowing = quickFix.isShowing();
        quickFix.getItems().setAll(next);
        if (wasShowing) {
            quickFix.hide();
            Platform.runLater(quickFix::show);
        }
    }

    /** {@code requested} is the text the action was computed for; its edit is skipped if that changed. */
    private MenuItem quickFixMenuItem(Either<Command, CodeAction> either, String requested) {
        if (either == null) return null;
        String title;
        Runnable action;
        if (either.isLeft()) {
            Command cmd = either.getLeft();
            if (cmd == null) return null;
            title = cmd.getTitle();
            action = () -> ctx.getLspBridge().executeCommand(cmd.getCommand(), cmd.getArguments());
        } else {
            CodeAction ca = either.getRight();
            if (ca == null) return null;
            title = ca.getTitle();
            action = () -> {
                if (!requested.equals(codeArea.getSourceText())) {
                    ctx.getStatusBar().setMessage("Document changed; quick fix skipped");
                    return;
                }
                if (ca.getEdit() != null) {
                    int files = LspEdits.applyWorkspaceEdit(ctx, ca.getEdit());
                    compositor.invalidateOverlays();
                    compositor.applyHighlightingNow();
                    semanticTokens.schedule();
                    ctx.getStatusBar().setMessage("Quick fix applied to " + files + " file(s)");
                }
                if (ca.getCommand() != null) {
                    ctx.getLspBridge().executeCommand(ca.getCommand().getCommand(), ca.getCommand().getArguments());
                }
            };
        }
        if (title == null || title.isBlank()) return null;
        MenuItem mi = new MenuItem(title);
        mi.setOnAction(_ -> action.run());
        return mi;
    }

    private List<Diagnostic> diagnosticsContainingLine(int line) {
        return QuickFixes.containingLine(ctx.getDiagnosticsBus().forUri(path.toUri().toString()), line);
    }

    private static MenuItem disabledItem(String text) {
        MenuItem mi = new MenuItem(text);
        mi.setDisable(true);
        return mi;
    }
}
