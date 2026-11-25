package org.mtype.editor.lsp;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.jsonrpc.MessageConsumer;
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint;
import org.eclipse.lsp4j.jsonrpc.messages.CancelParams;
import org.eclipse.lsp4j.jsonrpc.messages.Message;
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage;
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage;
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MYT-422 at the JSON-RPC message level: a real lsp4j {@link RemoteEndpoint} proxy (no process, no
 * streams) records every message LspBridge writes, and on which thread.
 */
class LspBridgeWireTest {
    private static final String OUT_THREAD = "test-lsp-out";

    private final List<Message> wire = Collections.synchronizedList(new ArrayList<>());
    private final List<String> writers = Collections.synchronizedList(new ArrayList<>());

    private void awaitMessages(int count) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (wire.size() < count && System.nanoTime() < deadline) Thread.sleep(5);
        assertEquals(count, wire.size(), "messages on the wire: " + wire);
    }

    @Test
    void cancelWritesCancelRequestOnTheOutThread() throws Exception {
        MessageConsumer recorder = message -> {
            wire.add(message);
            writers.add(Thread.currentThread().getName());
        };
        RemoteEndpoint remote = new RemoteEndpoint(recorder, ServiceEndpoints.toEndpoint(new Object()));
        LanguageServer proxy = ServiceEndpoints.toServiceObject(remote, LanguageServer.class);
        ExecutorService out = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, OUT_THREAD);
            t.setDaemon(true);
            return t;
        });
        try {
            LspBridge bridge = new LspBridge(proxy, out, line -> { });
            Path file = Path.of("Main.mt").toAbsolutePath();

            bridge.didChange(file, "d.", 2);
            CompletableFuture<List<CompletionItem>> result = bridge.completion(file, 0, 2, ".");
            awaitMessages(2);
            assertTrue(result.cancel(true));
            awaitMessages(3);

            NotificationMessage change = assertInstanceOf(NotificationMessage.class, wire.get(0));
            assertEquals("textDocument/didChange", change.getMethod());
            RequestMessage request = assertInstanceOf(RequestMessage.class, wire.get(1));
            assertEquals("textDocument/completion", request.getMethod());
            NotificationMessage cancel = assertInstanceOf(NotificationMessage.class, wire.get(2));
            assertEquals("$/cancelRequest", cancel.getMethod());
            CancelParams params = assertInstanceOf(CancelParams.class, cancel.getParams());
            assertEquals(request.getId(), params.getId());

            assertEquals(List.of(OUT_THREAD, OUT_THREAD, OUT_THREAD), writers,
                    "every message, the cancel included, is written by the outgoing thread");
        } finally {
            out.shutdownNow();
        }
    }
}
