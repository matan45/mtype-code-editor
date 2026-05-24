package org.mtype.editor.ui.output;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.InlineCssTextArea;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.ui.editor.EditorTab;

import java.util.List;

public class OutputPane extends TabPane {
    private final TextArea runArea = new TextArea();
    private final InlineCssTextArea lspArea = new InlineCssTextArea();
    private final InlineCssTextArea compileArea = new InlineCssTextArea();
    private final InlineCssTextArea packagesArea = new InlineCssTextArea();
    private final InlineCssTextArea gitArea = new InlineCssTextArea();
    private final InlineCssTextArea debugConsoleArea = new InlineCssTextArea();
    private final Tab runTab;
    private final Tab lspTab;
    private final Tab compileTab;
    private final Tab packagesTab;
    private final Tab gitTab;
    private final Tab debugConsoleTab;
    private final Tab terminalTab;
    private final Tab callHierarchyTab;
    private final Tab referencesTab;
    private final Tab problemsTab;
    private final Tab outlineTab;
    private CallHierarchyPane callHierarchyPane;
    private ReferencesPane referencesPane;
    private TerminalPane terminalPane;
    private OutlinePanel outlinePanel;

    public OutputPane() {
        runArea.setEditable(false);
        runArea.getStyleClass().add("mt-output");
        lspArea.setEditable(false);
        lspArea.getStyleClass().add("mt-output");
        compileArea.setEditable(false);
        compileArea.getStyleClass().add("mt-output");
        packagesArea.setEditable(false);
        packagesArea.getStyleClass().add("mt-output");
        gitArea.setEditable(false);
        gitArea.getStyleClass().add("mt-output");
        debugConsoleArea.setEditable(false);
        debugConsoleArea.getStyleClass().add("mt-output");

        runTab = new Tab("Run", runArea);
        runTab.setClosable(false);

        Button clearLspButton = new Button("Clear");
        clearLspButton.getStyleClass().add("mt-output-toolbar-button");
        clearLspButton.setOnAction(_ -> lspArea.clear());
        HBox lspToolbar = new HBox(clearLspButton);
        lspToolbar.setAlignment(Pos.CENTER_RIGHT);
        lspToolbar.setPadding(new Insets(4, 6, 4, 6));
        lspToolbar.getStyleClass().add("mt-output-toolbar");
        BorderPane lspContent = new BorderPane(new VirtualizedScrollPane<>(lspArea));
        lspContent.setTop(lspToolbar);
        lspTab = new Tab("LSP Log", lspContent);
        lspTab.setClosable(false);

        Button clearCompileButton = new Button("Clear");
        clearCompileButton.getStyleClass().add("mt-output-toolbar-button");
        clearCompileButton.setOnAction(_ -> compileArea.clear());
        HBox compileToolbar = new HBox(clearCompileButton);
        compileToolbar.setAlignment(Pos.CENTER_RIGHT);
        compileToolbar.setPadding(new Insets(4, 6, 4, 6));
        compileToolbar.getStyleClass().add("mt-output-toolbar");
        BorderPane compileContent = new BorderPane(new VirtualizedScrollPane<>(compileArea));
        compileContent.setTop(compileToolbar);
        compileTab = new Tab("Compile", compileContent);
        compileTab.setClosable(false);

        Button clearPackagesButton = new Button("Clear");
        clearPackagesButton.getStyleClass().add("mt-output-toolbar-button");
        clearPackagesButton.setOnAction(_ -> packagesArea.clear());
        HBox packagesToolbar = new HBox(clearPackagesButton);
        packagesToolbar.setAlignment(Pos.CENTER_RIGHT);
        packagesToolbar.setPadding(new Insets(4, 6, 4, 6));
        packagesToolbar.getStyleClass().add("mt-output-toolbar");
        BorderPane packagesContent = new BorderPane(new VirtualizedScrollPane<>(packagesArea));
        packagesContent.setTop(packagesToolbar);
        packagesTab = new Tab("Packages", packagesContent);
        packagesTab.setClosable(false);

        Button clearGitButton = new Button("Clear");
        clearGitButton.getStyleClass().add("mt-output-toolbar-button");
        clearGitButton.setOnAction(_ -> gitArea.clear());
        HBox gitToolbar = new HBox(clearGitButton);
        gitToolbar.setAlignment(Pos.CENTER_RIGHT);
        gitToolbar.setPadding(new Insets(4, 6, 4, 6));
        gitToolbar.getStyleClass().add("mt-output-toolbar");
        BorderPane gitContent = new BorderPane(new VirtualizedScrollPane<>(gitArea));
        gitContent.setTop(gitToolbar);
        gitTab = new Tab("Git", gitContent);
        gitTab.setClosable(false);

        Button clearDebugButton = new Button("Clear");
        clearDebugButton.getStyleClass().add("mt-output-toolbar-button");
        clearDebugButton.setOnAction(_ -> debugConsoleArea.clear());
        HBox debugToolbar = new HBox(clearDebugButton);
        debugToolbar.setAlignment(Pos.CENTER_RIGHT);
        debugToolbar.setPadding(new Insets(4, 6, 4, 6));
        debugToolbar.getStyleClass().add("mt-output-toolbar");
        BorderPane debugContent = new BorderPane(new VirtualizedScrollPane<>(debugConsoleArea));
        debugContent.setTop(debugToolbar);
        debugConsoleTab = new Tab("Debug Console", debugContent);
        debugConsoleTab.setClosable(false);

        terminalTab = new Tab("Terminal");
        terminalTab.setClosable(false);
        callHierarchyTab = new Tab("Call Hierarchy");
        callHierarchyTab.setClosable(false);
        referencesTab = new Tab("References");
        referencesTab.setClosable(false);
        problemsTab = new Tab("Problems");
        problemsTab.setClosable(false);
        outlineTab = new Tab("Outline");
        outlineTab.setClosable(false);

        getTabs().addAll(problemsTab, outlineTab, runTab, terminalTab, debugConsoleTab, compileTab, packagesTab, lspTab, gitTab, callHierarchyTab, referencesTab);
        getStyleClass().add("mt-output-pane");
        setTabClosingPolicy(TabClosingPolicy.UNAVAILABLE);
    }

