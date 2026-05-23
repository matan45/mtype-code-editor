package org.mtype.editor.ui.output;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import org.eclipse.lsp4j.Location;
import org.mtype.editor.app.AppContext;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bottom-pane tab that renders the result of textDocument/references as a
 * file-grouped tree of source-line previews. Double-clicking a row opens the
 * editor at the reference location. Mirrors CallHierarchyPane's structure
 * but is a single-level grouping (no lazy expansion) since references are
 * returned in one shot, not paged.
 */
public class ReferencesPane extends BorderPane {
    private final AppContext ctx;
    private final TreeView<RefNode> tree = new TreeView<>();
    private final Label header = new Label("Right-click an identifier and pick \"Find All References\", or press Shift+F12");

    public ReferencesPane(AppContext ctx) {
        this.ctx = ctx;
        getStyleClass().add("mt-references");

        Button clear = new Button("Clear");
        clear.getStyleClass().add("mt-output-toolbar-button");
        clear.setOnAction(_ -> clear());

        Region spacer = new Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        HBox toolbar = new HBox(8, header, spacer, clear);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(4, 6, 4, 6));
        toolbar.getStyleClass().add("mt-references-toolbar");

        tree.setShowRoot(false);
        tree.setCellFactory(_ -> new RefCell());
        tree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                TreeItem<RefNode> sel = tree.getSelectionModel().getSelectedItem();
                if (sel != null && sel.getValue() != null && sel.getValue().location != null) {
                    navigateTo(sel.getValue().location);
                }
            }
        });

        setTop(toolbar);
        setCenter(tree);
    }

    public void show(String symbolLabel, List<? extends Location> locations) {
        if (locations == null || locations.isEmpty()) {
            header.setText(symbolLabel + " — 0 references");
            tree.setRoot(new TreeItem<>());
            return;
        }
        // Preserve LSP-returned order within each file. LinkedHashMap keeps
        // insertion order of file groups so files appear in the order their
        // first reference appeared.
        Map<String, java.util.List<Location>> byUri = new LinkedHashMap<>();
        for (Location loc : locations) {
            byUri.computeIfAbsent(loc.getUri(), _ -> new java.util.ArrayList<>()).add(loc);
        }

        header.setText(symbolLabel + " — " + locations.size() + " reference"
                + (locations.size() == 1 ? "" : "s")
                + " in " + byUri.size() + " file" + (byUri.size() == 1 ? "" : "s"));

        TreeItem<RefNode> root = new TreeItem<>(new RefNode(null, null, null));
        for (Map.Entry<String, java.util.List<Location>> e : byUri.entrySet()) {
            Path filePath = uriToPath(e.getKey());
            String fileLabel = filePath != null && filePath.getFileName() != null
                    ? filePath.getFileName().toString()
                    : e.getKey();
            TreeItem<RefNode> fileItem = new TreeItem<>(new RefNode(
                    fileLabel + " (" + e.getValue().size() + ")", null, filePath));
            fileItem.setExpanded(true);
            // Cache file lines once per file so each reference row can show
            // a snippet without re-reading the file from disk.
            String[] lines = filePath != null ? readLines(filePath) : null;
            for (Location loc : e.getValue()) {
                int line = loc.getRange() != null && loc.getRange().getStart() != null
                        ? loc.getRange().getStart().getLine() : 0;
                String snippet = lines != null && line >= 0 && line < lines.length
                        ? lines[line].strip() : "";
                String label = (line + 1) + ": " + snippet;
                fileItem.getChildren().add(new TreeItem<>(new RefNode(label, loc, filePath)));
            }
            root.getChildren().add(fileItem);
        }
        tree.setRoot(root);
    }

    private void clear() {
        header.setText("Right-click an identifier and pick \"Find All References\", or press Shift+F12");
        tree.setRoot(new TreeItem<>());
    }

    private void navigateTo(Location loc) {
        Path target = uriToPath(loc.getUri());
        if (target == null) {
            ctx.getStatusBar().setMessage("Bad URI: " + loc.getUri());
            return;
        }
        int line = loc.getRange() != null && loc.getRange().getStart() != null
                ? loc.getRange().getStart().getLine() : 0;
        int col = loc.getRange() != null && loc.getRange().getStart() != null
                ? loc.getRange().getStart().getCharacter() : 0;
        ctx.getTabPane().openAt(target, line, col);
    }

    private static Path uriToPath(String uri) {
        try {
            return Paths.get(URI.create(uri));
        } catch (Exception _) {
            return null;
        }
    }

    private static String[] readLines(Path path) {
        try {
            return Files.readString(path).split("\\R", -1);
        } catch (Exception _) {
            return null;
        }
    }

    private record RefNode(String label, Location location, Path filePath) {}

    private static final class RefCell extends TreeCell<RefNode> {
        @Override
        protected void updateItem(RefNode item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : item.label);
            setGraphic(null);
        }
    }
}
