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

    public StreamPump(InputStream in, Consumer<String> lineSink, String threadName) {
        this.in = in;
        this.lineSink = lineSink;
        this.threadName = threadName;
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
        }
    }
}
