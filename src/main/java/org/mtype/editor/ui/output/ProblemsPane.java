package org.mtype.editor.ui.output;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.mtype.editor.app.AppContext;

import java.net.URI;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Aggregated diagnostics across all files. Listens to DiagnosticsBus. */
public class ProblemsPane extends BorderPane {

    private final AppContext ctx;
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final TableView<Row> table = new TableView<>(rows);

    public ProblemsPane(AppContext ctx) {
        this.ctx = ctx;
        getStyleClass().add("mt-problems");
        buildTable();
        setCenter(table);

        Button clearButton = new Button("Clear");
        clearButton.getStyleClass().add("mt-output-toolbar-button");
        clearButton.setOnAction(e -> rows.clear());
        HBox toolbar = new HBox(clearButton);
        toolbar.setAlignment(Pos.CENTER_RIGHT);
        toolbar.setPadding(new Insets(4, 6, 4, 6));
        toolbar.getStyleClass().add("mt-output-toolbar");
        setTop(toolbar);
        ctx.getDiagnosticsBus().addListener((uri, diags) -> refreshFor(uri, diags));
        // Seed from any diagnostics that arrived before we wired the listener.
        Map<String, List<Diagnostic>> snap = ctx.getDiagnosticsBus().snapshot();
        for (Map.Entry<String, List<Diagnostic>> e : snap.entrySet()) {
            refreshFor(e.getKey(), e.getValue());
        }
    }

    @SuppressWarnings("unchecked")
    private void buildTable() {
        table.setPlaceholder(new Label("No problems detected."));
        table.getStyleClass().add("mt-problems-table");
        table.setRowFactory(tv -> {
            TableRow<Row> r = new TableRow<>();
            r.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !r.isEmpty()) {
                    navigateTo(r.getItem());
                }
            });
            return r;
        });

        TableColumn<Row, String> severityCol = new TableColumn<>();
        severityCol.setCellValueFactory(c -> c.getValue().severityProp);
        severityCol.setCellFactory(c -> new SeverityCell());
        severityCol.setPrefWidth(32);
        severityCol.setMinWidth(32);
        severityCol.setMaxWidth(32);
        severityCol.setSortable(false);

        TableColumn<Row, String> messageCol = new TableColumn<>();
        messageCol.setGraphic(alignedHeader("Description", Pos.CENTER_LEFT));
        messageCol.setCellValueFactory(c -> c.getValue().messageProp);
        messageCol.setStyle("-fx-alignment: CENTER-LEFT;");
        messageCol.setPrefWidth(600);

        TableColumn<Row, String> fileCol = new TableColumn<>();
        fileCol.setGraphic(alignedHeader("File", Pos.CENTER_LEFT));
        fileCol.setCellValueFactory(c -> c.getValue().fileProp);
        fileCol.setStyle("-fx-alignment: CENTER-LEFT;");
        fileCol.setPrefWidth(180);

        TableColumn<Row, String> lineCol = new TableColumn<>();
        lineCol.setGraphic(alignedHeader("Line", Pos.CENTER_RIGHT));
        lineCol.setCellValueFactory(c -> c.getValue().lineProp);
        lineCol.setStyle("-fx-alignment: CENTER-RIGHT;");
        lineCol.setPrefWidth(60);

        table.getColumns().addAll(severityCol, messageCol, fileCol, lineCol);
    }

    /** A column header that fills its slot and respects an explicit alignment. */
    private static Label alignedHeader(String text, Pos alignment) {
        Label l = new Label(text);
        l.setMaxWidth(Double.MAX_VALUE);
        l.setAlignment(alignment);
        return l;
    }

    private void refreshFor(String uri, List<Diagnostic> diags) {
        rows.removeIf(r -> r.uri.equals(uri));
        if (diags == null || diags.isEmpty()) return;
        Path path;
        try { path = Path.of(URI.create(uri)); } catch (Exception e) { return; }
        String fileName = path.getFileName() == null ? uri : path.getFileName().toString();
        for (Diagnostic d : diags) {
            rows.add(new Row(uri, path, d, fileName));
        }
        rows.sort(Comparator
                .comparingInt((Row r) -> severityRank(r.severity))
                .thenComparing(r -> r.file)
                .thenComparingInt(r -> r.line));
    }

    private void navigateTo(Row r) {
        if (r == null) return;
        ctx.getTabPane().openAt(r.path, r.line, r.column);
    }

    private static int severityRank(DiagnosticSeverity s) {
        if (s == null) return 3;
        return switch (s) {
            case Error -> 0;
            case Warning -> 1;
            case Information -> 2;
            case Hint -> 3;
        };
    }

    /* ----- row + severity cell ----- */

    static final class Row {
        final String uri;
        final Path path;
        final DiagnosticSeverity severity;
        final String message;
        final String file;
        final int line;
        final int column;
        final SimpleStringProperty severityProp;
        final SimpleStringProperty messageProp;
        final SimpleStringProperty fileProp;
        final SimpleStringProperty lineProp;

        Row(String uri, Path path, Diagnostic d, String fileName) {
            this.uri = uri;
            this.path = path;
            this.severity = d.getSeverity();
            this.message = d.getMessage();
            this.file = fileName;
            this.line = d.getRange().getStart().getLine();
            this.column = d.getRange().getStart().getCharacter();
            this.severityProp = new SimpleStringProperty(severityKey(severity));
            this.messageProp = new SimpleStringProperty(message);
            this.fileProp = new SimpleStringProperty(fileName);
            this.lineProp = new SimpleStringProperty(String.valueOf(line + 1));
        }
    }

    private static String severityKey(DiagnosticSeverity s) {
        if (s == null) return "error";
        return switch (s) {
            case Error -> "error";
            case Warning -> "warning";
            case Information -> "info";
            case Hint -> "hint";
        };
    }

    private static final class SeverityCell extends TableCell<Row, String> {
        private final Label dot = new Label();
        SeverityCell() {
            setAlignment(Pos.CENTER);
            dot.getStyleClass().add("mt-severity-dot");
        }
        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            dot.getStyleClass().removeIf(c -> c.startsWith("mt-severity-"));
            dot.getStyleClass().add("mt-severity-dot");
            dot.getStyleClass().add("mt-severity-" + item);
            setGraphic(dot);
            setText(null);
        }
    }
}
