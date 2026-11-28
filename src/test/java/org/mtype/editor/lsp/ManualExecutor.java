package org.mtype.editor.lsp;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * An executor that only queues: the test decides when queued tasks run ({@link #runAll()}), on its own
 * thread, one at a time in submission order. Thread-safe, so another thread may queue meanwhile.
 */
final class ManualExecutor implements Executor {
    private final Deque<Runnable> tasks = new ArrayDeque<>();
    /** When set, {@link #execute} rejects every task, like a shut-down executor. */
    volatile boolean rejecting;

    @Override
    public synchronized void execute(Runnable task) {
        if (rejecting) throw new RejectedExecutionException("rejecting");
        tasks.add(task);
    }

    synchronized int size() {
        return tasks.size();
    }

    /** Run every queued task, including the ones they queue, in FIFO order. */
    void runAll() {
        while (true) {
            Runnable next;
            synchronized (this) {
                next = tasks.poll();
            }
            if (next == null) return;
            next.run();
        }
    }
}
