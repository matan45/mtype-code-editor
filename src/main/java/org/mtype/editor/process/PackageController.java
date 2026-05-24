package org.mtype.editor.process;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.workspace.Workspace;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class PackageController {
    private final AppContext ctx;
    private final AtomicReference<Process> current = new AtomicReference<>();
    private final ReadOnlyBooleanWrapper running = new ReadOnlyBooleanWrapper(false);

    public PackageController(AppContext ctx) {
        this.ctx = ctx;
    }

    public ReadOnlyBooleanProperty runningProperty() {
        return running.getReadOnlyProperty();
    }

    public void install(Path mtproj) {
        List<String> args = new ArrayList<>();
        args.add("install");
        if (mtproj != null) args.add(mtproj.toString());
        runMtpm(args, "install", mtproj == null ? null : mtproj.getParent(), true);
    }

    public void add(Path mtproj, AddPackageSpec spec) {
        if (spec == null) return;
        List<String> args = new ArrayList<>();
        args.add("add");
        args.add(spec.name() + "@" + spec.version());
        if (spec.source() != null && !spec.source().isBlank()) {
            args.add("--source");
            args.add(spec.source());
        }
        if (mtproj != null) args.add(mtproj.toString());
        runMtpm(args, "add " + spec.name(), mtproj == null ? null : mtproj.getParent(), true);
    }

    public void remove(Path mtproj, String packageName) {
        if (packageName == null || packageName.isBlank()) return;
        List<String> args = new ArrayList<>();
        args.add("remove");
        args.add(packageName);
        if (mtproj != null) args.add(mtproj.toString());
        runMtpm(args, "remove " + packageName, mtproj == null ? null : mtproj.getParent(), true);
    }

    public void list(Path mtproj) {
        List<String> args = new ArrayList<>();
        args.add("list");
        if (mtproj != null) args.add(mtproj.toString());
        runMtpm(args, "list", mtproj == null ? null : mtproj.getParent(), false);
    }

    private void runMtpm(List<String> args, String label, Path cwdHint, boolean refreshOnSuccess) {
        if (running.get()) {
            ctx.getStatusBar().setMessage("mtpm is busy — wait for current operation");
            return;
        }
        Workspace ws = ctx.getWorkspace();
        if (ws == null) {
            ctx.getStatusBar().setMessage("No workspace open");
            return;
        }

        String exe = ctx.getSettings().toolchain.packageManager;
        if (exe == null || exe.isBlank()) {
            ctx.getOutputPane().appendPackages(
                    "mtpm path is not configured (Settings → Toolchain → Package Manager)", true);
            ctx.getOutputPane().focusPackages();
            return;
        }

        File cwd = (cwdHint != null ? cwdHint : ws.root()).toFile();

        List<String> cmd = new ArrayList<>();
        cmd.add(exe);
        cmd.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(cwd)
                .redirectErrorStream(false);

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException ex) {
            ctx.getOutputPane().appendPackages("Failed to start mtpm.exe: " + ex.getMessage(), true);
            ctx.getOutputPane().focusPackages();
            return;
        }
        current.set(proc);
        Platform.runLater(() -> running.set(true));
        ctx.getOutputPane().clearPackages();
        ctx.getOutputPane().focusPackages();
        ctx.getOutputPane().appendPackages("$ mtpm " + String.join(" ", args), false);
        ctx.getStatusBar().setMessage("Running " + label);

        new StreamPump(proc.getInputStream(),
                line -> ctx.getOutputPane().appendPackages(line, false),
                "mtpm-stdout").start();
        new StreamPump(proc.getErrorStream(),
                line -> ctx.getOutputPane().appendPackages(line, true),
                "mtpm-stderr").start();

        Path refreshDir = cwd.toPath();
        proc.onExit().thenAccept(p -> {
            int code = p.exitValue();
            ctx.getOutputPane().appendPackages("[exit " + code + "]", false);
            current.compareAndSet(p, null);
            Platform.runLater(() -> {
                running.set(false);
                ctx.getStatusBar().setMessage(label + " exit " + code);
                if (refreshOnSuccess && code == 0 && ctx.getTreeView() != null) {
                    ctx.getTreeView().refreshDirectory(refreshDir);
                }
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
        Platform.runLater(() -> running.set(false));
    }
}
