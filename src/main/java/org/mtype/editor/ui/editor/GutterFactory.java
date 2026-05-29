package org.mtype.editor.ui.editor;

import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.debug.BreakpointService;

import java.nio.file.Path;
import java.util.function.IntSupplier;

/**
 * Builds the per-paragraph gutter graphic: line number, a breakpoint toggle, a diagnostic/execution
 * marker bar, a fold arrow, and (above all of it, when present) a code-lens row. It reads live state
 * from the diagnostics / code-lens / folding controllers and an execution-line supplier; controllers
 * trigger a re-render by re-setting this factory on the code area (the shared "gutter refresh" hook).
 */
final class GutterFactory {
    private static final double CODE_LENS_LABEL_X = 65;

    private final MTypeCodeArea codeArea;
    private final Path path;
    private final AppContext ctx;
    private final DiagnosticsController diagnostics;
    private final CodeLensController codeLens;
    private final CodeFoldingController folding;
    private final IntSupplier executionLine;

    GutterFactory(MTypeCodeArea codeArea, Path path, AppContext ctx, DiagnosticsController diagnostics,
                  CodeLensController codeLens, CodeFoldingController folding, IntSupplier executionLine) {
        this.codeArea = codeArea;
        this.path = path;
        this.ctx = ctx;
        this.diagnostics = diagnostics;
        this.codeLens = codeLens;
        this.folding = folding;
        this.executionLine = executionLine;
    }

    Node paragraphGraphic(int paragraphIndex) {
        Node lineNumber = lineNumber(paragraphIndex);
        String lensTitle = codeLens.lensTitleAt(paragraphIndex);
        if (lensTitle == null) return lineNumber;

        Label title = new Label(lensTitle);
        title.getStyleClass().add("mt-code-lens");
        title.setCursor(Cursor.HAND);
        title.setTranslateX(CODE_LENS_LABEL_X);
        title.setOnMouseClicked(e -> {
            codeLens.showReferencesAt(paragraphIndex, title);
            e.consume();
        });

        Pane lensRow = new Pane(title);
        lensRow.getStyleClass().add("mt-code-lens-row");
        lensRow.setPickOnBounds(false);

        VBox block = new VBox(lensRow, lineNumber);
        block.getStyleClass().add("mt-code-lens-block");
        return block;
    }

    private Node lineNumber(int paragraphIndex) {
        Label label = new Label(Integer.toString(paragraphIndex + 1));
        label.getStyleClass().add("lineno");

        Region breakpoint = new Region();
        breakpoint.getStyleClass().add("mt-gutter-breakpoint");
        BreakpointService bs = ctx.getBreakpointService();
        boolean isBreakpointOn = bs != null && bs.breakpointsIn(path).contains(paragraphIndex);
        if (isBreakpointOn) breakpoint.getStyleClass().add("mt-gutter-breakpoint-on");
        breakpoint.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && bs != null) {
                bs.toggle(path, paragraphIndex);
                e.consume();
            }
        });

        Region marker = new Region();
        marker.getStyleClass().add("mt-gutter-marker");
        DiagnosticSeverity sev = diagnostics.severityAt(paragraphIndex);
        if (sev != null) {
            marker.getStyleClass().add("mt-gutter-" + gutterSeverityClass(sev));
        }
        if (paragraphIndex == executionLine.getAsInt()) {
            marker.getStyleClass().add("mt-gutter-execution-arrow");
        }

        Node fold = foldGutterNode(paragraphIndex);

        HBox row = new HBox(breakpoint, marker, fold, label);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("mt-gutter-row");
        return row;
    }

    private Node foldGutterNode(int paragraphIndex) {
        if (!folding.isFoldable(paragraphIndex)) {
            Region spacer = new Region();
            spacer.getStyleClass().add("mt-gutter-fold-spacer");
            return spacer;
        }
        boolean folded = folding.isFoldedAt(paragraphIndex);
        Label arrow = new Label(folded ? "▶" : "▼");
        arrow.getStyleClass().add("mt-gutter-fold-arrow");
        arrow.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                folding.toggleFoldAt(paragraphIndex);
                e.consume();
            }
        });
        return arrow;
    }

    private static String gutterSeverityClass(DiagnosticSeverity s) {
        return switch (s) {
            case Error -> "error";
            case Warning -> "warning";
            case Information -> "info";
            case Hint -> "hint";
        };
    }
}
