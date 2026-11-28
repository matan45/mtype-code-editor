package org.mtype.editor.ui.editor;

import javafx.application.Platform;
import org.eclipse.lsp4j.SignatureHelp;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.lsp.LspBridge;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Signature-help popup. Auto-triggers on {@code (} / {@code ,} (and refreshes on backspace while
 * showing), hides on {@code )}, and can be invoked explicitly with Ctrl+Shift+Space. Only the latest
 * request's response is shown; a newer request, {@code )} or closing the tab cancels the older one.
 */
final class SignatureHelpController {
    private final MTypeCodeArea codeArea;
    private final Path path;
    private final AppContext ctx;
    private final boolean lspManaged;
    /** Sends the tab's pending didChange, so the server sees the {@code (} that triggered the request. */
    private final Runnable flushDidChange;
    private final SignatureHelpPopup popup = new SignatureHelpPopup();
    private final LatestRequest<SignatureHelp> inFlight = new LatestRequest<>();

    private ScheduledFuture<?> pending;
    private boolean disposed;

    SignatureHelpController(MTypeCodeArea codeArea, Path path, AppContext ctx, boolean lspManaged,
                            Runnable flushDidChange) {
        this.codeArea = codeArea;
        this.path = path;
        this.ctx = ctx;
        this.lspManaged = lspManaged;
        this.flushDidChange = flushDidChange;
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
                // The call is closed: a request scheduled or in flight for it must not show the popup.
                if (pending != null) pending.cancel(false);
                inFlight.cancel();
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
        if (!lspManaged || disposed) return;
        LspBridge lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) return;
        // Put the text the user sees on the wire first: the server answers requests in order (MYT-422).
        flushDidChange.run();
        int[] lc = codeArea.displayToSourceLineChar(codeArea.getCaretPosition());
        CompletableFuture<SignatureHelp> request = lsp.signatureHelp(path, lc[0], lc[1], triggerChar);
        long token = inFlight.begin(request);
        request.thenAcceptAsync(help -> {
            if (!inFlight.isCurrent(token)) return;
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
        disposed = true;
        if (pending != null) pending.cancel(false);
        inFlight.cancel();
        popup.hide();
    }
}
