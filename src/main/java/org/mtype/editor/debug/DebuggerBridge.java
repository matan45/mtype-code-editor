package org.mtype.editor.debug;

import javafx.application.Platform;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.process.StreamPump;
import org.mtype.editor.workspace.Workspace;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Owns the mType debug interpreter child process and translates the line-based
 * text protocol into typed events on {@link DebuggerEventBus}.
 *
 * Lifecycle, session counter, and FX-thread dispatch follow the LspBridge pattern.
 */
public class DebuggerBridge {
    private final AppContext ctx;
    private final DebuggerEventBus events;
    private final BreakpointService breakpoints;

    private Process process;
    private BufferedWriter writer;
    private long session;
    private boolean running;
    private Path lastFile;
    private DebuggerEventBus.State state = DebuggerEventBus.State.IDLE;

    public DebuggerBridge(AppContext ctx, DebuggerEventBus events, BreakpointService breakpoints) {
        this.ctx = ctx;
        this.events = events;
        this.breakpoints = breakpoints;
    }

    public synchronized boolean isRunning() { return running; }
    public synchronized long getSession() { return session; }
    public synchronized Path getLastFile() { return lastFile; }
    public synchronized DebuggerEventBus.State getState() { return state; }

    /* ============================== lifecycle ============================== */

    public synchronized void start(Path file) throws IOException {
        stop();
        long startSession = ++session;
        lastFile = file;

        String exe = resolveInterpreter();
        Workspace ws = ctx.getWorkspace();
        if (ws == null) throw new IOException("No workspace open");
        ctx.getOutputPane().appendDebugConsole("$ " + exe + " --debug " + file, "console");

        ProcessBuilder pb = new ProcessBuilder(exe, "--debug", file.toString())
                .directory(ws.root().toFile())
                .redirectErrorStream(false);
        process = pb.start();
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        running = true;
        setState(DebuggerEventBus.State.RUNNING);

        new StreamPump(process.getInputStream(),
                line -> handleProtocolLine(startSession, line),
                "mtype-dbg-stdout").start();
        new StreamPump(process.getErrorStream(),
                line -> handleStderrLine(startSession, line),
                "mtype-dbg-stderr").start();

        process.onExit().thenAccept(p -> {
            synchronized (DebuggerBridge.this) {
                if (startSession != session) return;
                running = false;
                setState(DebuggerEventBus.State.TERMINATED);
            }
            events.fireTerminated();
        });

        breakpoints.replayTo(this);
    }

    public synchronized void stop() {
        session++;
        running = false;
        Process p = process;
        BufferedWriter w = writer;
        process = null;
        writer = null;
        if (w != null) {
            try { writeRaw(w, "STOP"); } catch (Exception ignored) {}
            try { w.close(); } catch (Exception ignored) {}
        }
        if (p != null) {
            try {
                p.destroy();
                if (!p.waitFor(1, TimeUnit.SECONDS)) p.destroyForcibly();
            } catch (Exception ignored) {
                p.destroyForcibly();
            }
        }
        if (state != DebuggerEventBus.State.IDLE && state != DebuggerEventBus.State.TERMINATED) {
            setState(DebuggerEventBus.State.IDLE);
            events.fireTerminated();
        } else {
            setState(DebuggerEventBus.State.IDLE);
        }
    }

    /* ============================== commands ============================== */

    public void cont() { sendAndResume("CONTINUE", Map.of()); }
    public void stepOver() { sendAndResume("STEPOVER", Map.of()); }
    public void stepInto() { sendAndResume("STEPINTO", Map.of()); }
    public void stepOut() { sendAndResume("STEPOUT", Map.of()); }

    public void setBreakpoint(Path file, int line1) {
        send("SETBREAKPOINT", linked("file", file.toString(), "line", Integer.toString(line1)));
    }

    public void clearBreakpoint(Path file, int line1) {
        send("CLEARBREAKPOINT", linked("file", file.toString(), "line", Integer.toString(line1)));
    }

    public void clearAllBreakpoints() { send("CLEARALL", Map.of()); }

    public void getStackTrace() { send("GETSTACKTRACE", Map.of()); }

    public void getVariables(String scope) { send("GETVARIABLES", linked("scope", scope)); }

    public void expandVariable(long reference) { send("EXPANDVARIABLE", linked("ref", Long.toString(reference))); }

    /**
     * Sends EVALUATE. The server's RESULT doesn't echo a key, so the caller's
     * {@code requestKey} is stashed and surfaced on the next RESULT event.
     * Watch evaluation in {@link org.mtype.editor.ui.debug.DebuggerPanel} serializes
     * requests, so a single in-flight slot is sufficient for the first cut.
     */
    public void evaluate(String requestKey, String expression, int frame) {
        pendingEvaluateKey = requestKey == null ? "" : requestKey;
        send("EVALUATE", linked("expr", expression, "frame", Integer.toString(frame)));
    }

    private volatile String pendingEvaluateKey = "";

    /* ============================== I/O ============================== */

    private synchronized void send(String cmd, Map<String, String> params) {
        BufferedWriter w = writer;
        if (w == null) return;
        try {
            writeRaw(w, DebugProtocolCodec.encode(cmd, params));
        } catch (IOException ignored) {}
    }

    private synchronized void sendAndResume(String cmd, Map<String, String> params) {
        BufferedWriter w = writer;
        if (w == null) return;
        try {
            writeRaw(w, DebugProtocolCodec.encode(cmd, params));
            setState(DebuggerEventBus.State.RUNNING);
            events.fireResumed();
        } catch (IOException ignored) {}
    }

    private void writeRaw(BufferedWriter w, String line) throws IOException {
        w.write(line);
        w.write('\n');
        w.flush();
    }

