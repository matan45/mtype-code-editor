package org.mtype.editor.ui.dialogs;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public class NewWorkspaceDialog extends Dialog<Path> {

    private final TextField nameField = new TextField();
    private final TextField versionField = new TextField("1.0.0");
    private final TextField outputField = new TextField("build");
    private final ObservableList<String> members = FXCollections.observableArrayList();
    private final ListView<String> membersList = new ListView<>(members);

    public NewWorkspaceDialog(Window owner, Path workspaceRoot) {
        initOwner(owner);
        setTitle("New mType Workspace");
        setHeaderText("Create a new workspace (.mtworkspace) in " + workspaceRoot.getFileName());
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

        nameField.setPromptText("MyWorkspace");
        membersList.setPrefHeight(120);

        Button addBtn = new Button("Add...");
        Button removeBtn = new Button("Remove");
        removeBtn.disableProperty().bind(membersList.getSelectionModel().selectedItemProperty().isNull());
        addBtn.setOnAction(e -> {
            TextInputDialog d = Dialogs.prompt(owner, "Add Member",
                    "Relative path to a member project (folder or .mtproj)",
                    "Path:", null);
            Optional<String> r = d.showAndWait();
            r.map(String::trim).filter(s -> !s.isEmpty()).ifPresent(members::add);
        });
        removeBtn.setOnAction(e -> {
            String sel = membersList.getSelectionModel().getSelectedItem();
            if (sel != null) members.remove(sel);
        });
        HBox memberButtons = new HBox(6, addBtn, removeBtn);

        int row = 0;
        grid.add(new Label("Name:"), 0, row);
        grid.add(nameField, 1, row++);
        grid.add(new Label("Version:"), 0, row);
        grid.add(versionField, 1, row++);
        grid.add(new Label("Output dir:"), 0, row);
        grid.add(outputField, 1, row++);
        grid.add(new Label("Members:"), 0, row);
        grid.add(membersList, 1, row++);
        grid.add(new Label(""), 0, row);
        grid.add(memberButtons, 1, row++);

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
            String output = trimOrDefault(outputField.getText(), "build");

            Path file = workspaceRoot.resolve(name + ".mtworkspace");
            if (Files.exists(file)) {
                Dialogs.error(getOwner(), null, file.getFileName() + " already exists.").showAndWait();
                return null;
            }

            StringBuilder memberXml = new StringBuilder();
            for (String m : members) {
                memberXml.append("    <Member Path=\"").append(xmlEscape(m)).append("\" />\n");
            }

            String xml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Workspace Name="%s" Version="%s">
                      <Members>
                    %s  </Members>
                      <Output Directory="%s" />
                    </Workspace>
                    """.formatted(xmlEscape(name), xmlEscape(version), memberXml, xmlEscape(output));
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
