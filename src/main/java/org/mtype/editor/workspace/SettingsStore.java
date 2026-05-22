package org.mtype.editor.workspace;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads and saves user-global editor settings.
 * Path: %USERPROFILE%\.mtype-editor\settings.json
 */
public final class SettingsStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SettingsStore() {}

    public static Path settingsFile() {
        return Path.of(System.getProperty("user.home"), ".mtype-editor", "settings.json");
    }

    public static WorkspaceSettings load() {
        Path file = settingsFile();
        if (!Files.isRegularFile(file)) {
            return WorkspaceSettings.defaults();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            WorkspaceSettings loaded = GSON.fromJson(json, WorkspaceSettings.class);
            if (loaded == null) return WorkspaceSettings.defaults();
            if (loaded.toolchain == null) loaded.toolchain = WorkspaceSettings.Toolchain.defaults();
            if (loaded.editor == null) loaded.editor = WorkspaceSettings.EditorPrefs.defaults();
            return loaded;
        } catch (IOException e) {
            return WorkspaceSettings.defaults();
        }
    }

    public static void save(WorkspaceSettings settings) throws IOException {
        Path file = settingsFile();
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(settings), StandardCharsets.UTF_8);
    }
}
