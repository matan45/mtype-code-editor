package org.mtype.editor.ui.editor;

import org.eclipse.lsp4j.Diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Pure helpers behind the quick-fix request (right-click submenu and Ctrl+.). No JavaFX, so they are
 * unit-testable.
 */
final class QuickFixes {

    private QuickFixes() {}

    /**
     * The diagnostics whose range covers {@code line} (0-based). They are sent with the request as
     * received from the server, so the opaque {@code data} the server attached (e.g. the missing
     * type name an "Add import" fix needs) comes back to it unchanged.
     */
    static List<Diagnostic> containingLine(List<Diagnostic> all, int line) {
        List<Diagnostic> here = new ArrayList<>();
        if (all == null) return here;
        for (Diagnostic d : all) {
            if (d == null || d.getRange() == null) continue;
            int start = d.getRange().getStart().getLine();
            int end = d.getRange().getEnd().getLine();
            if (line >= start && line <= end) here.add(d);
        }
        return here;
    }

    /**
     * Whether to ask again with fresh diagnostics: the request flushes the pending edit first, so the
     * server publishes diagnostics for the new text while the request is in flight. If they changed
     * (typing a line and pressing Ctrl+. right away), the first answer was for the old diagnostics.
     */
    static boolean shouldRetry(List<Diagnostic> sent, List<Diagnostic> fresh) {
        return !Objects.equals(sent, fresh);
    }
}
