package org.mtype.editor.terminal;

import org.mtype.editor.app.AppContext;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class TerminalController {
    private final AppContext ctx;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final List<TerminalSession> sessions = new CopyOnWriteArrayList<>();

    public TerminalController(AppContext ctx) {
        this.ctx = ctx;
    }

    public TerminalSession openSession(
            Consumer<TerminalSession.OutputChunk> outputSink,
            Consumer<Integer> exitSink) throws IOException {
        int id = nextId.getAndIncrement();
        Path cwd = ctx.getWorkspace() != null
                ? ctx.getWorkspace().root()
                : Path.of(System.getProperty("user.dir"));
        TerminalSession session = new TerminalSession(id, cwd, outputSink, code -> {
            sessions.removeIf(s -> s.id() == id);
            exitSink.accept(code);
        });
        session.start();
        sessions.add(session);
        return session;
    }

    public void closeSession(TerminalSession session) {
        if (session == null) return;
        sessions.removeIf(s -> s.id() == session.id());
        session.close();
    }

    public void shutdownAll() {
        for (TerminalSession session : List.copyOf(sessions)) {
            closeSession(session);
        }
    }
}
