package org.mtype.editor.workspace;

public class WorkspaceSettings {
    public Toolchain toolchain = Toolchain.defaults();
    public EditorPrefs editor = EditorPrefs.defaults();

    public static WorkspaceSettings defaults() {
        WorkspaceSettings s = new WorkspaceSettings();
        s.toolchain = Toolchain.defaults();
        s.editor = EditorPrefs.defaults();
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
        public String fontFamily = "Consolas";
        public int fontSize = 14;
        public String theme = "dark";

        public static EditorPrefs defaults() {
            return new EditorPrefs();
        }
    }
}
