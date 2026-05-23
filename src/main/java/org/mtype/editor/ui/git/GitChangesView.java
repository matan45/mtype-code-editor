package org.mtype.editor.ui.git;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.stage.Window;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.git.GitService;
import org.mtype.editor.git.GitService.CommitEntry;
import org.mtype.editor.git.GitService.StatusEntry;
import org.mtype.editor.ui.dialogs.Dialogs;
import org.mtype.editor.ui.dialogs.QuickPickDialog;
import org.mtype.editor.workspace.Workspace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class GitChangesView extends BorderPane {
    private static final int HISTORY_LIMIT = 50;

    private final AppContext ctx;
    private final Label stateLabel = new Label("Open a folder to view Git changes");
    private final TreeView<GitNode> tree = new TreeView<>();
    private final SplitPane contentSplit = new SplitPane();
    private final BorderPane changesPane = new BorderPane();
    private final BorderPane historyPane = new BorderPane();
    private final ListView<CommitEntry> historyList = new ListView<>();
    private final Label historyStateLabel = new Label("No Git history");
    private final TextArea commitMessage = new TextArea();
    private final Label branchChip = new Label("");
    private final SimpleIntegerProperty stagedCount = new SimpleIntegerProperty(0);
    private Workspace workspace;
    private String currentBranch = "";

    public GitChangesView(AppContext ctx) {
        this.ctx = ctx;
        getStyleClass().add("mt-git-view");

        Label title = new Label("Source Control");
        title.getStyleClass().add("mt-panel-title");

        branchChip.getStyleClass().add("mt-git-branch-chip");
        branchChip.setVisible(false);
        branchChip.setManaged(false);

        MenuButton overflow = new MenuButton("⋯");
        overflow.getStyleClass().add("mt-panel-button");
        overflow.setTooltip(new Tooltip("More actions"));
        buildOverflowMenu(overflow);

        Button refresh = new Button("Refresh");
        refresh.getStyleClass().add("mt-panel-button");
        refresh.setOnAction(_ -> refresh());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(title, branchChip, spacer, overflow, refresh);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setSpacing(6);
        header.setPadding(new Insets(8, 10, 6, 10));
        header.getStyleClass().add("mt-panel-header");

        commitMessage.setPromptText("Commit message (Ctrl+Enter to commit)");
        commitMessage.setPrefRowCount(2);
        commitMessage.setWrapText(true);
        commitMessage.getStyleClass().add("mt-git-commit-msg");
        commitMessage.setOnKeyPressed(e -> {
            if (e.isControlDown() && e.getCode() == javafx.scene.input.KeyCode.ENTER) {
                doCommit();
                e.consume();
            }
        });

        Button commitButton = new Button("Commit");
        commitButton.getStyleClass().add("mt-git-commit-btn");
        commitButton.setMaxWidth(Double.MAX_VALUE);
        commitButton.disableProperty().bind(
                Bindings.createBooleanBinding(
                        () -> commitMessage.getText() == null || commitMessage.getText().isBlank() || stagedCount.get() == 0,
                        commitMessage.textProperty(), stagedCount));
        commitButton.setOnAction(_ -> doCommit());

        VBox commitBox = new VBox(commitMessage, commitButton);
        commitBox.setSpacing(4);
        commitBox.setPadding(new Insets(4, 10, 6, 10));
        commitBox.getStyleClass().add("mt-git-commit-box");

        VBox top = new VBox(header, commitBox);
        top.getStyleClass().add("mt-git-top");

        tree.setShowRoot(false);
        tree.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        tree.setCellFactory(_ -> new GitTreeCell());
        tree.getStyleClass().add("mt-git-tree");
        tree.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                TreeItem<GitNode> selected = tree.getSelectionModel().getSelectedItem();
                if (selected == null || selected.getValue() == null) return;
                Path file = selected.getValue().path();
                if (file != null) openDiff(file);
            }
        });

        historyList.setCellFactory(_ -> new CommitHistoryCell());
        historyList.getStyleClass().add("mt-git-history-list");
        historyList.setFocusTraversable(false);

        Label historyTitle = new Label("History");
        historyTitle.getStyleClass().add("mt-git-history-title");
        HBox historyHeader = new HBox(historyTitle);
        historyHeader.setAlignment(Pos.CENTER_LEFT);
        historyHeader.getStyleClass().add("mt-git-history-header");

        historyStateLabel.getStyleClass().add("mt-empty-state");
        historyPane.setTop(historyHeader);
        historyPane.setCenter(historyStateLabel);
        historyPane.getStyleClass().add("mt-git-history-pane");

        contentSplit.setOrientation(Orientation.VERTICAL);
        contentSplit.getItems().addAll(changesPane, historyPane);
        contentSplit.setDividerPositions(0.58);
        contentSplit.getStyleClass().add("mt-git-content-split");

        stateLabel.getStyleClass().add("mt-empty-state");
        setTop(top);
        setCenter(stateLabel);
    }

    public void setWorkspace(Workspace workspace) {
        this.workspace = workspace;
        refresh();
    }

    public void refresh() {
        Workspace ws = workspace;
        if (ws == null) {
            stagedCount.set(0);
            currentBranch = "";
            branchChip.setText("");
            branchChip.setVisible(false);
            branchChip.setManaged(false);
            ctx.getStatusBar().setBranch("", 0, 0, false);
            showMessage("Open a folder to view Git changes");
            return;
        }

        GitService git = ctx.getGitService();
        if (git == null) return;

        git.status().handleAsync((entries, err) -> {
            if (err != null || workspace != ws) {
                if (err != null) showMessage("This folder is not a Git repository");
                return null;
            }
            renderEntries(entries);
            return null;
        }, Platform::runLater);

        git.recentCommits(HISTORY_LIMIT).handleAsync((commits, err) -> {
            if (workspace != ws) return null;
            if (err != null) {
                showHistoryMessage();
                return null;
            }
            renderHistory(commits);
            return null;
        }, Platform::runLater);

        git.currentBranch().thenCombineAsync(git.aheadBehind(), (branch, ab) -> {
            if (workspace != ws) return null;
            currentBranch = branch == null ? "" : branch;
            if (!currentBranch.isEmpty()) {
                branchChip.setText("⎇ " + currentBranch);
                branchChip.setVisible(true);
                branchChip.setManaged(true);
            } else {
                branchChip.setVisible(false);
                branchChip.setManaged(false);
            }
            ctx.getStatusBar().setBranch(currentBranch, ab.ahead(), ab.behind(), ab.hasUpstream());
            ctx.getStatusBar().setOnBranchClick(this::openBranchQuickPick);
            return null;
        }, Platform::runLater);
    }

    private void renderEntries(List<StatusEntry> entries) {
        entries.sort(Comparator
                .comparing(StatusEntry::group)
                .thenComparing(e -> e.path().toString().toLowerCase()));

        int staged = (int) entries.stream().filter(e -> "Staged Changes".equals(e.group())).count();
        stagedCount.set(staged);

        if (entries.isEmpty()) {
            showChangesMessage();
            return;
        }

        TreeItem<GitNode> root = new TreeItem<>(GitNode.group("Git"));
        addGroup(root, "Merge Conflicts", entries);
        addGroup(root, "Staged Changes", entries);
        addGroup(root, "Changes", entries);
        addGroup(root, "Untracked", entries);
        root.setExpanded(true);
        tree.setRoot(root);
        changesPane.setCenter(tree);
        showGitContent();
    }

    private void addGroup(TreeItem<GitNode> root, String group, List<StatusEntry> entries) {
        TreeItem<GitNode> groupItem = new TreeItem<>(GitNode.group(group));
        for (StatusEntry e : entries) {
            if (e.group().equals(group)) {
                groupItem.getChildren().add(new TreeItem<>(GitNode.file(e)));
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

    private void showChangesMessage() {
        stateLabel.setText("No Git changes");
        changesPane.setCenter(stateLabel);
        showGitContent();
    }

    private void showGitContent() {
        if (getCenter() != contentSplit) {
            setCenter(contentSplit);
        }
    }

    private void renderHistory(List<CommitEntry> commits) {
        if (commits == null || commits.isEmpty()) {
            showHistoryMessage();
            return;
        }
        historyList.getItems().setAll(commits);
        historyPane.setCenter(historyList);
        showGitContent();
    }

    private void showHistoryMessage() {
        historyStateLabel.setText("No Git history");
        historyPane.setCenter(historyStateLabel);
        if (workspace != null) showGitContent();
    }

    private Window window() {
        return getScene() == null ? null : getScene().getWindow();
    }

    // ---------- Actions ----------

    private void openDiff(Path file) {
        GitService git = ctx.getGitService();
        if (git == null) return;
        git.diffAgainstHead(file).whenCompleteAsync((pair, err) -> {
            if (err != null) {
                ctx.getStatusBar().setMessage("Diff failed: " + err.getMessage());
                return;
            }
            String title = file.getFileName() + " (Working Tree)";
            ctx.getTabPane().openDiff(file, pair.oldContent(), pair.newContent(), title, pair.binary());
        }, Platform::runLater);
    }

    private void doStage(List<Path> files) {
        runOp(ctx.getGitService().add(files), "Staged " + files.size() + " file(s)", "Stage failed");
    }

    private void doUnstage(List<Path> files) {
        runOp(ctx.getGitService().unstage(files), "Unstaged " + files.size() + " file(s)", "Unstage failed");
    }

    private void doDiscard(List<Path> files) {
        Optional<javafx.scene.control.ButtonType> res = Dialogs.confirm(window(),
                        "Discard changes?",
                        files.size() + " file(s) will be reverted to HEAD. This cannot be undone.")
                .showAndWait();
        if (res.isEmpty() || res.get() != javafx.scene.control.ButtonType.OK) return;
        runOp(ctx.getGitService().discard(files), "Discarded " + files.size() + " file(s)", "Discard failed");
    }

    private void doCommit() {
        String msg = commitMessage.getText();
        if (msg == null || msg.isBlank()) return;
        runOp(ctx.getGitService().commit(msg), "Committed", "Commit failed", commitMessage::clear);
    }

    public void openBranchQuickPick() {
        GitService git = ctx.getGitService();
        if (git == null || workspace == null) return;
        var localF = git.localBranches();
        var remoteF = git.remoteBranches();
        var currentF = git.currentBranch();

        localF.thenCombineAsync(remoteF, (locals, remotes) -> {
            List<BranchOption> all = new ArrayList<>();
            for (String b : locals) all.add(new BranchOption(b, b, false));
            for (String b : remotes) {
                if (b.endsWith("/HEAD")) continue;
                all.add(new BranchOption(b, b, true));
            }
            return all;
        }, Platform::runLater).thenCombineAsync(currentF, (all, current) -> {
            List<BranchOption> filtered = new ArrayList<>();
            for (BranchOption o : all) {
                if (!o.remote() && o.displayName().equals(current)) continue;
                filtered.add(o);
            }
            return filtered;
        }, Platform::runLater).thenAcceptAsync(options -> {
            if (options.isEmpty()) {
                ctx.getStatusBar().setMessage("No branches available");
                return;
            }
            QuickPickDialog<BranchOption> q = new QuickPickDialog<>(window(),
                    "Switch Branch", options,
                    BranchOption::displayName,
                    null,
                    o -> o.remote() ? "Remote" : "Local");
            q.showAndWait().ifPresent(this::doCheckoutOption);
        }, Platform::runLater);
    }

    private void doCheckoutOption(BranchOption opt) {
        // For a remote branch like "origin/feature-x", checkout by short name so git
        // creates a local tracking branch (or switches to an existing local of the same name).
        String target = opt.displayName();
        if (opt.remote()) {
            int slash = target.indexOf('/');
            if (slash >= 0 && slash < target.length() - 1) target = target.substring(slash + 1);
        }
        doCheckout(target);
    }

    private void doCheckout(String branch) {
        runOp(ctx.getGitService().checkoutBranch(branch),
                "Switched to " + branch, "Checkout failed");
    }

    private record BranchOption(String displayName, String fullRef, boolean remote) {
    }

    private void doMerge() {
        GitService git = ctx.getGitService();
        if (git == null) return;
        git.localBranches().thenCombineAsync(git.currentBranch(),
                (branches, current) -> branches.stream()
                        .filter(b -> !b.equals(current))
                        .collect(Collectors.toList()),
                Platform::runLater
        ).thenAcceptAsync(branches -> {
            if (branches.isEmpty()) {
                ctx.getStatusBar().setMessage("No branches to merge");
                return;
            }
            QuickPickDialog<String> q = new QuickPickDialog<>(window(),
                    "Merge Branch", branches, b -> b, null);
            q.showAndWait().ifPresent(branch ->
                    runOp(ctx.getGitService().merge(branch), "Merged " + branch, "Merge failed", null, true));
        }, Platform::runLater);
    }

    private void doCreateBranch() {
        GitService git = ctx.getGitService();
        if (git == null) return;
        git.localBranches().thenAcceptAsync(branches -> {
            QuickPickDialog<String> q = new QuickPickDialog<>(window(),
                    "Create Branch From…", branches, b -> b, null);
            q.showAndWait().ifPresent(from -> {
                var prompt = Dialogs.prompt(window(), "New Branch",
                        "Create branch from " + from, "Name:", "");
                prompt.showAndWait().ifPresent(name -> {
                    if (name.isBlank()) return;
                    runOp(ctx.getGitService().createBranch(name.trim(), from),
                            "Created and switched to " + name, "Create branch failed");
                });
            });
        }, Platform::runLater);
    }

    private void doDeleteBranch() {
        GitService git = ctx.getGitService();
        if (git == null) return;
        var localsF = git.localBranches();
        var currentF = git.currentBranch();
        var defaultF = git.defaultBranch();
        localsF.thenCombineAsync(currentF, (list, cur) -> list.stream()
                                .filter(b -> !b.equals(cur))
                                .collect(Collectors.toList()),
                        Platform::runLater)
                .thenCombineAsync(defaultF, (list, def) -> list.stream()
                                .filter(b -> def == null || def.isBlank() || !b.equals(def))
                                .collect(Collectors.toList()),
                        Platform::runLater)
                .thenAcceptAsync(branches -> {
                    if (branches.isEmpty()) {
                        ctx.getStatusBar().setMessage("No deletable branches");
                        return;
                    }
                    QuickPickDialog<String> q = new QuickPickDialog<>(window(),
                            "Delete Branch", branches, b -> b, null);
                    q.showAndWait().ifPresent(branch -> {
                        Optional<javafx.scene.control.ButtonType> res = Dialogs.confirm(window(),
                                        "Delete branch?", "Branch '" + branch + "' will be deleted.")
                                .showAndWait();
                        if (res.isEmpty() || res.get() != javafx.scene.control.ButtonType.OK) return;
                        ctx.getGitService().deleteBranch(branch, false).whenCompleteAsync((_, err) -> {
                            if (err == null) {
                                ctx.getStatusBar().setMessage("Deleted " + branch);
                                refresh();
                                return;
                            }
                            String msg = err.getCause() == null ? err.getMessage() : err.getCause().getMessage();
                            if (msg != null && msg.toLowerCase().contains("not fully merged")) {
                                Optional<javafx.scene.control.ButtonType> force = Dialogs.confirm(window(),
                                                "Force delete?",
                                                "Branch '" + branch + "' is not fully merged. Force delete with -D?")
                                        .showAndWait();
                                if (force.isPresent() && force.get() == javafx.scene.control.ButtonType.OK) {
                                    runOp(ctx.getGitService().deleteBranch(branch, true),
                                            "Force-deleted " + branch, "Delete failed");
                                }
                            } else {
                                Dialogs.error(window(), "Delete failed", msg == null ? "Unknown error" : msg).showAndWait();
                            }
                        }, Platform::runLater);
                    });
                }, Platform::runLater);
    }

    private void doStash() {
        var prompt = Dialogs.prompt(window(), "Stash",
                "Stash changes", "Optional message:", "");
        prompt.showAndWait().ifPresent(msg -> runOp(
                ctx.getGitService().stash(msg), "Stashed", "Stash failed"));
    }

    private void doStashPop() {
        runOp(ctx.getGitService().stashPop(), "Popped stash", "Pop failed");
    }

    private void runOp(java.util.concurrent.CompletableFuture<Void> fut, String okMsg, String failMsg) {
        runOp(fut, okMsg, failMsg, null);
    }

    private void runOp(java.util.concurrent.CompletableFuture<Void> fut, String okMsg, String failMsg, Runnable onSuccess) {
        runOp(fut, okMsg, failMsg, onSuccess, false);
    }

    private void runOp(java.util.concurrent.CompletableFuture<Void> fut,
                       String okMsg,
                       String failMsg,
                       Runnable onSuccess,
                       boolean refreshOnFailure) {
        ctx.getStatusBar().setMessage(okMsg + "…");
        fut.whenCompleteAsync((_, err) -> {
            if (err == null) {
                ctx.getStatusBar().setMessage(okMsg);
                if (onSuccess != null) onSuccess.run();
                refresh();
            } else {
                String msg = err.getCause() == null ? err.getMessage() : err.getCause().getMessage();
                ctx.getStatusBar().setMessage(failMsg + ": " + msg);
                if (refreshOnFailure) refresh();
                if (isConflictMessage(msg)) showConflictDialog();
                ctx.getOutputPane().focusGit();
            }
        }, Platform::runLater);
    }

    private boolean isConflictMessage(String msg) {
        if (msg == null) return false;
        String lower = msg.toLowerCase();
        return lower.contains("conflict")
                || lower.contains("automatic merge failed")
                || lower.contains("fix conflicts")
                || lower.contains("unmerged");
    }

    private void showConflictDialog() {
        GitService git = ctx.getGitService();
        if (git == null || workspace == null) return;
        git.conflictedFiles().whenCompleteAsync((files, err) -> {
            StringBuilder body = new StringBuilder();
            body.append("Resolve conflict markers in the editor, stage the resolved files, then commit.");
            if (err == null && files != null && !files.isEmpty()) {
                body.append("\n\nConflicted files:");
                Path root = workspace.root();
                for (Path file : files) {
                    Path display = root.relativize(file.toAbsolutePath().normalize());
                    body.append("\n- ").append(display);
                }
            }
            Dialogs.error(window(), "Merge conflicts detected", body.toString()).showAndWait();
        }, Platform::runLater);
    }

    private void buildOverflowMenu(MenuButton overflow) {
        MenuItem push = new MenuItem("Push");
        push.setOnAction(_ -> runOp(ctx.getGitService().push(), "Pushed", "Push failed"));
        MenuItem pull = new MenuItem("Pull");
        pull.setOnAction(_ -> runOp(ctx.getGitService().pull(), "Pulled", "Pull failed", null, true));
        MenuItem fetch = new MenuItem("Fetch");
        fetch.setOnAction(_ -> runOp(ctx.getGitService().fetch(), "Fetched", "Fetch failed"));

        MenuItem checkout = new MenuItem("Checkout Branch…");
        checkout.setOnAction(_ -> openBranchQuickPick());
        MenuItem merge = new MenuItem("Merge Branch…");
        merge.setOnAction(_ -> doMerge());
        MenuItem newBranch = new MenuItem("Create Branch…");
        newBranch.setOnAction(_ -> doCreateBranch());
        MenuItem delBranch = new MenuItem("Delete Branch…");
        delBranch.setOnAction(_ -> doDeleteBranch());

        MenuItem stash = new MenuItem("Stash…");
        stash.setOnAction(_ -> doStash());
        MenuItem pop = new MenuItem("Pop Stash");
        pop.setOnAction(_ -> doStashPop());

        overflow.getItems().addAll(push, pull, fetch,
                new SeparatorMenuItem(),
                checkout, merge, newBranch, delBranch,
                new SeparatorMenuItem(),
                stash, pop);
    }

    // ---------- Cell rendering ----------

    private record GitNode(String title, Path path, String badge, String group) {
        static GitNode group(String title) {
            return new GitNode(title, null, null, null);
        }

        static GitNode file(StatusEntry e) {
            return new GitNode(e.path().getFileName().toString(), e.path(), e.label(), e.group());
        }
    }

    private class GitTreeCell extends TreeCell<GitNode> {
        private final HBox actions = new HBox();
        private final Label badgeLabel = new Label();
        private final Label nameLabel = new Label();
        private final HBox graphic = new HBox();

        GitTreeCell() {
            badgeLabel.getStyleClass().add("mt-git-badge");
            nameLabel.getStyleClass().add("mt-git-name");
            actions.setSpacing(4);
            actions.setAlignment(Pos.CENTER_RIGHT);
            actions.getStyleClass().add("mt-git-actions");
            actions.visibleProperty().bind(hoverProperty().or(selectedProperty()));
            actions.managedProperty().bind(actions.visibleProperty());

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            graphic.setSpacing(6);
            graphic.setAlignment(Pos.CENTER_LEFT);
            graphic.getChildren().addAll(badgeLabel, nameLabel, spacer, actions);
        }

        @Override
        protected void updateItem(GitNode item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("mt-git-group", "mt-git-file");
            setContextMenu(null);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            if (item.path() == null) {
                setText(item.title());
                setGraphic(null);
                getStyleClass().add("mt-git-group");
                return;
            }
            badgeLabel.setText(item.badge());
            nameLabel.setText(item.title());
            actions.getChildren().clear();
            Path file = item.path();
            String group = item.group();

            Label diffIcon = makeIcon("↔", "Open changes", evt -> {
                openDiff(file);
                evt.consume();
            });
            actions.getChildren().add(diffIcon);

            if ("Staged Changes".equals(group)) {
                Label unstage = makeIcon("−", "Unstage", evt -> {
                    doUnstage(List.of(file));
                    evt.consume();
                });
                actions.getChildren().add(unstage);
            } else {
                Label discard = makeIcon("↶", "Discard changes", evt -> {
                    doDiscard(List.of(file));
                    evt.consume();
                });
                Label stage = makeIcon("+", "Stage changes", evt -> {
                    doStage(List.of(file));
                    evt.consume();
                });
                actions.getChildren().addAll(discard, stage);
            }

            setText(null);
            setGraphic(graphic);
            getStyleClass().add("mt-git-file");
            setContextMenu(buildContextMenu(file, group));
        }

        private Label makeIcon(String text, String tooltip, javafx.event.EventHandler<javafx.scene.input.MouseEvent> onClick) {
            Label l = new Label(text);
            l.getStyleClass().add("mt-git-action-icon");
            l.setTooltip(new Tooltip(tooltip));
            l.setOnMouseClicked(onClick);
            return l;
        }

        private ContextMenu buildContextMenu(Path file, String group) {
            ContextMenu menu = new ContextMenu();
            MenuItem openFile = new MenuItem("Open File");
            openFile.setOnAction(_ -> {
                if (Files.isRegularFile(file)) ctx.getTabPane().openFile(file);
            });
            MenuItem openDiff = new MenuItem("Open Changes");
            openDiff.setOnAction(_ -> openDiff(file));

            menu.getItems().addAll(openFile, openDiff, new SeparatorMenuItem());

            List<Path> sel = selectedFilesInGroup(group);
            if (sel.isEmpty()) sel = List.of(file);
            final List<Path> targets = sel;

            if ("Staged Changes".equals(group)) {
                MenuItem unstage = new MenuItem("Unstage Changes");
                unstage.setOnAction(_ -> doUnstage(targets));
                menu.getItems().add(unstage);
            } else {
                MenuItem stage = new MenuItem("Stage Changes");
                stage.setOnAction(_ -> doStage(targets));
                MenuItem discard = new MenuItem("Discard Changes");
                discard.setOnAction(_ -> doDiscard(targets));
                menu.getItems().addAll(stage, discard);
            }
            return menu;
        }

        private List<Path> selectedFilesInGroup(String group) {
            List<Path> out = new ArrayList<>();
            for (TreeItem<GitNode> item : tree.getSelectionModel().getSelectedItems()) {
                if (item == null) continue;
                GitNode v = item.getValue();
                if (v != null && v.path() != null && group.equals(v.group())) {
                    out.add(v.path());
                }
            }
            return out;
        }
    }

    private static class CommitHistoryCell extends ListCell<CommitEntry> {
        @Override
        protected void updateItem(CommitEntry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                return;
            }

            Label graph = new Label(renderGraph(item.graph()));
            graph.getStyleClass().add("mt-git-history-graph");

            Label subject = new Label(item.subject());
            subject.getStyleClass().add("mt-git-history-subject");
            subject.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(subject, Priority.ALWAYS);

            HBox subjectRow = new HBox(subject);
            subjectRow.setAlignment(Pos.CENTER_LEFT);
            subjectRow.setSpacing(5);
            addDecorationChips(subjectRow, item.decorations());

            Label meta = new Label(item.author() + "  " + item.date() + "  " + item.shortHash());
            meta.getStyleClass().add("mt-git-history-meta");

            VBox text = new VBox(subjectRow, meta);
            text.setSpacing(1);
            HBox.setHgrow(text, Priority.ALWAYS);

            HBox row = new HBox(graph, text);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setSpacing(8);
            row.getStyleClass().add("mt-git-history-row");

            setText(null);
            setGraphic(row);
            setTooltip(new Tooltip(item.shortHash() + " " + item.subject()));
        }

        private String renderGraph(String graph) {
            if (graph == null || graph.isBlank()) return "●";
            return graph
                    .replace('*', '●')
                    .replace('|', '│')
                    .replace('\\', '╲')
                    .replace('/', '╱')
                    .replace('_', '─');
        }

        private void addDecorationChips(HBox row, String decorations) {
            if (decorations == null || decorations.isBlank()) return;
            String[] parts = decorations.split(",");
            int shown = 0;
            for (String raw : parts) {
                String text = decorationLabel(raw.trim());
                if (text.isBlank()) continue;
                if (shown == 3) {
                    Label more = decorationChip("+" + (parts.length - shown));
                    row.getChildren().add(more);
                    return;
                }
                row.getChildren().add(decorationChip(text));
                shown++;
            }
        }

        private String decorationLabel(String raw) {
            if (raw == null) return "";
            if (raw.startsWith("HEAD -> ")) return raw.substring("HEAD -> ".length());
            return raw;
        }

        private Label decorationChip(String text) {
            Label chip = new Label(text);
            chip.getStyleClass().add("mt-git-history-chip");
            return chip;
        }
    }
}
