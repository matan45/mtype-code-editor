package org.mtype.editor.app;

import org.mtype.editor.lsp.LspBridge;
import org.mtype.editor.process.RunController;
import org.mtype.editor.ui.editor.EditorTabPane;
import org.mtype.editor.ui.git.GitChangesView;
import org.mtype.editor.ui.output.OutputPane;
import org.mtype.editor.ui.status.StatusBar;
import org.mtype.editor.ui.tree.WorkspaceTreeView;
import org.mtype.editor.workspace.Workspace;

public class AppContext {
    private Workspace workspace;
    private LspBridge lspBridge;
    private RunController runController;
    private EditorTabPane tabPane;
    private OutputPane outputPane;
    private StatusBar statusBar;
    private WorkspaceTreeView treeView;
    private GitChangesView gitChangesView;

    public Workspace getWorkspace() { return workspace; }
    public LspBridge getLspBridge() { return lspBridge; }
    public RunController getRunController() { return runController; }
    public EditorTabPane getTabPane() { return tabPane; }
    public OutputPane getOutputPane() { return outputPane; }
    public StatusBar getStatusBar() { return statusBar; }
    public WorkspaceTreeView getTreeView() { return treeView; }
    public GitChangesView getGitChangesView() { return gitChangesView; }

    public void setWorkspace(Workspace w) { this.workspace = w; }
    public void setLspBridge(LspBridge b) { this.lspBridge = b; }
    public void setRunController(RunController r) { this.runController = r; }
    public void setTabPane(EditorTabPane p) { this.tabPane = p; }
    public void setOutputPane(OutputPane o) { this.outputPane = o; }
    public void setStatusBar(StatusBar s) { this.statusBar = s; }
    public void setTreeView(WorkspaceTreeView t) { this.treeView = t; }
    public void setGitChangesView(GitChangesView g) { this.gitChangesView = g; }

    public void openWorkspace(Workspace ws) {
        if (this.workspace != null) {
            try { lspBridge.stop(); } catch (Exception ignored) {}
            tabPane.closeAll();
        }
        this.workspace = ws;
        treeView.setWorkspace(ws);
        if (gitChangesView != null) gitChangesView.setWorkspace(ws);
        statusBar.setMessage("Opened " + ws.getRoot());
        statusBar.setLspState("LSP: starting...");
        try {
            lspBridge.start(ws);
        } catch (Exception ex) {
            statusBar.setLspState("LSP: failed");
            outputPane.appendLspLog("[error] " + ex.getMessage());
        }
    }
}
