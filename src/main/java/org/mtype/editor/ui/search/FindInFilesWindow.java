package org.mtype.editor.ui.search;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.ui.chrome.WindowResizer;
import org.mtype.editor.ui.chrome.WindowTitleBar;
import org.mtype.editor.search.FindInFilesService;
import org.mtype.editor.search.SearchMatch;
import org.mtype.editor.search.SearchQuery;
import org.mtype.editor.ui.tree.IconFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.PatternSyntaxException;

public class FindInFilesWindow {

    private final AppContext ctx;
    private final Stage stage;
    private final FindInFilesService service = new FindInFilesService();

    private final TextField searchField = new TextField();
    private final ToggleButton caseToggle = new ToggleButton("Aa");
    private final ToggleButton wordToggle = new ToggleButton("W");
    private final ToggleButton regexToggle = new ToggleButton(".*");
    private final TextField maskField = new TextField();
    private final TreeView<Object> resultsTree = new TreeView<>();
    private final Label statusLabel = new Label("Type to search.");
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Button stopButton = new Button("Stop");

    private final PauseTransition debounce = new PauseTransition(Duration.millis(200));

    private final Map<Path, TreeItem<Object>> fileItems = new HashMap<>();
    private Path workspaceRootSnapshot;
    private long generation = 0;

    public FindInFilesWindow(AppContext ctx, Stage owner) {
        this.ctx = ctx;
        this.stage = new Stage();

        BorderPane root = new BorderPane();
        root.getStyleClass().addAll("mt-dialog", "mt-find-in-files");

        stage.initStyle(StageStyle.UNDECORATED);
        stage.initOwner(owner);
        stage.setTitle("Find in Files");
        WindowTitleBar titleBar = new WindowTitleBar(stage, null);

        VBox topStack = new VBox(titleBar, buildTop());
        root.setTop(topStack);
        root.setCenter(buildCenter());
        root.setBottom(buildStatusBar());

        Scene scene = new Scene(root, 820, 600);
        var css = FindInFilesWindow.class.getResource("/css/mtype-dark.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());

        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.ESCAPE),
                this::hide);

        stage.setScene(scene);
        WindowResizer.install(stage, scene);
        stage.setOnCloseRequest(e -> { e.consume(); hide(); });

