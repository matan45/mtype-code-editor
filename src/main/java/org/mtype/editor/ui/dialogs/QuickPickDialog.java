package org.mtype.editor.ui.dialogs;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public class QuickPickDialog<T> {
    private final Dialog<T> dialog = new Dialog<>();
    private final TextField filter = new TextField();
    private final ListView<T> listView = new ListView<>();
    private final ObservableList<T> backing;
    private final FilteredList<T> filtered;

    public QuickPickDialog(Window owner,
                           String title,
                           List<T> items,
                           Function<T, String> labelFn,
                           Function<T, String> descriptionFn) {
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.setHeaderText(null);

        backing = FXCollections.observableArrayList(items);
        filtered = new FilteredList<>(backing, t -> true);
        listView.setItems(filtered);
        listView.setPrefHeight(320);
        listView.setMinWidth(360);
        listView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                String label = labelFn.apply(item);
                String desc = descriptionFn == null ? null : descriptionFn.apply(item);
                if (desc == null || desc.isEmpty()) {
                    setText(label);
                    setGraphic(null);
                } else {
                    Label lbl = new Label(label);
                    lbl.getStyleClass().add("mt-quickpick-label");
                    Label descLabel = new Label(desc);
                    descLabel.getStyleClass().add("mt-quickpick-desc");
                    VBox row = new VBox(lbl, descLabel);
                    row.setSpacing(2);
                    setText(null);
                    setGraphic(row);
                }
            }
        });

        filter.setPromptText("Type to filter…");
        filter.textProperty().addListener((obs, oldV, newV) -> {
            String q = newV == null ? "" : newV.toLowerCase();
            filtered.setPredicate(t -> {
                if (q.isEmpty()) return true;
                String lbl = labelFn.apply(t);
                if (lbl != null && lbl.toLowerCase().contains(q)) return true;
                if (descriptionFn != null) {
                    String d = descriptionFn.apply(t);
                    if (d != null && d.toLowerCase().contains(q)) return true;
                }
                return false;
            });
            if (!filtered.isEmpty()) listView.getSelectionModel().select(0);
        });

        filter.setOnKeyPressed(e -> {
            int sel = listView.getSelectionModel().getSelectedIndex();
            if (e.getCode() == KeyCode.DOWN) {
                int next = Math.min(filtered.size() - 1, sel + 1);
                listView.getSelectionModel().select(next);
                listView.scrollTo(next);
                e.consume();
            } else if (e.getCode() == KeyCode.UP) {
                int prev = Math.max(0, sel - 1);
                listView.getSelectionModel().select(prev);
                listView.scrollTo(prev);
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER) {
                commit();
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                dialog.setResult(null);
                dialog.close();
                e.consume();
            }
        });

        listView.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) commit();
        });

        VBox content = new VBox(filter, listView);
        content.setSpacing(6);
        content.setPadding(new Insets(10));
        Dialogs.themeNode(filter);
        Dialogs.themeNode(listView);

        DialogPane pane = dialog.getDialogPane();
        pane.setContent(content);
        pane.getButtonTypes().setAll(ButtonType.CANCEL);
        Dialogs.theme(dialog);
        pane.setPrefWidth(420);

        if (!filtered.isEmpty()) listView.getSelectionModel().select(0);
        Platform.runLater(filter::requestFocus);

        dialog.setResultConverter(bt -> {
            if (bt == ButtonType.CANCEL) return null;
            return listView.getSelectionModel().getSelectedItem();
        });
    }

    private void commit() {
        T sel = listView.getSelectionModel().getSelectedItem();
        dialog.setResult(sel);
        dialog.close();
    }

    public Optional<T> showAndWait() {
        return dialog.showAndWait();
    }
}
