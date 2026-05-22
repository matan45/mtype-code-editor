package org.mtype.editor.ui.editor;

import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.mtype.editor.app.AppContext;
import org.mtype.editor.git.GitService;
import org.mtype.editor.ui.dialogs.Dialogs;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.IntFunction;

public class DiffTab extends Tab {
    private static final Collection<String> STYLE_DEL = Collections.singletonList("mt-diff-del");
    private static final Collection<String> STYLE_ADD = Collections.singletonList("mt-diff-add");
    private static final Collection<String> STYLE_PAD = Collections.singletonList("mt-diff-pad");
    private static final Collection<String> STYLE_EQ = Collections.emptyList();

    private final AppContext ctx;
    private final Path path;

    public DiffTab(AppContext ctx, Path path, String title, String leftText, String rightText, boolean binary) {
        this.ctx = ctx;
        this.path = path;
        setText(title);
        setClosable(true);

        if (binary) {
            Label msg = new Label("Binary file — cannot show diff");
            msg.getStyleClass().add("mt-empty-state");
            StackPane wrap = new StackPane(msg);
            wrap.setAlignment(Pos.CENTER);
            setContent(wrap);
            return;
        }

        List<DiffComputer.Row> rows = DiffComputer.diff(leftText, rightText);

        // Compute per-row line numbers, skipping padding lines so the gutter
        // displays the actual file line and not the diff-row index.
        Integer[] leftLn = new Integer[rows.size()];
        Integer[] rightLn = new Integer[rows.size()];
        int lc = 0, rc = 0;
        for (int i = 0; i < rows.size(); i++) {
            DiffComputer.Row r = rows.get(i);
            if (r.left() != null) leftLn[i] = ++lc;
            if (r.right() != null) rightLn[i] = ++rc;
        }
        int leftMaxDigits = Math.max(1, (int) Math.floor(Math.log10(Math.max(1, lc))) + 1);
        int rightMaxDigits = Math.max(1, (int) Math.floor(Math.log10(Math.max(1, rc))) + 1);

        CodeArea left = makeArea();
        CodeArea right = makeArea();
        applyFontFromSettings(left);
        applyFontFromSettings(right);

        StringBuilder leftBuf = new StringBuilder();
        StringBuilder rightBuf = new StringBuilder();
        for (int i = 0; i < rows.size(); i++) {
            DiffComputer.Row r = rows.get(i);
            leftBuf.append(r.left() == null ? "" : r.left());
            rightBuf.append(r.right() == null ? "" : r.right());
            if (i < rows.size() - 1) {
                leftBuf.append('\n');
                rightBuf.append('\n');
            }
        }
        left.replaceText(leftBuf.toString());
        right.replaceText(rightBuf.toString());

        for (int i = 0; i < rows.size(); i++) {
            DiffComputer.Row r = rows.get(i);
            switch (r.op()) {
                case EQUAL -> {
                    left.setParagraphStyle(i, STYLE_EQ);
                    right.setParagraphStyle(i, STYLE_EQ);
                }
                case DELETE -> {
                    left.setParagraphStyle(i, STYLE_DEL);
                    right.setParagraphStyle(i, STYLE_PAD);
                }
                case INSERT -> {
                    left.setParagraphStyle(i, STYLE_PAD);
                    right.setParagraphStyle(i, STYLE_ADD);
                }
                case REPLACE -> {
                    left.setParagraphStyle(i, STYLE_DEL);
                    right.setParagraphStyle(i, STYLE_ADD);
                }
            }
        }

        left.setParagraphGraphicFactory(plainLineNumberFactory(leftLn, leftMaxDigits));
        right.setParagraphGraphicFactory(revertableLineNumberFactory(rightLn, rightMaxDigits, rows, rightText));

        VirtualizedScrollPane<CodeArea> leftScroll = new VirtualizedScrollPane<>(left);
        VirtualizedScrollPane<CodeArea> rightScroll = new VirtualizedScrollPane<>(right);

        left.estimatedScrollYProperty().bindBidirectional(right.estimatedScrollYProperty());
        left.estimatedScrollXProperty().bindBidirectional(right.estimatedScrollXProperty());

        SplitPane split = new SplitPane(wrapWithHeader("HEAD", leftScroll),
                                        wrapWithHeader("Working Tree", rightScroll));
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.5);
        setContent(split);
    }

    public Path getPath() { return path; }

    private static CodeArea makeArea() {
        CodeArea area = new CodeArea();
        area.setEditable(false);
        area.getStyleClass().add("mt-diff-area");
        return area;
    }

    private void applyFontFromSettings(CodeArea area) {
        if (ctx.getSettings() == null || ctx.getSettings().editor == null) return;
        var prefs = ctx.getSettings().editor;
        StringBuilder sb = new StringBuilder();
        if (prefs.fontFamily != null && !prefs.fontFamily.isBlank()) {
            String family = prefs.fontFamily.replace("\"", "");
            sb.append("-fx-font-family: \"").append(family).append("\"; ");
        }
        if (prefs.fontSize > 0) {
            sb.append("-fx-font-size: ").append(prefs.fontSize).append("; ");
        }
        if (sb.length() > 0) area.setStyle(sb.toString());
    }

    private static IntFunction<Node> plainLineNumberFactory(Integer[] lineNums, int digits) {
        String fmt = "%" + digits + "s";
        return idx -> {
            Label label = new Label(idx < lineNums.length && lineNums[idx] != null
                    ? String.format(fmt, lineNums[idx])
                    : String.format(fmt, ""));
            label.getStyleClass().add("lineno");
            return label;
        };
    }

    private IntFunction<Node> revertableLineNumberFactory(Integer[] lineNums, int digits,
                                                          List<DiffComputer.Row> rows,
                                                          String workingTreeText) {
        String fmt = "%" + digits + "s";
        return idx -> {
            Label number = new Label(idx < lineNums.length && lineNums[idx] != null
                    ? String.format(fmt, lineNums[idx])
                    : String.format(fmt, ""));
            number.getStyleClass().add("lineno");

            DiffComputer.Row row = idx < rows.size() ? rows.get(idx) : null;
            if (row == null || row.op() == DiffComputer.Op.EQUAL) {
                return number;
            }
            Label revert = new Label("↩");
            revert.getStyleClass().add("mt-diff-revert");
            revert.setTooltip(new Tooltip("Revert this line"));
            revert.setOnMouseClicked(e -> {
                e.consume();
                revertLine(idx, rows, workingTreeText);
            });
            HBox row2 = new HBox(revert, number);
            row2.setAlignment(Pos.CENTER_LEFT);
            row2.getStyleClass().add("mt-diff-gutter-row");
            return row2;
        };
    }

    private void revertLine(int rowIdx, List<DiffComputer.Row> rows, String workingTreeText) {
        EditorTab open = ctx.getTabPane().findByPath(path);
        if (open != null && open.isDirty()) {
            Dialogs.error(getTabPane() == null ? null : getTabPane().getScene().getWindow(),
                    "Cannot revert line",
                    "The file has unsaved changes. Save or discard them first.").showAndWait();
            return;
        }

        DiffComputer.Row row = rows.get(rowIdx);
        // Compute the 0-based working-tree line index this row corresponds to
        int rightLineIndex = 0;
        for (int i = 0; i < rowIdx; i++) {
            if (rows.get(i).right() != null) rightLineIndex++;
        }

        List<String> lines = splitKeepEol(workingTreeText);
        String eol = detectEol(workingTreeText);
        switch (row.op()) {
            case DELETE -> {
                // Working tree is missing row.left() — re-insert it
                int insertAt = Math.min(rightLineIndex, lines.size());
                lines.add(insertAt, row.left());
            }
            case INSERT -> {
                if (rightLineIndex < lines.size()) lines.remove(rightLineIndex);
            }
            case REPLACE -> {
                if (rightLineIndex < lines.size()) lines.set(rightLineIndex, row.left());
            }
            case EQUAL -> { return; }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append(lines.get(i));
            if (i < lines.size() - 1) sb.append(eol);
        }
        if (workingTreeText.endsWith(eol)) sb.append(eol);
        String newContent = sb.toString();

        try {
            Files.writeString(path, newContent, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            ctx.getStatusBar().setMessage("Revert failed: " + ex.getMessage());
            return;
        }

        if (open != null) {
            open.getCodeArea().replaceText(newContent);
        }
        ctx.getStatusBar().setMessage("Reverted line");
        if (ctx.getGitChangesView() != null) ctx.getGitChangesView().refresh();
        reloadDiff();
    }

    private void reloadDiff() {
        GitService git = ctx.getGitService();
        if (git == null) return;
        git.diffAgainstHead(path).whenCompleteAsync((pair, err) -> {
            if (err != null) return;
            ctx.getTabPane().openDiff(path, pair.oldContent(), pair.newContent(),
                    path.getFileName() + " (Working Tree)", pair.binary());
        }, Platform::runLater);
    }

    private static List<String> splitKeepEol(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        String[] parts = text.split("\n", -1);
        // Drop one trailing empty from a final \n
        int n = parts.length;
        if (n > 0 && parts[n - 1].isEmpty() && text.endsWith("\n")) n--;
        for (int i = 0; i < n; i++) {
            String s = parts[i];
            if (s.endsWith("\r")) s = s.substring(0, s.length() - 1);
            out.add(s);
        }
        return out;
    }

    private static String detectEol(String text) {
        return text != null && text.contains("\r\n") ? "\r\n" : "\n";
    }

    private static BorderPane wrapWithHeader(String headerText, VirtualizedScrollPane<CodeArea> body) {
        Label header = new Label(headerText);
        header.getStyleClass().add("mt-diff-header");
        HBox headerBar = new HBox(header);
        headerBar.setAlignment(Pos.CENTER_LEFT);
        headerBar.getStyleClass().add("mt-diff-header-bar");
        BorderPane wrap = new BorderPane();
        wrap.setTop(headerBar);
        wrap.setCenter(body);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return wrap;
    }
}
