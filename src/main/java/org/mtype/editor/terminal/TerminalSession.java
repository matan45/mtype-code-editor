package org.mtype.editor.terminal;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class TerminalSession {
    public record OutputChunk(String text, boolean stderr) {}

    private final int id;
    private final Path cwd;
    private final Consumer<OutputChunk> outputSink;
    private final Consumer<Integer> exitSink;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private Process process;
    private BufferedWriter writer;

    public TerminalSession(
            int id,
            Path cwd,
            Consumer<OutputChunk> outputSink,
            Consumer<Integer> exitSink) {
        this.id = id;
        this.cwd = cwd;
        this.outputSink = outputSink;
        this.exitSink = exitSink;
    }

    public int id() {
        return id;
    }

    public String title() {
        return "cmd " + id;
    }

    public Path cwd() {
        return cwd;
    }

    public synchronized void start() throws IOException {
        ProcessBuilder pb = new ProcessBuilder("cmd.exe")
                .directory(cwd.toFile())
                .redirectErrorStream(false);
        process = pb.start();
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), Charset.defaultCharset()));
        pump(process.getInputStream(), false, "mtype-terminal-" + id + "-stdout");
        pump(process.getErrorStream(), true, "mtype-terminal-" + id + "-stderr");
        process.onExit().thenAccept(p -> {
            closed.set(true);
            exitSink.accept(p.exitValue());
        });
    }

    public synchronized void send(String command) throws IOException {
        if (closed.get() || process == null || !process.isAlive() || writer == null) {
            throw new IOException("Terminal is not running");
        }
        writer.write(command);
        writer.write(System.lineSeparator());
        writer.flush();
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        Process p;
        synchronized (this) {
            p = process;
            try {
                if (writer != null) {
                    writer.write("exit");
                    writer.write(System.lineSeparator());
                    writer.flush();
                    writer.close();
                }
            } catch (Exception ignored) {
            }
        }
        if (p == null) return;
        try {
            if (!p.waitFor(600, TimeUnit.MILLISECONDS)) {
                p.destroy();
            }
            if (!p.waitFor(600, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
    }

    private void pump(InputStream input, boolean stderr, String threadName) {
        Thread thread = new Thread(() -> {
            try (InputStreamReader reader = new InputStreamReader(input, Charset.defaultCharset())) {
                char[] buffer = new char[1024];
                int n;
                while ((n = reader.read(buffer)) >= 0) {
                    if (n > 0) {
                        outputSink.accept(new OutputChunk(new String(buffer, 0, n), stderr));
                    }
                }
            } catch (Exception ignored) {
            }
        }, threadName);
        thread.setDaemon(true);
        thread.start();
    }
}
