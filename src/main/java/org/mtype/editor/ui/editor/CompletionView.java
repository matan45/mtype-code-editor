package org.mtype.editor.ui.editor;

import org.eclipse.lsp4j.CompletionItem;

import java.util.List;

/**
 * The completion popup as {@link CompletionController} sees it (MYT-423). The controller owns all state
 * (which word, which request, which items); a view only shows the ranked list it is given and reports
 * what the user did. {@link ContextMenuCompletionView} is today's implementation; MYT-436 swaps in a
 * list-based popup behind this same seam. FX thread only.
 */
interface CompletionView {

    /** What the user did in the popup itself. */
    interface Listener {
        /** A row was chosen in the popup (mouse, or the popup's own Enter on a focused row). */
        void onAccept(CompletionItem item);

        /** The popup closed itself (outside click, its own Esc). Not called for {@link #hide()}. */
        void onDismissed();
    }

    void setListener(Listener listener);

    /** Show {@code ranked} (non-empty, best first) at the caret, or update the open popup in place. */
    void show(List<CompletionItem> ranked);

    /** Close the popup. Programmatic: never calls {@link Listener#onDismissed()}. */
    void hide();

    boolean isShowing();

    /** The highlighted row, else the first one; null when hidden. */
    CompletionItem selected();

    /** Whether the popup's own window has the focus (so the editor losing it is not a dismissal). */
    boolean ownsFocus();

    void dispose();
}
