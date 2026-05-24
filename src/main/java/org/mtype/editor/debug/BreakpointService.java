package org.mtype.editor.debug;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Workspace-scope breakpoint registry. Lines are 0-based (matches CodeArea
 * paragraphs); conversion to the protocol's 1-based lines happens at the
 * bridge boundary.
 *
 * The bridge is set after construction (via {@link #attachBridge}) so the
 * service can mirror mutations to the live debug session.
 */
public class BreakpointService {

    private final Map<Path, Set<Integer>> byPath = new LinkedHashMap<>();
    private final List<BiConsumer<Path, Set<Integer>>> listeners = new CopyOnWriteArrayList<>();
    private DebuggerBridge bridge;

    public synchronized void attachBridge(DebuggerBridge bridge) { this.bridge = bridge; }

    /** Toggle a breakpoint at the given 0-based line. Returns the new state (true = on). */
    public boolean toggle(Path path, int line0) {
        boolean nowOn;
        Set<Integer> snapshot;
        synchronized (this) {
            Set<Integer> lines = byPath.computeIfAbsent(path, _ -> new TreeSet<>());
            if (lines.contains(line0)) {
                lines.remove(line0);
                nowOn = false;
            } else {
                lines.add(line0);
                nowOn = true;
            }
            if (lines.isEmpty()) byPath.remove(path);
            snapshot = new TreeSet<>(lines);
        }
        if (bridge != null && bridge.isRunning()) {
            if (nowOn) bridge.setBreakpoint(path, line0 + 1);
            else bridge.clearBreakpoint(path, line0 + 1);
        }
        notify(path, snapshot);
        return nowOn;
    }

    /** All breakpoints in this file as 0-based lines. */
    public synchronized Set<Integer> breakpointsIn(Path path) {
        Set<Integer> s = byPath.get(path);
        return s == null ? Collections.emptySet() : new TreeSet<>(s);
    }

    public synchronized Map<Path, Set<Integer>> snapshot() {
        Map<Path, Set<Integer>> out = new LinkedHashMap<>();
        for (Map.Entry<Path, Set<Integer>> e : byPath.entrySet()) {
            out.put(e.getKey(), new TreeSet<>(e.getValue()));
        }
        return out;
    }

    public void clearAll() {
        List<Path> paths;
        synchronized (this) {
            paths = new ArrayList<>(byPath.keySet());
            byPath.clear();
        }
        if (bridge != null && bridge.isRunning()) bridge.clearAllBreakpoints();
        for (Path p : paths) notify(p, Collections.emptySet());
    }

    /** Called by the bridge right after the process is up, to replay all known breakpoints. */
    public void replayTo(DebuggerBridge target) {
        Map<Path, Set<Integer>> snap;
        synchronized (this) { snap = snapshot(); }
        for (Map.Entry<Path, Set<Integer>> e : snap.entrySet()) {
            for (int line0 : e.getValue()) {
                target.setBreakpoint(e.getKey(), line0 + 1);
            }
        }
    }

    public void addListener(BiConsumer<Path, Set<Integer>> l) { listeners.add(l); }

    private void notify(Path path, Set<Integer> lines) {
        for (BiConsumer<Path, Set<Integer>> l : listeners) {
            try { l.accept(path, lines); } catch (Exception ignored) {}
        }
    }
}
