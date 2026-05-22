package org.mtype.editor.search;

import javafx.application.Platform;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class FindInFilesService {

    public interface Listener {
        void onStarted();
        void onBatch(List<SearchMatch> batch);
        void onFinished(int filesScanned, int totalMatches, boolean cancelled, String errorOrNull);
    }

    private static final long MAX_FILE_BYTES = 10L * 1024L * 1024L;
    private static final int  MAX_LINE_CHARS = 400;
    private static final int  BATCH_SIZE = 100;
    private static final long BATCH_INTERVAL_MS = 500;

    private static final Set<String> BINARY_EXTS = Set.of(
            "exe", "dll", "png", "jpg", "jpeg", "gif", "pdf", "zip", "tar", "gz", "7z",
            "mtc", "class", "jar", "ico", "bmp", "ttf", "otf", "woff", "woff2",
            "mp3", "mp4", "mov", "avi", "wav", "ogg", "so", "dylib", "obj", "bin");

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mtype-find-in-files");
        t.setDaemon(true);
        return t;
    });

    private Future<?> currentFuture;
    private AtomicBoolean currentCancelled;

    public synchronized void search(Path workspaceRoot, SearchQuery query, Listener listener) {
        cancel();
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        this.currentCancelled = cancelled;
        this.currentFuture = EXEC.submit(() -> runSearch(workspaceRoot, query, listener, cancelled));
    }

    public synchronized void cancel() {
        if (currentCancelled != null) currentCancelled.set(true);
        if (currentFuture != null) currentFuture.cancel(true);
    }

    private void runSearch(Path root, SearchQuery query, Listener listener, AtomicBoolean cancelled) {
        Platform.runLater(listener::onStarted);

        Pattern pattern;
        try {
            pattern = query.compile();
        } catch (PatternSyntaxException ex) {
            Platform.runLater(() -> listener.onFinished(0, 0, false, ex.getDescription()));
            return;
        }
        PathMatcher mask = query.pathMatcher();

        final List<SearchMatch> pending = new ArrayList<>();
        final int[] filesScanned = {0};
        final int[] totalMatches = {0};
        final long[] lastFlushNanos = {System.nanoTime()};

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (cancelled.get()) return FileVisitResult.TERMINATE;
                    if (!dir.equals(root)) {
                        String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                        if (name.startsWith(".")) return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (cancelled.get()) return FileVisitResult.TERMINATE;
                    if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                    String name = file.getFileName() == null ? "" : file.getFileName().toString();
                    if (name.startsWith(".")) return FileVisitResult.CONTINUE;
                    if (attrs.size() > MAX_FILE_BYTES) return FileVisitResult.CONTINUE;
                    if (isBinaryExt(name)) return FileVisitResult.CONTINUE;
                    if (mask != null) {
                        Path rel;
                        try { rel = root.relativize(file); } catch (IllegalArgumentException ex) { return FileVisitResult.CONTINUE; }
                        if (!mask.matches(rel)) return FileVisitResult.CONTINUE;
                    }

                    filesScanned[0]++;
                    searchFile(file, pattern, pending, totalMatches, lastFlushNanos, listener, cancelled);
                    return cancelled.get() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });

            flush(pending, listener);
            final boolean wasCancelled = cancelled.get();
            Platform.runLater(() -> listener.onFinished(filesScanned[0], totalMatches[0], wasCancelled, null));
        } catch (IOException ex) {
            flush(pending, listener);
            Platform.runLater(() -> listener.onFinished(filesScanned[0], totalMatches[0], cancelled.get(), ex.getMessage()));
        } catch (RuntimeException ex) {
            flush(pending, listener);
            Platform.runLater(() -> listener.onFinished(filesScanned[0], totalMatches[0], cancelled.get(), ex.getMessage()));
        }
    }

    private static void searchFile(Path file, Pattern pattern,
                                   List<SearchMatch> pending, int[] totalMatches,
                                   long[] lastFlushNanos, Listener listener,
                                   AtomicBoolean cancelled) {
        int lineNo = 0;
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            var it = lines.iterator();
            while (it.hasNext()) {
                if (cancelled.get()) return;
                String line = it.next();
                Matcher m = pattern.matcher(line);
                String snippet = line.length() > MAX_LINE_CHARS ? line.substring(0, MAX_LINE_CHARS) : line;
                while (m.find()) {
                    int s = m.start();
                    int e = m.end();
                    if (s == e) { // zero-width match — advance to avoid infinite loop
                        if (e >= line.length()) break;
                        continue;
                    }
                    int clampedEnd = Math.min(e, snippet.length());
                    int clampedStart = Math.min(s, snippet.length());
                    pending.add(new SearchMatch(file, lineNo, clampedStart, clampedEnd, snippet));
                    totalMatches[0]++;
                }
                lineNo++;
                long now = System.nanoTime();
                if (pending.size() >= BATCH_SIZE
                        || (now - lastFlushNanos[0]) / 1_000_000L >= BATCH_INTERVAL_MS) {
                    flush(pending, listener);
                    lastFlushNanos[0] = now;
                }
            }
        } catch (MalformedInputException ex) {
            // binary or non-UTF-8 file — skip silently
        } catch (UncheckedIOException | IOException ex) {
            // file vanished or unreadable mid-walk — skip silently
        }
    }

    private static void flush(List<SearchMatch> pending, Listener listener) {
        if (pending.isEmpty()) return;
        final List<SearchMatch> snapshot = new ArrayList<>(pending);
        pending.clear();
        Platform.runLater(() -> listener.onBatch(snapshot));
    }

    private static boolean isBinaryExt(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        return BINARY_EXTS.contains(name.substring(dot + 1).toLowerCase());
    }
}
