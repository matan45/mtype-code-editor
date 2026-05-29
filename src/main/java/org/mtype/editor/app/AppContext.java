package org.mtype.editor.app;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import org.mtype.editor.debug.BreakpointService;
import org.mtype.editor.debug.DebuggerBridge;
import org.mtype.editor.debug.DebuggerEventBus;
import org.mtype.editor.git.GitService;
import org.mtype.editor.lsp.LspBridge;
import org.mtype.editor.process.BuildController;
import org.mtype.editor.process.PackageController;
import org.mtype.editor.process.RunController;
import org.mtype.editor.terminal.TerminalController;
import org.mtype.editor.ui.editor.EditorTabPane;
import org.mtype.editor.ui.git.GitChangesView;
import org.mtype.editor.ui.output.OutputPane;
import org.mtype.editor.ui.status.StatusBar;
import org.mtype.editor.lsp.DiagnosticsBus;
import org.mtype.editor.ui.search.FindInFilesWindow;
import org.mtype.editor.ui.tree.WorkspaceTreeView;
import org.mtype.editor.workspace.Workspace;
import org.mtype.editor.workspace.WorkspaceSettings;

public class AppContext {
    private Workspace workspace;
    private WorkspaceSettings settings = WorkspaceSettings.defaults();
    private final DiagnosticsBus diagnosticsBus = new DiagnosticsBus();
    private LspBridge lspBridge;
    private RunController runController;
    private BuildController buildController;
    private PackageController packageController;
    private TerminalController terminalController;
    private EditorTabPane tabPane;
    private OutputPane outputPane;
    private StatusBar statusBar;
    private WorkspaceTreeView treeView;
    private GitChangesView gitChangesView;
    private GitService gitService;
    private FindInFilesWindow findInFilesWindow;
    private DebuggerBridge debuggerBridge;
    private DebuggerEventBus debuggerEventBus;
    private BreakpointService breakpointService;
    private final ReadOnlyBooleanWrapper hasProjectFile = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyBooleanWrapper workspaceOpen = new ReadOnlyBooleanWrapper(false);

    public Workspace getWorkspace() { return workspace; }
    public WorkspaceSettings getSettings() { return settings; }
    public DiagnosticsBus getDiagnosticsBus() { return diagnosticsBus; }
    public LspBridge getLspBridge() { return lspBridge; }
    public RunController getRunController() { return runController; }
    public BuildController getBuildController() { return buildController; }
    public PackageController getPackageController() { return packageController; }
    public TerminalController getTerminalController() { return terminalController; }
    public EditorTabPane getTabPane() { return tabPane; }
    public OutputPane getOutputPane() { return outputPane; }
    public StatusBar getStatusBar() { return statusBar; }
    public WorkspaceTreeView getTreeView() { return treeView; }
    public GitChangesView getGitChangesView() { return gitChangesView; }
    public GitService getGitService() { return gitService; }
    public FindInFilesWindow getFindInFilesWindow() { return findInFilesWindow; }
    public DebuggerBridge getDebuggerBridge() { return debuggerBridge; }
    public DebuggerEventBus getDebuggerEventBus() { return debuggerEventBus; }
    public BreakpointService getBreakpointService() { return breakpointService; }

    public ReadOnlyBooleanProperty hasProjectFileProperty() { return hasProjectFile.getReadOnlyProperty(); }
    public ReadOnlyBooleanProperty workspaceOpenProperty() { return workspaceOpen.getReadOnlyProperty(); }

    public void setWorkspace(Workspace w) { this.workspace = w; }
    public void setSettings(WorkspaceSettings s) { this.settings = s != null ? s : WorkspaceSettings.defaults(); }
    public void setLspBridge(LspBridge b) { this.lspBridge = b; }
    public void setRunController(RunController r) { this.runController = r; }
    public void setBuildController(BuildController b) { this.buildController = b; }
    public void setPackageController(PackageController p) { this.packageController = p; }
    public void setTerminalController(TerminalController t) { this.terminalController = t; }
    public void setTabPane(EditorTabPane p) { this.tabPane = p; }
    public void setOutputPane(OutputPane o) { this.outputPane = o; }
    public void setStatusBar(StatusBar s) { this.statusBar = s; }
    public void setTreeView(WorkspaceTreeView t) { this.treeView = t; }
    public void setGitChangesView(GitChangesView g) { this.gitChangesView = g; }
    public void setGitService(GitService s) { this.gitService = s; }
    public void setFindInFilesWindow(FindInFilesWindow w) { this.findInFilesWindow = w; }
    public void setDebuggerBridge(DebuggerBridge b) { this.debuggerBridge = b; }
    public void setDebuggerEventBus(DebuggerEventBus b) { this.debuggerEventBus = b; }
    public void setBreakpointService(BreakpointService s) { this.breakpointService = s; }
    public void setDebuggerPanel() {
    }

    public void refreshHasProjectFile() {
        hasProjectFile.set(workspace != null && workspace.hasProjectFile());
    }

    public void openWorkspace(Workspace ws) {
        if (this.workspace != null) {
            try { if (debuggerBridge != null) debuggerBridge.stop(); } catch (Exception ignored) {}
            try { if (breakpointService != null) breakpointService.clearAll(); } catch (Exception ignored) {}
            try { lspBridge.stop(); } catch (Exception ignored) {}
            tabPane.closeAll();
        }
        this.workspace = ws;
        workspaceOpen.set(ws != null);
        treeView.setWorkspace(ws);
        if (gitChangesView != null) gitChangesView.setWorkspace(ws);
        assert ws != null;
        statusBar.setMessage("Opened " + ws.root());
        statusBar.setLspState("LSP: starting...");
        refreshHasProjectFile();
        try {
            lspBridge.start(ws);
        } catch (Exception ex) {
            statusBar.setLspState("LSP: failed");
            outputPane.appendLspLog("[error] " + ex.getMessage());
        }
    }
}
