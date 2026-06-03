package org.mtype.editor.workspace;

import javafx.application.Platform;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.ui.editor.EditorTab;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Workspace-scoped filesystem watcher. It runs the blocking WatchService loop on a daemon thread,
 * then coalesces bursts of events before touching JavaFX/LSP state.
 */
public final class WorkspaceFileWatcher implements AutoCloseable {
    private static final long DEBOUNCE_MILLIS = 180;

    private final AppContext ctx;
    private final Path root;
    private final WatchService watchService;
    private final Map<WatchKey, Path> keys = new HashMap<>();
    private final Map<Path, ChangeKind> pending = new HashMap<>();
    private final ScheduledExecutorService debounceExec;
    private final Thread thread;

    private volatile boolean closed;
    private ScheduledFuture<?> pendingFlush;

    public WorkspaceFileWatcher(AppContext ctx, Workspace workspace) throws IOException {
        this.ctx = ctx;
        this.root = workspace.root().toAbsolutePath().normalize();
        this.watchService = root.getFileSystem().newWatchService();
        this.debounceExec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mtype-file-watch-debounce");
            t.setDaemon(true);
            return t;
        });
        registerAll(root);
        this.thread = new Thread(this::watchLoop, "mtype-file-watch");
        this.thread.setDaemon(true);
        this.thread.start();
    }

    private void watchLoop() {
        while (!closed) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ignored) {
                return;
            }

            Path dir;
            synchronized (keys) {
                dir = keys.get(key);
            }
            if (dir != null) {
                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        enqueue(root, ChangeKind.CHANGED);
                        continue;
                    }

                    Path name = (Path) event.context();
                    Path changed = dir.resolve(name).toAbsolutePath().normalize();
                    if (isIgnored(changed)) continue;

                    if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                        if (Files.isDirectory(changed)) {
                            try { registerAll(changed); } catch (IOException ignored) {}
                        }
                        enqueue(changed, ChangeKind.CREATED);
                    } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                        enqueue(changed, ChangeKind.DELETED);
                    } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                        enqueue(changed, ChangeKind.CHANGED);
                    }
                }
            }

            if (!key.reset()) {
                synchronized (keys) {
                    keys.remove(key);
                }
            }
        }
    }

    private void registerAll(Path start) throws IOException {
        if (!Files.isDirectory(start) || isIgnored(start)) return;
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path absolute = dir.toAbsolutePath().normalize();
                if (isIgnored(absolute)) return FileVisitResult.SKIP_SUBTREE;
                register(absolute);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void register(Path dir) throws IOException {
        WatchKey key = dir.register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        synchronized (keys) {
            keys.put(key, dir);
        }
    }

    private void enqueue(Path path, ChangeKind kind) {
        synchronized (pending) {
            pending.merge(path, kind, WorkspaceFileWatcher::merge);
            if (pendingFlush != null) pendingFlush.cancel(false);
            pendingFlush = debounceExec.schedule(this::flushPending, DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    private void flushPending() {
        List<Change> changes;
        synchronized (pending) {
            if (pending.isEmpty()) return;
            changes = new ArrayList<>(pending.size());
            for (Map.Entry<Path, ChangeKind> entry : pending.entrySet()) {
                changes.add(new Change(entry.getKey(), entry.getValue()));
            }
            pending.clear();
        }
        Platform.runLater(() -> applyChanges(changes));
    }

    private void applyChanges(List<Change> changes) {
        if (closed || changes.isEmpty()) return;

        Set<Path> parentsToRefresh = new HashSet<>();
        boolean fullRefresh = false;
        for (Change change : changes) {
            Path path = change.path();
            if (path.equals(root)) {
                fullRefresh = true;
            } else if (path.getParent() != null) {
                parentsToRefresh.add(path.getParent());
            }

            EditorTab tab = findOpenTab(path);
            if (tab == null) continue;
            if (change.kind() == ChangeKind.DELETED) {
                tab.handleExternalDelete();
            } else if (Files.isRegularFile(path)) {
                tab.reloadFromDiskExternal();
            }
        }

        refreshExplorer(fullRefresh, parentsToRefresh);
        ctx.refreshHasProjectFile();
        if (ctx.getGitChangesView() != null) ctx.getGitChangesView().refresh();
        if (ctx.getLspBridge() != null) ctx.getLspBridge().refreshWatchedMtFiles();
    }

    private void refreshExplorer(boolean fullRefresh, Set<Path> parents) {
        if (ctx.getTreeView() == null) return;
        if (fullRefresh || parents.size() > 20) {
            ctx.getTreeView().refresh();
            return;
        }
        for (Path parent : parents) {
            ctx.getTreeView().refreshDirectory(parent);
        }
    }

    private EditorTab findOpenTab(Path path) {
        if (ctx.getTabPane() == null || path == null) return null;
        EditorTab exact = ctx.getTabPane().findByPath(path);
        if (exact != null) return exact;

        String target = comparablePath(path);
        for (EditorTab tab : ctx.getTabPane().openTabs()) {
            if (comparablePath(tab.getPath()).equals(target)) return tab;
        }
        return null;
    }

    private static ChangeKind merge(ChangeKind current, ChangeKind next) {
        if (next == ChangeKind.DELETED) return ChangeKind.DELETED;
        if (current == ChangeKind.DELETED) return next == ChangeKind.CREATED ? ChangeKind.CHANGED : current;
        if (current == ChangeKind.CREATED) return ChangeKind.CREATED;
        if (next == ChangeKind.CREATED) return ChangeKind.CREATED;
        return ChangeKind.CHANGED;
    }

    private static String comparablePath(Path path) {
        return path.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
    }

    private boolean isIgnored(Path path) {
        for (Path part : path) {
            if (".git".equalsIgnoreCase(part.toString())) return true;
        }
        return false;
    }

    @Override
    public void close() {
        closed = true;
        if (pendingFlush != null) pendingFlush.cancel(false);
        debounceExec.shutdownNow();
        try { watchService.close(); } catch (IOException ignored) {}
        thread.interrupt();
    }

    private enum ChangeKind {
        CREATED,
        CHANGED,
        DELETED
    }

    private record Change(Path path, ChangeKind kind) {
    }
}
