package org.mtype.editor.ui.editor;

import javafx.application.Platform;
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
import java.util.List;
import java.util.Optional;

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

    private Menu quickFixMenu;
    private int quickFixRequestSerial;

    EditorCommands(MTypeCodeArea codeArea, Path path, AppContext ctx, boolean lspManaged,
                   StyleCompositor compositor, SemanticTokensController semanticTokens) {
        this.codeArea = codeArea;
        this.path = path;
        this.ctx = ctx;
        this.lspManaged = lspManaged;
        this.compositor = compositor;
        this.semanticTokens = semanticTokens;
    }

    /* ----- commands ----- */

    void formatDocument() {
        if (!lspManaged) return;
        LspBridge lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) {
            ctx.getStatusBar().setMessage("LSP not ready");
            return;
        }
        lsp.format(path, 4, true).thenAcceptAsync(edits -> {
            boolean hadChanges = edits != null && !edits.isEmpty();
            if (hadChanges) {
                LspEdits.applyToCodeArea(codeArea, edits);
            }
            // RichTextFX clears styles in replaced ranges, and any cached diagnostic spans
            // reference old offsets — so always re-tokenize before showing the result.
            compositor.invalidateOverlays();
            compositor.applyHighlightingNow();
            semanticTokens.schedule();
            ctx.getStatusBar().setMessage(hadChanges
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

            lsp.rename(path, line, col, newName).thenAcceptAsync(edit -> {
                if (edit == null) {
                    ctx.getStatusBar().setMessage("Rename returned no edit");
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
        if (quickFixMenu != null) populateQuickFix(quickFixMenu, offset);
    }

    private void populateQuickFix(Menu quickFix, int offset) {
        if (!lspManaged) {
            quickFix.getItems().setAll(disabledItem("(not available)"));
            return;
        }
        LspBridge lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) {
            quickFix.getItems().setAll(disabledItem("LSP not ready"));
            return;
        }
        int clampedOffset = Math.max(0, Math.min(offset, codeArea.getLength()));
        int[] lc = codeArea.displayToSourceLineChar(clampedOffset);
        Position pos = new Position(lc[0], lc[1]);
        Range range = new Range(pos, pos);
        List<Diagnostic> here = diagnosticsContainingLine(lc[0]);

        final int serial = ++quickFixRequestSerial;
        quickFix.getItems().setAll(disabledItem("Loading..."));
        ctx.getOutputPane().appendLspLog("[codeAction] " + (lc[0] + 1) + ":" + (lc[1] + 1)
                + " diagnostics=" + here.size());

        lsp.codeAction(path, range, here).thenAcceptAsync(actions -> {
            if (serial != quickFixRequestSerial) return; // a newer request has superseded
            int count = actions == null ? 0 : actions.size();
            ctx.getOutputPane().appendLspLog("[codeAction] returned " + count + " action(s)");
            if (count == 0) {
                replaceQuickFixItems(quickFix, List.of(disabledItem("(no actions)")));
                return;
            }
            List<MenuItem> next = new ArrayList<>();
            for (var either : actions) {
                MenuItem mi = quickFixMenuItem(either);
                if (mi != null) next.add(mi);
            }
            if (next.isEmpty()) {
                replaceQuickFixItems(quickFix, List.of(disabledItem("(no actions)")));
            } else {
                replaceQuickFixItems(quickFix, next);
            }
        }, Platform::runLater).exceptionally(t -> {
            Platform.runLater(() -> {
                ctx.getOutputPane().appendLspLog("[codeAction error] " + t.getMessage());
                replaceQuickFixItems(quickFix, List.of(disabledItem("(error: " + t.getMessage() + ")")));
            });
            return null;
        });
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

    private MenuItem quickFixMenuItem(Either<Command, CodeAction> either) {
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
        List<Diagnostic> all = ctx.getDiagnosticsBus().forUri(path.toUri().toString());
        List<Diagnostic> here = new ArrayList<>();
        for (Diagnostic d : all) {
            int s = d.getRange().getStart().getLine();
            int e = d.getRange().getEnd().getLine();
            if (line >= s && line <= e) here.add(d);
        }
        return here;
    }

    private static MenuItem disabledItem(String text) {
        MenuItem mi = new MenuItem(text);
        mi.setDisable(true);
        return mi;
    }
}
