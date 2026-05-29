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
import java.util.ArrayList;
import java.util.Comparator;
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
     */
    public static void applyToCodeArea(MTypeCodeArea area, List<? extends TextEdit> edits) {
        if (edits == null || edits.isEmpty()) return;
        String source = area.getSourceText();
        List<? extends TextEdit> sorted = new ArrayList<>(edits);
        // Apply in reverse offset order so earlier positions stay valid
        sorted.sort(Comparator
                .comparingInt((TextEdit e) -> e.getRange().getStart().getLine()).reversed()
                .thenComparing(Comparator.comparingInt((TextEdit e) -> e.getRange().getStart().getCharacter()).reversed()));
        int caretBefore = area.getCaretPosition();
        for (TextEdit e : sorted) {
            int sStart = Positions.offset(source, e.getRange().getStart());
            int sEnd = Positions.offset(source, e.getRange().getEnd());
            if (sStart > sEnd) {
                int t = sStart;
                sStart = sEnd;
                sEnd = t;
            }
            // Map source offsets to current display offsets each iteration (descending order keeps
            // earlier positions valid as the document shrinks/grows).
            int start = area.sourceToDisplay(sStart);
            int end = area.sourceToDisplay(sEnd);
            area.replaceText(start, end, e.getNewText() == null ? "" : e.getNewText());
        }
        int finalLen = area.getLength();
        area.moveTo(Math.min(caretBefore, finalLen));
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
        List<? extends TextEdit> sorted = new ArrayList<>(edits);
        sorted.sort(Comparator
                .comparingInt((TextEdit e) -> e.getRange().getStart().getLine()).reversed()
                .thenComparing(Comparator.comparingInt((TextEdit e) -> e.getRange().getStart().getCharacter()).reversed()));
        StringBuilder sb = new StringBuilder(text);
        for (TextEdit e : sorted) {
            int start = Positions.offset(text, e.getRange().getStart());
            int end = Positions.offset(text, e.getRange().getEnd());
            if (start > end) {
                int t = start;
                start = end;
                end = t;
            }
            sb.replace(start, end, e.getNewText() == null ? "" : e.getNewText());
        }
        return sb.toString();
    }
}
