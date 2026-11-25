package org.mtype.editor.lsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DocumentVersionsTest {
    private static final String A = "file:///a.mt";
    private static final String B = "file:///b.mt";

    private final DocumentVersions versions = new DocumentVersions();

    @Test
    void unknownUriAcceptsAndRecords() {
        assertTrue(versions.acceptChange(A, 3));
        assertEquals(3, versions.lastSent(A));
    }

    @Test
    void newerVersionAccepted() {
        versions.opened(A, 1);
        assertTrue(versions.acceptChange(A, 2));
        assertTrue(versions.acceptChange(A, 7)); // gaps are fine: the editor bumps per edit, sends per debounce
        assertEquals(7, versions.lastSent(A));
    }

    @Test
    void equalVersionRejected() {
        versions.opened(A, 1);
        assertTrue(versions.acceptChange(A, 2));
        assertFalse(versions.acceptChange(A, 2));
        assertEquals(2, versions.lastSent(A));
    }

    @Test
    void olderVersionRejected() {
        versions.opened(A, 1);
        assertTrue(versions.acceptChange(A, 5));
        assertFalse(versions.acceptChange(A, 4));
        assertFalse(versions.acceptChange(A, 1));
        assertEquals(5, versions.lastSent(A));
    }

    @Test
    void openResetsBaselineDownward() {
        // DiffTab's temporary open (its own counter) followed by an editor tab opening the file at v1.
        versions.opened(A, 7);
        versions.opened(A, 1);
        assertEquals(1, versions.lastSent(A));
        assertTrue(versions.acceptChange(A, 2));
    }

    @Test
    void closeForgetsBaseline() {
        versions.opened(A, 1);
        assertTrue(versions.acceptChange(A, 9));
        versions.closed(A);
        assertNull(versions.lastSent(A));
        assertTrue(versions.acceptChange(A, 2));
    }

    @Test
    void urisAreIndependent() {
        versions.opened(A, 5);
        versions.opened(B, 1);
        assertTrue(versions.acceptChange(B, 2));
        assertFalse(versions.acceptChange(A, 2));
        assertEquals(5, versions.lastSent(A));
        assertEquals(2, versions.lastSent(B));
    }
}
