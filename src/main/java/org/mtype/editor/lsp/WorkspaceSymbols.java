package org.mtype.editor.lsp;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.WorkspaceSymbolLocation;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure helpers for {@code workspace/symbol} results (MYT-447): normalise the two reply shapes and
 * merge a re-query with the Ctrl+T picker's base list. No JavaFX, so they are unit-testable.
 */
public final class WorkspaceSymbols {

    private WorkspaceSymbols() {}

    /**
     * Flattens either reply shape into {@link WorkspaceSymbol}s. lsp4j picks the side from the fields
     * it sees (an element with {@code deprecated} means the legacy {@code SymbolInformation[]}), so
     * that shape is converted instead of silently becoming an empty list.
     */
    @SuppressWarnings("deprecation")
    public static List<WorkspaceSymbol> fromEither(
            Either<List<? extends SymbolInformation>, List<? extends WorkspaceSymbol>> reply) {
        if (reply == null) return Collections.emptyList();
        if (reply.isRight()) {
            return reply.getRight() == null ? Collections.emptyList() : new ArrayList<>(reply.getRight());
        }
        List<? extends SymbolInformation> legacy = reply.getLeft();
        if (legacy == null) return Collections.emptyList();
        List<WorkspaceSymbol> out = new ArrayList<>(legacy.size());
        for (SymbolInformation info : legacy) {
            if (info == null) continue;
            WorkspaceSymbol symbol = new WorkspaceSymbol();
            symbol.setName(info.getName());
            symbol.setKind(info.getKind());
            symbol.setContainerName(info.getContainerName());
            if (info.getLocation() != null) symbol.setLocation(Either.forLeft(info.getLocation()));
            out.add(symbol);
        }
        return out;
    }

    /**
     * The picker's list: the latest re-query's results first, then the base ({@code ""}) results it
     * didn't already contain. Keeping the base means a prefix miss on the server (typing "og") never
     * hides an entry the dialog's own substring filter would still show.
     */
    public static List<WorkspaceSymbol> merge(List<WorkspaceSymbol> latest, List<WorkspaceSymbol> base) {
        Map<String, WorkspaceSymbol> byKey = new LinkedHashMap<>();
        addAll(byKey, latest);
        addAll(byKey, base);
        return new ArrayList<>(byKey.values());
    }

    private static void addAll(Map<String, WorkspaceSymbol> byKey, List<WorkspaceSymbol> symbols) {
        if (symbols == null) return;
        for (WorkspaceSymbol symbol : symbols) {
            if (symbol != null) byKey.putIfAbsent(keyOf(symbol), symbol);
        }
    }

    /** Identity of a symbol for de-duplication: name, file and start position. */
    static String keyOf(WorkspaceSymbol symbol) {
        String uri = "";
        int line = -1;
        int character = -1;
        Either<Location, WorkspaceSymbolLocation> location = symbol.getLocation();
        if (location != null && location.isLeft() && location.getLeft() != null) {
            Location full = location.getLeft();
            uri = full.getUri();
            if (full.getRange() != null && full.getRange().getStart() != null) {
                Position start = full.getRange().getStart();
                line = start.getLine();
                character = start.getCharacter();
            }
        } else if (location != null && location.isRight() && location.getRight() != null) {
            uri = location.getRight().getUri();
        }
        return symbol.getName() + '\u0000' + uri + '\u0000' + line + ':' + character;
    }
}
