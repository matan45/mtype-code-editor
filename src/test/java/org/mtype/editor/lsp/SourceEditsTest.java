package org.mtype.editor.lsp;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceEditsTest {

    private static TextEdit edit(int startLine, int startChar, int endLine, int endChar, String text) {
        return new TextEdit(new Range(new Position(startLine, startChar), new Position(endLine, endChar)), text);
    }

    /** What the mType formatter sends: ONE edit from (0,0) to (lineCount,0) holding the formatted text. */
    private static TextEdit wholeDocument(String src, String formatted) {
        int lines = (int) src.chars().filter(c -> c == '\n').count() + 1;
        return edit(0, 0, lines, 0, formatted);
    }

    @Test
    void identicalWholeDocumentEditIsANoOp() {
        String src = "fn main() {\n    print(1);\n}\n";
        assertTrue(SourceEdits.minimize(src, List.of(wholeDocument(src, src))).isEmpty());
    }

    @Test
    void oneChangedLineGivesOneSmallEdit() {
        String src = "fn main() {\n  print(1);\n}\n";
        String fmt = "fn main() {\n    print(1);\n}\n";
        List<SourceEdits.Edit> edits = SourceEdits.minimize(src, List.of(wholeDocument(src, fmt)));
        assertEquals(List.of(new SourceEdits.Edit(14, 14, "  ")), edits);
        assertEquals(fmt, SourceEdits.apply(src, edits));
    }

    @Test
    void changesOnDistantLinesStaySeparate() {
        // Re-indent lines 1 and 4 only: lines 2-3 (think: a folded body) must not be replaced.
        String src = "a {\n  x;\n  keep1;\n  keep2;\n  y;\n}\n";
        String fmt = "a {\n    x;\n  keep1;\n  keep2;\n    y;\n}\n";
        List<SourceEdits.Edit> edits = SourceEdits.minimize(src, List.of(wholeDocument(src, fmt)));
        assertEquals(2, edits.size());
        assertTrue(edits.get(0).start() > edits.get(1).start(), "edits must be sorted descending");
        assertEquals(fmt, SourceEdits.apply(src, edits));
        int keepStart = src.indexOf("  keep1");
        int keepEnd = src.indexOf("  y;");
        for (SourceEdits.Edit e : edits) {
            assertTrue(e.end() <= keepStart || e.start() >= keepEnd, "edit " + e + " touches the unchanged lines");
        }
    }

    @Test
    void lineCountChangeGivesOneTrimmedEdit() {
        String src = "a {\n  x; y;\n}\n";
        String fmt = "a {\n  x;\n  y;\n}\n";
        List<SourceEdits.Edit> edits = SourceEdits.minimize(src, List.of(wholeDocument(src, fmt)));
        assertEquals(List.of(new SourceEdits.Edit(8, 8, "\n ")), edits);
        assertEquals(fmt, SourceEdits.apply(src, edits));
    }

    @Test
    void endBeyondTheLastLineClamps() {
        String src = "x\ny";
        List<SourceEdits.Edit> edits = SourceEdits.minimize(src, List.of(edit(0, 0, 99, 0, "x\nz")));
        assertEquals(List.of(new SourceEdits.Edit(2, 3, "z")), edits);
        assertEquals("x\nz", SourceEdits.apply(src, edits));
    }

    @Test
    void reversedRangeIsNormalised() {
        // lsp4j's TextEdit constructor rejects a null newText; a null can only arrive via JSON and is
        // treated as "" by minimize, like the empty text used here.
        String src = "hello world";
        assertEquals("hello", SourceEdits.apply(src, SourceEdits.minimize(src, List.of(edit(0, 11, 0, 5, "")))));
    }

    @Test
    void renameEditsApplyLikeTheOriginals() {
        String src = "var foo = 1;\nprint(foo);\nfoo = foo + 1;\n";
        List<TextEdit> edits = List.of(
                edit(0, 4, 0, 7, "bar"),
                edit(1, 6, 1, 9, "bar"),
                edit(2, 0, 2, 3, "bar"),
                edit(2, 6, 2, 9, "bar"));
        List<SourceEdits.Edit> minimal = SourceEdits.minimize(src, edits);
        assertEquals(4, minimal.size());
        assertEquals("var bar = 1;\nprint(bar);\nbar = bar + 1;\n", SourceEdits.apply(src, minimal));
    }

    @Test
    void insertsAtTheSamePositionKeepTheirArrayOrder() {
        List<TextEdit> edits = List.of(edit(0, 0, 0, 0, "a"), edit(0, 0, 0, 0, "b"));
        assertEquals("abx", SourceEdits.apply("x", SourceEdits.minimize("x", edits)));
    }

    @Test
    void minimizingNeverChangesTheResultingText() {
        String src = "fn a() {\n  x = 1;\n\n  if (x) { y(); }\n}\nfn b() {}\n";
        String[] formatted = {
                "fn a() {\n    x = 1;\n\n    if (x) {\n        y();\n    }\n}\nfn b() {}\n",
                "fn a() {\n  x = 1;\n  if (x) { y(); }\n}\n",
                "",
                src + "// trailing\n",
                "// leading\n" + src,
                src.replace("  ", "\t"),
        };
        for (String fmt : formatted) {
            List<SourceEdits.Edit> edits = SourceEdits.minimize(src, List.of(wholeDocument(src, fmt)));
            assertEquals(fmt, SourceEdits.apply(src, edits), "formatted variant: " + fmt);
        }
    }

    @Test
    void mapOffsetFollowsTheText() {
        // "0123456789": insert "XX" at 2, replace [5,7) with "Y", delete [8,9)  ->  "01XX234Y79"
        List<SourceEdits.Edit> desc = List.of(
                new SourceEdits.Edit(8, 9, ""),
                new SourceEdits.Edit(5, 7, "Y"),
                new SourceEdits.Edit(2, 2, "XX"));
        assertEquals("01XX234Y79", SourceEdits.apply("0123456789", desc));
        assertEquals(1, SourceEdits.mapOffset(1, desc));   // before every edit
        assertEquals(2, SourceEdits.mapOffset(2, desc));   // an insertion exactly at the offset lands after it
        assertEquals(6, SourceEdits.mapOffset(4, desc));   // '4' moved right by the insertion
        assertEquals(7, SourceEdits.mapOffset(5, desc));   // start of a replaced span stays at its start
        assertEquals(8, SourceEdits.mapOffset(6, desc));   // inside a replaced span: clamped to its new text
        assertEquals(8, SourceEdits.mapOffset(7, desc));   // '7' right after the replaced span
        assertEquals(10, SourceEdits.mapOffset(10, desc)); // end of text
    }
}
