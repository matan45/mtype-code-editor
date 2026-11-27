package org.mtype.editor.ui.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionAnchorTest {

    @Test
    void wordStartScansBackOverWordChars() {
        CompletionAnchor anchor = CompletionAnchor.at("  foo.bar", 3, 9);

        assertEquals(new CompletionAnchor(3, 6), anchor);
        assertEquals("bar", anchor.prefix("  foo.bar", 9));
    }

    @Test
    void emptyPrefixRightAfterDot() {
        CompletionAnchor anchor = CompletionAnchor.at("d.", 0, 2);

        assertEquals(2, anchor.wordStartCol());
        assertEquals("", anchor.prefix("d.", 2));
    }

    @Test
    void digitsUnderscoreAndNonAsciiLettersAreWordChars() {
        assertEquals(1, CompletionAnchor.wordStart("(a_1é", 5));
    }

    @Test
    void containsWhileTypingMoreWordChars() {
        CompletionAnchor anchor = CompletionAnchor.at("d.b", 0, 3);

        assertTrue(anchor.contains("d.bazz", 0, 6));
        assertEquals("bazz", anchor.prefix("d.bazz", 6));
    }

    @Test
    void containsAfterBackspaceToEmptyPrefix() {
        CompletionAnchor anchor = CompletionAnchor.at("d.b", 0, 3);

        assertTrue(anchor.contains("d.", 0, 2));
        assertEquals("", anchor.prefix("d.", 2));
    }

    @Test
    void leavesWhenBackspacingPastWordStart() {
        CompletionAnchor anchor = CompletionAnchor.at("d.b", 0, 3);

        assertFalse(anchor.contains("d", 0, 1));
    }

    @Test
    void leavesOnNonWordChar() {
        CompletionAnchor anchor = CompletionAnchor.at("foo", 0, 3);

        assertFalse(anchor.contains("foo(", 0, 4));
        assertFalse(anchor.contains("foo ", 0, 4));
    }

    @Test
    void leavesOnOtherLine() {
        CompletionAnchor anchor = CompletionAnchor.at("foo", 0, 3);

        assertFalse(anchor.contains("foo", 1, 3));
    }

    @Test
    void leavesWhenCaretMovesLeftOfWordStart() {
        CompletionAnchor anchor = CompletionAnchor.at("x = foo", 0, 7);

        assertFalse(anchor.contains("x = foo", 0, 3));
    }

    @Test
    void staysWhenCaretMovesInsideWord() {
        CompletionAnchor anchor = CompletionAnchor.at("x = foo", 0, 7);

        assertTrue(anchor.contains("x = foo", 0, 5));
        assertEquals("f", anchor.prefix("x = foo", 5));
    }

    @Test
    void leavesWhenNewWordStartsAfterEmptyAnchor() {
        CompletionAnchor anchor = CompletionAnchor.at("d.", 0, 2);

        assertFalse(anchor.contains("d. b", 0, 4));
    }

    @Test
    void columnPastLineEndNotContained() {
        CompletionAnchor anchor = CompletionAnchor.at("foo", 0, 3);

        assertFalse(anchor.contains("foo", 0, 4));
    }

    @Test
    void wordEndStopsAtNonWordChar() {
        assertEquals(5, CompletionAnchor.wordEnd("print(x)", 2));
        assertEquals(5, CompletionAnchor.wordEnd("print(x)", 5));
    }
}
