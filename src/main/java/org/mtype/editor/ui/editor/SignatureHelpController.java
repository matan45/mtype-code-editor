package org.mtype.editor.ui.editor;

import javafx.application.Platform;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.lsp.LspBridge;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Signature-help popup. Auto-triggers on {@code (} / {@code ,} (and refreshes on backspace while
 * showing), hides on {@code )}, and can be invoked explicitly with Ctrl+Shift+Space. A request serial
 * discards stale responses.
 */
final class SignatureHelpController {
    private final MTypeCodeArea codeArea;
    private final Path path;
    private final AppContext ctx;
    private final boolean lspManaged;
    private final SignatureHelpPopup popup = new SignatureHelpPopup();

    private ScheduledFuture<?> pending;
    private int requestSerial;

    SignatureHelpController(MTypeCodeArea codeArea, Path path, AppContext ctx, boolean lspManaged) {
        this.codeArea = codeArea;
        this.path = path;
        this.ctx = ctx;
        this.lspManaged = lspManaged;
    }

    void maybeSignatureHelp(String oldText, String newText) {
        int caret = codeArea.getCaretPosition();
        int delta = newText.length() - oldText.length();
        if (delta == 1 && caret > 0) {
            char c = newText.charAt(caret - 1);
            if (c == '(' || c == ',') {
                scheduleSignatureHelp(String.valueOf(c));
                return;
            }
            if (c == ')') {
                popup.hide();
                return;
            }
        }
        if (delta == -1 && popup.isShowing()) {
            scheduleSignatureHelp(null);
        }
    }

    private void scheduleSignatureHelp(String triggerChar) {
        if (!lspManaged) return;
        if (pending != null) pending.cancel(false);
        pending = EditorTab.BG_EXEC.schedule(
                () -> Platform.runLater(() -> requestSignatureHelpNow(triggerChar)),
                150, TimeUnit.MILLISECONDS);
    }

    void requestSignatureHelpAtCaret() {
        requestSignatureHelpNow(null);
    }

    private void requestSignatureHelpNow(String triggerChar) {
        if (!lspManaged) return;
        LspBridge lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) return;
        int[] lc = codeArea.displayToSourceLineChar(codeArea.getCaretPosition());
        final int request = ++requestSerial;
        lsp.signatureHelp(path, lc[0], lc[1], triggerChar).thenAcceptAsync(help -> {
            if (request != requestSerial) return;
            if (help == null || help.getSignatures() == null || help.getSignatures().isEmpty()) {
                popup.hide();
                return;
            }
            Optional<javafx.geometry.Bounds> caretBounds = codeArea.getCaretBounds();
            if (caretBounds.isEmpty()) return;
            javafx.geometry.Bounds b = caretBounds.get();
            popup.show(codeArea, help, b.getMinX(), b.getMinY() - 6);
        }, Platform::runLater);
    }

    void dispose() {
        if (pending != null) pending.cancel(false);
        popup.hide();
    }
}
