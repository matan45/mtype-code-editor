package org.mtype.editor.lsp;

import org.eclipse.lsp4j.ResourceOperation;
import org.eclipse.lsp4j.TextDocumentEdit;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.ui.editor.EditorTab;
import org.mtype.editor.ui.editor.MTypeCodeArea;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Apply LSP TextEdits / WorkspaceEdits to open editor tabs and on-disk files.
 */
public final class LspEdits {
    private LspEdits() {
    }

    /**
     * Apply a list of edits to a single area. Edits must not overlap. Edit ranges are in SOURCE
     * coordinates (no inlay hints); they are mapped to the area's display offsets before applying so
     * that embedded inlay-hint segments are preserved (or correctly removed when their code is replaced).
     *
     * <p>Edits are first shrunk to the spans that really change ({@link SourceEdits#minimize}): the mType
     * formatter answers with one whole-document edit, and replacing the whole area would reset every
     * paragraph style (folds, code-lens padding, execution line) even when nothing changed. The caret is
     * mapped through the applied edits so it stays on the same code ({@code replaceText} itself always
     * moves it to the end of the inserted text).
     *
     * @return the number of (minimal) edits applied; 0 means the document is unchanged
     */
    public static int applyToCodeArea(MTypeCodeArea area, List<? extends TextEdit> edits) {
        if (edits == null || edits.isEmpty()) return 0;
        List<SourceEdits.Edit> minimal = SourceEdits.minimize(area.getSourceText(), edits);
        if (minimal.isEmpty()) return 0;
        int sourceCaret = area.displayToSource(area.getCaretPosition());
        // Descending order: mapping each edit's source offsets to display offsets stays valid because
        // only text AFTER the current edit has changed so far.
        for (SourceEdits.Edit e : minimal) {
            area.replaceText(area.sourceToDisplay(e.start()), area.sourceToDisplay(e.end()), e.text());
        }
        int caret = area.sourceToDisplay(SourceEdits.mapOffset(sourceCaret, minimal));
        area.moveTo(Math.min(caret, area.getLength()));
        return minimal.size();
    }

    /**
     * Apply a WorkspaceEdit across the whole AppContext (open tabs + on-disk files).
     */
    public static int applyWorkspaceEdit(AppContext ctx, WorkspaceEdit edit) {
        if (edit == null) return 0;
        int filesTouched = 0;

        // documentChanges takes precedence per LSP spec
        List<Either<TextDocumentEdit, ResourceOperation>> docChanges = edit.getDocumentChanges();
        if (docChanges != null && !docChanges.isEmpty()) {
            for (Either<TextDocumentEdit, ResourceOperation> e : docChanges) {
                if (e.isLeft()) {
                    TextDocumentEdit tde = e.getLeft();
                    String uri = tde.getTextDocument().getUri();
                    if (applyEditsToUri(ctx, uri, tde.getEdits())) filesTouched++;
                }
                // ResourceOperation (create/rename/delete files) intentionally unsupported in v1
            }
        } else if (edit.getChanges() != null) {
            for (Map.Entry<String, List<TextEdit>> entry : edit.getChanges().entrySet()) {
                if (applyEditsToUri(ctx, entry.getKey(), entry.getValue())) filesTouched++;
            }
        }
        return filesTouched;
    }

    private static boolean applyEditsToUri(AppContext ctx, String uri, List<? extends TextEdit> edits) {
        if (edits == null || edits.isEmpty()) return false;
        Path path;
        try {
            path = Path.of(URI.create(uri));
        } catch (Exception ex) {
            return false;
        }
        EditorTab tab = ctx.getTabPane() == null ? null : ctx.getTabPane().findByPath(path);
        if (tab != null) {
            applyToCodeArea(tab.getCodeArea(), edits);
            return true;
        }
        // On-disk apply
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            String updated = applyToString(text, edits);
            Files.writeString(path, updated, StandardCharsets.UTF_8);
            return true;
        } catch (IOException ex) {
            ctx.getOutputPane().appendLspLog("[rename io] " + path + ": " + ex.getMessage());
            return false;
        }
    }

    private static String applyToString(String text, List<? extends TextEdit> edits) {
        return SourceEdits.apply(text, SourceEdits.minimize(text, edits));
    }
}