    private List<Tab> canonicalOrder() {
        return List.of(problemsTab, outlineTab, runTab, terminalTab, debugConsoleTab, compileTab, packagesTab, lspTab, gitTab, callHierarchyTab, referencesTab);
    }

    private Tab tabByName(String name) {
        for (Tab t : canonicalOrder()) {
            if (t.getText() != null && t.getText().startsWith(name)) return t;
        }
        return null;
    }

    public List<String> tabNames() {
        return List.of("Problems", "Outline", "Run", "Terminal", "Debug Console", "Compile", "Packages", "LSP Log", "Git", "Call Hierarchy", "References");
    }

    public boolean isTabVisible(String name) {
        Tab t = tabByName(name);
        return t != null && getTabs().contains(t);
    }

    public void setTabVisible(String name, boolean visible) {
        Tab target = tabByName(name);
        if (target == null) return;
        boolean present = getTabs().contains(target);
        if (visible == present) return;
        if (!visible) {
            getTabs().remove(target);
            return;
        }
        int insertAt = 0;
        for (Tab t : canonicalOrder()) {
            if (t == target) break;
            if (getTabs().contains(t)) insertAt++;
        }
        if (insertAt > getTabs().size()) insertAt = getTabs().size();
        getTabs().add(insertAt, target);
    }

    public void attachCallHierarchy(AppContext ctx) {
        callHierarchyPane = new CallHierarchyPane(ctx);
        callHierarchyTab.setContent(callHierarchyPane);
    }

    public void attachReferences(AppContext ctx) {
        referencesPane = new ReferencesPane(ctx);
        referencesTab.setContent(referencesPane);
    }

    public void attachProblems(AppContext ctx) {
        ProblemsPane problemsPane = new ProblemsPane(ctx);
        problemsTab.setContent(problemsPane);
        // Bind the tab label to the visible row count so the Clear button
        // (which clears rows but not the bus) also resets the label.
        problemsPane.rowCountBinding().addListener((_, _, newV) -> {
            int n = newV.intValue();
            Platform.runLater(() ->
                    problemsTab.setText(n > 0 ? "Problems (" + n + ")" : "Problems"));
        });
    }

    public void attachTerminal(AppContext ctx) {
        terminalPane = new TerminalPane(ctx);
        terminalTab.setContent(terminalPane);
    }

    public void attachOutline(AppContext ctx) {
        outlinePanel = new OutlinePanel(ctx);
        outlineTab.setContent(outlinePanel);
    }

    public void refreshOutlineFor(EditorTab tab) {
        if (outlinePanel != null) outlinePanel.refreshIfActive(tab);
    }

    public void showCallHierarchy(CallHierarchyItem item) {
        if (callHierarchyPane == null) return;
        Platform.runLater(() -> {
            getSelectionModel().select(callHierarchyTab);
            callHierarchyPane.show(item);
        });
    }

    public void showReferences(String symbolLabel, List<? extends org.eclipse.lsp4j.Location> locations) {
        if (referencesPane == null) return;
        Platform.runLater(() -> {
            getSelectionModel().select(referencesTab);
            referencesPane.show(symbolLabel, locations);
        });
    }

