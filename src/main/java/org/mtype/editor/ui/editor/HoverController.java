package org.mtype.editor.ui.editor;

import javafx.geometry.Point2D;
import javafx.application.Platform;
import javafx.scene.Cursor;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import org.fxmisc.richtext.CharacterHit;
import org.fxmisc.richtext.event.MouseOverTextEvent;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.mtype.editor.app.AppContext;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;

/**
 * Hover documentation popup and the Ctrl+hover hyperlink underline. Installs its own mouse/key
 * handlers on the code area (mirroring {@link InlayHintsController}'s self-wiring). The underline span
 * is pushed into {@link StyleCompositor}'s top overlay layer; Ctrl+<i>click</i> go-to-definition is
 * wired separately in {@link EditorTab}.
 */
final class HoverController {
    private final MTypeCodeArea codeArea;
    private final Path path;
    private final AppContext ctx;
    private final boolean lspManaged;
    private final StyleCompositor compositor;
    private final HoverPopup hoverPopup = new HoverPopup();

    private boolean ctrlDown;
    private double lastMouseX = -1;
    private double lastMouseY = -1;
    private int linkHoverStart = -1;
    private int linkHoverEnd = -1;

    HoverController(MTypeCodeArea codeArea, Path path, AppContext ctx, boolean lspManaged, StyleCompositor compositor) {
        this.codeArea = codeArea;
        this.path = path;
        this.ctx = ctx;
        this.lspManaged = lspManaged;
        this.compositor = compositor;
        install();
    }

    private void install() {
        codeArea.setMouseOverTextDelay(Duration.ofMillis(500));
        codeArea.addEventHandler(MouseOverTextEvent.MOUSE_OVER_TEXT_BEGIN, e -> {
            Point2D screen = e.getScreenPosition();
            Point2D local = codeArea.screenToLocal(screen);
            int charIdx = local == null
                    ? e.getCharacterIndex()
                    : codeArea.hit(local.getX(), local.getY()).getInsertionIndex();
            requestHover(charIdx, screen);
        });
        codeArea.addEventHandler(MouseOverTextEvent.MOUSE_OVER_TEXT_END, _ -> hoverPopup.hide());

        // Ctrl+hover hyperlink underline.
        codeArea.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            lastMouseX = e.getX();
            lastMouseY = e.getY();
            updateLinkHover();
        });
        codeArea.addEventFilter(MouseEvent.MOUSE_EXITED, _ -> {
            lastMouseX = -1;
            lastMouseY = -1;
            clearLinkHover();
        });
        codeArea.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.CONTROL) {
                ctrlDown = true;
                updateLinkHover();
            }
        });
        codeArea.addEventFilter(KeyEvent.KEY_RELEASED, e -> {
            if (e.getCode() == KeyCode.CONTROL) {
                ctrlDown = false;
                clearLinkHover();
            }
        });
    }

    private void requestHover(int charIdx, Point2D pos) {
        if (!lspManaged) return;
        if (ctx.getLspBridge() == null) return;
        int[] lc = codeArea.displayToSourceLineChar(charIdx);
        ctx.getLspBridge().hover(path, lc[0], lc[1]).thenAcceptAsync(content -> {
            if (content == null || content.isBlank()) { hoverPopup.hide(); return; }
            hoverPopup.show(codeArea, content, pos.getX() + 10, pos.getY() + 10);
        }, Platform::runLater);
    }

    private void updateLinkHover() {
        if (!ctrlDown || lastMouseX < 0 || lastMouseY < 0) {
            clearLinkHover();
            return;
        }
        CharacterHit hit = codeArea.hit(lastMouseX, lastMouseY);
        int[] range = Words.identifierRangeAt(codeArea, hit.getInsertionIndex());
        if (range == null) {
            clearLinkHover();
            return;
        }
        if (range[0] == linkHoverStart && range[1] == linkHoverEnd) return;
        linkHoverStart = range[0];
        linkHoverEnd = range[1];
        compositor.setLinkHover(buildLinkHoverSpans(range[0], range[1]));
        codeArea.setCursor(Cursor.HAND);
    }

    private void clearLinkHover() {
        if (linkHoverStart < 0) return;
        linkHoverStart = -1;
        linkHoverEnd = -1;
        compositor.setLinkHover(null);
        codeArea.setCursor(null);
    }

    private StyleSpans<Collection<String>> buildLinkHoverSpans(int start, int end) {
        int length = codeArea.getLength();
        if (start < 0 || end > length || start >= end) return null;
        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        if (start > 0) b.add(Collections.emptyList(), start);
        b.add(Collections.singleton("mt-link-hover"), end - start);
        if (end < length) b.add(Collections.emptyList(), length - end);
        return b.create();
    }

    void dispose() {
        hoverPopup.hide();
    }
}
