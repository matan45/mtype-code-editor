package org.mtype.editor.ui.output;

import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tab;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolKind;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.ui.editor.EditorTab;

import java.util.List;

public class OutlinePanel extends BorderPane {
    private final TreeView<DocumentSymbol> tree = new TreeView<>();
    private final Label empty = new Label("No symbols");
    private EditorTab activeTab;

    public OutlinePanel(AppContext ctx) {
        getStyleClass().add("mt-outline");
        tree.setShowRoot(false);
        tree.getStyleClass().add("mt-outline-tree");
        tree.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        tree.setCellFactory(_ -> new SymbolCell());
        tree.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) openSelected();
        });
        tree.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) openSelected();
        });
        empty.getStyleClass().add("mt-empty-state");
        setCenter(empty);

        if (ctx.getTabPane() != null) {
            ctx.getTabPane().getSelectionModel().selectedItemProperty().addListener((_, _, newT) -> bindTo(newT instanceof EditorTab et ? et : null));
            Tab current = ctx.getTabPane().getSelectionModel().getSelectedItem();
            if (current instanceof EditorTab et) bindTo(et);
        }
    }

    private void bindTo(EditorTab tab) {
        this.activeTab = tab;
        refresh();
    }

    public void refreshIfActive(EditorTab tab) {
        if (tab == activeTab) Platform.runLater(this::refresh);
    }

    private void refresh() {
        if (activeTab == null) {
            setCenter(empty);
            tree.setRoot(null);
            return;
        }
        List<DocumentSymbol> symbols = activeTab.getLastDocumentSymbols();
        if (symbols == null || symbols.isEmpty()) {
            setCenter(empty);
            tree.setRoot(null);
            return;
        }
        TreeItem<DocumentSymbol> root = new TreeItem<>();
        for (DocumentSymbol s : symbols) {
            root.getChildren().add(buildItem(s));
        }
        tree.setRoot(root);
        setCenter(tree);
    }

    private TreeItem<DocumentSymbol> buildItem(DocumentSymbol s) {
        TreeItem<DocumentSymbol> item = new TreeItem<>(s);
        item.setExpanded(true);
        if (s.getChildren() != null) {
            for (DocumentSymbol child : s.getChildren()) {
                item.getChildren().add(buildItem(child));
            }
        }
        return item;
    }

    private void openSelected() {
        TreeItem<DocumentSymbol> sel = tree.getSelectionModel().getSelectedItem();
        if (sel == null || sel.getValue() == null || activeTab == null) return;
        DocumentSymbol s = sel.getValue();
        Range r = s.getSelectionRange() != null ? s.getSelectionRange() : s.getRange();
        if (r == null || r.getStart() == null) return;
        Position start = r.getStart();
        activeTab.revealPosition(start.getLine(), start.getCharacter());
    }

    private static String kindGlyph(SymbolKind kind) {
        if (kind == null) return "?";
        return switch (kind) {
            case Class -> "C";
            case Interface -> "I";
            case Struct -> "S";
            case Enum -> "E";
            case EnumMember -> "e";
            case Method -> "m";
            case Function -> "ƒ";
            case Constructor -> "c";
            case Field -> "f";
            case Property -> "p";
            case Variable -> "v";
            case Constant -> "K";
            case Module -> "M";
            case Namespace -> "N";
            case Package -> "P";
            case TypeParameter -> "T";
            case File -> "□";
            case String -> "\"";
            case Number -> "#";
            case Boolean -> "B";
            case Array -> "[]";
            case Object -> "{}";
            case Key -> "k";
            case Null -> "⌀";
            case Event -> "ε";
            case Operator -> "±";
        };
    }

    private static class SymbolCell extends TreeCell<DocumentSymbol> {
        @Override
        protected void updateItem(DocumentSymbol item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            String name = item.getName() == null ? "" : item.getName();
            String detail = item.getDetail();
            String glyph = "[" + kindGlyph(item.getKind()) + "] ";
            String text = (detail == null || detail.isBlank()) ? (glyph + name) : (glyph + name + "   " + detail);
            setText(text);
        }
    }
}
