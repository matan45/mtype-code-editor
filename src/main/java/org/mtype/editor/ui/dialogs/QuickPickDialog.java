package org.mtype.editor.ui.dialogs;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.*;
import java.util.function.Function;

public class QuickPickDialog<T> {
    /**
     * Sentinel class marking header rows in the internal model.
     */
    private static final class Header {
        final String text;

        Header(String text) {
            this.text = text;
        }
    }

    private final Dialog<T> dialog = new Dialog<>();
    private final TextField filter = new TextField();
    private final ListView<Object> listView = new ListView<>();
    private final ObservableList<Object> backing;
    private final FilteredList<Object> filtered;
    private final Function<T, String> labelFn;
    private final Function<T, String> descriptionFn;
    private final boolean grouped;
    private final Set<String> collapsedGroups = new HashSet<>();
    private String currentQuery = "";

    public QuickPickDialog(Window owner,
                           String title,
                           List<T> items,
                           Function<T, String> labelFn,
                           Function<T, String> descriptionFn) {
        this(owner, title, items, labelFn, descriptionFn, null);
    }

    public QuickPickDialog(Window owner,
                           String title,
                           List<T> items,
                           Function<T, String> labelFn,
                           Function<T, String> descriptionFn,
                           Function<T, String> groupFn) {
        this.labelFn = labelFn;
        this.descriptionFn = descriptionFn;
        this.grouped = groupFn != null;
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.setHeaderText(null);

        backing = FXCollections.observableArrayList(buildModel(items, groupFn));
        filtered = new FilteredList<>(backing, o -> true);
        listView.setItems(filtered);
        listView.setPrefHeight(320);
        listView.setMinWidth(360);
        listView.setCellFactory(lv -> new ListCell<>() {
            @Override
            @SuppressWarnings("unchecked")
            protected void updateItem(Object item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("mt-quickpick-header-cell");
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setMouseTransparent(false);
                    return;
                }
                if (item instanceof Header h) {
                    boolean collapsed = collapsedGroups.contains(h.text);
                    Label chevron = new Label(collapsed ? "▶" : "▼");
                    chevron.getStyleClass().add("mt-quickpick-chevron");
                    Label header = new Label(h.text);
                    header.getStyleClass().add("mt-quickpick-header");
                    HBox row = new HBox(chevron, header);
                    row.setSpacing(6);
                    row.setAlignment(Pos.CENTER_LEFT);
                    setText(null);
                    setGraphic(row);
                    getStyleClass().add("mt-quickpick-header-cell");
                    setMouseTransparent(false);
                    setOnMouseClicked(ev -> {
                        if (collapsedGroups.contains(h.text)) collapsedGroups.remove(h.text);
                        else collapsedGroups.add(h.text);
                        refilter();
                        ev.consume();
                    });
                    return;
                }
                setOnMouseClicked(null);
                setMouseTransparent(false);
                T t = (T) item;
                String label = labelFn.apply(t);
                String desc = descriptionFn == null ? null : descriptionFn.apply(t);
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
            currentQuery = newV == null ? "" : newV.toLowerCase();
            refilter();
            int first = firstSelectableIndex(0, 1);
            if (first >= 0) listView.getSelectionModel().select(first);
            else listView.getSelectionModel().clearSelection();
        });

        filter.setOnKeyPressed(e -> {
            int sel = listView.getSelectionModel().getSelectedIndex();
            if (e.getCode() == KeyCode.DOWN) {
                int next = firstSelectableIndex(sel + 1, 1);
                if (next >= 0) {
                    listView.getSelectionModel().select(next);
                    listView.scrollTo(next);
                }
                e.consume();
            } else if (e.getCode() == KeyCode.UP) {
                int prev = firstSelectableIndex(sel - 1, -1);
                if (prev >= 0) {
                    listView.getSelectionModel().select(prev);
                    listView.scrollTo(prev);
                }
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

        int first = firstSelectableIndex(0, 1);
        if (first >= 0) listView.getSelectionModel().select(first);
        Platform.runLater(filter::requestFocus);

        dialog.setResultConverter(bt -> {
            if (bt == ButtonType.CANCEL) return null;
            Object sel = listView.getSelectionModel().getSelectedItem();
            return sel instanceof Header ? null : (T) sel;
        });
    }

    private List<Object> buildModel(List<T> items, Function<T, String> groupFn) {
        List<Object> model = new ArrayList<>();
        if (groupFn == null) {
            model.addAll(items);
            return model;
        }
        Map<String, List<T>> byGroup = new LinkedHashMap<>();
        for (T item : items) {
            String g = groupFn.apply(item);
            if (g == null) g = "";
            byGroup.computeIfAbsent(g, k -> new ArrayList<>()).add(item);
        }
        for (Map.Entry<String, List<T>> entry : byGroup.entrySet()) {
            model.add(new Header(entry.getKey()));
            model.addAll(entry.getValue());
        }
        return model;
    }

    private void refilter() {
        filtered.setPredicate(makePredicate(currentQuery));
        listView.refresh();
    }

    private String groupOf(Object item) {
        int idx = backing.indexOf(item);
        for (int i = idx - 1; i >= 0; i--) {
            Object o = backing.get(i);
            if (o instanceof Header h) return h.text;
        }
        return null;
    }

    private java.util.function.Predicate<Object> makePredicate(String q) {
        if (!grouped) {
            return o -> {
                if (q.isEmpty()) return true;
                if (o instanceof Header) return false;
                return matches((T) o, q);
            };
        }
        // Grouped: headers stay visible (so users can expand collapsed ones).
        // Items are hidden when their group is collapsed or when they don't match the query.
        return o -> {
            if (o instanceof Header h) {
                if (collapsedGroups.contains(h.text)) return true;
                if (q.isEmpty()) return true;
                int idx = backing.indexOf(h);
                for (int i = idx + 1; i < backing.size(); i++) {
                    Object next = backing.get(i);
                    if (next instanceof Header) break;
                    if (matches((T) next, q)) return true;
                }
                return false;
            }
            String g = groupOf(o);
            if (g != null && collapsedGroups.contains(g)) return false;
            if (q.isEmpty()) return true;
            return matches((T) o, q);
        };
    }

    private boolean matches(T t, String q) {
        String lbl = labelFn.apply(t);
        if (lbl != null && lbl.toLowerCase().contains(q)) return true;
        if (descriptionFn != null) {
            String d = descriptionFn.apply(t);
            return d != null && d.toLowerCase().contains(q);
        }
        return false;
    }

    /**
     * Find the first index >= start (or <= start if step==-1) that is selectable (not a header).
     */
    private int firstSelectableIndex(int start, int step) {
        int i = start;
        while (i >= 0 && i < filtered.size()) {
            if (!(filtered.get(i) instanceof Header)) return i;
            i += step;
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private void commit() {
        Object sel = listView.getSelectionModel().getSelectedItem();
        if (sel instanceof Header) return;
        dialog.setResult((T) sel);
        dialog.close();
    }

    public Optional<T> showAndWait() {
        return dialog.showAndWait();
    }
}
