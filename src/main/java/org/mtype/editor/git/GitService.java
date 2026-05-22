package org.mtype.editor.git;

import org.mtype.editor.app.AppContext;
import org.mtype.editor.process.StreamPump;
import org.mtype.editor.workspace.Workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GitService {
    private final AppContext ctx;
    private final java.util.concurrent.atomic.AtomicInteger threadIdx = new java.util.concurrent.atomic.AtomicInteger(0);
    private final ExecutorService gitExec = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mtype-git-" + threadIdx.getAndIncrement());
        t.setDaemon(true);
        return t;
    });

    public GitService(AppContext ctx) {
        this.ctx = ctx;
    }

    public record StatusEntry(String code, Path path, String group, String label) {}
    public record AheadBehind(int ahead, int behind, boolean hasUpstream) {}
    public record DiffPair(String oldContent, String newContent, boolean binary) {}
    public record CommitEntry(
            String graph,
            String hash,
            String shortHash,
            String subject,
            String author,
            String date,
            String decorations) {}

    public record ProcResult(int exitCode, List<String> stdout, List<String> stderr) {
        public boolean ok() { return exitCode == 0; }
        public String stdoutJoined() { return String.join("\n", stdout); }
        public String stderrJoined() { return String.join("\n", stderr); }
        public String firstStdoutLine() { return stdout.isEmpty() ? "" : stdout.get(0); }
    }

    public CompletableFuture<List<StatusEntry>> status() {
        return exec(false, "status", "--porcelain=v1").thenApply(r -> {
            if (!r.ok()) throw new RuntimeException("git status failed: " + r.stderrJoined());
            Path root = workspaceRootOrThrow();
            List<StatusEntry> out = new ArrayList<>();
            for (String line : r.stdout) {
                StatusEntry e = parseStatus(root, line);
                if (e != null) out.add(e);
            }
            return out;
        });
    }

    public CompletableFuture<String> currentBranch() {
        return exec(false, "rev-parse", "--abbrev-ref", "HEAD").thenApply(r -> {
            if (!r.ok()) return "";
            String s = r.firstStdoutLine().trim();
            return "HEAD".equals(s) ? "" : s;
        });
    }

    public CompletableFuture<AheadBehind> aheadBehind() {
        return exec(false, "rev-list", "--left-right", "--count", "@{u}...HEAD").thenApply(r -> {
            if (!r.ok()) return new AheadBehind(0, 0, false);
            String line = r.firstStdoutLine().trim();
            String[] parts = line.split("\\s+");
            if (parts.length != 2) return new AheadBehind(0, 0, true);
            try {
                int behind = Integer.parseInt(parts[0]);
                int ahead = Integer.parseInt(parts[1]);
                return new AheadBehind(ahead, behind, true);
            } catch (NumberFormatException nfe) {
                return new AheadBehind(0, 0, true);
            }
        });
    }

    public CompletableFuture<List<String>> localBranches() {
        return exec(false, "for-each-ref", "refs/heads", "--format=%(refname:short)")
                .thenApply(r -> r.ok() ? new ArrayList<>(r.stdout) : Collections.emptyList());
    }

    public CompletableFuture<List<String>> remoteBranches() {
        return exec(false, "for-each-ref", "refs/remotes", "--format=%(refname:short)")
                .thenApply(r -> r.ok() ? new ArrayList<>(r.stdout) : Collections.emptyList());
    }

    public CompletableFuture<String> defaultBranch() {
        return exec(false, "symbolic-ref", "refs/remotes/origin/HEAD").thenCompose(r -> {
            if (r.ok()) {
                String s = r.firstStdoutLine().trim();
                int slash = s.lastIndexOf('/');
                return CompletableFuture.completedFuture(slash >= 0 ? s.substring(slash + 1) : s);
            }
            return localBranches().thenApply(list -> {
                if (list.contains("main")) return "main";
                if (list.contains("master")) return "master";
                return "";
            });
        });
    }

    public CompletableFuture<List<CommitEntry>> recentCommits(int limit) {
        int cappedLimit = Math.max(1, Math.min(limit, 500));
        return exec(false,
                "log",
                "--graph",
                "--decorate=short",
                "--date=short",
                "--pretty=format:%x1f%H%x1f%h%x1f%s%x1f%an%x1f%ad%x1f%D",
                "-n",
                Integer.toString(cappedLimit)
        ).thenApply(r -> {
            if (!r.ok()) throw new RuntimeException("git log failed: " + r.stderrJoined());
            List<CommitEntry> out = new ArrayList<>();
            for (String line : r.stdout) {
                CommitEntry entry = parseCommitLine(line);
                if (entry != null) out.add(entry);
            }
            return out;
        });
    }

    public CompletableFuture<DiffPair> diffAgainstHead(Path file) {
        return CompletableFuture.supplyAsync(() -> {
            Path root = workspaceRootOrThrow();
            String rel = root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
            // Detect binary
            try {
                ProcResult numstat = execSync(false, "diff", "--numstat", "HEAD", "--", rel);
                if (numstat.ok() && !numstat.stdout.isEmpty()) {
                    String row = numstat.stdout.get(0);
                    if (row.startsWith("-\t-\t")) {
                        return new DiffPair("", "", true);
                    }
                }
            } catch (Exception ignored) {}

            String oldText = "";
            try {
                ProcResult show = execSync(true, "show", "HEAD:" + rel);
                if (show.ok()) oldText = show.stdoutJoined();
            } catch (Exception ignored) {}

            String newText = "";
            try {
                if (Files.isRegularFile(file)) {
                    newText = Files.readString(file, StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) {}
            return new DiffPair(oldText, newText, false);
        }, gitExec);
    }

    public CompletableFuture<Void> add(List<Path> files) {
        if (files == null || files.isEmpty()) return CompletableFuture.completedFuture(null);
        List<String> args = new ArrayList<>();
        args.add("add");
        args.add("--");
        addPaths(args, files);
        return exec(true, args.toArray(new String[0])).thenApply(r -> {
            if (!r.ok()) throw new RuntimeException("git add failed: " + r.stderrJoined());
            return null;
        });
    }

    public CompletableFuture<Void> unstage(List<Path> files) {
        if (files == null || files.isEmpty()) return CompletableFuture.completedFuture(null);
        List<String> args = new ArrayList<>();
        args.add("restore");
        args.add("--staged");
        args.add("--");
        addPaths(args, files);
        return exec(true, args.toArray(new String[0])).thenApply(r -> {
            if (!r.ok()) throw new RuntimeException("git restore --staged failed: " + r.stderrJoined());
            return null;
        });
    }

    public CompletableFuture<Void> discard(List<Path> files) {
        if (files == null || files.isEmpty()) return CompletableFuture.completedFuture(null);
        return exec(false, "rev-parse", "--verify", "HEAD").thenCompose(headCheck -> {
            boolean hasHead = headCheck.ok();
            List<Path> tracked = new ArrayList<>();
            List<Path> untracked = new ArrayList<>();
            Path root = workspaceRootOrThrow();
            for (Path p : files) {
                String rel = root.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/');
                try {
                    ProcResult check = execSync(false, "ls-files", "--error-unmatch", "--", rel);
                    if (check.ok()) tracked.add(p);
                    else untracked.add(p);
                } catch (Exception e) {
                    untracked.add(p);
                }
            }
            List<CompletableFuture<Void>> ops = new ArrayList<>();
            if (!tracked.isEmpty() && hasHead) {
                List<String> args = new ArrayList<>();
                args.add("restore");
                args.add("--source=HEAD");
                args.add("--staged");
                args.add("--worktree");
                args.add("--");
                addPaths(args, tracked);
                ops.add(exec(true, args.toArray(new String[0])).thenApply(r -> {
                    if (!r.ok()) throw new RuntimeException("git restore failed: " + r.stderrJoined());
                    return null;
                }));
            }
            for (Path p : untracked) {
                ops.add(CompletableFuture.runAsync(() -> {
                    try { Files.deleteIfExists(p); }
                    catch (IOException ex) { throw new RuntimeException("Failed to delete " + p + ": " + ex.getMessage()); }
                }, gitExec));
            }
            return CompletableFuture.allOf(ops.toArray(new CompletableFuture[0]));
        });
    }

    public CompletableFuture<Void> commit(String message) {
        if (message == null || message.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Commit message is empty"));
        }
        return exec(true, "commit", "-m", message).thenApply(r -> {
            if (!r.ok()) throw new RuntimeException("git commit failed: " + r.stderrJoined());
            return null;
        });
    }

    public CompletableFuture<Void> push() {
        return exec(true, "push").thenApply(r -> {
            if (!r.ok()) throw new RuntimeException("git push failed: " + r.stderrJoined());
            return null;
        });
    }

    public CompletableFuture<Void> pull() {
        return exec(true, "pull").thenApply(r -> {
            if (!r.ok()) throw new RuntimeException("git pull failed: " + r.stderrJoined());
            return null;
        });
    }

    public CompletableFuture<Void> fetch() {
        return exec(true, "fetch", "--prune").thenApply(r -> {
            if (!r.ok()) throw new RuntimeException("git fetch failed: " + r.stderrJoined());
            return null;
        });
    }

    public CompletableFuture<Void> checkoutBranch(String branch) {
        return exec(true, "checkout", branch).thenApply(r -> {
            if (!r.ok()) throw new RuntimeException("git checkout failed: " + r.stderrJoined());
            return null;
        });
    }

    public CompletableFuture<Void> merge(String branch) {
        return exec(true, "merge", branch).thenApply(r -> {
            if (!r.ok()) throw new RuntimeException("git merge failed: " + r.stderrJoined());
            return null;
        });
    }

    public CompletableFuture<Void> createBranch(String name, String from) {
        String[] args = (from == null || from.isBlank())
                ? new String[]{"switch", "-c", name}
                : new String[]{"switch", "-c", name, from};
        return exec(true, args).thenApply(r -> {
            if (!r.ok()) throw new RuntimeException("git switch -c failed: " + r.stderrJoined());
            return null;
        });
    }

    public CompletableFuture<Void> deleteBranch(String name, boolean force) {
        String flag = force ? "-D" : "-d";
        return exec(true, "branch", flag, name).thenApply(r -> {
            if (!r.ok()) throw new RuntimeException(r.stderrJoined());
            return null;
        });
    }

    public CompletableFuture<Void> stash(String message) {
        List<String> args = new ArrayList<>();
        args.add("stash");
        args.add("push");
        args.add("--include-untracked");
        if (message != null && !message.isBlank()) {
            args.add("-m");
            args.add(message);
        }
        return exec(true, args.toArray(new String[0])).thenApply(r -> {
            if (!r.ok()) throw new RuntimeException("git stash failed: " + r.stderrJoined());
            return null;
        });
    }

    public CompletableFuture<Void> stashPop() {
        return exec(true, "stash", "pop").thenApply(r -> {
            if (!r.ok()) throw new RuntimeException("git stash pop failed: " + r.stderrJoined());
            return null;
        });
    }

    public CompletableFuture<Boolean> isGitRepo() {
        return exec(false, "rev-parse", "--is-inside-work-tree").thenApply(r ->
                r.ok() && "true".equals(r.firstStdoutLine().trim()));
    }

    // ---- internals ----

    private void addPaths(List<String> args, List<Path> files) {
        Path root = workspaceRootOrThrow();
        for (Path p : files) {
            args.add(root.relativize(p.toAbsolutePath().normalize()).toString().replace('\\', '/'));
        }
    }

    private static StatusEntry parseStatus(Path root, String line) {
        if (line == null || line.length() < 4) return null;
        String code = line.substring(0, 2);
        String rawPath = line.substring(3);
        int renameArrow = rawPath.indexOf(" -> ");
        if (renameArrow >= 0) rawPath = rawPath.substring(renameArrow + 4);
        Path path = root.resolve(rawPath).normalize();
        return new StatusEntry(code, path, groupFor(code), labelFor(code));
    }

    private static String groupFor(String code) {
        if ("??".equals(code)) return "Untracked";
        if (code.charAt(0) != ' ' && code.charAt(0) != '?') return "Staged Changes";
        return "Changes";
    }

    private static String labelFor(String code) {
        if ("??".equals(code)) return "U";
        if ("A ".equals(code) || " A".equals(code)) return "A";
        if (code.indexOf('D') >= 0) return "D";
        if (code.indexOf('R') >= 0) return "R";
        if (code.indexOf('C') >= 0) return "C";
        return "M";
    }

    private static CommitEntry parseCommitLine(String line) {
        if (line == null) return null;
        int firstField = line.indexOf('\u001f');
        if (firstField < 0) return null;
        String graph = line.substring(0, firstField);
        String[] fields = line.substring(firstField + 1).split("\u001f", -1);
        if (fields.length < 6) return null;
        return new CommitEntry(
                graph,
                fields[0],
                fields[1],
                fields[2],
                fields[3],
                fields[4],
                fields[5]);
    }

    private Path workspaceRootOrThrow() {
        Workspace ws = ctx.getWorkspace();
        if (ws == null) throw new IllegalStateException("No workspace open");
        return ws.getRoot();
    }

    /** Run git asynchronously. If echo is true, every line is also mirrored to the Git output tab. */
    private CompletableFuture<ProcResult> exec(boolean echo, String... args) {
        return CompletableFuture.supplyAsync(() -> execSync(echo, args), gitExec);
    }

    private ProcResult execSync(boolean echo, String... args) {
        Workspace ws = ctx.getWorkspace();
        if (ws == null) throw new IllegalStateException("No workspace open");
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-c");
        cmd.add("core.quotePath=false");
        cmd.addAll(Arrays.asList(args));

        if (echo && ctx.getOutputPane() != null) {
            ctx.getOutputPane().appendGit("$ git " + String.join(" ", args), false);
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .directory(ws.getRoot().toFile())
                    .redirectErrorStream(false);
            Process proc = pb.start();

            ConcurrentLinkedQueue<String> outLines = new ConcurrentLinkedQueue<>();
            ConcurrentLinkedQueue<String> errLines = new ConcurrentLinkedQueue<>();

            Thread outPump = new StreamPump(proc.getInputStream(), line -> {
                outLines.add(line);
                if (echo && ctx.getOutputPane() != null) {
                    ctx.getOutputPane().appendGit(line, false);
                }
            }, "mtype-git-out").start();

            Thread errPump = new StreamPump(proc.getErrorStream(), line -> {
                errLines.add(line);
                if (echo && ctx.getOutputPane() != null) {
                    ctx.getOutputPane().appendGit(line, true);
                }
            }, "mtype-git-err").start();

            int exit = proc.waitFor();
            outPump.join(500);
            errPump.join(500);

            if (echo && exit != 0 && ctx.getOutputPane() != null) {
                ctx.getOutputPane().appendGit("[exit " + exit + "]", true);
            }

            return new ProcResult(exit, new ArrayList<>(outLines), new ArrayList<>(errLines));
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            if (echo && ctx.getOutputPane() != null) {
                ctx.getOutputPane().appendGit("Failed to run git: " + ex.getMessage(), true);
            }
            throw new RuntimeException("git invocation failed: " + ex.getMessage(), ex);
        }
    }
}
