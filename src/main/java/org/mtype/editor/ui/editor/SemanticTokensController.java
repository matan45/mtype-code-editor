package org.mtype.editor.ui.editor;

import javafx.application.Platform;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.lsp.LspBridge;
import org.mtype.editor.lsp.SemanticTokensDecoder;

import java.nio.file.Path;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Requests {@code textDocument/semanticTokens/full} (debounced) and decodes the result into the
 * semantic overlay layer of {@link StyleCompositor}. A request serial discards stale responses, and a
 * source snapshot guards against applying tokens decoded for text the user has since changed.
 */
final class SemanticTokensController {
    private final MTypeCodeArea codeArea;
    private final Path path;
    private final AppContext ctx;
    private final StyleCompositor compositor;
    private final boolean lspManaged;

    private ScheduledFuture<?> pending;
    private int requestSerial;

    SemanticTokensController(MTypeCodeArea codeArea, Path path, AppContext ctx,
                             StyleCompositor compositor, boolean lspManaged) {
        this.codeArea = codeArea;
        this.path = path;
        this.ctx = ctx;
        this.compositor = compositor;
        this.lspManaged = lspManaged;
    }

    void schedule() {
        if (!lspManaged) return;
        if (pending != null) pending.cancel(false);
        pending = EditorTab.BG_EXEC.schedule(
                () -> Platform.runLater(this::requestNow),
                300, TimeUnit.MILLISECONDS);
    }

    private void requestNow() {
        if (!lspManaged) return;
        LspBridge lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) return;
        final int request = ++requestSerial;
        final String snapshot = codeArea.getSourceText();
        final SemanticTokensLegend legend = lsp.getSemanticLegend();
        if (legend == null) return;
        lsp.semanticTokensFull(path).thenAcceptAsync(tokens -> {
            if (request != requestSerial) return;
            if (tokens == null) return;
            if (!snapshot.equals(codeArea.getSourceText())) return;
            compositor.setSemantic(SemanticTokensDecoder.decode(snapshot, tokens, legend, codeArea));
        }, Platform::runLater);
    }

    void dispose() {
        if (pending != null) pending.cancel(false);
    }
}
