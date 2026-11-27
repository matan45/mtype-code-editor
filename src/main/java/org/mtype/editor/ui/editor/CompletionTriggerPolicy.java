package org.mtype.editor.ui.editor;

import java.util.Set;
import java.util.function.Supplier;

/**
 * Decides what one user edit does to completion (MYT-424): keep completing the word, start a new
 * completion on a trigger character, re-rank after a deletion, or cancel.
 *
 * <ul>
 *   <li>A word character continues the open session; without one it starts a session only in
 *       {@link LexicalContext.Kind#CODE} or {@link LexicalContext.Kind#IMPORT_PATH}, never in a comment or
 *       a plain string.</li>
 *   <li>Any other character is a trigger only if the server advertised it, and only where it means
 *       something: {@code :} only as the second colon of {@code ::} in code; {@code "} and {@code /} only
 *       inside an import path (a closing quote lands in code, and {@code /} in code is division);
 *       {@code @} in code (annotations) and in import paths ({@code @alias}); the rest, i.e. {@code .},
 *       in code only, interpolation holes included.</li>
 *   <li>Deleting one character re-ranks while the caret is still in the session's word.</li>
 *   <li>Anything else (paste, undo, a multi-character edit, a newline) cancels.</li>
 * </ul>
 * An active snippet changes nothing: typing over a selected placeholder is one character inserted, so it
 * completes like any other typing. Ctrl+Space is not an edit and always requests, wherever the caret is.
 *
 * <p>Pure (no JavaFX), so it is unit-tested directly.
 */
final class CompletionTriggerPolicy {

    enum Action {
        /** Complete the word at the caret: keep (or open) its session, re-rank now, re-request. */
        CONTINUE,
        /** A trigger character: a new session at the caret whose request says TriggerCharacter. */
        START,
        /** A character was deleted inside the session's word: re-rank now, re-request. */
        RERANK,
        /** No completion here: drop the session. */
        CANCEL
    }

    /** {@code triggerCharacter} is set only for {@link Action#START}; every other request is Invoked. */
    record Decision(Action action, String triggerCharacter) {
        static final Decision CONTINUE = new Decision(Action.CONTINUE, null);
        static final Decision RERANK = new Decision(Action.RERANK, null);
        static final Decision CANCEL = new Decision(Action.CANCEL, null);

        static Decision start(String triggerCharacter) {
            return new Decision(Action.START, triggerCharacter);
        }
    }

    private CompletionTriggerPolicy() {}

    /**
     * @param removed        the text the edit replaced ("" for plain typing; the selection when typing over one)
     * @param inserted       the text the edit inserted
     * @param before         the source character before the inserted one, or {@code '\0'} at a line start
     * @param contextAfter   the lexical context at the caret after the edit; only evaluated when a rule needs it
     * @param serverTriggers the single-character trigger characters the server advertised (may be empty)
     * @param inSession      a completion session is open and the caret, after the edit, is still in its word
     */
    static Decision onEdit(String removed, String inserted, char before,
                           Supplier<LexicalContext.Kind> contextAfter, Set<String> serverTriggers,
                           boolean inSession) {
        if (inserted.length() == 1) {
            return onTyped(inserted.charAt(0), before, contextAfter, serverTriggers, inSession);
        }
        if (inserted.isEmpty() && removed.length() == 1) {
            return inSession ? Decision.RERANK : Decision.CANCEL;
        }
        return Decision.CANCEL;
    }

    private static Decision onTyped(char typed, char before, Supplier<LexicalContext.Kind> contextAfter,
                                    Set<String> serverTriggers, boolean inSession) {
        if (Words.isWordChar(typed)) {
            if (inSession) return Decision.CONTINUE;
            LexicalContext.Kind context = contextAfter.get();
            return context == LexicalContext.Kind.CODE || context == LexicalContext.Kind.IMPORT_PATH
                    ? Decision.CONTINUE : Decision.CANCEL;
        }
        String trigger = String.valueOf(typed);
        if (!serverTriggers.contains(trigger)) return Decision.CANCEL;
        LexicalContext.Kind context = contextAfter.get();
        boolean fires = switch (typed) {
            case ':' -> context == LexicalContext.Kind.CODE && before == ':';
            case '"', '/' -> context == LexicalContext.Kind.IMPORT_PATH;
            case '@' -> context == LexicalContext.Kind.CODE || context == LexicalContext.Kind.IMPORT_PATH;
            default -> context == LexicalContext.Kind.CODE;
        };
        return fires ? Decision.start(trigger) : Decision.CANCEL;
    }
}
