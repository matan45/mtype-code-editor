package org.mtype.editor.ui.dialogs;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.stage.Window;
import javafx.util.Duration;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolLocation;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.lsp.LspBridge;
import org.mtype.editor.lsp.WorkspaceSymbols;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

/**
 * Ctrl+T "Go to Symbol in Workspace" (MYT-447). The dialog opens at once and lists the server's
 * empty-query result (every symbol, capped); typing re-queries the server after a short pause so
 * symbols past the cap are reachable, while the dialog's own filter narrows the list instantly.
 */
public final class WorkspaceSymbolPicker {

    private static final Duration REQUERY_DELAY = Duration.millis(150);

    private WorkspaceSymbolPicker() {}

    public static void open(AppContext ctx, Window owner) {
        var lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) {
            ctx.getStatusBar().setMessage("LSP not ready");
            return;
        }
        QuickPickDialog<WorkspaceSymbol> dlg = new QuickPickDialog<>(
                owner,
                "Workspace Symbols",
                List.of(),
                WorkspaceSymbolPicker::labelOf,
                WorkspaceSymbolPicker::descriptionOf,
                WorkspaceSymbolPicker::groupOf);
        dlg.setPlaceholder("Loading workspace symbols…");
        Search search = new Search(lsp, dlg);
        dlg.setOnQueryChanged(search::queryChanged);
        search.start();
        Optional<WorkspaceSymbol> chosen = dlg.showAndWait();
        search.close();
        chosen.ifPresent(si -> openSymbol(ctx, si));
    }

    /**
     * One picker session: the {@code ""} base list plus debounced re-queries. A generation counter
     * drops responses for text the user has since changed (the FindInFilesWindow idiom), and
     * nothing touches the dialog once it has closed. Runs on the FX thread.
     */
    private static final class Search {
        private final LspBridge lsp;
        private final QuickPickDialog<WorkspaceSymbol> dlg;
        private final PauseTransition debounce = new PauseTransition(REQUERY_DELAY);
        private List<WorkspaceSymbol> base = List.of();
        private List<WorkspaceSymbol> latest = List.of();
        private String query = "";
        private long generation;
        private boolean closed;

        Search(LspBridge lsp, QuickPickDialog<WorkspaceSymbol> dlg) {
            this.lsp = lsp;
            this.dlg = dlg;
            debounce.setOnFinished(_ -> requery());
        }

        void start() {
            lsp.workspaceSymbol("").thenAcceptAsync(symbols -> {
                if (closed) return;
                base = symbols;
                show();
            }, Platform::runLater);
        }

        void queryChanged(String text) {
            query = text == null ? "" : text.trim();
            if (query.isEmpty()) {
                debounce.stop();
                ++generation; // any in-flight result is for text that is gone
                latest = List.of();
                show();
                return;
            }
            debounce.playFromStart();
        }

        void close() {
            closed = true;
            ++generation;
            debounce.stop();
        }

        private void requery() {
            final long myGen = ++generation;
            lsp.workspaceSymbol(query).thenAcceptAsync(symbols -> {
                if (closed || myGen != generation) return; // superseded, or dialog gone
                latest = symbols;
                show();
            }, Platform::runLater);
        }

        private void show() {
            dlg.setItems(WorkspaceSymbols.merge(latest, base));
            dlg.setPlaceholder(base.isEmpty() && latest.isEmpty()
                    ? "No workspace symbols"
                    : "No matching symbols");
        }
    }

    private static void openSymbol(AppContext ctx, WorkspaceSymbol si) {
        Either<Location, WorkspaceSymbolLocation> location = si.getLocation();
        if (location == null) {
            ctx.getStatusBar().setMessage("Bad symbol location");
            return;
        }
        String uri;
        Position start;
        if (location.isLeft() && location.getLeft() != null
                && location.getLeft().getRange() != null
                && location.getLeft().getRange().getStart() != null) {
            // Full Location with a range — jump to the exact position.
            uri = location.getLeft().getUri();
            start = location.getLeft().getRange().getStart();
        } else if (location.isRight() && location.getRight() != null) {
            // WorkspaceSymbolLocation carries only a URI — open at the top of the file.
            uri = location.getRight().getUri();
            start = new Position(0, 0);
        } else {
            ctx.getStatusBar().setMessage("Bad symbol location");
            return;
        }
        if (uri == null) {
            ctx.getStatusBar().setMessage("Bad symbol location");
            return;
        }
        try {
            Path p = Paths.get(java.net.URI.create(uri));
            ctx.getTabPane().openAt(p, start.getLine(), start.getCharacter());
        } catch (Exception ex) {
            ctx.getStatusBar().setMessage("Bad URI: " + uri);
        }
    }

    private static String labelOf(WorkspaceSymbol s) {
        String name = s.getName() == null ? "?" : s.getName();
        String container = s.getContainerName();
        if (container == null || container.isBlank()) return name;
        return container + "." + name;
    }

    private static String descriptionOf(WorkspaceSymbol s) {
        String uri = uriOf(s);
        if (uri == null) return "";
        int slash = uri.lastIndexOf('/');
        return slash >= 0 ? uri.substring(slash + 1) : uri;
    }

    private static String uriOf(WorkspaceSymbol s) {
        Either<Location, WorkspaceSymbolLocation> loc = s.getLocation();
        if (loc == null) return null;
        if (loc.isLeft() && loc.getLeft() != null) return loc.getLeft().getUri();
        if (loc.isRight() && loc.getRight() != null) return loc.getRight().getUri();
        return null;
    }

    private static String groupOf(WorkspaceSymbol s) {
        SymbolKind k = s.getKind();
        if (k == null) return "Other";
        return switch (k) {
            case Class, Interface, Struct, Enum -> "Types";
            case Method, Function, Constructor -> "Functions";
            case Field, Property, Variable, Constant, EnumMember -> "Fields";
            case Module, Namespace, Package -> "Modules";
            default -> "Other";
        };
    }
}
