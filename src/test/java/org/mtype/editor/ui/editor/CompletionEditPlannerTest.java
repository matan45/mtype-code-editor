package org.mtype.editor.ui.editor;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionEditPlannerTest {

    private static final String IMPORT_FOO = "import { Foo } from \"./Foo.mt\";\n";

    /* ----- helpers ----- */

    private static TextEdit edit(int line, int startCol, int endCol, String text) {
        return new TextEdit(new Range(new Position(line, startCol), new Position(line, endCol)), text);
    }

    private static TextEdit insertAt(int line, int col, String text) {
        return edit(line, col, col, text);
    }

    private static CompletionItem withEdit(String label, TextEdit edit) {
        CompletionItem item = new CompletionItem(label);
        item.setTextEdit(Either.forLeft(edit));
        return item;
    }

    private static CompletionItem snippet(String label, String snippetText) {
        CompletionItem item = new CompletionItem(label);
        item.setInsertText(snippetText);
        item.setInsertTextFormat(InsertTextFormat.Snippet);
        return item;
    }

    private static int lineStart(String source, int caret) {
        return source.lastIndexOf('\n', caret - 1) + 1;
    }

    private static int lineOf(String source, int offset) {
        return (int) source.substring(0, offset).chars().filter(c -> c == '\n').count();
    }

    private static String lineText(String source, int caret) {
        int end = source.indexOf('\n', caret);
        return source.substring(lineStart(source, caret), end < 0 ? source.length() : end);
    }

    /** Plans {@code item} on {@code marked} ('|' = caret), as answered by a request at {@code requestCol}. */
    private static CompletionEditPlanner.Plan plan(String marked, int requestCol, CompletionItem item) {
        int caret = marked.indexOf('|');
        String source = marked.replace("|", "");
        CompletionAnchor anchor = CompletionAnchor.at(lineText(source, caret), lineOf(source, caret),
                caret - lineStart(source, caret));
        return CompletionEditPlanner.plan(source, caret, anchor, requestCol, item);
    }

    /** Plans {@code item} on {@code marked}, as answered at the caret itself. */
    private static CompletionEditPlanner.Plan plan(String marked, CompletionItem item) {
        int caret = marked.indexOf('|');
        return plan(marked, caret - lineStart(marked, caret), item);
    }

    /** The text after applying the plan's changes in order, each at its ORIGINAL offsets (as the controller does). */
    private static String apply(String marked, CompletionEditPlanner.Plan plan) {
        StringBuilder text = new StringBuilder(marked.replace("|", ""));
        for (CompletionEditPlanner.Change change : plan.changes()) {
            text.replace(change.start(), change.end(), change.text());
        }
        return text.toString();
    }

    /* ----- main edit range ----- */

    @Test
    void noTextEditUsesInsertText() {
        CompletionItem item = new CompletionItem("print");
        item.setInsertText("println");
        CompletionEditPlanner.Plan plan = plan("pri|", item);

        assertEquals("println", apply("pri|", plan));
        assertEquals(7, plan.selectionStart());
        assertEquals(7, plan.selectionEnd());
    }

    @Test
    void labelWhenNoInsertText() {
        assertEquals("print", apply("pri|", plan("pri|", new CompletionItem("print"))));
    }

    @Test
    void serverRangeNarrowerThanPrefixIsWidened() {
        // The server counts UTF-8 bytes: with "é" before the caret it sees the prefix "ba", not "bar".
        String marked = "s = \"é\"; foo.bar|";
        CompletionEditPlanner.Plan plan = plan(marked, withEdit("barrel", edit(0, 14, 16, "barrel")));

        assertEquals("s = \"é\"; foo.barrel", apply(marked, plan));
        assertEquals(13, plan.insertStart());
        assertEquals(19, plan.selectionStart());
    }

    @Test
    void serverRangeWiderThanWordClampedToWordStart() {
        assertEquals("foo.bar", apply("foo.ba|", plan("foo.ba|", withEdit("bar", edit(0, 3, 6, "bar")))));
    }

    @Test
    void staleServerEndAfterBackspaceKeepsTextAfterCaret() {
        // Requested at "foo.ba|)" (col 6, range [4,6]); the user then backspaced to "foo.b|)".
        String marked = "foo.b|)";
        CompletionEditPlanner.Plan plan = plan(marked, 6, withEdit("barrel", edit(0, 4, 6, "barrel")));

        assertEquals("foo.barrel)", apply(marked, plan));
    }

    @Test
    void serverEndPastRequestCaretReplacesRestOfWord() {
        CompletionEditPlanner.Plan plan = plan("pri|nt", withEdit("print", edit(0, 0, 5, "print")));

        assertEquals("print", apply("pri|nt", plan));
    }

    @Test
    void serverEndPastRequestCaretClampedToWordEnd() {
        CompletionEditPlanner.Plan plan = plan("pri|n)", withEdit("print", edit(0, 0, 6, "print")));

        assertEquals("print)", apply("pri|n)", plan));
    }

    @Test
    void serverRangeOnOtherLineIgnored() {
        String marked = "a\nfoo|";

        assertEquals("a\nfood", apply(marked, plan(marked, withEdit("food", edit(0, 0, 1, "food")))));
    }

    @Test
    void caretOutsideAnchorWordReturnsNull() {
        String source = "foo bar";
        CompletionAnchor anchorOfFoo = CompletionAnchor.at(source, 0, 3);

        assertNull(CompletionEditPlanner.plan(source, 7, anchorOfFoo, 3, new CompletionItem("food")));
    }

    @Test
    void nullNewTextReturnsNull() {
        assertNull(plan("fo|", new CompletionItem()));
    }

    /* ----- additional edits (auto-import) ----- */

    @Test
    void importAboveShiftsInsertStartAndCaret() {
        String marked = "import { A } from \"./A.mt\";\n\nx = Fo|";
        CompletionItem item = withEdit("Foo", edit(2, 4, 6, "Foo"));
        item.setAdditionalTextEdits(List.of(insertAt(1, 0, IMPORT_FOO)));
        CompletionEditPlanner.Plan plan = plan(marked, item);

        String result = apply(marked, plan);
        assertEquals("import { A } from \"./A.mt\";\n" + IMPORT_FOO + "\nx = Foo", result);
        assertTrue(plan.changes().get(0).main());
        assertFalse(plan.changes().get(1).main());
        assertEquals(result.lastIndexOf("Foo"), plan.insertStart());
        assertEquals(result.length(), plan.selectionStart());
        assertTrue(plan.hasAdditionalEdits());
    }

    @Test
    void importAtSameOffsetGoesFirst() {
        // No imports yet and typing on line 0: the server inserts the import at (0,0), where the word starts.
        CompletionItem item = withEdit("Foo", edit(0, 0, 2, "Foo"));
        item.setAdditionalTextEdits(List.of(insertAt(0, 0, IMPORT_FOO)));
        CompletionEditPlanner.Plan plan = plan("Fo|", item);

        assertEquals(IMPORT_FOO + "Foo", apply("Fo|", plan));
        assertEquals(IMPORT_FOO.length(), plan.insertStart());
        assertEquals(IMPORT_FOO.length() + 3, plan.selectionStart());
    }

    @Test
    void importAtSameOffsetAsEmptyPrefixGoesFirst() {
        CompletionItem item = new CompletionItem("Foo");
        item.setAdditionalTextEdits(List.of(insertAt(0, 0, IMPORT_FOO)));

        assertEquals(IMPORT_FOO + "Foo", apply("|", plan("|", item)));
    }

    @Test
    void multipleInsertsAtOnePositionKeepArrayOrder() {
        CompletionItem item = new CompletionItem("Foo");
        item.setAdditionalTextEdits(List.of(insertAt(0, 0, "import A;\n"), insertAt(0, 0, "import B;\n")));

        assertEquals("import A;\nimport B;\n\nFoo", apply("\nFo|", plan("\nFo|", item)));
    }

    @Test
    void additionalEditAfterWordDoesNotShift() {
        CompletionItem item = new CompletionItem("Foo");
        item.setAdditionalTextEdits(List.of(insertAt(1, 3, "!")));
        CompletionEditPlanner.Plan plan = plan("Fo|\nend", item);

        assertEquals("Foo\nend!", apply("Fo|\nend", plan));
        assertEquals(0, plan.insertStart());
        assertEquals(3, plan.selectionStart());
    }

    @Test
    void overlappingAdditionalEditDropped() {
        CompletionItem item = new CompletionItem("food");
        item.setAdditionalTextEdits(List.of(edit(0, 0, 1, "X")));
        CompletionEditPlanner.Plan plan = plan("foo|", item);

        assertEquals("food", apply("foo|", plan));
        assertFalse(plan.hasAdditionalEdits());
        assertEquals(1, plan.changes().size());
    }

    /* ----- snippets ----- */

    @Test
    void snippetPlaceholdersShiftWithImport() {
        String marked = "\nfo|";
        CompletionItem item = snippet("foo", "foo(${1:a}, ${2:b})$0");
        item.setTextEdit(Either.forLeft(edit(1, 0, 2, "foo(${1:a}, ${2:b})$0")));
        item.setAdditionalTextEdits(List.of(insertAt(0, 0, IMPORT_FOO)));
        CompletionEditPlanner.Plan plan = plan(marked, item);

        assertEquals(IMPORT_FOO + "\nfoo(a, b)", apply(marked, plan));
        int start = IMPORT_FOO.length() + 1;
        assertEquals(start, plan.insertStart());
        assertEquals("foo(a, b)", plan.insertText());
        assertEquals(start + 4, plan.selectionStart()); // "a"
        assertEquals(start + 5, plan.selectionEnd());

        SnippetSession session = plan.snippet();
        assertNotNull(session);
        SnippetSession.Selection second = session.advance(start + 5, start + 4, start + 5);
        assertEquals(new SnippetSession.Selection(start + 7, start + 8, false), second); // "b"
        SnippetSession.Selection last = session.advance(start + 8, start + 7, start + 8);
        assertEquals(new SnippetSession.Selection(start + 9, start + 9, true), last);
    }

    @Test
    void snippetWithoutTabStopsCaretAtEnd() {
        CompletionEditPlanner.Plan plan = plan("d.ba|", snippet("bark", "bark()$0"));

        assertEquals("d.bark()", apply("d.ba|", plan));
        assertNull(plan.snippet());
        assertEquals(8, plan.selectionStart());
        assertEquals(8, plan.selectionEnd());
    }

    @Test
    void snippetWithoutFinalTabStopSelectsPlaceholder() {
        // Builtins come without $0.
        CompletionEditPlanner.Plan plan = plan("pri|", snippet("print", "print(${1:value})"));

        assertEquals("print(value)", apply("pri|", plan));
        assertNotNull(plan.snippet());
        assertEquals(6, plan.selectionStart());
        assertEquals(11, plan.selectionEnd());
    }

    @Test
    void templateSnippetSelectsFirstPlaceholder() {
        CompletionEditPlanner.Plan plan = plan("cla|", snippet("class template", "class ${1:Name} {\n    $0\n}"));

        assertEquals("class Name {\n    \n}", apply("cla|", plan));
        assertEquals(6, plan.selectionStart());
        assertEquals(10, plan.selectionEnd());
    }
}
