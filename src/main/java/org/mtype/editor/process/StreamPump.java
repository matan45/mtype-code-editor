package org.mtype.editor.process;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public class StreamPump implements Runnable {
    private final InputStream in;
    private final Consumer<String> lineSink;
    private final String threadName;
    private final Runnable onClose;

    public StreamPump(InputStream in, Consumer<String> lineSink, String threadName) {
        this(in, lineSink, threadName, null);
    }

    /**
     * @param onClose optional callback invoked once when the stream ends (EOF or
     *                error). Lets a socket transport detect a host disconnect.
     */
    public StreamPump(InputStream in, Consumer<String> lineSink, String threadName, Runnable onClose) {
        this.in = in;
        this.lineSink = lineSink;
        this.threadName = threadName;
        this.onClose = onClose;
    }

    public Thread start() {
        Thread t = new Thread(this, threadName);
        t.setDaemon(true);
        t.start();
        return t;
    }

    @Override
    public void run() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    lineSink.accept(line);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (onClose != null) {
                try {
                    onClose.run();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
