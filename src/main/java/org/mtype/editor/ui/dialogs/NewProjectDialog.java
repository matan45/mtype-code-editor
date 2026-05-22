package org.mtype.editor.ui.dialogs;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class NewProjectDialog extends Dialog<Path> {

    private final TextField nameField = new TextField();
    private final TextField versionField = new TextField("1.0.0");
    private final TextField sourceField = new TextField("src/**/*.mt");
    private final TextField outputField = new TextField("build");

    public NewProjectDialog(Window owner, Path workspaceRoot) {
        initOwner(owner);
        setTitle("New mType Project");
        setHeaderText("Create a new project (.mtproj) in " + workspaceRoot.getFileName());
        Dialogs.theme(this);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        ColumnConstraints c0 = new ColumnConstraints();
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        c1.setFillWidth(true);
        grid.getColumnConstraints().addAll(c0, c1);

        nameField.setPromptText("MyProject");

        int row = 0;
        grid.add(new Label("Name:"), 0, row);
        grid.add(nameField, 1, row++);
        grid.add(new Label("Version:"), 0, row);
        grid.add(versionField, 1, row++);
        grid.add(new Label("Source glob:"), 0, row);
        grid.add(sourceField, 1, row++);
        grid.add(new Label("Output dir:"), 0, row);
        grid.add(outputField, 1, row++);

        getDialogPane().setContent(grid);
        getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);

        setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            String name = nameField.getText() == null ? "" : nameField.getText().trim();
            if (name.isEmpty()) {
                Dialogs.error(getOwner(), null, "Name is required.").showAndWait();
                return null;
            }
            if (name.contains("/") || name.contains("\\") || name.contains("..")) {
                Dialogs.error(getOwner(), null, "Name cannot contain path separators.").showAndWait();
                return null;
            }
            String version = trimOrDefault(versionField.getText(), "1.0.0");
            String source = trimOrDefault(sourceField.getText(), "src/**/*.mt");
            String output = trimOrDefault(outputField.getText(), "build");

            Path file = workspaceRoot.resolve(name + ".mtproj");
            if (Files.exists(file)) {
                Dialogs.error(getOwner(), null, file.getFileName() + " already exists.").showAndWait();
                return null;
            }
            String xml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Project Name="%s" Version="%s">
                      <Source>
                        <Include>%s</Include>
                      </Source>
                      <Output Directory="%s" />
                    </Project>
                    """.formatted(xmlEscape(name), xmlEscape(version), xmlEscape(source), xmlEscape(output));
            try {
                Files.writeString(file, xml, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                Dialogs.error(getOwner(), null, "Failed to write " + file + ":\n" + ex.getMessage()).showAndWait();
                return null;
            }
            return file;
        });
    }

    private static String trimOrDefault(String s, String def) {
        if (s == null) return def;
        String t = s.trim();
        return t.isEmpty() ? def : t;
    }

    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
