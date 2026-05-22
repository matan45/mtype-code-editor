package org.mtype.editor.ui.git;

import javafx.application.Platform;
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
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.workspace.Workspace;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GitChangesView extends BorderPane {
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mtype-git-status");
        t.setDaemon(true);
        return t;
    });

    private final AppContext ctx;
    private final Label stateLabel = new Label("Open a folder to view Git changes");
    private final TreeView<GitNode> tree = new TreeView<>();
    private Workspace workspace;

    public GitChangesView(AppContext ctx) {
        this.ctx = ctx;
        getStyleClass().add("mt-git-view");

        Label title = new Label("Source Control");
        title.getStyleClass().add("mt-panel-title");

        Button refresh = new Button("Refresh");
        refresh.getStyleClass().add("mt-panel-button");
        refresh.setOnAction(e -> refresh());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(title, spacer, refresh);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(8, 10, 6, 10));
        header.getStyleClass().add("mt-panel-header");

        tree.setShowRoot(false);
        tree.setCellFactory(tv -> new GitTreeCell());
        tree.getStyleClass().add("mt-git-tree");
        tree.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                TreeItem<GitNode> selected = tree.getSelectionModel().getSelectedItem();
                if (selected == null || selected.getValue() == null) return;
                Path file = selected.getValue().path();
                if (file == null) return;
                if (Files.isRegularFile(file)) {
                    ctx.getTabPane().openFile(file);
                } else {
                    ctx.getStatusBar().setMessage("File is not available: " + file.getFileName());
                }
            }
        });

        stateLabel.getStyleClass().add("mt-empty-state");
        setTop(header);
        setCenter(stateLabel);
    }

    public void setWorkspace(Workspace workspace) {
        this.workspace = workspace;
        refresh();
    }

    public void refresh() {
        Workspace ws = workspace;
        if (ws == null) {
            showMessage("Open a folder to view Git changes");
            return;
        }
        showMessage("Checking Git status...");
        EXEC.submit(() -> loadStatus(ws));
    }

    private void loadStatus(Workspace ws) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "-c", "core.quotePath=false", "status", "--porcelain=v1");
            pb.directory(ws.getRoot().toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            List<GitEntry> entries = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    GitEntry entry = parseLine(ws.getRoot(), line);
                    if (entry != null) entries.add(entry);
                }
            }
            int exit = process.waitFor();
            if (exit != 0) {
                Platform.runLater(() -> {
                    if (workspace == ws) showMessage("This folder is not a Git repository");
                });
                return;
            }
            entries.sort(Comparator
                    .comparing(GitEntry::group)
                    .thenComparing(e -> e.path().toString().toLowerCase()));
            Platform.runLater(() -> {
                if (workspace == ws) showEntries(entries);
            });
        } catch (Exception ex) {
            Platform.runLater(() -> {
                if (workspace == ws) showMessage("Git status is unavailable");
            });
        }
    }

    private GitEntry parseLine(Path root, String line) {
        if (line == null || line.length() < 4) return null;
        String code = line.substring(0, 2);
        String rawPath = line.substring(3);
        int renameArrow = rawPath.indexOf(" -> ");
        if (renameArrow >= 0) rawPath = rawPath.substring(renameArrow + 4);

        Path path = root.resolve(rawPath).normalize();
        return new GitEntry(code, path, groupFor(code), labelFor(code));
    }

    private void showEntries(List<GitEntry> entries) {
        if (entries.isEmpty()) {
            showMessage("No Git changes");
            return;
        }

        TreeItem<GitNode> root = new TreeItem<>(GitNode.group("Git"));
        addGroup(root, "Staged Changes", entries);
        addGroup(root, "Changes", entries);
        addGroup(root, "Untracked", entries);
        root.setExpanded(true);
        tree.setRoot(root);
        setCenter(tree);
    }

    private void addGroup(TreeItem<GitNode> root, String group, List<GitEntry> entries) {
        TreeItem<GitNode> groupItem = new TreeItem<>(GitNode.group(group));
        for (GitEntry entry : entries) {
            if (entry.group().equals(group)) {
                groupItem.getChildren().add(new TreeItem<>(GitNode.file(entry)));
            }
        }
        if (!groupItem.getChildren().isEmpty()) {
            groupItem.setExpanded(true);
            root.getChildren().add(groupItem);
        }
    }

    private void showMessage(String text) {
        stateLabel.setText(text);
        setCenter(stateLabel);
    }

    private static String groupFor(String code) {
        if ("??".equals(code)) return "Untracked";
        if (code.charAt(0) != ' ') return "Staged Changes";
        return "Changes";
    }

    private static String labelFor(String code) {
        if ("??".equals(code)) return "U";
        if ("A ".equals(code) || " A".equals(code)) return "A";
        if (code.indexOf('D') >= 0) return "D";
        if (code.indexOf('R') >= 0) return "R";
        if (code.indexOf('C') >= 0) return "C";
        return "M";
    }

    private record GitEntry(String code, Path path, String group, String label) {}

    private record GitNode(String title, Path path, String badge) {
        static GitNode group(String title) {
            return new GitNode(title, null, null);
        }

        static GitNode file(GitEntry entry) {
            return new GitNode(entry.path().getFileName().toString(), entry.path(), entry.label());
        }
    }

    private static class GitTreeCell extends TreeCell<GitNode> {
        @Override
        protected void updateItem(GitNode item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("mt-git-group", "mt-git-file");
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            if (item.path() == null) {
                setText(item.title());
                getStyleClass().add("mt-git-group");
            } else {
                setText(item.badge() + "  " + item.title());
                getStyleClass().add("mt-git-file");
            }
        }
    }
}
