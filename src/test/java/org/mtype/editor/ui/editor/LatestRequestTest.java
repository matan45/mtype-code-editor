package org.mtype.editor.ui.editor;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class LatestRequestTest {
    private final LatestRequest<String> latest = new LatestRequest<>();

    @Test
    void beginCancelsPrevious() {
        CompletableFuture<String> first = new CompletableFuture<>();
        CompletableFuture<String> second = new CompletableFuture<>();
        long firstToken = latest.begin(first);
        long secondToken = latest.begin(second);

        assertTrue(first.isCancelled());
        assertFalse(second.isCancelled());
        assertFalse(latest.isCurrent(firstToken));
        assertTrue(latest.isCurrent(secondToken));
    }

    @Test
    void cancelInvalidatesTokenAndCancelsFuture() {
        CompletableFuture<String> request = new CompletableFuture<>();
        long token = latest.begin(request);

        latest.cancel();

        assertTrue(request.isCancelled());
        assertFalse(latest.isCurrent(token));
    }

    @Test
    void completedThenCancelledIsNotCurrent() {
        // The response completed on the LSP thread and its handler is queued on the FX thread, then the
        // FX thread cancels: the handler must see the request is no longer current.
        CompletableFuture<String> request = new CompletableFuture<>();
        long token = latest.begin(request);
        request.complete("items");
        assertTrue(latest.isCurrent(token));

        latest.cancel();

        assertFalse(latest.isCurrent(token));
        assertFalse(request.isCancelled());
        assertEquals("items", request.getNow(null));
    }

    @Test
    void cancelWithNothingInFlightIsNoop() {
        latest.cancel();
        assertFalse(latest.isPending());
        CompletableFuture<String> request = new CompletableFuture<>();
        long token = latest.begin(request);
        assertTrue(latest.isCurrent(token));
        assertFalse(request.isCancelled());
    }

    @Test
    void isPendingTracksState() {
        assertFalse(latest.isPending());
        CompletableFuture<String> request = new CompletableFuture<>();
        latest.begin(request);
        assertTrue(latest.isPending());
        request.complete("done");
        assertFalse(latest.isPending());
        latest.begin(new CompletableFuture<>());
        assertTrue(latest.isPending());
        latest.cancel();
        assertFalse(latest.isPending());
    }
}
