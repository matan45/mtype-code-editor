package org.mtype.editor.ui.editor;

import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoldRegionsTest {

    private static DocumentSymbol symbol(SymbolKind kind, int startLine, int endLine, DocumentSymbol... children) {
        Range range = new Range(new Position(startLine, 0), new Position(endLine, 1));
        DocumentSymbol s = new DocumentSymbol("s", kind, range, range);
        s.setChildren(List.of(children));
        return s;
    }

    private static Map<Integer, FoldRegions.Region> compute(String text, DocumentSymbol... symbols) {
        String[] lines = text.split("\n", -1);
        return FoldRegions.compute(List.of(symbols), lines.length, i -> lines[i]);
    }

    @Test
    void closingBraceLineStaysVisible() {
        String text = "fn area(Shape s) {\n  let a = 1;\n  return a;\n}\n";
        assertEquals(Map.of(0, new FoldRegions.Region(0, 2)), compute(text, symbol(SymbolKind.Function, 0, 3)));
    }

    @Test
    void codeBeforeTheClosingBraceIsHiddenToo() {
        String text = "fn f() {\n  a();\n  b(); }\n";
        assertEquals(Map.of(0, new FoldRegions.Region(0, 2)), compute(text, symbol(SymbolKind.Function, 0, 2)));
    }

    @Test
    void allmanBraceMakesTheBraceLineTheHeader() {
        String text = "fn f()\n{\n  a();\n  b();\n}\n";
        assertEquals(Map.of(1, new FoldRegions.Region(1, 3)), compute(text, symbol(SymbolKind.Method, 0, 4)));
    }

    @Test
    void inlayHintCharsBeforeTheClosingBraceAreIgnored() {
        String hint = String.valueOf(MTypeCodeArea.HINT_CHAR);
        String text = "fn f() {\n  a();\n  b();\n" + hint + "}\n";
        assertEquals(Map.of(0, new FoldRegions.Region(0, 2)), compute(text, symbol(SymbolKind.Function, 0, 3)));
    }

    @Test
    void nothingToFold() {
        // Two-line function: nothing is left to hide once the closing line stays visible.
        assertTrue(compute("fn f() {\n}\n", symbol(SymbolKind.Function, 0, 1)).isEmpty());
        // Not a function / method / constructor.
        assertTrue(compute("class C {\n  x;\n  y;\n}\n", symbol(SymbolKind.Class, 0, 3)).isEmpty());
        // Symbol range beyond the document (stale symbols).
        assertTrue(compute("fn f() {\n  a();\n}\n", symbol(SymbolKind.Function, 0, 9)).isEmpty());
        // No opening brace at all.
        assertTrue(compute("fn f()\n  a();\n  b();\nend\n", symbol(SymbolKind.Function, 0, 3)).isEmpty());
    }

    @Test
    void nestedSymbolsAreCollected() {
        String text = "class C {\n  fn m() {\n    a();\n    b();\n  }\n  fn n() {\n    c();\n  }\n}\n";
        Map<Integer, FoldRegions.Region> regions = compute(text, symbol(SymbolKind.Class, 0, 8,
                symbol(SymbolKind.Method, 1, 4),
                symbol(SymbolKind.Constructor, 5, 7)));
        assertEquals(Map.of(1, new FoldRegions.Region(1, 3), 5, new FoldRegions.Region(5, 6)), regions);
    }

    /** Paragraphs 1-2 hidden: paragraph 0 is the folded header, 3 the visible closing brace. */
    private static final IntPredicate HIDDEN_1_2 = p -> p == 1 || p == 2;

    @Test
    void touchRuleTruthTable() {
        // Typing on the header: no hidden line touched, no line break -> the fold stays.
        assertFalse(FoldRegions.touchesFold(false, false, 0, 0, HIDDEN_1_2));
        // Enter at the end of the header: new visible line 1, hidden run pushed to 2-3 -> unfold.
        assertTrue(FoldRegions.touchesFold(false, true, 0, 1, p -> p == 2 || p == 3));
        // Backspace at column 0 of the closing brace: the removed line break carried the hidden style.
        assertTrue(FoldRegions.touchesFold(true, true, 2, 2, HIDDEN_1_2));
        // Column-0 insert into a hidden line un-hides it; the removed (empty) slice was hidden.
        assertTrue(FoldRegions.touchesFold(true, false, 1, 1, p -> p == 2));
        // An edit inside a hidden line that keeps it hidden (e.g. a formatter re-indent).
        assertTrue(FoldRegions.touchesFold(false, false, 1, 1, HIDDEN_1_2));
        // Enter at column 0 of the closing brace: the fold above stays valid.
        assertFalse(FoldRegions.touchesFold(false, true, 3, 4, HIDDEN_1_2));
        // An edit far away.
        assertFalse(FoldRegions.touchesFold(false, true, 10, 11, HIDDEN_1_2));
    }
}
