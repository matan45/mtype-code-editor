package org.mtype.editor.ui.editor;

import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.lsp.LspBridge;
import org.mtype.editor.ui.output.OutlinePanel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Fetches {@code textDocument/documentSymbol} (debounced) and fans the result out to: the breadcrumb
 * bar (ancestor chain at the caret), the {@link CodeFoldingController} (foldable regions), and the
 * outline panel. The symbol list is also exposed for the outline's lazy reads.
 */
final class DocumentSymbolController {
    private final MTypeCodeArea codeArea;
    private final Path path;
    private final AppContext ctx;
    private final boolean lspManaged;
    private final HBox breadcrumbBar;
    private final CodeFoldingController folding;
    private final EditorTab tab;

    private List<DocumentSymbol> lastDocumentSymbols = Collections.emptyList();
    private ScheduledFuture<?> pending;
    private int requestSerial;

    DocumentSymbolController(MTypeCodeArea codeArea, Path path, AppContext ctx, boolean lspManaged,
                             HBox breadcrumbBar, CodeFoldingController folding, EditorTab tab) {
        this.codeArea = codeArea;
        this.path = path;
        this.ctx = ctx;
        this.lspManaged = lspManaged;
        this.breadcrumbBar = breadcrumbBar;
        this.folding = folding;
        this.tab = tab;
    }

    List<DocumentSymbol> getLastDocumentSymbols() {
        return lastDocumentSymbols;
    }

    void schedule() {
        if (!lspManaged) return;
        if (pending != null) pending.cancel(false);
        pending = EditorTab.BG_EXEC.schedule(
                () -> Platform.runLater(this::requestNow),
                350, TimeUnit.MILLISECONDS);
    }

    private void requestNow() {
        if (!lspManaged) return;
        LspBridge lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) return;
        final int request = ++requestSerial;
        lsp.documentSymbol(path).thenAcceptAsync(raw -> {
            if (request != requestSerial) return;
            lastDocumentSymbols = OutlinePanel.flatten(raw);
            updateBreadcrumb();
            folding.setDocumentSymbols(lastDocumentSymbols);
            if (ctx.getOutputPane() != null) ctx.getOutputPane().refreshOutlineFor(tab);
        }, Platform::runLater);
    }

    void updateBreadcrumb() {
        if (lastDocumentSymbols == null || lastDocumentSymbols.isEmpty()) {
            showFallbackBreadcrumb();
            return;
        }
        int[] lc = codeArea.displayToSourceLineChar(codeArea.getCaretPosition());
        int line = lc[0];
        int col = lc[1];
        List<DocumentSymbol> chain = new ArrayList<>();
        collectAncestors(lastDocumentSymbols, line, col, chain);
        breadcrumbBar.getChildren().clear();
        if (chain.isEmpty()) {
            showFallbackBreadcrumb();
            return;
        }
        for (int i = 0; i < chain.size(); i++) {
            DocumentSymbol s = chain.get(i);
            Label name = new Label(s.getName() == null ? "?" : s.getName());
            name.getStyleClass().add("mt-breadcrumb-item");
            name.setOnMouseClicked(_ -> {
                Range r = s.getSelectionRange() != null ? s.getSelectionRange() : s.getRange();
                if (r != null && r.getStart() != null) tab.revealPosition(r.getStart().getLine(), r.getStart().getCharacter());
            });
            breadcrumbBar.getChildren().add(name);
            if (i < chain.size() - 1) {
                Label sep = new Label("›");
                sep.getStyleClass().add("mt-breadcrumb-sep");
                breadcrumbBar.getChildren().add(sep);
            }
        }
        showBreadcrumbBar();
    }

    private void showFallbackBreadcrumb() {
        breadcrumbBar.getChildren().clear();
        String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        Label fallback = new Label(name);
        fallback.getStyleClass().add("mt-breadcrumb-fallback");
        breadcrumbBar.getChildren().add(fallback);
        showBreadcrumbBar();
    }

    private void showBreadcrumbBar() {
        if (!breadcrumbBar.isVisible()) breadcrumbBar.setVisible(true);
        if (!breadcrumbBar.isManaged()) breadcrumbBar.setManaged(true);
    }

    private static void collectAncestors(List<DocumentSymbol> symbols, int line, int col, List<DocumentSymbol> out) {
        if (symbols == null) return;
        for (DocumentSymbol s : symbols) {
            Range r = s.getRange();
            if (r == null || r.getStart() == null || r.getEnd() == null) continue;
            if (positionInRange(line, col, r)) {
                out.add(s);
                collectAncestors(s.getChildren(), line, col, out);
                return;
            }
        }
    }

    private static boolean positionInRange(int line, int col, Range r) {
        Position s = r.getStart();
        Position e = r.getEnd();
        if (line < s.getLine() || line > e.getLine()) return false;
        if (line == s.getLine() && col < s.getCharacter()) return false;
        return line != e.getLine() || col <= e.getCharacter();
    }

    void dispose() {
        if (pending != null) pending.cancel(false);
    }
}
