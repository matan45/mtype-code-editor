package org.mtype.editor.lsp;

import javafx.application.Platform;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.PublishDiagnosticsParams;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Single source of truth for LSP diagnostics across the editor.
 * MTypeLanguageClient pushes raw publishDiagnostics here; the Problems panel,
 * gutter markers, and squiggle renderer all subscribe.
 */
public class DiagnosticsBus {

    private final Map<String, List<Diagnostic>> byUri = new LinkedHashMap<>();
    private final List<BiConsumer<String, List<Diagnostic>>> listeners = new CopyOnWriteArrayList<>();

    public void publish(PublishDiagnosticsParams params) {
        if (params == null || params.getUri() == null) return;
        String uri = params.getUri();
        List<Diagnostic> diags = params.getDiagnostics() == null
                ? Collections.emptyList()
                : new ArrayList<>(params.getDiagnostics());
        synchronized (byUri) {
            if (diags.isEmpty()) byUri.remove(uri);
            else byUri.put(uri, diags);
        }
        Platform.runLater(() -> {
            for (BiConsumer<String, List<Diagnostic>> l : listeners) {
                try { l.accept(uri, diags); } catch (Exception ignored) {}
            }
        });
    }

    public Map<String, List<Diagnostic>> snapshot() {
        synchronized (byUri) {
            return new LinkedHashMap<>(byUri);
        }
    }

    public List<Diagnostic> forUri(String uri) {
        synchronized (byUri) {
            return new ArrayList<>(byUri.getOrDefault(uri, Collections.emptyList()));
        }
    }

    public int totalCount() {
        synchronized (byUri) {
            int n = 0;
            for (List<Diagnostic> v : byUri.values()) n += v.size();
            return n;
        }
    }

    public void addListener(BiConsumer<String, List<Diagnostic>> listener) {
        listeners.add(listener);
    }
}
