package org.mtype.editor.ui.editor;

import org.junit.jupiter.api.Test;
import org.mtype.editor.ui.editor.CompletionTriggerPolicy.Action;
import org.mtype.editor.ui.editor.CompletionTriggerPolicy.Decision;

import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** MYT-424: which edits open, keep, re-rank or cancel completion. */
class CompletionTriggerPolicyTest {

    /** What the mType language server advertises (MTypeLanguageServer.cpp, completionProvider). */
    private static final Set<String> SERVER = Set.of(".", ":", "\"", "/", "@");

    private static final Supplier<LexicalContext.Kind> NOT_NEEDED = () -> {
        throw new AssertionError("the lexical context must not be computed for this edit");
    };

    /** The decision for having just typed the character before '|' in {@code marked}. */
    private static Decision typed(String marked, boolean inSession, Set<String> triggers) {
        int caret = marked.indexOf('|');
        String source = marked.replace("|", "");
        String inserted = String.valueOf(source.charAt(caret - 1));
        char before = caret >= 2 ? source.charAt(caret - 2) : '\0';
        return CompletionTriggerPolicy.onEdit("", inserted, before,
                () -> LexicalContext.at(source, caret), triggers, inSession);
    }

    private static Decision typed(String marked) {
        return typed(marked, false, SERVER);
    }

    private static void assertStart(String trigger, Decision decision) {
        assertEquals(Action.START, decision.action());
        assertEquals(trigger, decision.triggerCharacter());
    }

    private static void assertAction(Action action, Decision decision) {
        assertEquals(action, decision.action());
        assertNull(decision.triggerCharacter(), "only START reports a trigger character");
    }

    /* ----- identifier typing ----- */

    @Test
    void letterRequestsCompletion() {
        assertAction(Action.CONTINUE, typed("d|"));
        assertAction(Action.CONTINUE, typed("x = fo|"));
        assertAction(Action.CONTINUE, typed("d.b|"));
    }

    @Test
    void letterInOpenSessionContinuesWithoutScanning() {
        assertAction(Action.CONTINUE,
                CompletionTriggerPolicy.onEdit("", "a", 'b', NOT_NEEDED, SERVER, true));
    }

    @Test
    void letterInCommentOrStringDoesNotStartASession() {
        assertAction(Action.CANCEL, typed("// d|"));
        assertAction(Action.CANCEL, typed("/* d|"));
        assertAction(Action.CANCEL, typed("\"d|"));
    }

    @Test
    void letterInCommentContinuesASessionOpenedWithCtrlSpace() {
        assertAction(Action.CONTINUE, typed("// d|", true, SERVER));
    }

    @Test
    void letterInImportPathStartsASession() {
        assertAction(Action.CONTINUE, typed("import { A } from \"./mo|"));
    }

    /* ----- trigger characters ----- */

    @Test
    void dotTriggers() {
        assertStart(".", typed("d.|"));
        assertStart(".", typed("this.items.|"));
    }

    @Test
    void doubleColonTriggers() {
        assertStart(":", typed("Dog::|"));
    }

    @Test
    void singleColonDoesNotTrigger() {
        assertAction(Action.CANCEL, typed("x:|"));
        assertAction(Action.CANCEL, typed("function f(a:|"));
        assertAction(Action.CANCEL, typed("x = a ? b :|"));
    }

    @Test
    void atTriggersInCode() {
        assertStart("@", typed("@|"));
        assertStart("@", typed("class A {\n    @|"));
    }

    @Test
    void commentsDoNotTrigger() {
        assertAction(Action.CANCEL, typed("// d.|"));
        assertAction(Action.CANCEL, typed("/* d.|"));
        assertAction(Action.CANCEL, typed("/** @|"));
        assertAction(Action.CANCEL, typed("// Dog::|"));
    }

    @Test
    void codeAfterAClosedCommentTriggers() {
        assertStart(".", typed("/* x */ d.|"));
        assertStart(".", typed("// x\nd.|"));
    }

