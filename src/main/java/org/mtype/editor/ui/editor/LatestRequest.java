package org.mtype.editor.ui.editor;

import java.util.concurrent.CompletableFuture;

/**
 * The one LSP request of a kind (completion, signature help) a controller still wants an answer to.
 * Starting a new one cancels the previous; {@link #cancel()} drops the current one.
 *
 * <p>Hold the future {@code LspBridge} returned, not a {@code thenAcceptAsync} stage: cancelling a
 * dependent stage does not reach its source, so the server request would never be cancelled. And
 * check {@link #isCurrent} before applying a response: it may complete on the LSP thread just before
 * a cancel on the FX thread, with its handler already queued.
 *
 * <p>FX thread only.
 */
final class LatestRequest<T> {
    private CompletableFuture<T> current;
    private long serial;

    /** Track {@code request} as the current one, cancelling the previous; returns its token. */
    long begin(CompletableFuture<T> request) {
        cancel();
        current = request;
        return serial;
    }

    /** Whether the request {@code token} came from is still the current one. */
    boolean isCurrent(long token) {
        return current != null && token == serial;
    }

    /** Drop the current request (cancelling it when it hasn't completed). No-op when there is none. */
    void cancel() {
        serial++;
        CompletableFuture<T> request = current;
        current = null;
        if (request != null && !request.isDone()) request.cancel(true);
    }

    /** Whether a current request hasn't completed yet. */
    boolean isPending() {
        return current != null && !current.isDone();
    }
}
