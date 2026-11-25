package org.mtype.editor.lsp;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MYT-422: LspBridge writes every message from one ordered outgoing executor, drops stale didChange
 * versions, and lets callers cancel requests. A {@link ManualExecutor} stands in for the outgoing
 * thread, so the test sees exactly what was queued and when it reaches the (fake) server.
 */
class LspBridgeTest {
    private final FakeLanguageServer server = new FakeLanguageServer();
    private final ManualExecutor out = new ManualExecutor();
    private final List<String> bridgeLog = Collections.synchronizedList(new ArrayList<>());
    private final LspBridge bridge = new LspBridge(server, out, bridgeLog::add);
    private final Path file = Path.of("Main.mt").toAbsolutePath();

    private static Either<List<CompletionItem>, CompletionList> items(String... labels) {
        List<CompletionItem> list = new ArrayList<>();
        for (String label : labels) list.add(new CompletionItem(label));
        return Either.forLeft(list);
    }

    /** Open the document and deliver everything queued so far, then forget the recorded traffic. */
    private void openAt(int version) {
        bridge.didOpen(file, "", version);
        out.runAll();
        server.log.clear();
    }

    @Test
    void nothingIsWrittenOnTheCallingThread() {
        bridge.didOpen(file, "a", 1);
        bridge.didChange(file, "ab", 2);
        bridge.completion(file, 0, 2, null);

        assertTrue(server.log.isEmpty(), "the caller must only queue: " + server.log);
        assertEquals(3, out.size());

        out.runAll();
        assertEquals(List.of("didOpen:1", "didChange:2", "completion"), server.log);
    }

    @Test
    void flushThenCompleteSendsDidChangeBeforeCompletion() {
        openAt(1);

        // What EditorTab does: flushPendingDidChange() queues the pending didChange, then the request.
        bridge.didChange(file, "Dog d = new Dog();\nd.", 5);
        CompletableFuture<List<CompletionItem>> result = bridge.completion(file, 1, 2, ".");
        out.runAll();

        assertEquals(List.of("didChange:5", "completion"), server.log);
        server.completions.getFirst().complete(items("bark"));
        assertEquals("bark", result.getNow(null).getFirst().getLabel());
    }

    @Test
    void staleDidChangeIsDropped() {
        openAt(1);

        bridge.didChange(file, "v5", 5);
        bridge.didChange(file, "v4", 4);
        bridge.didChange(file, "v5 again", 5);
        out.runAll();

        assertEquals(List.of("didChange:5"), server.log);
        assertEquals(2, bridgeLog.size(), "both stale changes are reported: " + bridgeLog);
        assertTrue(bridgeLog.getFirst().contains("dropped stale didChange v4"), bridgeLog.getFirst());
        assertTrue(bridgeLog.getFirst().contains("last sent v5"), bridgeLog.getFirst());
    }

    @Test
    void didOpenResetsBaseline() {
        bridge.didOpen(file, "temp", 7);
        bridge.didChange(file, "temp 2", 8);
        bridge.didOpen(file, "editor", 1);   // e.g. an editor tab opening a file DiffTab had open
        bridge.didChange(file, "editor 2", 2);
        out.runAll();

        assertEquals(List.of("didOpen:7", "didChange:8", "didOpen:1", "didChange:2"), server.log);
        assertTrue(bridgeLog.isEmpty(), bridgeLog.toString());
    }

    @Test
    void didCloseForgetsBaseline() {
        openAt(1);
        bridge.didChange(file, "v5", 5);
        bridge.didClose(file);
        bridge.didChange(file, "v3", 3);
        out.runAll();

        assertEquals(List.of("didChange:5", "didClose", "didChange:3"), server.log);
    }

