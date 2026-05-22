package org.mtype.editor.process;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.workspace.Workspace;
import org.mtype.editor.workspace.WorkspaceSettings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class BuildController {
    private final AppContext ctx;
    private final AtomicReference<Process> current = new AtomicReference<>();
    private final ReadOnlyBooleanWrapper building = new ReadOnlyBooleanWrapper(false);

    public BuildController(AppContext ctx) {
        this.ctx = ctx;
    }

    public ReadOnlyBooleanProperty buildingProperty() {
        return building.getReadOnlyProperty();
    }

    public void build() {
        runMtype(List.of("--build"), "build");
    }

    public void buildLibrary() {
        runMtype(List.of("--build", "--lib"), "build library");
    }

    public void buildExecutable() {
        runMtype(List.of("--build", "--exe"), "build executable");
    }

    public void buildGuiExecutable() {
        runMtype(List.of("--build", "--exe", "--gui"), "build GUI executable");
    }

    public void showDepsTree() {
        runMtype(List.of("--deps"), "dependency tree");
    }

    public void showDepsForFile(Path file) {
        if (file == null) {
            ctx.getStatusBar().setMessage("No file selected");
            return;
        }
        runMtype(List.of("--deps", "--why", file.toString()), "dependencies for " + file.getFileName());
    }

    private void runMtype(List<String> mtypeArgs, String label) {
        if (building.get()) {
            ctx.getStatusBar().setMessage("Build already running");
            return;
        }
        Workspace ws = ctx.getWorkspace();
        if (ws == null) {
            ctx.getStatusBar().setMessage("No workspace open");
            return;
        }
        if (!ws.hasProjectFile()) {
            ctx.getOutputPane().appendCompile(
                    "No .mtproj or .mtworkspace found in " + ws.getRoot(), true);
            ctx.getOutputPane().focusCompile();
            return;
        }

        WorkspaceSettings settings = ctx.getSettings();
        String exe = settings.toolchain.interpreter;

        List<String> cmd = new ArrayList<>();
        cmd.add(exe);
        cmd.addAll(mtypeArgs);

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(ws.getRoot().toFile())
                .redirectErrorStream(false);

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException ex) {
            ctx.getOutputPane().appendCompile("Failed to start mType.exe: " + ex.getMessage(), true);
            ctx.getOutputPane().focusCompile();
            return;
        }
        current.set(proc);
        Platform.runLater(() -> building.set(true));
        ctx.getOutputPane().clearCompile();
        ctx.getOutputPane().focusCompile();
        ctx.getOutputPane().appendCompile("$ " + exe + " " + String.join(" ", mtypeArgs), false);
        ctx.getStatusBar().setMessage("Running " + label);

        new StreamPump(proc.getInputStream(),
                line -> ctx.getOutputPane().appendCompile(line, false),
                "mtype-build-stdout").start();
        new StreamPump(proc.getErrorStream(),
                line -> ctx.getOutputPane().appendCompile(line, true),
                "mtype-build-stderr").start();

        proc.onExit().thenAccept(p -> {
            int code = p.exitValue();
            ctx.getOutputPane().appendCompile("[exit " + code + "]", false);
            current.compareAndSet(p, null);
            Platform.runLater(() -> {
                building.set(false);
                ctx.getStatusBar().setMessage(label + " exit " + code);
            });
        });
    }

    public void stop() {
        Process p = current.getAndSet(null);
        if (p == null) return;
        p.destroy();
        try {
            if (!p.waitFor(1, TimeUnit.SECONDS)) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
        Platform.runLater(() -> building.set(false));
    }
}
