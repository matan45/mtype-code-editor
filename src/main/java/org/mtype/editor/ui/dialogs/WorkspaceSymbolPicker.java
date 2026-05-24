package org.mtype.editor.ui.dialogs;

import javafx.application.Platform;
import javafx.stage.Window;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
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
            List<SymbolInformation> items = symbols == null ? Collections.emptyList() : symbols;
            if (items.isEmpty()) {
                ctx.getStatusBar().setMessage("No workspace symbols");
                return;
            }
            QuickPickDialog<SymbolInformation> dlg = new QuickPickDialog<>(
                    owner,
                    "Workspace Symbols",
                    items,
                    WorkspaceSymbolPicker::labelOf,
                    WorkspaceSymbolPicker::descriptionOf,
                    WorkspaceSymbolPicker::groupOf);
            Optional<SymbolInformation> chosen = dlg.showAndWait();
            chosen.ifPresent(si -> openSymbol(ctx, si));
        }, Platform::runLater);
    }

    private static void openSymbol(AppContext ctx, SymbolInformation si) {
        Location loc = si.getLocation();
        if (loc == null || loc.getUri() == null || loc.getRange() == null
                || loc.getRange().getStart() == null) {
            ctx.getStatusBar().setMessage("Bad symbol location");
            return;
        }
        try {
            Path p = Paths.get(java.net.URI.create(loc.getUri()));
            Position start = loc.getRange().getStart();
            ctx.getTabPane().openAt(p, start.getLine(), start.getCharacter());
        } catch (Exception ex) {
            ctx.getStatusBar().setMessage("Bad URI: " + loc.getUri());
        }
    }

    private static String labelOf(SymbolInformation s) {
        String name = s.getName() == null ? "?" : s.getName();
        String container = s.getContainerName();
        if (container == null || container.isBlank()) return name;
        return container + "." + name;
    }

    private static String descriptionOf(SymbolInformation s) {
        Location loc = s.getLocation();
        if (loc == null || loc.getUri() == null) return "";
        String uri = loc.getUri();
        int slash = uri.lastIndexOf('/');
        return slash >= 0 ? uri.substring(slash + 1) : uri;
    }

    private static String groupOf(SymbolInformation s) {
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
