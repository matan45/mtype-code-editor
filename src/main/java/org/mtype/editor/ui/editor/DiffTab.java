package org.mtype.editor.ui.editor;

import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class DiffTab extends Tab {
    private static final Collection<String> STYLE_DEL = Collections.singletonList("mt-diff-del");
    private static final Collection<String> STYLE_ADD = Collections.singletonList("mt-diff-add");
    private static final Collection<String> STYLE_PAD = Collections.singletonList("mt-diff-pad");
    private static final Collection<String> STYLE_EQ = Collections.emptyList();

    private final Path path;

    public DiffTab(Path path, String title, String leftText, String rightText, boolean binary) {
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

        CodeArea left = makeArea("HEAD");
        CodeArea right = makeArea("Working Tree");

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

        VirtualizedScrollPane<CodeArea> leftScroll = new VirtualizedScrollPane<>(left);
        VirtualizedScrollPane<CodeArea> rightScroll = new VirtualizedScrollPane<>(right);

        // Synced scrolling: bidirectional bind of estimatedScrollY and estimatedScrollX
        left.estimatedScrollYProperty().bindBidirectional(right.estimatedScrollYProperty());
        left.estimatedScrollXProperty().bindBidirectional(right.estimatedScrollXProperty());

        SplitPane split = new SplitPane(wrapWithHeader("HEAD", leftScroll),
                                        wrapWithHeader("Working Tree", rightScroll));
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.5);
        setContent(split);
    }

    public Path getPath() { return path; }

    private static CodeArea makeArea(String label) {
        CodeArea area = new CodeArea();
        area.setEditable(false);
        area.setParagraphGraphicFactory(LineNumberFactory.get(area));
        area.getStyleClass().add("mt-diff-area");
        return area;
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
        HBox.setHgrow(headerBar, Priority.ALWAYS);
        return wrap;
    }
}
