package org.mtype.editor.ui.dialogs;

import javafx.application.Platform;
import javafx.stage.Window;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolLocation;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.mtype.editor.app.AppContext;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class WorkspaceSymbolPicker {

    private WorkspaceSymbolPicker() {}

    public static void open(AppContext ctx, Window owner) {
        var lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) {
            ctx.getStatusBar().setMessage("LSP not ready");
            return;
        }
        ctx.getStatusBar().setMessage("Loading workspace symbols...");
        lsp.workspaceSymbol("").thenAcceptAsync(symbols -> {
            ctx.getStatusBar().setMessage("");
            List<WorkspaceSymbol> items = symbols == null ? Collections.emptyList() : symbols;
            if (items.isEmpty()) {
                ctx.getStatusBar().setMessage("No workspace symbols");
                return;
            }
            QuickPickDialog<WorkspaceSymbol> dlg = new QuickPickDialog<>(
                    owner,
                    "Workspace Symbols",
                    items,
                    WorkspaceSymbolPicker::labelOf,
                    WorkspaceSymbolPicker::descriptionOf,
                    WorkspaceSymbolPicker::groupOf);
            Optional<WorkspaceSymbol> chosen = dlg.showAndWait();
            chosen.ifPresent(si -> openSymbol(ctx, si));
        }, Platform::runLater);
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
