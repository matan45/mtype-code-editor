package org.mtype.editor.ui.editor;

import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Point2D;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import org.fxmisc.richtext.CharacterHit;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.event.MouseOverTextEvent;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.TwoDimensional;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.lsp.LspBridge;
import org.mtype.editor.lsp.LspEdits;
import org.mtype.editor.lsp.Positions;
import org.mtype.editor.syntax.MTypeTokenizer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class EditorTab extends Tab {
    private static final ScheduledExecutorService BG_EXEC =
            Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "mtype-tab-bg");
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
    private ScheduledFuture<?> pendingCompletion;
    private boolean suppressDirty = true;
    private List<CompletionItem> currentCompletionItems = Collections.emptyList();

    public EditorTab(AppContext ctx, Path path) {
        this.ctx = ctx;
        this.path = path;
        setText(path.getFileName().toString());

        codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));
        codeArea.getStyleClass().add("code-area");
        completionMenu.getStyleClass().add("mt-completion");

        VirtualizedScrollPane<CodeArea> scroll = new VirtualizedScrollPane<>(codeArea);
        setContent(scroll);

        loadFile();
        suppressDirty = false;

        codeArea.textProperty().addListener((obs, oldText, newText) -> {
            if (!suppressDirty) markDirty();
            scheduleHighlight();
            scheduleDidChange();
            maybeAutoCompletion(oldText, newText);
        });

        codeArea.caretPositionProperty().addListener((obs, o, n) -> {
            int caret = n.intValue();
            TwoDimensional.Position p = codeArea.offsetToPosition(caret, TwoDimensional.Bias.Forward);
            ctx.getStatusBar().setCaret(p.getMajor(), p.getMinor());
        });

        codeArea.addEventFilter(KeyEvent.KEY_PRESSED, this::handleKey);

        // Hover
        codeArea.setMouseOverTextDelay(Duration.ofMillis(500));
        codeArea.addEventHandler(MouseOverTextEvent.MOUSE_OVER_TEXT_BEGIN, e -> {
            int charIdx = e.getCharacterIndex();
            Point2D pos = e.getScreenPosition();
            requestHover(charIdx, pos);
        });
        codeArea.addEventHandler(MouseOverTextEvent.MOUSE_OVER_TEXT_END, e -> hoverTooltip.hide());

        // Ctrl+Click → go to definition
        codeArea.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.isControlDown()) {
                goToDefinitionAtCaret();
                e.consume();
            }
        });

        // Right-click → position caret then show context menu
        codeArea.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                CharacterHit hit = codeArea.hit(e.getX(), e.getY());
                codeArea.moveTo(hit.getInsertionIndex());
            }
        });
        codeArea.setContextMenu(buildCodeContextMenu());

        setOnCloseRequest(ev -> {
            if (!confirmDiscardIfDirty()) ev.consume();
        });

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
        if (pendingCompletion != null) pendingCompletion.cancel(false);
        if (ctx.getLspBridge() != null) {
            try { ctx.getLspBridge().didClose(path); } catch (Exception ignored) {}
        }
    }

    /** Move the caret to a 0-based LSP position and ensure visible. */
    public void revealPosition(int line, int character) {
        int offset = Positions.offset(codeArea.getText(), line, character);
        codeArea.moveTo(offset);
        codeArea.requestFollowCaret();
        codeArea.requestFocus();
    }

    private ContextMenu buildCodeContextMenu() {
        ContextMenu menu = new ContextMenu();
        menu.getStyleClass().add("mt-code-context");

        MenuItem goToDef = new MenuItem("Go to Definition");
        goToDef.setAccelerator(new KeyCodeCombination(KeyCode.F12));
        goToDef.setOnAction(e -> goToDefinitionAtCaret());

        MenuItem rename = new MenuItem("Rename Symbol");
        rename.setAccelerator(new KeyCodeCombination(KeyCode.F2));
        rename.setOnAction(e -> renameAtCaret());

        MenuItem callHier = new MenuItem("Show Call Hierarchy");
        callHier.setAccelerator(new KeyCodeCombination(KeyCode.H,
                KeyCombination.CONTROL_DOWN, KeyCombination.ALT_DOWN));
        callHier.setOnAction(e -> showCallHierarchyAtCaret());

        MenuItem format = new MenuItem("Format Document");
        format.setAccelerator(new KeyCodeCombination(KeyCode.F,
                KeyCombination.SHIFT_DOWN, KeyCombination.ALT_DOWN));
        format.setOnAction(e -> formatDocument());

        MenuItem cut = new MenuItem("Cut");
        cut.setAccelerator(new KeyCodeCombination(KeyCode.X, KeyCombination.SHORTCUT_DOWN));
        cut.setOnAction(e -> codeArea.cut());

        MenuItem copy = new MenuItem("Copy");
        copy.setAccelerator(new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN));
        copy.setOnAction(e -> codeArea.copy());

        MenuItem paste = new MenuItem("Paste");
        paste.setAccelerator(new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN));
        paste.setOnAction(e -> codeArea.paste());

        menu.getItems().addAll(
                goToDef, rename, callHier,
                new SeparatorMenuItem(),
                format,
                new SeparatorMenuItem(),
                cut, copy, paste);
        return menu;
    }

    /* ================================ commands ================================ */

    public void formatDocument() {
        LspBridge lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) {
            ctx.getStatusBar().setMessage("LSP not ready");
            return;
        }
        lsp.format(path, 4, true).thenAcceptAsync(edits -> {
            if (edits == null || edits.isEmpty()) {
                ctx.getStatusBar().setMessage("No formatting changes");
                return;
            }
            LspEdits.applyToCodeArea(codeArea, edits);
            ctx.getStatusBar().setMessage("Formatted " + path.getFileName());
        }, Platform::runLater);
    }

    public void goToDefinitionAtCaret() {
        LspBridge lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) return;
        TwoDimensional.Position pos = codeArea.offsetToPosition(codeArea.getCaretPosition(), TwoDimensional.Bias.Forward);
        lsp.definition(path, pos.getMajor(), pos.getMinor()).thenAcceptAsync(loc -> {
            if (loc == null) {
                ctx.getStatusBar().setMessage("No definition");
                return;
            }
            try {
                Path targetPath = java.nio.file.Paths.get(java.net.URI.create(loc.getUri()));
                ctx.getTabPane().openAt(targetPath, loc.getRange().getStart().getLine(), loc.getRange().getStart().getCharacter());
            } catch (Exception ex) {
                ctx.getStatusBar().setMessage("Bad def URI: " + loc.getUri());
            }
        }, Platform::runLater);
    }

    public void showCallHierarchyAtCaret() {
        LspBridge lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) {
            ctx.getStatusBar().setMessage("LSP not ready");
            return;
        }
        TwoDimensional.Position pos = codeArea.offsetToPosition(codeArea.getCaretPosition(), TwoDimensional.Bias.Forward);
        lsp.prepareCallHierarchy(path, pos.getMajor(), pos.getMinor())
                .thenAcceptAsync(items -> {
                    if (items == null || items.isEmpty()) {
                        ctx.getStatusBar().setMessage("No callable here");
                        return;
                    }
                    ctx.getOutputPane().showCallHierarchy(items.get(0));
                }, Platform::runLater);
    }

    public void renameAtCaret() {
        LspBridge lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) {
            ctx.getStatusBar().setMessage("LSP not ready");
            return;
        }
        TwoDimensional.Position pos = codeArea.offsetToPosition(codeArea.getCaretPosition(), TwoDimensional.Bias.Forward);
        int line = pos.getMajor();
        int col = pos.getMinor();

        lsp.prepareRename(path, line, col).thenAcceptAsync(info -> {
            String placeholder = info != null && info.placeholder != null
                    ? info.placeholder : wordAroundCaret();
            if (placeholder == null || placeholder.isBlank()) {
                ctx.getStatusBar().setMessage("Can't rename here");
                return;
            }
            TextInputDialog dlg = new TextInputDialog(placeholder);
            dlg.setTitle("Rename Symbol");
            dlg.setHeaderText("Rename '" + placeholder + "'");
            dlg.setContentText("New name:");
            Optional<String> result = dlg.showAndWait();
            if (result.isEmpty()) return;
            String newName = result.get().trim();
            if (newName.isEmpty() || newName.equals(placeholder)) return;

            lsp.rename(path, line, col, newName).thenAcceptAsync(edit -> {
                if (edit == null) {
                    ctx.getStatusBar().setMessage("Rename returned no edit");
                    return;
                }
                int files = LspEdits.applyWorkspaceEdit(ctx, edit);
                ctx.getStatusBar().setMessage("Renamed in " + files + " file(s)");
            }, Platform::runLater);
        }, Platform::runLater);
    }

    /* ================================ internals ================================ */

    private void handleKey(KeyEvent e) {
        if (completionMenu.isShowing()) {
            if (e.getCode() == KeyCode.ESCAPE) {
                completionMenu.hide();
                e.consume();
                return;
            }
            if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.TAB) {
                MenuItem item = focusedMenuItem();
                if (item != null) {
                    item.fire();
                    e.consume();
                    return;
                }
            }
        }
        if (e.getCode() == KeyCode.SPACE && e.isControlDown()) {
            requestCompletionNow();
            e.consume();
            return;
        }
        if (e.getCode() == KeyCode.F12) {
            goToDefinitionAtCaret();
            e.consume();
            return;
        }
        if (e.getCode() == KeyCode.F2) {
            renameAtCaret();
            e.consume();
            return;
        }
        if (e.getCode() == KeyCode.F && e.isShiftDown() && e.isAltDown()) {
            formatDocument();
            e.consume();
            return;
        }
        if (e.getCode() == KeyCode.H && e.isControlDown() && e.isAltDown()) {
            showCallHierarchyAtCaret();
            e.consume();
        }
    }

    private MenuItem focusedMenuItem() {
        for (MenuItem mi : completionMenu.getItems()) {
            if (mi.getStyleableNode() != null && mi.getStyleableNode().isFocused()) return mi;
        }
        return completionMenu.getItems().isEmpty() ? null : completionMenu.getItems().get(0);
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

    /* ----- highlighting ----- */

    private void scheduleHighlight() {
        if (pendingHighlight != null) pendingHighlight.cancel(false);
        String snapshot = codeArea.getText();
        pendingHighlight = BG_EXEC.schedule(() -> {
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
            } catch (Exception ignored) {}
        }
        codeArea.setStyleSpans(0, lastTokenSpans);
    }

    /* ----- LSP sync ----- */

    private void scheduleDidChange() {
        if (pendingDidChange != null) pendingDidChange.cancel(false);
        String snapshot = codeArea.getText();
        int v = version.incrementAndGet();
        pendingDidChange = BG_EXEC.schedule(() -> {
            if (ctx.getLspBridge() != null) ctx.getLspBridge().didChange(path, snapshot, v);
        }, 200, TimeUnit.MILLISECONDS);
    }

    /* ----- completion ----- */

    private void maybeAutoCompletion(String oldText, String newText) {
        int caret = codeArea.getCaretPosition();
        int delta = newText.length() - oldText.length();
        if (delta != 1) {
            if (completionMenu.isShowing() && delta == -1) {
                // user deleted a char while popup open — refresh
                scheduleCompletion();
            } else if (completionMenu.isShowing()) {
                completionMenu.hide();
            }
            return;
        }
        if (caret == 0) return;
        char c = newText.charAt(caret - 1);
        if (Character.isLetterOrDigit(c) || c == '_' || c == '.') {
            scheduleCompletion();
        } else {
            completionMenu.hide();
        }
    }

    private void scheduleCompletion() {
        if (pendingCompletion != null) pendingCompletion.cancel(false);
        pendingCompletion = BG_EXEC.schedule(
                () -> Platform.runLater(this::requestCompletionNow),
                180, TimeUnit.MILLISECONDS);
    }

    private void requestCompletionNow() {
        LspBridge lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) return;
        TwoDimensional.Position pos = codeArea.offsetToPosition(codeArea.getCaretPosition(), TwoDimensional.Bias.Forward);
        lsp.completion(path, pos.getMajor(), pos.getMinor()).thenAcceptAsync(items -> {
            populateCompletionMenu(items);
        }, Platform::runLater);
    }

    private void populateCompletionMenu(List<CompletionItem> items) {
        if (items == null || items.isEmpty()) {
            completionMenu.hide();
            currentCompletionItems = Collections.emptyList();
            return;
        }
        String prefix = currentWordPrefix();
        java.util.List<CompletionItem> filtered = filter(items, prefix);
        if (filtered.isEmpty()) {
            completionMenu.hide();
            return;
        }
        currentCompletionItems = filtered;
        completionMenu.getItems().clear();
        int shown = 0;
        for (CompletionItem ci : filtered) {
            MenuItem mi = buildCompletionItem(ci);
            completionMenu.getItems().add(mi);
            if (++shown >= 30) break;
        }
        if (!completionMenu.isShowing()) {
            Optional<javafx.geometry.Bounds> caretBounds = codeArea.getCaretBounds();
            if (caretBounds.isPresent()) {
                javafx.geometry.Bounds b = caretBounds.get();
                completionMenu.show(codeArea, b.getMinX(), b.getMaxY());
            }
        }
    }

    private MenuItem buildCompletionItem(CompletionItem ci) {
        String label = ci.getLabel() == null ? "" : ci.getLabel();
        String detail = ci.getDetail();
        Label kindBadge = new Label(kindShort(ci.getKind()));
        kindBadge.getStyleClass().add("mt-completion-kind");
        kindBadge.getStyleClass().add("mt-completion-kind-" + (ci.getKind() == null ? "Other" : ci.getKind().name()));

        MenuItem mi = new MenuItem(detail == null || detail.isBlank() ? label : (label + "   " + detail));
        mi.setGraphic(kindBadge);
        mi.setOnAction(ev -> applyCompletion(ci));
        return mi;
    }

    private void applyCompletion(CompletionItem ci) {
        // Prefer textEdit if present (server may dictate exact range), else replace current word.
        TextEdit te = ci.getTextEdit() != null && ci.getTextEdit().isLeft()
                ? ci.getTextEdit().getLeft() : null;
        if (te != null) {
            LspEdits.applyToCodeArea(codeArea, java.util.Collections.singletonList(te));
        } else {
            String insert = ci.getInsertText() != null ? ci.getInsertText() : ci.getLabel();
            int caret = codeArea.getCaretPosition();
            String text = codeArea.getText();
            int start = caret;
            while (start > 0 && isWordChar(text.charAt(start - 1))) start--;
            codeArea.replaceText(start, caret, insert);
        }
        completionMenu.hide();
    }

    private String currentWordPrefix() {
        int caret = codeArea.getCaretPosition();
        String text = codeArea.getText();
        int start = caret;
        while (start > 0 && isWordChar(text.charAt(start - 1))) start--;
        return text.substring(start, caret);
    }

    private String wordAroundCaret() {
        int caret = codeArea.getCaretPosition();
        String text = codeArea.getText();
        int start = caret;
        int end = caret;
        while (start > 0 && isWordChar(text.charAt(start - 1))) start--;
        while (end < text.length() && isWordChar(text.charAt(end))) end++;
        if (start == end) return null;
        return text.substring(start, end);
    }

    private static List<CompletionItem> filter(List<CompletionItem> items, String prefix) {
        if (prefix == null || prefix.isEmpty()) return items;
        String low = prefix.toLowerCase();
        java.util.List<CompletionItem> out = new java.util.ArrayList<>();
        for (CompletionItem ci : items) {
            String key = ci.getFilterText() != null ? ci.getFilterText()
                    : (ci.getLabel() != null ? ci.getLabel() : "");
            if (key.toLowerCase().contains(low)) out.add(ci);
        }
        return out.isEmpty() ? items : out;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static String kindShort(CompletionItemKind k) {
        if (k == null) return "·";
        return switch (k) {
            case Class -> "C";
            case Interface -> "I";
            case Struct -> "S";
            case Enum -> "E";
            case EnumMember -> "e";
            case Method, Function, Constructor -> "ƒ";
            case Field -> "f";
            case Property -> "p";
            case Variable -> "v";
            case Constant -> "c";
            case Keyword -> "k";
            case Module -> "m";
            case Snippet -> "»";
            case File -> "□";
            case Folder -> "▥";
            case TypeParameter -> "T";
            case Text -> "a";
            default -> "·";
        };
    }

    /* ----- hover ----- */

    private void requestHover(int charIdx, Point2D pos) {
        if (ctx.getLspBridge() == null) return;
        TwoDimensional.Position p = codeArea.offsetToPosition(charIdx, TwoDimensional.Bias.Forward);
        ctx.getLspBridge().hover(path, p.getMajor(), p.getMinor()).thenAcceptAsync(content -> {
            if (content == null || content.isBlank()) { hoverTooltip.hide(); return; }
            hoverTooltip.setText(content);
            hoverTooltip.show(codeArea, pos.getX() + 10, pos.getY() + 10);
        }, Platform::runLater);
    }
}
