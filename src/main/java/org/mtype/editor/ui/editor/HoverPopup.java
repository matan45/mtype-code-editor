package org.mtype.editor.ui.editor;

import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Popup;
import org.fxmisc.richtext.model.StyleSpan;
import org.fxmisc.richtext.model.StyleSpans;
import org.mtype.editor.syntax.MTypeTokenizer;

import java.util.Collection;

/**
 * Code-aware hover popup. Tokenizes the hover content via {@link MTypeTokenizer}
 * and renders each span with the editor's syntax-highlight CSS classes. Container
 * styling lives in {@code .hover-popup} / token colors in {@code .hover-popup .text.mt-*}.
 */
public final class HoverPopup {

    private final Popup popup = new Popup();
    private final TextFlow flow = new TextFlow();

    public HoverPopup() {
        StackPane container = new StackPane(flow);
        container.getStyleClass().add("hover-popup");
        flow.getStyleClass().add("hover-popup-content");
        flow.setMaxWidth(640);
        popup.getContent().add(container);
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
    }

    public void show(Node anchor, String content, double screenX, double screenY) {
        flow.getChildren().clear();
        if (content == null || content.isBlank()) {
            popup.hide();
            return;
        }
        StyleSpans<Collection<String>> spans = MTypeTokenizer.compute(content);
        int pos = 0;
        for (StyleSpan<Collection<String>> span : spans) {
            int end = pos + span.getLength();
            if (end > content.length()) end = content.length();
            if (end > pos) {
                Text t = new Text(content.substring(pos, end));
                t.getStyleClass().add("text");
                for (String cls : span.getStyle()) t.getStyleClass().add(cls);
                flow.getChildren().add(t);
            }
            pos = end;
        }
        if (anchor != null) popup.show(anchor, screenX, screenY);
    }

    public void hide() {
        popup.hide();
    }
}
