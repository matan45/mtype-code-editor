package org.mtype.editor.ui.editor;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.lsp.LspBridge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Code-lens rows (debounced fetch) shown above signatures in the gutter, plus the references popup
 * menu opened when a lens is clicked. Owns the lens-by-paragraph map and the set of paragraphs styled
 * with extra top padding; both are consulted by {@link GutterFactory} and {@link EditorTab}'s
 * execution-line styling.
 */
final class CodeLensController {
    private final MTypeCodeArea codeArea;
    private final Path path;
    private final AppContext ctx;
    private final boolean lspManaged;
    private final Runnable gutterRefresh;
    private final ContextMenu referencesMenu = new ContextMenu();

    private final Map<Integer, CodeLensLine> codeLensByParagraph = new HashMap<>();
    private final Set<Integer> codeLensParagraphStyles = new HashSet<>();
    private ScheduledFuture<?> pending;
    private int requestSerial;

    CodeLensController(MTypeCodeArea codeArea, Path path, AppContext ctx, boolean lspManaged, Runnable gutterRefresh) {
        this.codeArea = codeArea;
        this.path = path;
        this.ctx = ctx;
        this.lspManaged = lspManaged;
        this.gutterRefresh = gutterRefresh;
        referencesMenu.getStyleClass().add("mt-references");
    }

    /* ----- gutter queries ----- */

    /** Lens title for the given paragraph, or null if none. */
    String lensTitleAt(int paragraphIndex) {
        CodeLensLine lens = codeLensByParagraph.get(paragraphIndex);
        return lens == null ? null : lens.title();
    }

    boolean isCodeLensParagraph(int line) {
        return codeLensParagraphStyles.contains(line);
    }

    /** Resolve and display the references popup for the lens on this paragraph, anchored at {@code anchor}. */
    void showReferencesAt(int paragraphIndex, Node anchor) {
        CodeLensLine lens = codeLensByParagraph.get(paragraphIndex);
        if (lens != null) showCodeLensReferences(lens, anchor);
    }

    /* ----- fetch ----- */

    void schedule() {
        if (!lspManaged) return;
        if (pending != null) pending.cancel(false);
        pending = EditorTab.BG_EXEC.schedule(
                () -> Platform.runLater(this::requestNow),
                450, TimeUnit.MILLISECONDS);
    }

    private void requestNow() {
        LspBridge lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) return;
        int request = ++requestSerial;
        lsp.codeLens(path).thenAcceptAsync(lenses -> {
            if (request != requestSerial) return;
            applyCodeLenses(lenses);
        }, Platform::runLater);
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
        gutterRefresh.run();
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

    /* ----- references popup ----- */

    private void showCodeLensReferences(CodeLensLine lens, Node anchor) {
        LspBridge lsp = ctx.getLspBridge();
        if (lsp == null || !lsp.isReady()) {
            ctx.getStatusBar().setMessage("LSP not ready");
            return;
        }
        lsp.references(path, lens.line(), lens.character(), false).thenAcceptAsync(locations -> {
            if (locations == null || locations.isEmpty()) {
                ctx.getStatusBar().setMessage("No references");
                return;
            }
            ctx.getStatusBar().setMessage(lens.title());
            showReferencesMenu(anchor, locations);
        }, Platform::runLater);
    }

    private void showReferencesMenu(Node anchor, List<? extends Location> locations) {
        referencesMenu.hide();
        referencesMenu.getItems().clear();
        Map<Path, String[]> fileLines = new HashMap<>();
        int index = 1;
        for (Location location : locations) {
            CustomMenuItem item = new CustomMenuItem(referenceRow(index, location, fileLines), true);
            item.setOnAction(_ -> Locations.openLocation(ctx, location, "reference"));
            referencesMenu.getItems().add(item);
            index++;
        }
        referencesMenu.show(anchor, Side.BOTTOM, 0, 2);
    }

    private Node referenceRow(int index, Location location, Map<Path, String[]> fileLines) {
        ReferencePreview preview = referencePreview(location, fileLines);

        Region icon = new Region();
        icon.getStyleClass().add("mt-reference-icon");

        Label ordinal = new Label(index + ".");
        ordinal.getStyleClass().add("mt-reference-index");
        ordinal.setMinWidth(34);
        ordinal.setPrefWidth(34);
        ordinal.setMaxWidth(34);

        Label file = new Label(preview.file());
        file.getStyleClass().add("mt-reference-file");
        file.setMinWidth(92);
        file.setPrefWidth(92);
        file.setMaxWidth(92);
        file.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS);

        Label position = new Label(preview.position());
        position.getStyleClass().add("mt-reference-position");
        position.setMinWidth(48);
        position.setPrefWidth(48);
        position.setMaxWidth(48);

        Label snippet = new Label(preview.snippet());
        snippet.getStyleClass().add("mt-reference-snippet");
        snippet.setMinWidth(0);
        snippet.setPrefWidth(420);
        snippet.setMaxWidth(420);
        snippet.setTextOverrun(OverrunStyle.ELLIPSIS);
        HBox.setHgrow(snippet, Priority.NEVER);

        HBox row = new HBox(6, icon, ordinal, file, position, snippet);
        row.getStyleClass().add("mt-reference-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMinWidth(640);
        row.setPrefWidth(640);
        row.setMaxWidth(640);
        return row;
    }

    private ReferencePreview referencePreview(Location location, Map<Path, String[]> fileLines) {
        if (location == null || location.getUri() == null || location.getRange() == null
                || location.getRange().getStart() == null) {
            return new ReferencePreview("Unknown reference", "", "");
        }

        Position start = location.getRange().getStart();
        int line = start.getLine();
        int character = start.getCharacter();
        Path refPath = Locations.uriToPath(location.getUri());
        if (refPath == null) {
            return new ReferencePreview(location.getUri(), (line + 1) + ":" + (character + 1), "");
        }

        Path name = refPath.getFileName();
        String file = name == null ? refPath.toString() : name.toString();
        String snippet = "";
        String[] lines = fileLines.computeIfAbsent(refPath, this::readFileLines);
        if (lines != null && line >= 0 && line < lines.length) {
            snippet = lines[line].strip();
        }
        return new ReferencePreview(file, (line + 1) + ":" + (character + 1), snippet);
    }

    private String[] readFileLines(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).split("\\R", -1);
        } catch (Exception ignored) {
            return null;
        }
    }

    void dispose() {
        if (pending != null) pending.cancel(false);
        referencesMenu.hide();
    }

    private record CodeLensLine(String title, int line, int character) {}
    private record ReferencePreview(String file, String position, String snippet) {}
}