    public void appendRun(String line, boolean stderr) {
        String text = (stderr ? "[err] " : "") + line + System.lineSeparator();
        Platform.runLater(() -> runArea.appendText(text));
    }

    public void appendLspLog(String line) {
        String text = line + System.lineSeparator();
        String style = looksLikeLspError(line) ? "-fx-fill: #e06c75;" : "";
        Platform.runLater(() -> {
            int start = lspArea.getLength();
            lspArea.appendText(text);
            int end = lspArea.getLength();
            lspArea.setStyle(start, end, style);
            lspArea.moveTo(end);
            lspArea.requestFollowCaret();
        });
    }

    private static boolean looksLikeLspError(String line) {
        if (line == null) return false;
        if (line.startsWith("[stderr]")) return true;
        String lower = line.toLowerCase();
        return lower.contains("error") || lower.contains("failed");
    }

    public void appendCompile(String line, boolean stderr) {
        String text = line + System.lineSeparator();
        String style = stderr ? "-fx-fill: #e06c75;" : "";
        Platform.runLater(() -> {
            int start = compileArea.getLength();
            compileArea.appendText(text);
            int end = compileArea.getLength();
            compileArea.setStyle(start, end, style);
            compileArea.moveTo(end);
            compileArea.requestFollowCaret();
        });
    }

    public void appendPackages(String line, boolean stderr) {
        String text = line + System.lineSeparator();
        String style = stderr ? "-fx-fill: #e06c75;" : "";
        Platform.runLater(() -> {
            int start = packagesArea.getLength();
            packagesArea.appendText(text);
            int end = packagesArea.getLength();
            packagesArea.setStyle(start, end, style);
            packagesArea.moveTo(end);
            packagesArea.requestFollowCaret();
        });
    }

    public void appendGit(String line, boolean stderr) {
        String text = line + System.lineSeparator();
        String style = stderr ? "-fx-fill: #e06c75;" : "";
        Platform.runLater(() -> {
            int start = gitArea.getLength();
            gitArea.appendText(text);
            int end = gitArea.getLength();
            gitArea.setStyle(start, end, style);
            gitArea.moveTo(end);
            gitArea.requestFollowCaret();
        });
    }

    public void appendDebugConsole(String line, String category) {
        if (line == null) return;
        boolean stderr = "stderr".equals(category);
        boolean console = "console".equals(category);
        String prefix = stderr ? "[err] " : "";
        String text = prefix + line + System.lineSeparator();
        String style;
        if (stderr) style = "-fx-fill: #ff5252; -fx-font-weight: bold;";
        else if (console) style = "-fx-fill: #7a8290; -fx-font-style: italic;";
        else style = "-fx-fill: #abb2bf;";
        Platform.runLater(() -> {
            int start = debugConsoleArea.getLength();
            debugConsoleArea.appendText(text);
            int end = debugConsoleArea.getLength();
            debugConsoleArea.setStyle(start, end, style);
            debugConsoleArea.moveTo(end);
            debugConsoleArea.requestFollowCaret();
        });
    }

    public void clearDebugConsole() {
        Platform.runLater(debugConsoleArea::clear);
    }

    public void focusDebugConsole() {
        Platform.runLater(() -> getSelectionModel().select(debugConsoleTab));
    }

    public void clearRun() {
        Platform.runLater(runArea::clear);
    }

    public void clearCompile() {
        Platform.runLater(compileArea::clear);
    }

    public void clearPackages() {
        Platform.runLater(packagesArea::clear);
    }

    public void focusRun() {
        Platform.runLater(() -> getSelectionModel().select(runTab));
    }

    public void focusCompile() {
        Platform.runLater(() -> getSelectionModel().select(compileTab));
    }

    public void focusPackages() {
        Platform.runLater(() -> getSelectionModel().select(packagesTab));
    }

    public void focusGit() {
        Platform.runLater(() -> getSelectionModel().select(gitTab));
    }

    public void newTerminal() {
        Platform.runLater(() -> {
            if (!getTabs().contains(terminalTab)) {
                setTabVisible("Terminal", true);
            }
            getSelectionModel().select(terminalTab);
            if (terminalPane != null) terminalPane.newTerminal();
        });
    }

    public void focusTerminal() {
        Platform.runLater(() -> {
            if (!getTabs().contains(terminalTab)) {
                setTabVisible("Terminal", true);
            }
            getSelectionModel().select(terminalTab);
            if (terminalPane != null) terminalPane.focusTerminal();
        });
    }
}