    @Test
    void completionCancelledBeforeSendNeverReachesServer() {
        openAt(1);
        CompletableFuture<List<CompletionItem>> result = bridge.completion(file, 0, 0, null);

        assertTrue(result.cancel(true));
        out.runAll();

        assertTrue(server.log.isEmpty(), "a cancelled request must not be written: " + server.log);
        assertTrue(server.completions.isEmpty());
        assertTrue(result.isCancelled());
    }

    @Test
    void completionCancelledAfterSendCancelsServerRequest() {
        openAt(1);
        CompletableFuture<List<CompletionItem>> result = bridge.completion(file, 0, 0, null);
        out.runAll();
        CompletableFuture<Either<List<CompletionItem>, CompletionList>> sent = server.completions.getFirst();

        result.cancel(true);
        assertFalse(sent.isCancelled(), "the cancel is written by the outgoing executor, not the caller");
        out.runAll();

        // Cancelling lsp4j's request future is what writes $/cancelRequest (see LspBridgeWireTest).
        assertTrue(sent.isCancelled());
        assertTrue(result.isCancelled());
    }

    @Test
    void responseAfterCancelIsIgnored() {
        openAt(1);
        CompletableFuture<List<CompletionItem>> result = bridge.completion(file, 0, 0, null);
        out.runAll();
        CompletableFuture<Either<List<CompletionItem>, CompletionList>> sent = server.completions.getFirst();

        result.cancel(true);
        sent.complete(items("late"));   // the response arrives before the queued cancel runs
        out.runAll();

        assertTrue(result.isCancelled());
        assertFalse(sent.isCancelled(), "nothing to cancel once the response is in");
    }

    @Test
    void serverErrorAndThrowingSendFallBack() {
        openAt(1);
        CompletableFuture<List<CompletionItem>> failed = bridge.completion(file, 0, 0, null);
        out.runAll();
        server.completions.getFirst().completeExceptionally(new RuntimeException("server error"));
        assertEquals(List.of(), failed.getNow(null));

        server.completionThrows = new IllegalStateException("stream closed");
        CompletableFuture<List<CompletionItem>> thrown = bridge.completion(file, 0, 0, null);
        out.runAll();
        assertEquals(List.of(), thrown.getNow(null));
        assertTrue(bridgeLog.stream().anyMatch(line -> line.contains("request failed")), bridgeLog.toString());

        CompletableFuture<String> hover = bridge.hover(file, 0, 0);   // not implemented by the fake
        out.runAll();
        assertEquals("", hover.getNow(null));
    }

    @Test
    void rejectedExecutionCompletesWithFallback() {
        out.rejecting = true;

        CompletableFuture<List<CompletionItem>> result = bridge.completion(file, 0, 0, null);
        bridge.didChange(file, "x", 2);   // must not throw either

        assertTrue(result.isDone(), "a request that can't be queued must still complete (save() waits on format)");
        assertEquals(List.of(), result.getNow(null));
        assertTrue(server.log.isEmpty());
    }

    @Test
    void stopDeliversQueuedNotificationsAndAnswersUnsentRequestsLocally() throws Exception {
        openAt(1);
        bridge.didClose(file);   // e.g. every tab closed on exit, just before stop()
        CompletableFuture<List<CompletionItem>> unsent = bridge.completion(file, 0, 0, null);

        Thread stopper = new Thread(bridge::stop, "test-stop");
        stopper.start();
        // stop() queues the shutdown/exit teardown behind the two tasks, then waits for it.
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (out.size() < 3 && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(3, out.size());
        out.runAll();
        stopper.join(5_000);
        assertFalse(stopper.isAlive());

        assertEquals(List.of("didClose", "shutdown", "exit"), server.log);
        assertEquals(List.of(), unsent.getNow(null));
        assertFalse(bridge.isReady());

        CompletableFuture<List<CompletionItem>> afterStop = bridge.completion(file, 0, 0, null);
        bridge.didChange(file, "x", 9);
        assertEquals(List.of(), afterStop.getNow(null));
        assertEquals(0, out.size(), "nothing is queued once stopped");
    }
}
