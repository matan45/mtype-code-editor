package org.mtype.editor.lsp;

import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.WorkspaceSymbol;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceSymbolsTest {

    private static Location at(String uri) {
        Position start = new Position(0, 6);
        return new Location(uri, new Range(start, new Position(0, 6 + 3)));
    }

    private static WorkspaceSymbol symbol(String name, String uri) {
        return new WorkspaceSymbol(name, SymbolKind.Class, Either.forLeft(at(uri)));
    }

    private static List<String> names(List<WorkspaceSymbol> symbols) {
        return symbols.stream().map(WorkspaceSymbol::getName).toList();
    }

    @Test
    void rightSideIsReturnedAsIs() {
        List<WorkspaceSymbol> right = List.of(symbol("Dog", "file:///Dog.mt"));
        assertEquals(right, WorkspaceSymbols.fromEither(Either.forRight(right)));
    }

    @Test
    @SuppressWarnings("deprecation")
    void legacySymbolInformationIsConvertedNotDropped() {
        SymbolInformation info = new SymbolInformation("Dog", SymbolKind.Class, at("file:///Dog.mt"));
        info.setContainerName("zoo");

        List<WorkspaceSymbol> out = WorkspaceSymbols.fromEither(Either.forLeft(List.of(info)));

        assertEquals(1, out.size());
        WorkspaceSymbol dog = out.getFirst();
        assertEquals("Dog", dog.getName());
        assertEquals(SymbolKind.Class, dog.getKind());
        assertEquals("zoo", dog.getContainerName());
        assertTrue(dog.getLocation().isLeft());
        assertEquals(new Position(0, 6), dog.getLocation().getLeft().getRange().getStart());
    }

    @Test
    void nullReplyGivesEmptyList() {
        assertTrue(WorkspaceSymbols.fromEither(null).isEmpty());
    }

    @Test
    void mergePutsLatestFirstAndDropsDuplicates() {
        WorkspaceSymbol dog = symbol("Dog", "file:///Dog.mt");
        WorkspaceSymbol ant = symbol("Ant", "file:///Ant.mt");
        WorkspaceSymbol dogAgain = symbol("Dog", "file:///Dog.mt");

        List<WorkspaceSymbol> merged = WorkspaceSymbols.merge(List.of(dog), List.of(ant, dogAgain));

        assertEquals(List.of("Dog", "Ant"), names(merged));
    }

    @Test
    void sameNameInDifferentFilesStaysDistinct() {
        WorkspaceSymbol a = symbol("Shared", "file:///a.mt");
        WorkspaceSymbol b = symbol("Shared", "file:///b.mt");

        assertEquals(2, WorkspaceSymbols.merge(List.of(a), List.of(b)).size());
    }

    @Test
    void baseEntriesSurviveAPrefixMissOnTheServer() {
        // Typing "og": the server matches by prefix and returns nothing, but the picker's
        // substring filter still shows Dog from the base list. The merge must keep it.
        WorkspaceSymbol dog = symbol("Dog", "file:///Dog.mt");

        assertEquals(List.of("Dog"), names(WorkspaceSymbols.merge(List.of(), List.of(dog))));
    }
}
