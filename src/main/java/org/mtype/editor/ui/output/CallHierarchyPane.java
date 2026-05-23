package org.mtype.editor.ui.output;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import org.eclipse.lsp4j.CallHierarchyIncomingCall;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyOutgoingCall;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.lsp.LspBridge;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Panel that shows incoming or outgoing call hierarchy for a chosen symbol.
 */
public class CallHierarchyPane extends BorderPane {
    private final AppContext ctx;
    private final TreeView<HierarchyNode> tree = new TreeView<>();
    private final ToggleButton incomingBtn = new ToggleButton("Incoming");
    private final ToggleButton outgoingBtn = new ToggleButton("Outgoing");
    private final Label rootLabel = new Label("Right-click an identifier and pick \"Show Call Hierarchy\", or press Ctrl+Alt+H");
    private CallHierarchyItem rootItem;

    public CallHierarchyPane(AppContext ctx) {
        this.ctx = ctx;
        getStyleClass().add("mt-call-hierarchy");

        ToggleGroup tg = new ToggleGroup();
        incomingBtn.setToggleGroup(tg);
        outgoingBtn.setToggleGroup(tg);
        incomingBtn.setSelected(true);
        incomingBtn.setOnAction(_ -> {
            if (!incomingBtn.isSelected()) incomingBtn.setSelected(true);
            else if (rootItem != null) loadRoot(rootItem);
        });
        outgoingBtn.setOnAction(_ -> {
            if (!outgoingBtn.isSelected()) outgoingBtn.setSelected(true);
            else if (rootItem != null) loadRoot(rootItem);
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
        HBox toolbar = new HBox(8, incomingBtn, outgoingBtn, spacer, rootLabel);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("mt-call-hierarchy-toolbar");
        toolbar.setStyle("-fx-padding: 4 6 4 6;");

        tree.setShowRoot(true);
        tree.setCellFactory(_ -> new HierarchyCell());
        tree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                TreeItem<HierarchyNode> sel = tree.getSelectionModel().getSelectedItem();
                if (sel != null && sel.getValue() != null) navigateTo(sel.getValue().item);
            }
        });

        setTop(toolbar);
        setCenter(tree);
    }

    public void show(CallHierarchyItem item) {
        rootItem = item;
        rootLabel.setText(item.getName());
        loadRoot(item);
    }

    private void loadRoot(CallHierarchyItem item) {
        HierarchyNode rootNode = new HierarchyNode(item);
        TreeItem<HierarchyNode> root = new LazyHierarchyItem(rootNode, outgoingBtn.isSelected());
        root.setExpanded(true);
        tree.setRoot(root);
    }

    private void navigateTo(CallHierarchyItem item) {
        if (item == null) return;
        try {
            Path target = java.nio.file.Paths.get(URI.create(item.getUri()));
            int line = item.getSelectionRange() != null ? item.getSelectionRange().getStart().getLine() : 0;
            int col = item.getSelectionRange() != null ? item.getSelectionRange().getStart().getCharacter() : 0;
            ctx.getTabPane().openAt(target, line, col);
        } catch (Exception ex) {
            ctx.getStatusBar().setMessage("Bad URI: " + item.getUri());
        }
    }

    /* ------------------------------ tree items ------------------------------ */

    private final class LazyHierarchyItem extends TreeItem<HierarchyNode> {
        private boolean loaded;
        private final boolean outgoing;

        LazyHierarchyItem(HierarchyNode value, boolean outgoing) {
            super(value);
            this.outgoing = outgoing;
            // mark expandable so the disclosure triangle shows before we know real children
            super.getChildren().add(new TreeItem<>(HierarchyNode.PLACEHOLDER));
            expandedProperty().addListener((_, _, isNow) -> {
                if (isNow && !loaded) loadChildren();
            });
        }

        private void loadChildren() {
            loaded = true;
            LspBridge lsp = ctx.getLspBridge();
            if (lsp == null || !lsp.isReady() || getValue() == null || getValue().item == null) {
                super.getChildren().clear();
                return;
            }
            CallHierarchyItem parent = getValue().item;
            if (outgoing) {
                lsp.outgoingCalls(parent).thenAcceptAsync(this::setOutgoing, Platform::runLater);
            } else {
                lsp.incomingCalls(parent).thenAcceptAsync(this::setIncoming, Platform::runLater);
            }
        }

        private void setIncoming(List<CallHierarchyIncomingCall> calls) {
            super.getChildren().clear();
            if (calls == null || calls.isEmpty()) return;
            for (CallHierarchyIncomingCall c : calls) {
                super.getChildren().add(new LazyHierarchyItem(new HierarchyNode(c.getFrom()), false));
            }
        }

        private void setOutgoing(List<CallHierarchyOutgoingCall> calls) {
            super.getChildren().clear();
            if (calls == null || calls.isEmpty()) return;
            for (CallHierarchyOutgoingCall c : calls) {
                super.getChildren().add(new LazyHierarchyItem(new HierarchyNode(c.getTo()), true));
            }
        }
    }

    private record HierarchyNode(CallHierarchyItem item) {
            static final HierarchyNode PLACEHOLDER = new HierarchyNode(null);

        @Override
            public String toString() {
                if (item == null) return "loading…";
                String name = item.getName() == null ? "?" : item.getName();
                String detail = item.getDetail();
                String file = "";
                try {
                    Path p = Paths.get(URI.create(item.getUri()));
                    file = "  —  " + (p.getFileName() == null ? p.toString() : p.getFileName().toString());
                } catch (Exception ignored) {
                }
                return (detail == null || detail.isBlank()) ? name + file : (name + "   " + detail + file);
            }
        }

    private static final class HierarchyCell extends TreeCell<HierarchyNode> {
        @Override
        protected void updateItem(HierarchyNode item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : item.toString());
            setGraphic(null);
        }
    }
}
