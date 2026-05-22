package org.mtype.editor.ui.settings;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.FileChooser;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import org.mtype.editor.workspace.WorkspaceSettings;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class SettingsDialog extends Dialog<WorkspaceSettings> {

    private final TextField interpreterField = new TextField();
    private final TextField lspField = new TextField();
    private final TextField mtpmField = new TextField();
    private final ComboBox<String> fontFamilyCombo = new ComboBox<>();
    private final Spinner<Integer> fontSizeSpinner = new Spinner<>(8, 32, 14);
    private final ComboBox<String> themeCombo = new ComboBox<>();
    private final CheckBox formatOnSaveCheck = new CheckBox();

    public SettingsDialog(Window owner, WorkspaceSettings current) {
        initOwner(owner);
        initStyle(StageStyle.UTILITY);
        setTitle("Settings");
        setHeaderText("Editor & Toolchain");
        setGraphic(null);

        DialogPane pane = getDialogPane();
        pane.getStyleClass().add("mt-dialog");
        var cssUrl = SettingsDialog.class.getResource("/css/mtype-dark.css");
        if (cssUrl != null) pane.getStylesheets().add(cssUrl.toExternalForm());

        configureFontFamilyCombo();
        prefillFields(current);
        pane.setContent(buildForm());
        pane.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        Button ok = (Button) pane.lookupButton(ButtonType.OK);
        ok.setText("Save");
        Button cancel = (Button) pane.lookupButton(ButtonType.CANCEL);
        cancel.setText("Cancel");

        setResultConverter(bt -> bt == ButtonType.OK ? collectSettings(current) : null);

        Platform.runLater(interpreterField::requestFocus);
    }

    private void configureFontFamilyCombo() {
        List<String> families = monospacedFamilies();
        fontFamilyCombo.getItems().setAll(families);
        fontFamilyCombo.setEditable(true);
        fontFamilyCombo.setVisibleRowCount(12);
        fontFamilyCombo.setMaxWidth(Double.MAX_VALUE);
        // Render each family name in its own typeface so the user gets a live preview.
        fontFamilyCombo.setCellFactory(lv -> new FontPreviewCell());
        fontFamilyCombo.setButtonCell(new FontPreviewCell());
    }

    private void prefillFields(WorkspaceSettings s) {
        interpreterField.setText(safe(s.toolchain.interpreter));
        lspField.setText(safe(s.toolchain.languageServer));
        mtpmField.setText(safe(s.toolchain.packageManager));

        String current = safe(s.editor.fontFamily);
        if (!current.isBlank() && !fontFamilyCombo.getItems().contains(current)) {
            fontFamilyCombo.getItems().add(0, current);
        }
        fontFamilyCombo.setValue(current.isBlank() ? "JetBrains Mono" : current);

        fontSizeSpinner.getValueFactory().setValue(s.editor.fontSize);
        themeCombo.getItems().setAll("dark");
        themeCombo.setValue(s.editor.theme == null ? "dark" : s.editor.theme);
        formatOnSaveCheck.setSelected(s.editor.formatOnSave);
    }

    private VBox buildForm() {
        VBox root = new VBox(14);
        root.setPadding(new Insets(4, 0, 4, 0));
        root.getStyleClass().add("mt-settings-form");

        root.getChildren().addAll(
                groupHeader("Toolchain"),
                buildGrid(
                        row("Interpreter", interpreterField, browse("Interpreter executable", interpreterField)),
                        row("Language server", lspField, browse("LSP server executable", lspField)),
                        row("Package manager", mtpmField, browse("Package manager executable", mtpmField))
                ),
                groupHeader("Editor"),
                buildGrid(
                        row("Font family", fontFamilyCombo, null),
                        row("Font size", fontSizeSpinner, null),
                        row("Theme", themeCombo, null),
                        row("Format on save", formatOnSaveCheck, null)
                )
        );
        return root;
    }

    private Label groupHeader(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("mt-settings-group");
        return l;
    }

    private GridPane buildGrid(javafx.scene.Node[]... rows) {
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setMinWidth(130);
        labelCol.setPrefWidth(130);
        ColumnConstraints fieldCol = new ColumnConstraints();
        fieldCol.setHgrow(Priority.ALWAYS);
        fieldCol.setFillWidth(true);
        ColumnConstraints btnCol = new ColumnConstraints();
        g.getColumnConstraints().addAll(labelCol, fieldCol, btnCol);

        for (int i = 0; i < rows.length; i++) {
            javafx.scene.Node[] r = rows[i];
            g.add(r[0], 0, i);
            g.add(r[1], 1, i);
            if (r[2] != null) g.add(r[2], 2, i);
        }
        return g;
    }

    private javafx.scene.Node[] row(String label, javafx.scene.Node field, javafx.scene.Node trailing) {
        Label l = new Label(label);
        l.getStyleClass().add("mt-settings-label");
        if (field instanceof TextField tf) tf.getStyleClass().add("mt-rename-field");
        if (field instanceof ComboBox<?> cb) cb.getStyleClass().add("mt-settings-combo");
        return new javafx.scene.Node[]{l, field, trailing};
    }

    private Button browse(String title, TextField target) {
        Button b = new Button("Browse...");
        b.getStyleClass().add("mt-browse-btn");
        b.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle(title);
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Executables", "*.exe"));
            String existing = target.getText();
            if (existing != null && !existing.isBlank()) {
                File f = new File(existing);
                if (f.getParentFile() != null && f.getParentFile().isDirectory()) {
                    fc.setInitialDirectory(f.getParentFile());
                    fc.setInitialFileName(f.getName());
                }
            }
            File chosen = fc.showOpenDialog(getOwner());
            if (chosen != null) target.setText(chosen.getAbsolutePath());
        });
        HBox wrap = new HBox(b);
        wrap.setAlignment(Pos.CENTER_LEFT);
        return b;
    }

    private WorkspaceSettings collectSettings(WorkspaceSettings base) {
        WorkspaceSettings s = new WorkspaceSettings();
        s.toolchain = new WorkspaceSettings.Toolchain();
        s.toolchain.interpreter = interpreterField.getText().trim();
        s.toolchain.languageServer = lspField.getText().trim();
        s.toolchain.packageManager = mtpmField.getText().trim();
        s.editor = new WorkspaceSettings.EditorPrefs();
        String family = fontFamilyCombo.getEditor().getText();
        if (family == null || family.isBlank()) family = fontFamilyCombo.getValue();
        s.editor.fontFamily = family == null ? "JetBrains Mono" : family.trim();
        s.editor.fontSize = fontSizeSpinner.getValue();
        s.editor.theme = themeCombo.getValue() == null ? "dark" : themeCombo.getValue();
        s.editor.formatOnSave = formatOnSaveCheck.isSelected();
        return s;
    }

    /* ------------------- font discovery ------------------- */

    /**
     * Returns installed font families that look monospaced, with a curated set
     * of common code fonts floated to the top.
     */
    private static List<String> monospacedFamilies() {
        Set<String> preferred = new LinkedHashSet<>(List.of(
                "JetBrains Mono", "Cascadia Code", "Cascadia Mono", "Fira Code",
                "Source Code Pro", "Hack", "IBM Plex Mono", "Consolas", "Courier New", "Monaco"
        ));
        List<String> all = Font.getFamilies();
        List<String> monos = new ArrayList<>();
        for (String f : all) {
            if (looksMonospaced(f)) monos.add(f);
        }
        monos.sort(Comparator.naturalOrder());

        List<String> ordered = new ArrayList<>();
        for (String p : preferred) {
            if (monos.contains(p)) ordered.add(p);
        }
        for (String f : monos) {
            if (!ordered.contains(f)) ordered.add(f);
        }
        return ordered;
    }

    /** Heuristic: a font is monospaced if "MMM" and "iii" render to the same width. */
    private static boolean looksMonospaced(String family) {
        try {
            Font f = Font.font(family, 14);
            if (f == null) return false;
            Text wide = new Text("MMMMM");
            wide.setFont(f);
            Text narrow = new Text("iiiii");
            narrow.setFont(f);
            double w1 = wide.getLayoutBounds().getWidth();
            double w2 = narrow.getLayoutBounds().getWidth();
            return Math.abs(w1 - w2) < 0.5;
        } catch (Exception e) {
            return false;
        }
    }

    private static class FontPreviewCell extends ListCell<String> {
        @Override
        protected void updateItem(String family, boolean empty) {
            super.updateItem(family, empty);
            if (empty || family == null) {
                setText(null);
                setFont(Font.getDefault());
            } else {
                setText(family);
                try { setFont(Font.font(family, 13)); }
                catch (Exception ignored) { setFont(Font.getDefault()); }
            }
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
