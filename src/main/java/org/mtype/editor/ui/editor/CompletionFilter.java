package org.mtype.editor.ui.editor;

import org.eclipse.lsp4j.CompletionItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Ranks a completion list against the typed prefix on every keystroke (MYT-423), so what Enter accepts
 * always matches what was typed. Order: exact match, then prefix, then subsequence (case-insensitive),
 * the server's {@code sortText} breaking ties, then the server's own order. An item matching none of
 * them is dropped, and when nothing matches the result is EMPTY (the popup hides) — never the whole list.
 *
 * <p>The match key is the item's {@code filterText} when set, else its {@code label} (LSP spec). Pure.
 */
final class CompletionFilter {
    private CompletionFilter() {}

    enum Tier { EXACT, PREFIX, SUBSEQUENCE }

    /** The matching items, best first. A new list; {@code items} is not modified. */
    static List<CompletionItem> rank(List<CompletionItem> items, String prefix) {
        if (items == null || items.isEmpty()) return List.of();
        String typed = prefix == null ? "" : prefix;
        record Ranked(CompletionItem item, Tier tier, String sortText, int index) {}
        List<Ranked> matches = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            CompletionItem item = items.get(i);
            if (item == null) continue;
            Tier tier = match(key(item), typed);
            if (tier != null) matches.add(new Ranked(item, tier, item.getSortText(), i));
        }
        // nullsLast keeps the order total when only some items carry a sortText (path items have none).
        matches.sort(Comparator.comparing(Ranked::tier)
                .thenComparing(Ranked::sortText, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparingInt(Ranked::index));
        List<CompletionItem> out = new ArrayList<>(matches.size());
        for (Ranked ranked : matches) out.add(ranked.item());
        return out;
    }

    /** How {@code key} matches {@code prefix}, or null when it doesn't. An empty prefix matches everything. */
    static Tier match(String key, String prefix) {
        if (prefix.isEmpty()) return Tier.PREFIX;
        String k = key.toLowerCase(Locale.ROOT);
        String p = prefix.toLowerCase(Locale.ROOT);
        if (k.equals(p)) return Tier.EXACT;
        if (k.startsWith(p)) return Tier.PREFIX;
        return isSubsequence(p, k) ? Tier.SUBSEQUENCE : null;
    }

    /** The text an item is matched on: a non-empty filterText, else the label, else "". */
    static String key(CompletionItem item) {
        String filterText = item.getFilterText();
        if (filterText != null && !filterText.isEmpty()) return filterText;
        return item.getLabel() == null ? "" : item.getLabel();
    }

    private static boolean isSubsequence(String needle, String haystack) {
        int matched = 0;
        for (int i = 0; i < haystack.length() && matched < needle.length(); i++) {
            if (haystack.charAt(i) == needle.charAt(matched)) matched++;
        }
        return matched == needle.length();
    }
}
