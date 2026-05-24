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
import org.mtype.editor.process.AddPackageSpec;

import java.nio.file.Path;

public class AddPackageDialog extends Dialog<AddPackageSpec> {

    private final TextField nameField = new TextField();
    private final TextField versionField = new TextField();
    private final TextField sourceField = new TextField();

    public AddPackageDialog(Window owner, Path mtprojFile) {
        initOwner(owner);
        setTitle("Add Package");
        String header = mtprojFile != null
                ? "Add a package to " + mtprojFile.getFileName()
                : "Add a package";
        setHeaderText(header);
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

        nameField.setPromptText("e.g. mathlib");
        versionField.setPromptText("^1.0.0  or  1.2.3  or  ~2.1.0");
        sourceField.setPromptText("github:user/repo  or  https://github.com/user/repo.git");

        int row = 0;
        grid.add(new Label("Package name:"), 0, row);
        grid.add(nameField, 1, row++);
        grid.add(new Label("Version:"), 0, row);
        grid.add(versionField, 1, row++);
        grid.add(new Label("Source:"), 0, row);
        grid.add(sourceField, 1, row++);

        getDialogPane().setContent(grid);
        getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);

        setResultConverter(bt -> {
            if (bt != ButtonType.OK) return null;
            String name = nameField.getText() == null ? "" : nameField.getText().trim();
            String version = versionField.getText() == null ? "" : versionField.getText().trim();
            String source = sourceField.getText() == null ? "" : sourceField.getText().trim();
            if (name.isEmpty()) {
                Dialogs.error(getOwner(), null, "Package name is required.").showAndWait();
                return null;
            }
            if (name.chars().anyMatch(Character::isWhitespace)) {
                Dialogs.error(getOwner(), null, "Package name cannot contain whitespace.").showAndWait();
                return null;
            }
            if (name.contains("@")) {
                Dialogs.error(getOwner(), null,
                        "Package name cannot contain '@'. Put the version in the Version field.").showAndWait();
                return null;
            }
            if (version.isEmpty()) {
                Dialogs.error(getOwner(), null, "Version is required.").showAndWait();
                return null;
            }
            if (source.isEmpty()) {
                Dialogs.error(getOwner(), null,
                        "Source is required (e.g. github:user/repo or a full https git URL).").showAndWait();
                return null;
            }
            return new AddPackageSpec(name, version, source);
        });
    }
}