    @Test
    void stringsDoNotTrigger() {
        assertAction(Action.CANCEL, typed("\"d.|"));
        assertAction(Action.CANCEL, typed("x = \"a@|"));
        assertAction(Action.CANCEL, typed("x = \"a/|"));
        assertAction(Action.CANCEL, typed("x = \"|"));
    }

    @Test
    void importPathStringTriggers() {
        assertStart("\"", typed("import { A } from \"|"));
        assertStart("/", typed("import { A } from \"./|"));
        assertStart("\"", typed("import lib \"|"));
        assertStart("@", typed("import { A } from \"@|"));
    }

    @Test
    void dotInImportPathDoesNotTrigger() {
        assertAction(Action.CANCEL, typed("import { A } from \".|"));
    }

    @Test
    void closingQuoteDoesNotTrigger() {
        assertAction(Action.CANCEL, typed("x = \"abc\"|"));
        assertAction(Action.CANCEL, typed("import { A } from \"./A.mt\"|"));
    }

    @Test
    void slashInCodeDoesNotTrigger() {
        assertAction(Action.CANCEL, typed("a /|"));
        assertAction(Action.CANCEL, typed("a //|"));
    }

    @Test
    void interpolationHoleTriggers() {
        assertStart(".", typed("$\"{d.|"));
        assertAction(Action.CANCEL, typed("$\"text d.|"));
    }

    @Test
    void characterTheServerDoesNotAdvertiseDoesNotTrigger() {
        assertAction(Action.CANCEL, typed("d.|", false, Set.of()));
        assertAction(Action.CANCEL, typed("@|", false, Set.of(".")));
        assertAction(Action.CANCEL, typed("f(|"));
    }

    @Test
    void notAdvertisedCharacterIsRejectedWithoutScanning() {
        assertAction(Action.CANCEL, CompletionTriggerPolicy.onEdit("", "(", 'f', NOT_NEEDED, SERVER, false));
        assertAction(Action.CANCEL, CompletionTriggerPolicy.onEdit("", " ", 'd', NOT_NEEDED, SERVER, true));
    }

    /* ----- deleting ----- */

    @Test
    void backspaceWhilePopupOpenReranks() {
        assertAction(Action.RERANK, CompletionTriggerPolicy.onEdit("r", "", 'a', NOT_NEEDED, SERVER, true));
    }

    @Test
    void backspacePastTheWordCancels() {
        assertAction(Action.CANCEL, CompletionTriggerPolicy.onEdit(".", "", 'd', NOT_NEEDED, SERVER, false));
        assertAction(Action.CANCEL, CompletionTriggerPolicy.onEdit("a", "", 'b', NOT_NEEDED, SERVER, false));
    }

    /* ----- snippets ----- */

    @Test
    void activeSnippetStillRequestsCompletion() {
        // "print(${1:value}, ${2:x})": typing "n" over the selected "value" is one character inserted.
        String afterTyping = "print(n, x)";
        assertAction(Action.CONTINUE, CompletionTriggerPolicy.onEdit("value", "n", '(',
                () -> LexicalContext.at(afterTyping, 7), SERVER, false));
        // And a member access typed inside the placeholder triggers as usual.
        String afterDot = "print(d., x)";
        assertStart(".", CompletionTriggerPolicy.onEdit("", ".", 'd',
                () -> LexicalContext.at(afterDot, 8), SERVER, false));
    }

    /* ----- everything else ----- */

    @Test
    void pasteUndoAndMultiCharacterEditsCancel() {
        assertAction(Action.CANCEL, CompletionTriggerPolicy.onEdit("", "foo.bar", 'x', NOT_NEEDED, SERVER, true));
        assertAction(Action.CANCEL, CompletionTriggerPolicy.onEdit("ab", "", 'x', NOT_NEEDED, SERVER, true));
        assertAction(Action.CANCEL, CompletionTriggerPolicy.onEdit("", "", 'x', NOT_NEEDED, SERVER, true));
    }

    @Test
    void newlineCancels() {
        assertAction(Action.CANCEL, CompletionTriggerPolicy.onEdit("", "\n", 'd', NOT_NEEDED, SERVER, true));
    }
}
