package org.mtype.editor.ui.editor;

import org.junit.jupiter.api.Test;
import org.mtype.editor.ui.editor.SnippetSession.Selection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MYT-424: a snippet completed inside a snippet nests, and the outer one resumes after it. */
class SnippetStackTest {

    /**
     * Outer {@code print(${1:value}, ${2:x})} at 100, then "value" replaced by accepting the snippet
     * {@code foo(${1:a})}: outer placeholder 1 = [106,112) "foo(a)", outer 2 = [114,115), outer final 116;
     * inner placeholder 1 = [110,111) "a", inner final 112.
     */
    private static SnippetStack nested() {
        SnippetStack stack = new SnippetStack();
        stack.push(SnippetSession.parse("print(${1:value}, ${2:x})").shift(100));
        stack.onEdit(106, 5, 6, false); // the accept's main edit, seen by the outer snippet
        stack.push(SnippetSession.parse("foo(${1:a})").shift(106));
        return stack;
    }

    @Test
    void innerSnippetOwnsTabThenTheOuterResumes() {
        SnippetStack stack = nested();

        assertEquals(new Selection(112, 112, true), stack.advance(111)); // inner done: caret after "foo(a)"
        assertTrue(stack.isActive());
        assertEquals(new Selection(114, 115, false), stack.advance(112)); // outer placeholder 2: "x"
        assertEquals(new Selection(116, 116, true), stack.advance(115));
        assertFalse(stack.isActive());
    }

    @Test
    void outerTracksTypingInsideTheInnerSnippet() {
        SnippetStack stack = nested();

        stack.onEdit(111, 0, 2, false); // "bc" typed after "a" in the inner placeholder

        assertEquals(new Selection(114, 114, true), stack.advance(113));
        assertEquals(new Selection(116, 117, false), stack.advance(114));
    }

    @Test
    void breakingTheOuterSnippetEndsTheInnerToo() {
        SnippetStack stack = nested();

        stack.onEdit(111, 4, 0, false); // from inside "foo(a)" past the start of the outer "x"

        assertFalse(stack.isActive());
        assertNull(stack.advance(111));
    }

    @Test
    void caretOutsideTheInnerSnippetFallsBackToTheOuter() {
        SnippetStack stack = nested();

        // 113 is past the inner snippet (it ends at 112) but in the ", " before the outer "x": the inner
        // snippet is dropped and the outer one moves on.
        assertEquals(new Selection(114, 115, false), stack.advance(113));
        assertTrue(stack.isActive());
    }

    @Test
    void emptyStackDoesNothing() {
        SnippetStack stack = new SnippetStack();

        assertFalse(stack.isActive());
        assertNull(stack.advance(0));
        stack.onEdit(0, 0, 1, false);
        assertFalse(stack.isActive());
    }
}