    private static Map<String, String> linked(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    /* ============================== inbound ============================== */

    private void handleStderrLine(long startSession, String line) {
        if (startSession != session) return;
        // In --debug mode, stdout is the protocol channel and stderr carries print() output
        // plus internal logs. We route it all to the Debug Console.
        ctx.getOutputPane().appendDebugConsole(line, "stderr");
    }

    private void handleProtocolLine(long startSession, String line) {
        if (startSession != session) return;
        DebugMessage msg = DebugProtocolCodec.decode(line);
        String cmd = msg.command();
        if (cmd.isEmpty()) return;

        switch (cmd) {
            case "STARTED" -> {
                Path file = pathOrNull(msg.get("file"));
                synchronized (this) { setState(DebuggerEventBus.State.RUNNING); }
                events.fireStarted(new DebuggerEventBus.StartedEvent(file));
            }
            case "STOPPED" -> {
                Path file = pathOrNull(msg.get("file"));
                int line1 = msg.getInt("line", 0);
                String reason = msg.get("reason", "unknown");
                String message = msg.get("message", "");
                synchronized (this) { setState(DebuggerEventBus.State.PAUSED); }
                events.fireStopped(new DebuggerEventBus.StoppedEvent(file, line1 - 1, reason, message));
                getStackTrace();
                getVariables("local");
                getVariables("global");
            }
            case "TERMINATED" -> {
                synchronized (this) { setState(DebuggerEventBus.State.TERMINATED); }
                events.fireTerminated();
            }
            case "OUTPUT" -> {
                String text = msg.get("text", "");
                String category = msg.get("category", "stdout");
                events.fireOutput(new DebuggerEventBus.OutputEvent(text, category));
            }
            case "STACKTRACE" -> {
                List<Frame> frames = new ArrayList<>();
                for (int i = 0; ; i++) {
                    String s = msg.get("frame" + i);
                    if (s == null) break;
                    frames.add(Frame.parse(s));
                }
                events.fireStack(new DebuggerEventBus.StackEvent(frames));
            }
            case "VARIABLES" -> {
                List<Variable> vars = collectVariables(msg, "var");
                String scope = msg.get("scope", inferScopeFromVars(vars));
                events.fireVariables(new DebuggerEventBus.VariablesEvent(scope, vars));
            }
            case "EXPANDEDVAR" -> {
                List<Variable> vars = collectVariables(msg, "child");
                long ref = msg.getLong("ref", 0);
                events.fireExpandedVar(new DebuggerEventBus.ExpandedVarEvent(ref, vars));
            }
            case "RESULT" -> {
                String key = pendingEvaluateKey;
                pendingEvaluateKey = "";
                String value = msg.get("value", "");
                String type = msg.get("type", "");
                long ref = msg.getLong("ref", 0);
                events.fireEvaluate(new DebuggerEventBus.EvaluateResultEvent(key, value, type, ref));
            }
            case "ERROR" -> events.fireError(new DebuggerEventBus.ErrorEvent(msg.get("message", "unknown")));
            case "OK" -> { /* generic ack — nothing to do today */ }
            default -> { /* unknown message — log to console for diagnostics */
                ctx.getOutputPane().appendDebugConsole("[unhandled] " + line, "console");
            }
        }
    }

    private String inferScopeFromVars(List<Variable> ignored) { return "local"; }

    private List<Variable> collectVariables(DebugMessage msg, String prefix) {
        List<Variable> out = new ArrayList<>();
        for (int i = 0; ; i++) {
            String name = msg.get(prefix + i + "_name");
            if (name == null) {
                // Fall back to the combined form var0="name=value:type:ref" if the split form is absent
                String combined = msg.get(prefix + i);
                if (combined == null) break;
                out.add(parseCombinedVar(combined));
                continue;
            }
            String value = msg.get(prefix + i + "_value", "");
            String type = msg.get(prefix + i + "_type", "");
            long ref = msg.getLong(prefix + i + "_ref", 0);
            out.add(new Variable(name, value, type, ref));
        }
        return out;
    }

    private static Variable parseCombinedVar(String s) {
        int eq = s.indexOf('=');
        if (eq < 0) return new Variable(s, "", "", 0);
        String name = s.substring(0, eq);
        String rest = s.substring(eq + 1);
        int lastColon = rest.lastIndexOf(':');
        if (lastColon < 0) return new Variable(name, rest, "", 0);
        long ref = 0;
        try { ref = Long.parseLong(rest.substring(lastColon + 1)); } catch (NumberFormatException ignored) {}
        String beforeRef = rest.substring(0, lastColon);
        int typeColon = beforeRef.lastIndexOf(':');
        if (typeColon < 0) return new Variable(name, beforeRef, "", ref);
        String value = beforeRef.substring(0, typeColon);
        String type = beforeRef.substring(typeColon + 1);
        return new Variable(name, value, type, ref);
    }

    private static Path pathOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Path.of(s); } catch (Exception e) { return null; }
    }

    private void setState(DebuggerEventBus.State next) {
        if (state == next) return;
        state = next;
        events.fireStateChanged(new DebuggerEventBus.StateChangedEvent(next));
    }

    /* ============================== resolve exe ============================== */

    private String resolveInterpreter() throws IOException {
        String configured = ctx.getSettings() != null
                && ctx.getSettings().toolchain != null
                ? ctx.getSettings().toolchain.interpreter
                : null;
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(configured);
            if (Files.isRegularFile(p)) return p.toString();
            // Allow bare command names that resolve via PATH
            if (!configured.contains("\\") && !configured.contains("/") && !p.isAbsolute()) return configured;
        }
        throw new IOException("Interpreter not configured (Settings → Toolchain → Interpreter)");
    }
}