        wireListeners();
        ctx.workspaceOpenProperty().addListener((obs, was, isOpen) -> onWorkspaceChanged());
    }

    private VBox buildTop() {
        searchField.setPromptText("Search…");
        HBox.setHgrow(searchField, Priority.ALWAYS);

        caseToggle.setTooltip(new Tooltip("Case sensitive"));
        wordToggle.setTooltip(new Tooltip("Whole word"));
        regexToggle.setTooltip(new Tooltip("Regex"));
        caseToggle.getStyleClass().add("mt-search-toggle");
        wordToggle.getStyleClass().add("mt-search-toggle");
        regexToggle.getStyleClass().add("mt-search-toggle");

        HBox row1 = new HBox(8, searchField, caseToggle, wordToggle, regexToggle);
        row1.setAlignment(Pos.CENTER_LEFT);

        Label filesLabel = new Label("Files:");
        filesLabel.getStyleClass().add("mt-search-files-label");
        maskField.setPromptText("e.g. *.mt, **/*.mtproj");
        HBox.setHgrow(maskField, Priority.ALWAYS);
        HBox row2 = new HBox(8, filesLabel, maskField);
        row2.setAlignment(Pos.CENTER_LEFT);

        VBox top = new VBox(8, row1, row2);
        top.setPadding(new Insets(12, 14, 8, 14));
        top.getStyleClass().add("mt-search-header");
        return top;
    }

    private BorderPane buildCenter() {
        resultsTree.setShowRoot(false);
        resultsTree.setRoot(new TreeItem<>(null));
        resultsTree.getStyleClass().add("mt-search-tree");
        resultsTree.setCellFactory(tv -> new SearchResultCell(() -> workspaceRootSnapshot));

        resultsTree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                openSelected();
            }
        });
        resultsTree.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) openSelected();
        });

        BorderPane center = new BorderPane(resultsTree);
        center.setPadding(new Insets(0, 14, 0, 14));
        return center;
    }

    private HBox buildStatusBar() {
        statusLabel.getStyleClass().add("mt-search-status");
        progress.setPrefSize(14, 14);
        progress.setMinSize(14, 14);
        progress.setMaxSize(14, 14);
        progress.setVisible(false);
        progress.setManaged(false);
        stopButton.getStyleClass().add("mt-panel-button");
        stopButton.setVisible(false);
        stopButton.setManaged(false);
        stopButton.setOnAction(e -> cancelSearch());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(8, statusLabel, spacer, progress, stopButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(8, 14, 12, 14));
        bar.getStyleClass().add("mt-search-statusbar");
        return bar;
    }

    private void wireListeners() {
        debounce.setOnFinished(e -> runSearch());

        searchField.textProperty().addListener((obs, was, now) -> {
            clearRegexError();
            debounce.playFromStart();
        });
        searchField.setOnAction(e -> { debounce.stop(); runSearch(); });

        maskField.textProperty().addListener((obs, was, now) -> debounce.playFromStart());

        caseToggle.selectedProperty().addListener((obs, was, now) -> debounce.playFromStart());
        wordToggle.selectedProperty().addListener((obs, was, now) -> debounce.playFromStart());
        regexToggle.selectedProperty().addListener((obs, was, now) -> {
            clearRegexError();
            debounce.playFromStart();
        });
    }

    public void showOrFocus() {
        Path root = currentWorkspaceRoot();
        if (root != null) {
            workspaceRootSnapshot = root;
            stage.setTitle("Find in Files — " + root.getFileName());
            searchField.setDisable(false);
            maskField.setDisable(false);
            if (statusLabel.getText().startsWith("Open a folder")) {
                statusLabel.setText("Type to search.");
            }
        } else {
            workspaceRootSnapshot = null;
            stage.setTitle("Find in Files");
            searchField.setDisable(true);
            maskField.setDisable(true);
            statusLabel.setText("Open a folder to use Find in Files.");
            clearResults();
        }

        if (!stage.isShowing()) {
            stage.show();
        } else {
            stage.toFront();
            stage.requestFocus();
        }
        Platform.runLater(() -> {
            searchField.requestFocus();
            searchField.selectAll();
        });
    }

    public void hide() {
        cancelSearch();
        stage.hide();
    }

    public void cancelSearch() {
        service.cancel();
        progress.setVisible(false);
        progress.setManaged(false);
        stopButton.setVisible(false);
        stopButton.setManaged(false);
    }

    private void onWorkspaceChanged() {
        clearResults();
        Path root = currentWorkspaceRoot();
        workspaceRootSnapshot = root;
        if (root == null) {
            searchField.setDisable(true);
            maskField.setDisable(true);
            statusLabel.setText("Open a folder to use Find in Files.");
            stage.setTitle("Find in Files");
        } else {
            searchField.setDisable(false);
            maskField.setDisable(false);
            statusLabel.setText("Type to search.");
            stage.setTitle("Find in Files — " + root.getFileName());
        }
    }

    private Path currentWorkspaceRoot() {
        return ctx.getWorkspace() == null ? null : ctx.getWorkspace().root();
    }

    private SearchQuery currentQuery() {
        return new SearchQuery(
                searchField.getText() == null ? "" : searchField.getText(),
                caseToggle.isSelected(),
                wordToggle.isSelected(),
                regexToggle.isSelected(),
                maskField.getText());
    }

    private void runSearch() {
        Path root = currentWorkspaceRoot();
        if (root == null) {
            statusLabel.setText("Open a folder to use Find in Files.");
            clearResults();
            return;
        }
        workspaceRootSnapshot = root;

        SearchQuery q = currentQuery();
        if (q.isBlank()) {
            cancelSearch();
            clearResults();
            statusLabel.setText("Type to search.");
            return;
        }

        if (q.regex()) {
            try { q.compile(); }
            catch (PatternSyntaxException ex) {
                showRegexError(ex);
                cancelSearch();
                clearResults();
                statusLabel.setText("Invalid regex.");
                return;
            }
        }
        clearRegexError();
        clearResults();

        final long myGen = ++generation;
        progress.setVisible(true);
        progress.setManaged(true);
        stopButton.setVisible(true);
        stopButton.setManaged(true);
        statusLabel.setText("Searching…");

        service.search(root, q, new FindInFilesService.Listener() {
            @Override public void onStarted() { /* status already set */ }
            @Override public void onBatch(List<SearchMatch> batch) {
                if (myGen != generation) return;
                appendBatch(batch);
            }
            @Override public void onFinished(int filesScanned, int totalMatches, boolean cancelled, String error) {
                if (myGen != generation) return;
                progress.setVisible(false);
                progress.setManaged(false);
                stopButton.setVisible(false);
                stopButton.setManaged(false);
                if (error != null) {
                    statusLabel.setText("Error: " + error);
                } else if (cancelled) {
                    statusLabel.setText(formatStatus(totalMatches, fileItems.size()) + " (cancelled, " + filesScanned + " scanned)");
                } else if (totalMatches == 0) {
                    statusLabel.setText("No matches in " + filesScanned + " files.");
                } else {
                    statusLabel.setText(formatStatus(totalMatches, fileItems.size()) + " (" + filesScanned + " files scanned)");
                }
            }
        });
    }

    private static String formatStatus(int totalMatches, int fileCount) {
        return totalMatches + (totalMatches == 1 ? " match in " : " matches in ")
                + fileCount + (fileCount == 1 ? " file" : " files");
    }

    private void appendBatch(List<SearchMatch> batch) {
        TreeItem<Object> root = resultsTree.getRoot();
        for (SearchMatch m : batch) {
            TreeItem<Object> fileItem = fileItems.get(m.path());
            int newCount;
            if (fileItem == null) {
                FileGroup fg = new FileGroup(m.path(), 1);
                fileItem = new TreeItem<>(fg);
                fileItem.setExpanded(true);
                fileItems.put(m.path(), fileItem);
                root.getChildren().add(fileItem);
                newCount = 1;
            } else {
                FileGroup old = (FileGroup) fileItem.getValue();
                newCount = old.matchCount() + 1;
                fileItem.setValue(new FileGroup(old.path(), newCount));
            }
            fileItem.getChildren().add(new TreeItem<>(m));
        }
        statusLabel.setText("Searching… " + formatStatus(countTotalMatches(), fileItems.size()));
    }

    private int countTotalMatches() {
        int n = 0;
        for (TreeItem<Object> fi : fileItems.values()) {
            if (fi.getValue() instanceof FileGroup fg) n += fg.matchCount();
        }
        return n;
    }

    private void clearResults() {
        fileItems.clear();
        resultsTree.setRoot(new TreeItem<>(null));
        resultsTree.getRoot().setExpanded(true);
    }

    private void openSelected() {
        TreeItem<Object> sel = resultsTree.getSelectionModel().getSelectedItem();
        if (sel == null) return;
        Object v = sel.getValue();
        if (v instanceof SearchMatch m) {
            ctx.getTabPane().openAt(m.path(), m.lineNumber(), m.columnStart());
        } else if (v instanceof FileGroup fg) {
            ctx.getTabPane().openFile(fg.path());
        }
    }

    private void showRegexError(PatternSyntaxException ex) {
        if (!searchField.getStyleClass().contains("mt-search-error")) {
            searchField.getStyleClass().add("mt-search-error");
        }
        searchField.setTooltip(new Tooltip(ex.getDescription()));
    }

    private void clearRegexError() {
        searchField.getStyleClass().remove("mt-search-error");
        searchField.setTooltip(null);
    }

    public record FileGroup(Path path, int matchCount) {}

    private static class SearchResultCell extends TreeCell<Object> {
        private final java.util.function.Supplier<Path> rootSupplier;

        SearchResultCell(java.util.function.Supplier<Path> rootSupplier) {
            this.rootSupplier = rootSupplier;
        }

        @Override
        protected void updateItem(Object item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("mt-search-file-row", "mt-search-match-row");
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            if (item instanceof FileGroup fg) {
                setGraphic(IconFactory.iconForPath(fg.path(), false));
                Path root = rootSupplier.get();
                String label;
                try {
                    label = root != null ? root.relativize(fg.path()).toString() : fg.path().toString();
                } catch (IllegalArgumentException ex) {
                    label = fg.path().toString();
                }
                setText(label + "  (" + fg.matchCount() + ")");
                getStyleClass().add("mt-search-file-row");
            } else if (item instanceof SearchMatch m) {
                String line = m.lineText();
                int s = Math.max(0, Math.min(m.columnStart(), line.length()));
                int e = Math.max(s, Math.min(m.columnEnd(), line.length()));
                Text lineNo = new Text(String.format("%5d  ", m.lineNumber() + 1));
                lineNo.getStyleClass().add("mt-search-line-no");
                Text before = new Text(line.substring(0, s));
                before.getStyleClass().add("mt-search-text");
                Text hit = new Text(line.substring(s, e));
                hit.getStyleClass().add("mt-search-hit");
                Text after = new Text(line.substring(e));
                after.getStyleClass().add("mt-search-text");
                TextFlow flow = new TextFlow(lineNo, before, hit, after);
                flow.getStyleClass().add("mt-search-match-flow");
                setText(null);
                setGraphic(flow);
                getStyleClass().add("mt-search-match-row");
            }
        }
    }
}
