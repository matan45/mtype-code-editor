package org.mtype.editor.workspace;

import java.util.LinkedHashSet;
import java.util.Set;

public class WorkspaceSettings {
    public Toolchain toolchain = Toolchain.defaults();
    public EditorPrefs editor = EditorPrefs.defaults();
    public ViewPrefs view = ViewPrefs.defaults();

    public static WorkspaceSettings defaults() {
        WorkspaceSettings s = new WorkspaceSettings();
        s.toolchain = Toolchain.defaults();
        s.editor = EditorPrefs.defaults();
        s.view = ViewPrefs.defaults();
        return s;
    }

    public static class Toolchain {
        public String interpreter;
        public String languageServer;
        public String packageManager;

        public static Toolchain defaults() {
            String home = System.getenv("MTYPE_HOME");
            String base = (home != null && !home.isBlank()) ? home : "C:\\matan\\mType";
            Toolchain t = new Toolchain();
            t.interpreter = base + "\\bin\\mType\\Release\\x64\\mType.exe";
            t.languageServer = base + "\\bin\\mtype-language-server\\Release\\x64\\mtype-language-server.exe";
            t.packageManager = base + "\\bin\\mtpm\\Release\\x64\\mtpm.exe";
            return t;
        }
    }

    public static class EditorPrefs {
        public String fontFamily = "JetBrains Mono";
        public int fontSize = 14;
        public String theme = "dark";
        public boolean formatOnSave = false;
        public boolean inlayHints = true;

        public static EditorPrefs defaults() {
            return new EditorPrefs();
        }
    }

    public static class ViewPrefs {
        public Set<String> hiddenBottomTabs = new LinkedHashSet<>();

        public static ViewPrefs defaults() {
            return new ViewPrefs();
        }
    }
}
