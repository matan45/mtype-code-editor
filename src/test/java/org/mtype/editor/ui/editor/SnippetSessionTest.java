package org.mtype.editor.ui.editor;

import org.junit.jupiter.api.Test;
import org.mtype.editor.ui.editor.SnippetSession.Selection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MYT-424: snippet tab stops follow every document edit, so completing inside a placeholder keeps them. */
class SnippetSessionTest {

    /**
     * {@code print(value, x)} inserted at offset 100: placeholder 1 = [106,111) "value",
     * placeholder 2 = [113,114) "x", final caret 115 (no {@code $0}: the end).
     */
    private static SnippetSession print() {
        SnippetSession session = SnippetSession.parse("print(${1:value}, ${2:x})").shift(100);
        assertEquals("print(value, x)", session.text());
        assertEquals(new Selection(106, 111, false), session.firstSelection());
        return session;
    }

    @Test
    void untouchedSnippetAdvancesThroughItsStops() {
        SnippetSession session = print();

        assertEquals(new Selection(113, 114, false), session.advance(111));
        assertEquals(new Selection(115, 115, true), session.advance(114));
    }

    @Test
    void typingOverSelectedPlaceholderShrinksItAndShiftsLaterStops() {
        SnippetSession session = print();

        assertTrue(session.onEdit(106, 5, 1, false)); // "n" replaces the selected "value"

        assertEquals(new Selection(109, 110, false), session.advance(107));
        assertEquals(new Selection(111, 111, true), session.advance(110));
    }

    @Test
    void insertAtEitherEdgeOfTheCurrentPlaceholderGrowsIt() {
        SnippetSession session = print();

        assertTrue(session.onEdit(106, 0, 2, false)); // at its start
        assertTrue(session.onEdit(113, 0, 3, false)); // at its (new) end

        assertEquals(new Selection(118, 119, false), session.advance(116));
    }

    @Test
    void adjacentNextPlaceholderShiftsInsteadOfGrowing() {
        SnippetSession session = SnippetSession.parse("${1:a}${2:b}");

        assertTrue(session.onEdit(1, 0, 1, false)); // typed at the end of "a", which is also the start of "b"

        assertEquals(new Selection(2, 3, false), session.advance(2));
        assertEquals(new Selection(3, 3, true), session.advance(3));
    }

    @Test
    void hintOnlyEditsNeverGrowAPlaceholder() {
        SnippetSession session = print();

        assertTrue(session.onEdit(106, 0, 1, true)); // a hint at the start: placeholder 1 moves to [107,112)
        assertTrue(session.onEdit(112, 0, 1, true)); // a hint at its end: it doesn't grow

        assertEquals(new Selection(115, 116, false), session.advance(112));
        assertEquals(new Selection(117, 117, true), session.advance(116));
    }

    @Test
    void editBeforeTheSnippetShiftsEverything() {
        SnippetSession session = print();

        assertTrue(session.onEdit(0, 0, 25, false)); // e.g. an auto-import at the top

        assertEquals(new Selection(138, 139, false), session.advance(136));
    }

    @Test
    void editAfterTheSnippetChangesNothing() {
        SnippetSession session = print();

        assertTrue(session.onEdit(200, 0, 5, false));

        assertEquals(new Selection(113, 114, false), session.advance(111));
        assertEquals(new Selection(115, 115, true), session.advance(114));
    }

    @Test
    void editAcrossAPlaceholderBoundaryBreaksTheSession() {
        assertFalse(print().onEdit(110, 4, 0, false)); // "e, x" spans the end of 1 and the start of 2
        assertFalse(print().onEdit(112, 2, 0, false)); // " x": into placeholder 2 from before it
    }

    @Test
    void caretOutsideTheSnippetEndsIt() {
        assertNull(print().advance(50));
        assertNull(print().advance(116));
    }

    @Test
    void containsCoversTheCurrentStopToTheLast() {
        SnippetSession session = print();

        assertFalse(session.contains(105));
        assertTrue(session.contains(106));
        assertTrue(session.contains(115));
        assertFalse(session.contains(116));
    }

    /**
     * The ticket's case: in {@code print(${1:value}, ${2:x})}, type "ne" over "value", accept
     * {@code newValue} — whose auto-import adds 25 characters at offset 0 — and Tab still selects "x".
     */
    @Test
    void completionWithAutoImportInsidePlaceholderKeepsTheNextStop() {
        SnippetSession session = print();

        assertTrue(session.onEdit(106, 5, 1, false)); // "n" over "value"
        assertTrue(session.onEdit(107, 0, 1, false)); // "e"
        assertTrue(session.onEdit(106, 2, 8, false)); // accept: "ne" -> "newValue" (the main edit comes first)
        assertTrue(session.onEdit(0, 0, 25, false));  // then the import above

        assertEquals(new Selection(141, 142, false), session.advance(139)); // caret after "newValue"
        assertEquals(new Selection(143, 143, true), session.advance(142));
    }
}
