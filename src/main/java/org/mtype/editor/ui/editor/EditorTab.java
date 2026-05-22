package org.mtype.editor.ui.editor;

import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Point2D;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.event.MouseOverTextEvent;
import org.fxmisc.richtext.model.StyleSpans;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.syntax.MTypeTokenizer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class EditorTab extends Tab {
    private static final ScheduledExecutorService HIGHLIGHT_EXEC =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mtype-highlighter");
                t.setDaemon(true);
                return t;
            });

    private final AppContext ctx;
    private final Path path;
    private final CodeArea codeArea = new CodeArea();
    private final SimpleBooleanProperty dirty = new SimpleBooleanProperty(false);
    private final AtomicInteger version = new AtomicInteger(1);
    private final ContextMenu completionMenu = new ContextMenu();
    private final Tooltip hoverTooltip = new Tooltip();
    private StyleSpans<Collection<String>> lastTokenSpans;
    private StyleSpans<Collection<String>> lastDiagnosticSpans;
    private ScheduledFuture<?> pendingHighlight;
    private ScheduledFuture<?> pendingDidChange;
    private boolean suppressDirty = true;

    public EditorTab(AppContext ctx, Path path) {
        this.ctx = ctx;
        this.path = path;
        setText(path.getFileName().toString());

        codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));
        codeArea.getStyleClass().add("code-area");

        VirtualizedScrollPane<CodeArea> scroll = new VirtualizedScrollPane<>(codeArea);
        setContent(scroll);

        loadFile();
        suppressDirty = false;

        codeArea.textProperty().addListener((obs, oldText, newText) -> {
            if (!suppressDirty) markDirty();
            scheduleHighlight();
            scheduleDidChange();
        });

        codeArea.caretPositionProperty().addListener((obs, o, n) -> {
            int caret = n.intValue();
            int line = codeArea.offsetToPosition(caret, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward).getMajor();
            int col  = codeArea.offsetToPosition(caret, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward).getMinor();
            ctx.getStatusBar().setCaret(line, col);
        });

        // Ctrl+Space → completion
        codeArea.addEventHandler(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.SPACE && e.isControlDown()) {
                requestCompletion();
                e.consume();
            }
        });

        // Hover
        codeArea.setMouseOverTextDelay(Duration.ofMillis(500));
        codeArea.addEventHandler(MouseOverTextEvent.MOUSE_OVER_TEXT_BEGIN, e -> {
            int charIdx = e.getCharacterIndex();
            Point2D pos = e.getScreenPosition();
            requestHover(charIdx, pos);
        });
        codeArea.addEventHandler(MouseOverTextEvent.MOUSE_OVER_TEXT_END, e -> {
            hoverTooltip.hide();
        });

        setOnCloseRequest(ev -> {
            if (!confirmDiscardIfDirty()) {
                ev.consume();
            }
        });

        // Initial highlighting + LSP didOpen
        Platform.runLater(this::applyHighlightingNow);
        if (ctx.getLspBridge() != null) {
            ctx.getLspBridge().didOpen(path, codeArea.getText(), version.get());
        }
    }

    public Path getPath() { return path; }
    public CodeArea getCodeArea() { return codeArea; }
    public int getVersion() { return version.get(); }
    public boolean isDirty() { return dirty.get(); }

    public void save() {
        try {
            Files.writeString(path, codeArea.getText(), StandardCharsets.UTF_8);
            dirty.set(false);
            setText(path.getFileName().toString());
            ctx.getStatusBar().setMessage("Saved " + path.getFileName());
        } catch (Exception ex) {
            ctx.getStatusBar().setMessage("Save failed: " + ex.getMessage());
        }
    }

    public void applyDiagnosticSpans(StyleSpans<Collection<String>> diagSpans) {
        this.lastDiagnosticSpans = diagSpans;
        applyCombinedStyles();
    }

    void onClosed() {
        if (pendingHighlight != null) pendingHighlight.cancel(false);
        if (pendingDidChange != null) pendingDidChange.cancel(false);
        if (ctx.getLspBridge() != null) {
            try { ctx.getLspBridge().didClose(path); } catch (Exception ignored) {}
        }
    }

    private boolean confirmDiscardIfDirty() {
        if (!dirty.get()) return true;
        Alert a = new Alert(Alert.AlertType.CONFIRMATION,
                "Discard unsaved changes to " + path.getFileName() + "?",
                ButtonType.YES, ButtonType.NO);
        a.setHeaderText(null);
        Optional<ButtonType> r = a.showAndWait();
        return r.isPresent() && r.get() == ButtonType.YES;
    }

    private void loadFile() {
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            codeArea.replaceText(text);
            codeArea.moveTo(0);
        } catch (Exception ex) {
            codeArea.replaceText("");
            ctx.getStatusBar().setMessage("Open failed: " + ex.getMessage());
        }
    }

    private void markDirty() {
        if (!dirty.get()) {
            dirty.set(true);
            setText("*" + path.getFileName().toString());
        }
    }

    private void scheduleHighlight() {
        if (pendingHighlight != null) pendingHighlight.cancel(false);
        String snapshot = codeArea.getText();
        pendingHighlight = HIGHLIGHT_EXEC.schedule(() -> {
            StyleSpans<Collection<String>> spans = MTypeTokenizer.compute(snapshot);
            Platform.runLater(() -> {
                lastTokenSpans = spans;
                applyCombinedStyles();
            });
        }, 120, TimeUnit.MILLISECONDS);
    }

    private void applyHighlightingNow() {
        StyleSpans<Collection<String>> spans = MTypeTokenizer.compute(codeArea.getText());
        lastTokenSpans = spans;
        applyCombinedStyles();
    }

    private void applyCombinedStyles() {
        if (lastTokenSpans == null) return;
        if (lastDiagnosticSpans != null) {
            try {
                StyleSpans<Collection<String>> combined = lastTokenSpans.overlay(
                        lastDiagnosticSpans,
                        (a, b) -> {
                            java.util.Set<String> merged = new java.util.LinkedHashSet<>(a);
                            merged.addAll(b);
                            return merged;
                        });
                codeArea.setStyleSpans(0, combined);
                return;
            } catch (Exception ignored) {
                // fall through to plain
            }
        }
        codeArea.setStyleSpans(0, lastTokenSpans);
    }

    private void scheduleDidChange() {
        if (pendingDidChange != null) pendingDidChange.cancel(false);
        String snapshot = codeArea.getText();
        int v = version.incrementAndGet();
        pendingDidChange = HIGHLIGHT_EXEC.schedule(() -> {
            if (ctx.getLspBridge() != null) {
                ctx.getLspBridge().didChange(path, snapshot, v);
            }
        }, 250, TimeUnit.MILLISECONDS);
    }

    private void requestCompletion() {
        if (ctx.getLspBridge() == null) return;
        int caret = codeArea.getCaretPosition();
        int line = codeArea.offsetToPosition(caret, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward).getMajor();
        int col  = codeArea.offsetToPosition(caret, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward).getMinor();
        ctx.getLspBridge().completion(path, line, col).thenAcceptAsync(items -> {
            completionMenu.getItems().clear();
            int shown = 0;
            for (String label : items) {
                MenuItem mi = new MenuItem(label);
                mi.setOnAction(ev -> insertCompletion(label));
                completionMenu.getItems().add(mi);
                if (++shown >= 50) break;
            }
            if (!completionMenu.getItems().isEmpty()) {
                Optional<javafx.geometry.Bounds> caretBounds = codeArea.getCaretBounds();
                if (caretBounds.isPresent()) {
                    javafx.geometry.Bounds b = caretBounds.get();
                    completionMenu.show(codeArea, b.getMaxX(), b.getMaxY());
                }
            } else {
                completionMenu.hide();
            }
        }, Platform::runLater);
    }

    private void insertCompletion(String text) {
        int caret = codeArea.getCaretPosition();
        // Replace partial word before caret
        int start = caret;
        String content = codeArea.getText();
        while (start > 0 && isWordChar(content.charAt(start - 1))) start--;
        codeArea.replaceText(start, caret, text);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private void requestHover(int charIdx, Point2D pos) {
        if (ctx.getLspBridge() == null) return;
        int line = codeArea.offsetToPosition(charIdx, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward).getMajor();
        int col  = codeArea.offsetToPosition(charIdx, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward).getMinor();
        ctx.getLspBridge().hover(path, line, col).thenAcceptAsync(content -> {
            if (content == null || content.isBlank()) {
                hoverTooltip.hide();
                return;
            }
            hoverTooltip.setText(content);
            hoverTooltip.show(codeArea, pos.getX() + 10, pos.getY() + 10);
        }, Platform::runLater);
    }
}
