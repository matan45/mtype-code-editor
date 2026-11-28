package org.mtype.editor.ui.editor;

import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionFilterTest {

    private static CompletionItem item(String label) {
        return new CompletionItem(label);
    }

    private static CompletionItem item(String label, String sortText) {
        CompletionItem item = new CompletionItem(label);
        item.setSortText(sortText);
        return item;
    }

    private static List<String> labels(List<CompletionItem> items) {
        return items.stream().map(CompletionItem::getLabel).toList();
    }

    @Test
    void noMatchReturnsEmpty() {
        // Was: nothing matched, so the WHOLE list came back and Enter accepted its first item.
        List<CompletionItem> ranked = CompletionFilter.rank(List.of(item("bar"), item("baz"), item("bark")), "bazz");

        assertTrue(ranked.isEmpty());
    }

    @Test
    void typoOnlyMatchDropped() {
        // The server keeps Levenshtein matches ("pirnt" -> print); locally they no longer match.
        assertTrue(CompletionFilter.rank(List.of(item("print"), item("private")), "pirnt").isEmpty());
    }

    @Test
    void exactThenPrefixThenSubsequence() {
        List<CompletionItem> items = List.of(item("xfoo"), item("foobar"), item("fxoxo"), item("foo"));

        assertEquals(List.of("foo", "foobar", "xfoo", "fxoxo"), labels(CompletionFilter.rank(items, "foo")));
    }

    @Test
    void sortTextBreaksTiesWithinTier() {
        List<CompletionItem> items = List.of(item("fooB", "2"), item("fooA", "1"));

        assertEquals(List.of("fooA", "fooB"), labels(CompletionFilter.rank(items, "foo")));
    }

    @Test
    void tierBeatsSortText() {
        List<CompletionItem> items = List.of(item("foobar", "00"), item("foo", "99"));

        assertEquals(List.of("foo", "foobar"), labels(CompletionFilter.rank(items, "foo")));
    }

    @Test
    void missingSortTextKeepsServerOrder() {
        // Import-path items carry no sortText and arrive folders first.
        List<CompletionItem> items = List.of(item("src/"), item("lib/"), item("Main.mt"));

        assertEquals(List.of("src/", "lib/", "Main.mt"), labels(CompletionFilter.rank(items, "")));
    }

    @Test
    void mixedSortTextIsTotalOrder() {
        List<CompletionItem> items = List.of(item("b"), item("a", "1"), item("c"));

        assertEquals(List.of("a", "b", "c"), labels(CompletionFilter.rank(items, "")));
    }

    @Test
    void filterTextTakesPrecedenceOverLabel() {
        CompletionItem item = item("print");
        item.setFilterText("log");

        assertEquals(List.of(item), CompletionFilter.rank(List.of(item), "lo"));
        assertTrue(CompletionFilter.rank(List.of(item), "pr").isEmpty());
    }

    @Test
    void emptyFilterTextFallsBackToLabel() {
        CompletionItem item = item("print");
        item.setFilterText("");

        assertEquals(List.of(item), CompletionFilter.rank(List.of(item), "pr"));
    }

    @Test
    void matchingIsCaseInsensitive() {
        assertEquals(List.of("Map", "mapper"), labels(CompletionFilter.rank(List.of(item("mapper"), item("Map")), "map")));
        assertEquals(List.of("HashMap"), labels(CompletionFilter.rank(List.of(item("HashMap")), "hashm")));
    }

    @Test
    void emptyPrefixKeepsAllInSortTextOrder() {
        List<CompletionItem> items = List.of(item("b", "2"), item("a", "1"));

        assertEquals(List.of("a", "b"), labels(CompletionFilter.rank(items, "")));
    }

    @Test
    void subsequenceRespectsOrder() {
        assertTrue(CompletionFilter.rank(List.of(item("ab")), "ba").isEmpty());
    }

    @Test
    void prefixLongerThanKeyDoesNotMatch() {
        assertTrue(CompletionFilter.rank(List.of(item("fo")), "foo").isEmpty());
    }

    @Test
    void nullLabelSafe() {
        CompletionItem unlabeled = new CompletionItem();

        assertEquals(List.of(unlabeled), CompletionFilter.rank(List.of(unlabeled), ""));
        assertTrue(CompletionFilter.rank(List.of(unlabeled), "a").isEmpty());
    }

    @Test
    void inputListUnmodified() {
        CompletionItem b = item("b", "2");
        CompletionItem a = item("a", "1");
        List<CompletionItem> items = new ArrayList<>(List.of(b, a));

        assertEquals(List.of(a, b), CompletionFilter.rank(items, ""));
        assertEquals(List.of(b, a), items);
    }
}
