package org.mtype.editor.ui.editor;

import org.eclipse.lsp4j.InlayHintKind;

/**
 * A virtual inlay-hint segment embedded directly in the {@link MTypeCodeArea} document so that
 * RichTextFX computes correct selection/caret/wrap geometry around it (unlike the old overlay +
 * {@code translateX} approach, which faked the position and broke text selection).
 *
 * <p>Each segment occupies exactly ONE character in the RichTextFX document model (rendered as the
 * Unicode object-replacement char {@code ￼} by {@code NodeSegmentOpsBase}); the visible content
 * is the pill node built by {@link MTypeCodeArea}. Because it adds a character to the document, every
 * mapping between the displayed document and the real source text is handled by
 * {@code MTypeCodeArea}'s display↔source offset helpers — the language server never sees these chars.
 */
record InlayHintSeg(String text, InlayHintKind kind) {
    /** Non-null sentinel required by {@code NodeSegmentOpsBase}'s empty-segment contract. */
    static final InlayHintSeg EMPTY = new InlayHintSeg("", null);
}
