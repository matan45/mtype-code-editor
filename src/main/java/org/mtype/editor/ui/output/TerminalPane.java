package org.mtype.editor.ui.output;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.terminal.TerminalController;
import org.mtype.editor.terminal.TerminalSession;

import java.io.IOException;

public class TerminalPane extends BorderPane {
    private final AppContext ctx;
    private final TerminalController controller;
    private final TabPane terminals = new TabPane();

    public TerminalPane(AppContext ctx) {
        this.ctx = ctx;
        this.controller = ctx.getTerminalController();

        Button newButton = new Button("+");
        newButton.setTooltip(new javafx.scene.control.Tooltip("New Terminal"));
        newButton.getStyleClass().add("mt-output-toolbar-button");
        newButton.setOnAction(_ -> newTerminal());

        Button clearButton = new Button("Clear");
        clearButton.getStyleClass().add("mt-output-toolbar-button");
        clearButton.setOnAction(_ -> {
            TerminalView view = activeView();
            if (view != null) view.clear();
        });

        HBox toolbar = new HBox(6, newButton, clearButton);
        toolbar.setAlignment(Pos.CENTER_RIGHT);
        toolbar.setPadding(new Insets(4, 6, 4, 6));
        toolbar.getStyleClass().add("mt-output-toolbar");

        terminals.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        terminals.getStyleClass().add("mt-terminal-tabs");

        setTop(toolbar);
        setCenter(terminals);
        getStyleClass().add("mt-terminal-pane");
    }

    public void newTerminal() {
        TerminalView view = new TerminalView();
        Tab tab = new Tab("cmd", view);
        tab.setOnCloseRequest(event -> {
            event.consume();
            closeTerminal(tab);
        });
        terminals.getTabs().add(tab);
        terminals.getSelectionModel().select(tab);

        try {
            TerminalSession session = controller.openSession(
                    chunk -> view.append(chunk.text()),
                    code -> Platform.runLater(() -> markExited(tab, view, code)));
            tab.setText(session.title());
            view.setSession(session);
            view.append("Started cmd.exe in " + session.cwd() + System.lineSeparator());
            view.focusTerminal();
        } catch (IOException ex) {
            view.append("Failed to start cmd.exe: " + ex.getMessage() + System.lineSeparator());
            view.disableInput();
            tab.setText("cmd failed");
            ctx.getStatusBar().setMessage("Failed to start terminal: " + ex.getMessage());
        }
    }

    public void focusTerminal() {
        if (terminals.getTabs().isEmpty()) {
            newTerminal();
            return;
        }
        TerminalView view = activeView();
        if (view != null) view.focusTerminal();
    }

    private TerminalView activeView() {
        Tab selected = terminals.getSelectionModel().getSelectedItem();
        return selected != null && selected.getContent() instanceof TerminalView view ? view : null;
    }

    private void closeTerminal(Tab tab) {
        if (tab.getContent() instanceof TerminalView view) {
            controller.closeSession(view.session());
        }
        terminals.getTabs().remove(tab);
    }

    private void markExited(Tab tab, TerminalView view, int code) {
        if (!tab.getText().endsWith(" (exited)")) {
            tab.setText(tab.getText() + " (exited)");
        }
        view.append(System.lineSeparator() + "[process exited with code " + code + "]"
                + System.lineSeparator());
        view.disableInput();
    }

    private static final class TerminalView extends BorderPane {
        private final TextArea terminal = new TextArea();
        private TerminalSession session;
        private int inputStart;

        private TerminalView() {
            terminal.setEditable(true);
            terminal.setWrapText(false);
            terminal.getStyleClass().addAll("mt-output", "mt-terminal-output");
            terminal.setOnKeyPressed(event -> {
                if (event.getCode() == KeyCode.ENTER) {
                    sendInput();
                    event.consume();
                } else if (event.getCode() == KeyCode.BACK_SPACE && terminal.getCaretPosition() <= inputStart) {
                    event.consume();
                } else if (event.getCode() == KeyCode.LEFT && terminal.getCaretPosition() <= inputStart) {
                    event.consume();
                } else if (event.getCode() == KeyCode.HOME) {
                    terminal.positionCaret(inputStart);
                    event.consume();
                }
            });
            terminal.caretPositionProperty().addListener((_, _, newV) -> {
                if (newV.intValue() < inputStart) {
                    Platform.runLater(() -> terminal.positionCaret(inputStart));
                }
            });
            setCenter(terminal);
            getStyleClass().add("mt-terminal-view");
        }

        private TerminalSession session() {
            return session;
        }

        private void setSession(TerminalSession session) {
            this.session = session;
        }

        private void append(String text) {
            Platform.runLater(() -> {
                boolean atEnd = terminal.getCaretPosition() >= terminal.getLength();
                String pending = terminal.getText(inputStart, terminal.getLength());
                terminal.deleteText(inputStart, terminal.getLength());
                terminal.appendText(text);
                inputStart = terminal.getLength();
                terminal.appendText(pending);
                terminal.positionCaret(atEnd ? terminal.getLength() : inputStart);
            });
        }

        private void clear() {
            terminal.clear();
            inputStart = 0;
        }

        private void focusTerminal() {
            Platform.runLater(() -> {
                terminal.requestFocus();
                terminal.positionCaret(terminal.getLength());
            });
        }

        private void disableInput() {
            terminal.setEditable(false);
        }

        private void sendInput() {
            String command = terminal.getText(inputStart, terminal.getLength());
            terminal.appendText(System.lineSeparator());
            inputStart = terminal.getLength();
            if (command == null || session == null) return;
            try {
                session.send(command);
            } catch (IOException ex) {
                append("Failed to send command: " + ex.getMessage() + System.lineSeparator());
                disableInput();
            }
        }
    }
}
