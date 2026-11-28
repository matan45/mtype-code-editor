package org.mtype.editor.ui.editor;

import java.util.ArrayList;
import java.util.List;

/**
 * The active snippet sessions, outermost first (MYT-424). Accepting a snippet completion inside a running
 * snippet pushes it: the inner one owns Tab until it reaches its final caret, then it is popped and the
 * next Tab continues the outer one. Every document edit is mapped through all of them, so the outer stops
 * stay exact while the inner snippet is being filled in.
 *
 * <p>Pure (no JavaFX), so it is unit-tested directly.
 */
final class SnippetStack {
    private final List<SnippetSession> sessions = new ArrayList<>();

    boolean isActive() {
        return !sessions.isEmpty();
    }

    void push(SnippetSession session) {
        sessions.add(session);
    }

    void clear() {
        sessions.clear();
    }

    /** Map one edit (see {@link SnippetSession#onEdit}); a broken session ends with every one nested in it. */
    void onEdit(int start, int removedLength, int insertedLength, boolean hintsOnly) {
        for (int i = 0; i < sessions.size(); i++) {
            if (!sessions.get(i).onEdit(start, removedLength, insertedLength, hintsOnly)) {
                sessions.subList(i, sessions.size()).clear();
                return;
            }
        }
    }

    /**
     * Tab at {@code caret}: the innermost session that still contains the caret moves to its next stop
     * (sessions the caret has left are dropped). A session that reaches its final caret is popped. Null
     * when no session is left to advance, so Tab does its normal thing.
     */
    SnippetSession.Selection advance(int caret) {
        while (!sessions.isEmpty()) {
            SnippetSession top = sessions.getLast();
            SnippetSession.Selection next = top.contains(caret) ? top.advance(caret) : null;
            if (next == null) {
                sessions.removeLast();
                continue;
            }
            if (next.finalCaret()) sessions.removeLast();
            return next;
        }
        return null;
    }
}
