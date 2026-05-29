package org.mtype.editor.ui.editor;

import javafx.application.Platform;
import org.fxmisc.richtext.model.StyleSpans;
import org.mtype.editor.syntax.Tokenizers;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Owns the editor's style layers and composes them onto the {@link MTypeCodeArea}.
 *
 * <p>Four overlay layers, applied in order so later layers win on overlap:
 * <ol>
 *   <li><b>tokens</b> — syntax highlighting from {@link Tokenizers} (the base layer)</li>
 *   <li><b>semantic</b> — semantic tokens from the language server</li>
 *   <li><b>diagnostic</b> — squiggle underlines mapped to display offsets</li>
 *   <li><b>linkHover</b> — the Ctrl+hover hyperlink underline</li>
 * </ol>
 *
 * <p>Spans are computed against the hint-augmented DISPLAY document, so any operation that mutates the
 * document (format, rename, quick fix, fold/unfold, inlay-hint segment changes) must
 * {@link #invalidateOverlays()} then {@link #applyHighlightingNow()} so stale-length overlays don't
 * fail RichTextFX's {@code setStyleSpans} length check.
 */
final class StyleCompositor {
    private final MTypeCodeArea codeArea;
    private final Path path;

    private StyleSpans<Collection<String>> tokens;
    private StyleSpans<Collection<String>> semantic;
    private StyleSpans<Collection<String>> diagnostic;
    private StyleSpans<Collection<String>> linkHover;
    private ScheduledFuture<?> pendingHighlight;

    StyleCompositor(MTypeCodeArea codeArea, Path path) {
        this.codeArea = codeArea;
        this.path = path;
    }

    /** Debounced background re-tokenize (120 ms). Skips applying if the display text changed meanwhile. */
    void scheduleHighlight() {
        if (pendingHighlight != null) pendingHighlight.cancel(false);
        String snapshot = codeArea.getText();
        pendingHighlight = EditorTab.BG_EXEC.schedule(() -> {
            StyleSpans<Collection<String>> spans = Tokenizers.computeFor(path, snapshot);
            Platform.runLater(() -> {
                // Skip if the display text changed since the snapshot (e.g. inlay-hint segments were
                // inserted/removed) — applying stale-length spans would fail the setStyleSpans length
                // check. A fresh highlight pass (or runHintMutation) will re-apply correct spans.
                if (!snapshot.equals(codeArea.getText())) return;
                tokens = spans;
                apply();
            });
        }, 120, TimeUnit.MILLISECONDS);
    }

    /** Synchronously re-tokenize the current display text and re-apply all layers. */
    void applyHighlightingNow() {
        tokens = Tokenizers.computeFor(path, codeArea.getText());
        apply();
    }

    /** Overlay the four layers and push them to the code area. No-op until the base layer exists. */
    void apply() {
        if (tokens == null) return;
        StyleSpans<Collection<String>> combined = tokens;
        if (semantic != null) {
            try { combined = combined.overlay(semantic, StyleCompositor::mergeStyles); } catch (Exception ignored) {}
        }
        if (diagnostic != null) {
            try { combined = combined.overlay(diagnostic, StyleCompositor::mergeStyles); } catch (Exception ignored) {}
        }
        if (linkHover != null) {
            try { combined = combined.overlay(linkHover, StyleCompositor::mergeStyles); } catch (Exception ignored) {}
        }
        codeArea.setStyleSpans(0, combined);
    }

    void setSemantic(StyleSpans<Collection<String>> spans) {
        this.semantic = spans;
        apply();
    }

    void setDiagnostic(StyleSpans<Collection<String>> spans) {
        this.diagnostic = spans;
        apply();
    }

    void setLinkHover(StyleSpans<Collection<String>> spans) {
        this.linkHover = spans;
        apply();
    }

    /** Drop the diagnostic + semantic overlays (their offsets are stale after a document mutation). */
    void invalidateOverlays() {
        this.diagnostic = null;
        this.semantic = null;
    }

    void dispose() {
        if (pendingHighlight != null) pendingHighlight.cancel(false);
    }

    private static Collection<String> mergeStyles(Collection<String> a, Collection<String> b) {
        Set<String> merged = new LinkedHashSet<>(a);
        merged.addAll(b);
        return merged;
    }
}
