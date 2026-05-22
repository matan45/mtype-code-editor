package org.mtype.editor.process;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.workspace.Workspace;
import org.mtype.editor.workspace.WorkspaceSettings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

public class RunController {
    private final AppContext ctx;
    private final AtomicReference<Process> current = new AtomicReference<>();
    private final ReadOnlyBooleanWrapper running = new ReadOnlyBooleanWrapper(false);

    public RunController(AppContext ctx) {
        this.ctx = ctx;
    }

    public ReadOnlyBooleanProperty runningProperty() {
        return running.getReadOnlyProperty();
    }

    public void run(Path file) {
        if (running.get()) return;
        Workspace ws = ctx.getWorkspace();
        if (ws == null) {
            ctx.getStatusBar().setMessage("No workspace open");
            return;
        }
        WorkspaceSettings settings = ctx.getSettings();
        String exe = settings.toolchain.interpreter;

        ProcessBuilder pb = new ProcessBuilder(exe, file.toString())
                .directory(ws.getRoot().toFile())
                .redirectErrorStream(false);

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException ex) {
            ctx.getOutputPane().appendRun("Failed to start interpreter: " + ex.getMessage(), true);
            ctx.getOutputPane().focusRun();
            return;
        }
        current.set(proc);
        Platform.runLater(() -> running.set(true));
        ctx.getOutputPane().clearRun();
        ctx.getOutputPane().focusRun();
        ctx.getOutputPane().appendRun("$ " + exe + " " + file, false);
        ctx.getStatusBar().setMessage("Running " + file.getFileName());

        new StreamPump(proc.getInputStream(),
                line -> ctx.getOutputPane().appendRun(line, false),
                "mtype-run-stdout").start();
        new StreamPump(proc.getErrorStream(),
                line -> ctx.getOutputPane().appendRun(line, true),
                "mtype-run-stderr").start();

        proc.onExit().thenAccept(p -> {
            int code = p.exitValue();
            ctx.getOutputPane().appendRun("[exit " + code + "]", false);
            current.compareAndSet(p, null);
            Platform.runLater(() -> {
                running.set(false);
                ctx.getStatusBar().setMessage("Exit " + code);
            });
        });
    }

    public void stop() {
        Process p = current.getAndSet(null);
        if (p == null) return;
        p.destroy();
        try {
            if (!p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
        Platform.runLater(() -> running.set(false));
    }
}
