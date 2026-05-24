package org.mtype.editor.ui.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Point2D;
import javafx.geometry.Side;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.Command;
import org.fxmisc.richtext.CharacterHit;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.event.MouseOverTextEvent;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.fxmisc.richtext.model.TwoDimensional;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.debug.BreakpointService;
import org.mtype.editor.debug.DebuggerEventBus;
import org.mtype.editor.lsp.LspBridge;
import org.mtype.editor.lsp.LspEdits;
import org.mtype.editor.lsp.Positions;
import org.mtype.editor.syntax.Tokenizers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
    private static final double CODE_LENS_LABEL_X = 65;
    private static final double EDITOR_SCROLL_SENSITIVITY = 1.8;
    private static final String INLAY_HINT_ANCHOR_STYLE = "mt-inlay-hint-anchor";

    private final AppContext ctx;
    private final Path path;
    private final CodeArea codeArea = new CodeArea();
    private final SimpleBooleanProperty dirty = new SimpleBooleanProperty(false);
    private final AtomicInteger version = new AtomicInteger(1);
    private final ContextMenu completionMenu = new ContextMenu();
    private final ContextMenu referencesMenu = new ContextMenu();
    private final HoverPopup hoverPopup = new HoverPopup();
    private final Pane inlayHintsLayer = new Pane();
    private final InlayHintsController inlayHintsController;
    private final Set<Integer> inlayHintAnchorOffsets = new HashSet<>();
    private final Map<Integer, CodeLensLine> codeLensByParagraph = new HashMap<>();
    private final Set<Integer> codeLensParagraphStyles = new HashSet<>();
    private final Map<Integer, org.eclipse.lsp4j.DiagnosticSeverity> diagnosticsByLine = new HashMap<>();
    private javafx.scene.control.Menu quickFixMenu;
    private int quickFixRequestSerial;
    private StyleSpans<Collection<String>> lastTokenSpans;
    private StyleSpans<Collection<String>> lastDiagnosticSpans;
    private ScheduledFuture<?> pendingHighlight;
    private ScheduledFuture<?> pendingDidChange;
    private ScheduledFuture<?> pendingCompletion;
    private ScheduledFuture<?> pendingCodeLens;
    private ScheduledFuture<?> pendingInlayHints;
    private int codeLensRequestSerial;
    private int inlayHintRequestSerial;
    private long openedLspSession = -1;
    private boolean suppressDirty = true;
    private List<CompletionItem> currentCompletionItems = Collections.emptyList();
    private SnippetSession activeSnippet;
    private int executionLine = -1;

    public EditorTab(AppContext ctx, Path path) {
        this.ctx = ctx;
        this.path = path;
        setText(path.getFileName().toString());

        codeArea.setParagraphGraphicFactory(this::paragraphGraphic);
        codeArea.getStyleClass().add("code-area");
        applyFontFromSettings();
        completionMenu.getStyleClass().add("mt-completion");
        referencesMenu.getStyleClass().add("mt-references");

        VirtualizedScrollPane<CodeArea> scroll = new VirtualizedScrollPane<>(codeArea);
        installScrollSensitivity();
        StackPane editorStack = new StackPane(scroll, inlayHintsLayer);
        setContent(editorStack);
        inlayHintsController = new InlayHintsController(codeArea, inlayHintsLayer, this::setInlayHintAnchors);

        loadFile();
        suppressDirty = false;

        codeArea.textProperty().addListener((obs, oldText, newText) -> {
            if (!suppressDirty) markDirty();
            scheduleHighlight();
            scheduleDidChange();
            scheduleCodeLensRefresh();
            scheduleInlayHintsRefresh();
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
        codeArea.addEventHandler(MouseOverTextEvent.MOUSE_OVER_TEXT_END, e -> hoverPopup.hide());

        // Ctrl+Click → go to definition
        codeArea.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.isControlDown()) {
                goToDefinitionAtCaret();
                e.consume();
            }
        });

        // Right-click → position caret, kick off the code-action request so the
        // submenu is already populated by the time the user hovers it.
        codeArea.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() == MouseButton.SECONDARY) {
                CharacterHit hit = codeArea.hit(e.getX(), e.getY());
                codeArea.moveTo(hit.getInsertionIndex());
                if (quickFixMenu != null) populateQuickFix(quickFixMenu);
            }
            if (completionMenu.isShowing()) completionMenu.hide();
        });
        codeArea.setContextMenu(buildCodeContextMenu());

        setOnCloseRequest(ev -> {
            if (!confirmDiscardIfDirty()) ev.consume();
        });

        Platform.runLater(this::applyHighlightingNow);
        onLspReady();
        wireDebuggerHooks();
    }

    private void wireDebuggerHooks() {
        BreakpointService bs = ctx.getBreakpointService();
        if (bs != null) {
            bs.addListener((p, _) -> {
                if (p == null || !p.equals(path)) return;
                Platform.runLater(() -> codeArea.setParagraphGraphicFactory(this::paragraphGraphic));
            });
        }
        DebuggerEventBus bus = ctx.getDebuggerEventBus();
        if (bus != null) {
            bus.onStopped(e -> {
                if (e.file() == null || !e.file().equals(path)) {
                    updateExecutionLine(-1);
                    return;
                }
                updateExecutionLine(e.line());
                int line0 = e.line();
                if (line0 >= 0 && line0 < codeArea.getParagraphs().size()) {
                    codeArea.moveTo(line0, 0);
                    codeArea.requestFollowCaret();
                }
            });
            bus.onResumed(() -> updateExecutionLine(-1));
            bus.onTerminated(() -> updateExecutionLine(-1));
        }
    }

    private void updateExecutionLine(int newLine) {
        int old = executionLine;
        executionLine = newLine;
        int paragraphCount = codeArea.getParagraphs().size();
        if (old >= 0 && old < paragraphCount && old != newLine) {
            codeArea.setParagraphStyle(old,
                    codeLensParagraphStyles.contains(old)
                            ? Collections.singletonList("mt-code-lens-paragraph")
                            : Collections.emptyList());
        }
        if (newLine >= 0 && newLine < paragraphCount) {
            List<String> classes = new ArrayList<>(2);
            classes.add("mt-execution-line");
            if (codeLensParagraphStyles.contains(newLine)) classes.add("mt-code-lens-paragraph");
            codeArea.setParagraphStyle(newLine, classes);
        }
        codeArea.setParagraphGraphicFactory(this::paragraphGraphic);
    }

    public Path getPath() { return path; }
    public CodeArea getCodeArea() { return codeArea; }
    public int getVersion() { return version.get(); }
    public boolean isDirty() { return dirty.get(); }

    public void save() {
        boolean formatFirst = ctx.getSettings() != null
                && ctx.getSettings().editor != null
                && ctx.getSettings().editor.formatOnSave;
        LspBridge lsp = ctx.getLspBridge();
        if (formatFirst && lsp != null && lsp.isReady()) {
            lsp.format(path, 4, true).thenAcceptAsync(edits -> {
                if (edits != null && !edits.isEmpty()) {
                    LspEdits.applyToCodeArea(codeArea, edits);
                    lastDiagnosticSpans = null;
                    applyHighlightingNow();
                }
                writeToDisk();
            }, Platform::runLater);
            return;
        }
        writeToDisk();
    }

    private void applyFontFromSettings() {
        if (ctx.getSettings() == null || ctx.getSettings().editor == null) return;
        var prefs = ctx.getSettings().editor;
        StringBuilder sb = new StringBuilder();
        if (prefs.fontFamily != null && !prefs.fontFamily.isBlank()) {
            String family = prefs.fontFamily.replace("\"", "");
            sb.append("-fx-font-family: \"").append(family).append("\"; ");
        }
        if (prefs.fontSize > 0) {
            sb.append("-fx-font-size: ").append(prefs.fontSize).append("; ");
        }
        if (sb.length() > 0) codeArea.setStyle(sb.toString());
    }

    private void writeToDisk() {
        try {
            Files.writeString(path, codeArea.getText(), StandardCharsets.UTF_8);
            dirty.set(false);
            setText(path.getFileName().toString());
            ctx.getStatusBar().setMessage("Saved " + path.getFileName());
            if (ctx.getGitChangesView() != null) ctx.getGitChangesView().refresh();
        } catch (Exception ex) {
            ctx.getStatusBar().setMessage("Save failed: " + ex.getMessage());
        }
    }

    public void applyDiagnosticSpans(StyleSpans<Collection<String>> diagSpans) {
        this.lastDiagnosticSpans = diagSpans;
        applyCombinedStyles();
    }

    public void applyDiagnosticLines(Map<Integer, org.eclipse.lsp4j.DiagnosticSeverity> nextLines) {
        if (nextLines == null) nextLines = Collections.emptyMap();
        if (nextLines.equals(diagnosticsByLine)) return;
        diagnosticsByLine.clear();
        diagnosticsByLine.putAll(nextLines);
        codeArea.setParagraphGraphicFactory(this::paragraphGraphic);
    }

    void onClosed() {
        if (pendingHighlight != null) pendingHighlight.cancel(false);
        if (pendingDidChange != null) pendingDidChange.cancel(false);
        if (pendingCompletion != null) pendingCompletion.cancel(false);
        if (pendingCodeLens != null) pendingCodeLens.cancel(false);
        if (pendingInlayHints != null) pendingInlayHints.cancel(false);
        inlayHintsController.dispose();
        if (ctx.getLspBridge() != null) {
            try { ctx.getLspBridge().didClose(path); } catch (Exception ignored) {}
        }
    }

    void onLspReady() {
        LspBridge lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) return;
        long lspSession = lsp.getSession();
        if (openedLspSession == lspSession) return;
        lsp.didOpen(path, codeArea.getText(), version.get());
        openedLspSession = lspSession;
        scheduleCodeLensRefresh();
        scheduleInlayHintsRefresh();
    }

    /* ----- code lens ----- */

    private Node paragraphGraphic(int paragraphIndex) {
        Node lineNumber = lineNumber(paragraphIndex);
        CodeLensLine lens = codeLensByParagraph.get(paragraphIndex);
        if (lens == null) return lineNumber;

        Label title = new Label(lens.title);
        title.getStyleClass().add("mt-code-lens");
        title.setCursor(Cursor.HAND);
        title.setTranslateX(CODE_LENS_LABEL_X);
        title.setOnMouseClicked(e -> {
            showCodeLensReferences(lens, title);
            e.consume();
        });

        Pane lensRow = new Pane(title);
        lensRow.getStyleClass().add("mt-code-lens-row");
        lensRow.setPickOnBounds(false);

        VBox block = new VBox(lensRow, lineNumber);
        block.getStyleClass().add("mt-code-lens-block");
        return block;
    }

    private Node lineNumber(int paragraphIndex) {
        Label label = new Label(Integer.toString(paragraphIndex + 1));
        label.getStyleClass().add("lineno");

        javafx.scene.layout.Region breakpoint = new javafx.scene.layout.Region();
        breakpoint.getStyleClass().add("mt-gutter-breakpoint");
        BreakpointService bs = ctx.getBreakpointService();
        boolean isBreakpointOn = bs != null && bs.breakpointsIn(path).contains(paragraphIndex);
        if (isBreakpointOn) breakpoint.getStyleClass().add("mt-gutter-breakpoint-on");
        breakpoint.setOnMouseClicked(e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY && bs != null) {
                bs.toggle(path, paragraphIndex);
                e.consume();
            }
        });

        javafx.scene.layout.Region marker = new javafx.scene.layout.Region();
        marker.getStyleClass().add("mt-gutter-marker");
        org.eclipse.lsp4j.DiagnosticSeverity sev = diagnosticsByLine.get(paragraphIndex);
        if (sev != null) {
            marker.getStyleClass().add("mt-gutter-" + gutterSeverityClass(sev));
        }
        if (paragraphIndex == executionLine) {
            marker.getStyleClass().add("mt-gutter-execution-arrow");
        }

        javafx.scene.layout.HBox row = new javafx.scene.layout.HBox(breakpoint, marker, label);
        row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        row.getStyleClass().add("mt-gutter-row");
        return row;
    }

    private static String gutterSeverityClass(org.eclipse.lsp4j.DiagnosticSeverity s) {
        return switch (s) {
            case Error -> "error";
            case Warning -> "warning";
            case Information -> "info";
            case Hint -> "hint";
        };
    }

    private void installScrollSensitivity() {
        codeArea.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (event.isShortcutDown() || event.isInertia()) return;

            double deltaY = event.getDeltaY();
            double deltaX = event.getDeltaX();
            if (deltaY == 0 && deltaX == 0) return;

            if (deltaY != 0) {
                double nextY = codeArea.estimatedScrollYProperty().getValue()
                        - deltaY * EDITOR_SCROLL_SENSITIVITY;
                codeArea.estimatedScrollYProperty().setValue(Math.max(0, nextY));
            }
            if (deltaX != 0) {
                double nextX = codeArea.estimatedScrollXProperty().getValue()
                        - deltaX * EDITOR_SCROLL_SENSITIVITY;
                codeArea.estimatedScrollXProperty().setValue(Math.max(0, nextX));
            }
            event.consume();
        });
    }

    private void scheduleCodeLensRefresh() {
        if (pendingCodeLens != null) pendingCodeLens.cancel(false);
        pendingCodeLens = BG_EXEC.schedule(
                () -> Platform.runLater(this::requestCodeLensNow),
                450,
                TimeUnit.MILLISECONDS);
    }

    private void requestCodeLensNow() {
        LspBridge lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) return;
        int request = ++codeLensRequestSerial;
        lsp.codeLens(path).thenAcceptAsync(lenses -> {
            if (request != codeLensRequestSerial) return;
            applyCodeLenses(lenses);
        }, Platform::runLater);
    }

    private void scheduleInlayHintsRefresh() {
        if (pendingInlayHints != null) pendingInlayHints.cancel(false);
        pendingInlayHints = BG_EXEC.schedule(
                () -> Platform.runLater(this::requestInlayHintsNow),
                350,
                TimeUnit.MILLISECONDS);
    }

    public void refreshInlayHintsFromSettings() {
        requestInlayHintsNow();
    }

    private void requestInlayHintsNow() {
        if (ctx.getSettings() == null
                || ctx.getSettings().editor == null
                || !ctx.getSettings().editor.inlayHints) {
            inlayHintsController.clear();
            return;
        }
        LspBridge lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) {
            inlayHintsController.clear();
            return;
        }
        int request = ++inlayHintRequestSerial;
        Range range = wholeDocumentRange();
        lsp.inlayHints(path, range).thenAcceptAsync(hints -> {
            if (request != inlayHintRequestSerial) return;
            inlayHintsController.setHints(hints);
        }, Platform::runLater);
    }

    private Range wholeDocumentRange() {
        int lastLine = Math.max(0, codeArea.getParagraphs().size() - 1);
        int lastCharacter = 0;
        try {
            lastCharacter = codeArea.getText(lastLine).length();
        } catch (Exception ignored) {}
        return new Range(new Position(0, 0), new Position(lastLine, lastCharacter));
    }

    private void applyCodeLenses(List<? extends CodeLens> lenses) {
        Map<Integer, CodeLensLine> next = new HashMap<>();
        if (lenses != null) {
            for (CodeLens lens : lenses) {
                if (lens == null || lens.getRange() == null || lens.getRange().getStart() == null) continue;
                Command command = lens.getCommand();
                String title = command == null ? null : command.getTitle();
                if (title == null || title.isBlank()) continue;
                int line = lens.getRange().getStart().getLine();
                if (line < 0 || line >= codeArea.getParagraphs().size()) continue;
                next.putIfAbsent(line, codeLensLine(lens, title));
            }
        }
        if (next.equals(codeLensByParagraph)) return;
        updateCodeLensParagraphStyles(next.keySet());
        codeLensByParagraph.clear();
        codeLensByParagraph.putAll(next);
        codeArea.setParagraphGraphicFactory(this::paragraphGraphic);
    }

    private void updateCodeLensParagraphStyles(Set<Integer> nextLines) {
        for (Integer line : new HashSet<>(codeLensParagraphStyles)) {
            if (!nextLines.contains(line) && line >= 0 && line < codeArea.getParagraphs().size()) {
                codeArea.setParagraphStyle(line, Collections.emptyList());
                codeLensParagraphStyles.remove(line);
            }
        }
        for (Integer line : nextLines) {
            if (line >= 0 && line < codeArea.getParagraphs().size() && codeLensParagraphStyles.add(line)) {
                codeArea.setParagraphStyle(line, Collections.singletonList("mt-code-lens-paragraph"));
            }
        }
    }

    private CodeLensLine codeLensLine(CodeLens lens, String title) {
        Position start = lens.getRange().getStart();
        int symbolLine = start.getLine();
        int symbolCharacter = start.getCharacter();
        Command command = lens.getCommand();
        if (command != null && command.getArguments() != null && command.getArguments().size() > 1) {
            Position commandPosition = positionArgument(command.getArguments().get(1));
            if (commandPosition != null) {
                symbolLine = commandPosition.getLine();
                symbolCharacter = commandPosition.getCharacter();
            }
        }
        return new CodeLensLine(title, symbolLine, symbolCharacter);
    }

    private Position positionArgument(Object value) {
        if (value instanceof JsonObject json) {
            JsonElement line = json.get("line");
            JsonElement character = json.get("character");
            if (line != null && character != null) {
                return new Position(line.getAsInt(), character.getAsInt());
            }
        }
        if (value instanceof Map<?, ?> map) {
            Integer line = intValue(map.get("line"));
            Integer character = intValue(map.get("character"));
            if (line != null && character != null) return new Position(line, character);
        }
        return null;
    }

    private Integer intValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof JsonElement json && json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) {
            return json.getAsInt();
        }
        return null;
    }

    private void showCodeLensReferences(CodeLensLine lens, Node anchor) {
        LspBridge lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) {
            ctx.getStatusBar().setMessage("LSP not ready");
            return;
        }
        lsp.references(path, lens.line, lens.character, false).thenAcceptAsync(locations -> {
            if (locations == null || locations.isEmpty()) {
                ctx.getStatusBar().setMessage("No references");
                return;
            }
            ctx.getStatusBar().setMessage(lens.title);
            showReferencesMenu(anchor, locations);
        }, Platform::runLater);
    }

    private void showReferencesMenu(Node anchor, List<? extends Location> locations) {
        referencesMenu.hide();
        referencesMenu.getItems().clear();
        int index = 1;
        for (Location location : locations) {
            MenuItem item = new MenuItem(index + ". " + referenceLabel(location));
            item.setOnAction(e -> openLocation(location, "reference"));
            referencesMenu.getItems().add(item);
            index++;
        }
        referencesMenu.show(anchor, Side.BOTTOM, 0, 2);
    }

    private String referenceLabel(Location location) {
        if (location == null || location.getUri() == null || location.getRange() == null
                || location.getRange().getStart() == null) {
            return "Unknown reference";
        }
        Position start = location.getRange().getStart();
        try {
            Path refPath = java.nio.file.Paths.get(java.net.URI.create(location.getUri()));
            Path name = refPath.getFileName();
            String display = name == null ? refPath.toString() : name.toString();
            return display + ":" + (start.getLine() + 1) + ":" + (start.getCharacter() + 1);
        } catch (Exception ignored) {
            return location.getUri() + ":" + (start.getLine() + 1) + ":" + (start.getCharacter() + 1);
        }
    }

    private record CodeLensLine(String title, int line, int character) {}

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

        javafx.scene.control.Menu quickFix = new javafx.scene.control.Menu("Quick Fix...");
        this.quickFixMenu = quickFix;
        // Populated on right-click (see MOUSE_PRESSED handler). No setOnShowing —
        // that would overwrite the freshly-fetched actions with a new "Loading..."
        // every time the user hovers the submenu.
        quickFix.getItems().add(disabledItem("(right-click on a diagnostic)"));

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

        MenuItem findRefs = new MenuItem("Find All References");
        findRefs.setAccelerator(new KeyCodeCombination(KeyCode.F12, KeyCombination.SHIFT_DOWN));
        findRefs.setOnAction(e -> findReferencesAtCaret());

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
                quickFix,
                new SeparatorMenuItem(),
                goToDef, rename, callHier, findRefs,
                new SeparatorMenuItem(),
                format,
                new SeparatorMenuItem(),
                cut, copy, paste);
        return menu;
    }

    private void populateQuickFix(javafx.scene.control.Menu quickFix) {
        LspBridge lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) {
            quickFix.getItems().setAll(disabledItem("LSP not ready"));
            return;
        }
        TwoDimensional.Position caret = codeArea.offsetToPosition(codeArea.getCaretPosition(), TwoDimensional.Bias.Forward);
        Position pos = new Position(caret.getMajor(), caret.getMinor());
        Range range = new Range(pos, pos);
        List<org.eclipse.lsp4j.Diagnostic> here = diagnosticsContainingLine(caret.getMajor());

        final int serial = ++quickFixRequestSerial;
        quickFix.getItems().setAll(disabledItem("Loading..."));
        ctx.getOutputPane().appendLspLog("[codeAction] " + (caret.getMajor() + 1) + ":" + (caret.getMinor() + 1)
                + " diagnostics=" + here.size());

        lsp.codeAction(path, range, here).thenAcceptAsync(actions -> {
            if (serial != quickFixRequestSerial) return; // a newer request has superseded
            int count = actions == null ? 0 : actions.size();
            ctx.getOutputPane().appendLspLog("[codeAction] returned " + count + " action(s)");
            if (count == 0) {
                replaceQuickFixItems(quickFix, java.util.List.of(disabledItem("(no actions)")));
                return;
            }
            java.util.List<MenuItem> next = new java.util.ArrayList<>();
            for (var either : actions) {
                MenuItem mi = quickFixMenuItem(either);
                if (mi != null) next.add(mi);
            }
            if (next.isEmpty()) {
                replaceQuickFixItems(quickFix, java.util.List.of(disabledItem("(no actions)")));
            } else {
                replaceQuickFixItems(quickFix, next);
            }
        }, Platform::runLater).exceptionally(t -> {
            Platform.runLater(() -> {
                ctx.getOutputPane().appendLspLog("[codeAction error] " + t.getMessage());
                replaceQuickFixItems(quickFix, java.util.List.of(disabledItem("(error: " + t.getMessage() + ")")));
            });
            return null;
        });
    }

    /** Replace submenu items. If it's already shown, force JavaFX to re-layout it. */
    private void replaceQuickFixItems(javafx.scene.control.Menu quickFix, java.util.List<MenuItem> next) {
        boolean wasShowing = quickFix.isShowing();
        quickFix.getItems().setAll(next);
        if (wasShowing) {
            quickFix.hide();
            Platform.runLater(quickFix::show);
        }
    }

    private MenuItem quickFixMenuItem(org.eclipse.lsp4j.jsonrpc.messages.Either<org.eclipse.lsp4j.Command, org.eclipse.lsp4j.CodeAction> either) {
        if (either == null) return null;
        String title;
        Runnable action;
        if (either.isLeft()) {
            org.eclipse.lsp4j.Command cmd = either.getLeft();
            if (cmd == null) return null;
            title = cmd.getTitle();
            action = () -> ctx.getLspBridge().executeCommand(cmd.getCommand(), cmd.getArguments());
        } else {
            org.eclipse.lsp4j.CodeAction ca = either.getRight();
            if (ca == null) return null;
            title = ca.getTitle();
            action = () -> {
                if (ca.getEdit() != null) {
                    int files = LspEdits.applyWorkspaceEdit(ctx, ca.getEdit());
                    lastDiagnosticSpans = null;
                    applyHighlightingNow();
                    ctx.getStatusBar().setMessage("Quick fix applied to " + files + " file(s)");
                }
                if (ca.getCommand() != null) {
                    ctx.getLspBridge().executeCommand(ca.getCommand().getCommand(), ca.getCommand().getArguments());
                }
            };
        }
        if (title == null || title.isBlank()) return null;
        MenuItem mi = new MenuItem(title);
        mi.setOnAction(e -> action.run());
        return mi;
    }

    private List<org.eclipse.lsp4j.Diagnostic> diagnosticsContainingLine(int line) {
        List<org.eclipse.lsp4j.Diagnostic> all = ctx.getDiagnosticsBus().forUri(path.toUri().toString());
        List<org.eclipse.lsp4j.Diagnostic> here = new java.util.ArrayList<>();
        for (org.eclipse.lsp4j.Diagnostic d : all) {
            int s = d.getRange().getStart().getLine();
            int e = d.getRange().getEnd().getLine();
            if (line >= s && line <= e) here.add(d);
        }
        return here;
    }

    private static MenuItem disabledItem(String text) {
        MenuItem mi = new MenuItem(text);
        mi.setDisable(true);
        return mi;
    }

    /* ================================ commands ================================ */

    public void formatDocument() {
        LspBridge lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) {
            ctx.getStatusBar().setMessage("LSP not ready");
            return;
        }
        lsp.format(path, 4, true).thenAcceptAsync(edits -> {
            boolean hadChanges = edits != null && !edits.isEmpty();
            if (hadChanges) {
                LspEdits.applyToCodeArea(codeArea, edits);
            }
            // RichTextFX clears styles in replaced ranges, and any cached diagnostic spans
            // reference old offsets — so always re-tokenize before showing the result.
            lastDiagnosticSpans = null;
            applyHighlightingNow();
            ctx.getStatusBar().setMessage(hadChanges
                    ? "Formatted " + path.getFileName()
                    : "No formatting changes");
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
            openLocation(loc, "definition");
        }, Platform::runLater);
    }

    private void openLocation(Location loc, String label) {
        if (loc == null || loc.getUri() == null || loc.getRange() == null || loc.getRange().getStart() == null) {
            ctx.getStatusBar().setMessage("Bad " + label + " location");
            return;
        }
        try {
            Path targetPath = java.nio.file.Paths.get(java.net.URI.create(loc.getUri()));
            ctx.getTabPane().openAt(targetPath, loc.getRange().getStart().getLine(), loc.getRange().getStart().getCharacter());
        } catch (Exception ex) {
            ctx.getStatusBar().setMessage("Bad " + label + " URI: " + loc.getUri());
        }
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

    public void findReferencesAtCaret() {
        LspBridge lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) {
            ctx.getStatusBar().setMessage("LSP not ready");
            return;
        }
        TwoDimensional.Position pos = codeArea.offsetToPosition(codeArea.getCaretPosition(), TwoDimensional.Bias.Forward);
        int line = pos.getMajor();
        int col = pos.getMinor();
        String word = wordAt(line, col);
        String label = word.isEmpty() ? "References" : word;
        lsp.references(path, line, col, true).thenAcceptAsync(locations -> {
            if (locations == null || locations.isEmpty()) {
                ctx.getStatusBar().setMessage("No references for " + label);
                ctx.getOutputPane().showReferences(label, java.util.Collections.emptyList());
                return;
            }
            ctx.getOutputPane().showReferences(label, locations);
        }, Platform::runLater);
    }

    private String wordAt(int line, int col) {
        try {
            String text = codeArea.getText();
            int offset = Positions.offset(text, line, col);
            int start = offset;
            while (start > 0 && isIdentChar(text.charAt(start - 1))) start--;
            int end = offset;
            while (end < text.length() && isIdentChar(text.charAt(end))) end++;
            return text.substring(start, end);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
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
            String placeholder = info != null && info.placeholder() != null
                    ? info.placeholder() : wordAroundCaret();
            if (placeholder == null || placeholder.isBlank()) {
                ctx.getStatusBar().setMessage("Can't rename here");
                return;
            }
            TextInputDialog dlg = new TextInputDialog(placeholder);
            dlg.initOwner(codeArea.getScene() != null ? codeArea.getScene().getWindow() : null);
            dlg.setTitle("Rename Symbol");
            dlg.setHeaderText("Rename '" + placeholder + "'");
            dlg.setContentText("New name");
            org.mtype.editor.ui.dialogs.Dialogs.theme(dlg);
            javafx.scene.control.DialogPane pane = dlg.getDialogPane();
            javafx.scene.control.TextField field = dlg.getEditor();
            field.getStyleClass().add("mt-rename-field");
            javafx.application.Platform.runLater(() -> {
                field.selectAll();
                field.requestFocus();
            });
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
                lastDiagnosticSpans = null;
                applyHighlightingNow();
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
        if (e.getCode() == KeyCode.TAB && activeSnippet != null) {
            if (advanceSnippet()) {
                e.consume();
                return;
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
            return;
        }
        if (e.getCode() == KeyCode.F12 && e.isShiftDown()) {
            findReferencesAtCaret();
            e.consume();
        }
    }

    private MenuItem focusedMenuItem() {
        for (MenuItem mi : completionMenu.getItems()) {
            if (mi.getStyleableNode() != null && mi.getStyleableNode().isFocused()) return mi;
        }
        return completionMenu.getItems().isEmpty() ? null : completionMenu.getItems().get(0);
    }

    public boolean canClose() {
        return confirmDiscardIfDirty();
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
        codeArea.getUndoManager().forgetHistory();
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
            StyleSpans<Collection<String>> spans = Tokenizers.computeFor(path, snapshot);
            Platform.runLater(() -> {
                lastTokenSpans = spans;
                applyCombinedStyles();
            });
        }, 120, TimeUnit.MILLISECONDS);
    }

    private void applyHighlightingNow() {
        StyleSpans<Collection<String>> spans = Tokenizers.computeFor(path, codeArea.getText());
        lastTokenSpans = spans;
        applyCombinedStyles();
    }

    private void applyCombinedStyles() {
        if (lastTokenSpans == null) return;
        StyleSpans<Collection<String>> combined = lastTokenSpans;
        if (lastDiagnosticSpans != null) {
            try {
                combined = combined.overlay(
                        lastDiagnosticSpans,
                        EditorTab::mergeStyles);
            } catch (Exception ignored) {}
        }
        StyleSpans<Collection<String>> inlayAnchors = inlayAnchorSpans(codeArea.getLength());
        if (inlayAnchors != null) {
            try {
                combined = combined.overlay(inlayAnchors, EditorTab::mergeStyles);
            } catch (Exception ignored) {}
        }
        codeArea.setStyleSpans(0, combined);
    }

    private void setInlayHintAnchors(List<Position> positions) {
        Set<Integer> next = new HashSet<>();
        String text = codeArea.getText();
        int length = text.length();
        if (positions != null && length > 0) {
            for (Position position : positions) {
                if (position == null) continue;
                try {
                    int offset = Positions.offset(text, position);
                    if (offset >= 0 && offset < length) {
                        char c = text.charAt(offset);
                        if (c != '\n' && c != '\r') next.add(offset);
                    }
                } catch (Exception ignored) {}
            }
        }
        if (next.equals(inlayHintAnchorOffsets)) return;
        inlayHintAnchorOffsets.clear();
        inlayHintAnchorOffsets.addAll(next);
        applyCombinedStyles();
        Platform.runLater(inlayHintsController::refreshLayout);
    }

    private StyleSpans<Collection<String>> inlayAnchorSpans(int length) {
        if (inlayHintAnchorOffsets.isEmpty() || length <= 0) return null;
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        int cursor = 0;
        boolean addedAnchor = false;
        for (int offset : new TreeSet<>(inlayHintAnchorOffsets)) {
            if (offset < cursor || offset < 0 || offset >= length) continue;
            if (offset > cursor) {
                builder.add(Collections.emptyList(), offset - cursor);
            }
            builder.add(Collections.singleton(INLAY_HINT_ANCHOR_STYLE), 1);
            cursor = offset + 1;
            addedAnchor = true;
        }
        if (!addedAnchor) return null;
        if (cursor < length) {
            builder.add(Collections.emptyList(), length - cursor);
        }
        return builder.create();
    }

    private static Collection<String> mergeStyles(Collection<String> a, Collection<String> b) {
        java.util.Set<String> merged = new java.util.LinkedHashSet<>(a);
        merged.addAll(b);
        return merged;
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
        if (activeSnippet != null) {
            completionMenu.hide();
            return;
        }
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
        int caret = codeArea.getCaretPosition();
        TwoDimensional.Position pos = codeArea.offsetToPosition(caret, TwoDimensional.Bias.Forward);
        String trigger = null;
        String text = codeArea.getText();
        int scan = caret - 1;
        while (scan >= 0 && isWordChar(text.charAt(scan))) scan--;
        if (scan >= 0) {
            char prior = text.charAt(scan);
            if (prior == '.' || prior == ':') trigger = String.valueOf(prior);
        }
        lsp.completion(path, pos.getMajor(), pos.getMinor(), trigger).thenAcceptAsync(items -> {
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
        if (ci == null) { completionMenu.hide(); return; }
        boolean hasExtras = (ci.getAdditionalTextEdits() != null && !ci.getAdditionalTextEdits().isEmpty())
                || ci.getCommand() != null;
        if (hasExtras) {
            applyResolvedCompletion(ci);
            return;
        }
        ctx.getLspBridge().resolveCompletion(ci)
                .thenAcceptAsync(resolved -> applyResolvedCompletion(resolved == null ? ci : resolved),
                        Platform::runLater);
    }

    private void applyResolvedCompletion(CompletionItem ci) {
        // The mType server sometimes returns a textEdit range narrower than the typed
        // prefix (e.g. only the last char), so we widen the replacement to the union of
        // the server's range and the word currently under the caret.
        TextEdit te = ci.getTextEdit() != null && ci.getTextEdit().isLeft()
                ? ci.getTextEdit().getLeft() : null;
        String newText;
        int serverStart = -1, serverEnd = -1;
        String text = codeArea.getText();
        if (te != null) {
            newText = te.getNewText();
            serverStart = Positions.offset(text, te.getRange().getStart());
            serverEnd = Positions.offset(text, te.getRange().getEnd());
            if (serverStart > serverEnd) { int t = serverStart; serverStart = serverEnd; serverEnd = t; }
        } else {
            newText = ci.getInsertText() != null ? ci.getInsertText() : ci.getLabel();
        }
        if (newText == null) { completionMenu.hide(); return; }

        int caret = codeArea.getCaretPosition();
        int wordStart = caret;
        while (wordStart > 0 && isWordChar(text.charAt(wordStart - 1))) wordStart--;
        int start = serverStart >= 0 ? Math.min(serverStart, wordStart) : wordStart;
        int end = serverEnd >= 0 ? Math.max(serverEnd, caret) : caret;

        // Apply additionalTextEdits (typically import statements above the caret) BEFORE
        // the main edit so snippet placeholder offsets land correctly. Shift start/end by
        // the net length delta of additional edits that fall above the main edit range.
        List<TextEdit> additional = ci.getAdditionalTextEdits();
        int delta = 0;
        if (additional != null && !additional.isEmpty()) {
            for (TextEdit aedit : additional) {
                int aEnd = Positions.offset(text, aedit.getRange().getEnd());
                if (aEnd <= start) {
                    int aStart = Positions.offset(text, aedit.getRange().getStart());
                    String anew = aedit.getNewText() == null ? "" : aedit.getNewText();
                    delta += anew.length() - (aEnd - aStart);
                }
            }
            LspEdits.applyToCodeArea(codeArea, additional);
        }
        start += delta;
        end += delta;

        activeSnippet = null;
        if (ci.getInsertTextFormat() == InsertTextFormat.Snippet) {
            SnippetSession snippet = SnippetSession.parse(newText, start);
            codeArea.replaceText(start, end, snippet.text());
            activeSnippet = snippet;
            applySnippetSelection(snippet.firstSelection());
        } else {
            codeArea.replaceText(start, end, newText);
        }

        if (ci.getCommand() != null) {
            ctx.getLspBridge().executeCommand(ci.getCommand().getCommand(), ci.getCommand().getArguments());
        }

        if (additional != null && !additional.isEmpty()) {
            lastDiagnosticSpans = null;
            applyHighlightingNow();
        }
        completionMenu.hide();
    }

    private boolean advanceSnippet() {
        javafx.scene.control.IndexRange selection = codeArea.getSelection();
        SnippetSession.Selection next = activeSnippet.advance(
                codeArea.getCaretPosition(),
                selection.getStart(),
                selection.getEnd());
        if (next == null) {
            activeSnippet = null;
            return false;
        }
        applySnippetSelection(next);
        return true;
    }

    private void applySnippetSelection(SnippetSession.Selection selection) {
        int length = codeArea.getLength();
        int start = Math.max(0, Math.min(selection.start(), length));
        int end = Math.max(0, Math.min(selection.end(), length));
        codeArea.selectRange(start, end);
        if (selection.finalCaret()) {
            activeSnippet = null;
        }
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
            if (content == null || content.isBlank()) { hoverPopup.hide(); return; }
            hoverPopup.show(codeArea, content, pos.getX() + 10, pos.getY() + 10);
        }, Platform::runLater);
    }
}
