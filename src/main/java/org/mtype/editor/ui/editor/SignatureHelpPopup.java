package org.mtype.editor.ui.editor;

import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Popup;
import org.eclipse.lsp4j.ParameterInformation;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureInformation;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.List;

public final class SignatureHelpPopup {
    private final Popup popup = new Popup();
    private final VBox container = new VBox();

    public SignatureHelpPopup() {
        container.getStyleClass().add("mt-signature-popup");
        StackPane shell = new StackPane(container);
        shell.getStyleClass().add("mt-signature-shell");
        popup.getContent().add(shell);
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
    }

    public boolean isShowing() { return popup.isShowing(); }
    public void hide() { popup.hide(); }

    public void show(Node anchor, SignatureHelp help, double screenX, double screenY) {
        if (help == null || help.getSignatures() == null || help.getSignatures().isEmpty()) {
            popup.hide();
            return;
        }
        List<SignatureInformation> sigs = help.getSignatures();
        int activeSig = help.getActiveSignature() == null ? 0 : help.getActiveSignature();
        if (activeSig < 0 || activeSig >= sigs.size()) activeSig = 0;
        SignatureInformation sig = sigs.get(activeSig);
        if (sig == null || sig.getLabel() == null) {
            popup.hide();
            return;
        }

        container.getChildren().clear();

        int activeParam = -1;
        if (sig.getActiveParameter() != null) activeParam = sig.getActiveParameter();
        else if (help.getActiveParameter() != null) activeParam = help.getActiveParameter();

        String label = sig.getLabel();
        TextFlow sigFlow = new TextFlow();
        sigFlow.getStyleClass().add("mt-signature-line");
        int[] paramRange = paramRange(sig, activeParam, label);
        if (paramRange != null) {
            appendText(sigFlow, label.substring(0, paramRange[0]), false);
            appendText(sigFlow, label.substring(paramRange[0], paramRange[1]), true);
            appendText(sigFlow, label.substring(paramRange[1]), false);
        } else {
            appendText(sigFlow, label, false);
        }
        container.getChildren().add(sigFlow);

        if (sigs.size() > 1) {
            Text counter = new Text("(" + (activeSig + 1) + "/" + sigs.size() + ")");
            counter.getStyleClass().add("mt-signature-counter");
            container.getChildren().add(new TextFlow(counter));
        }

        String paramDoc = paramDoc(sig, activeParam);
        if (paramDoc != null && !paramDoc.isBlank()) {
            Text doc = new Text(paramDoc);
            doc.getStyleClass().add("mt-signature-doc");
            container.getChildren().add(new TextFlow(doc));
        } else {
            String sigDoc = docText(sig.getDocumentation());
            if (sigDoc != null && !sigDoc.isBlank()) {
                Text doc = new Text(sigDoc);
                doc.getStyleClass().add("mt-signature-doc");
                container.getChildren().add(new TextFlow(doc));
            }
        }

        if (anchor != null && anchor.getScene() != null && anchor.getScene().getWindow() != null) {
            popup.show(anchor, screenX, screenY);
        }
    }

    private static void appendText(TextFlow flow, String s, boolean active) {
        if (s == null || s.isEmpty()) return;
        Text t = new Text(s);
        t.getStyleClass().add(active ? "mt-signature-active-param" : "mt-signature-text");
        flow.getChildren().add(t);
    }

    private static int[] paramRange(SignatureInformation sig, int activeParam, String label) {
        if (activeParam < 0) return null;
        List<ParameterInformation> params = sig.getParameters();
        if (params == null || activeParam >= params.size()) return null;
        ParameterInformation p = params.get(activeParam);
        if (p == null || p.getLabel() == null) return null;
        Either<String, org.eclipse.lsp4j.jsonrpc.messages.Tuple.Two<Integer, Integer>> either = p.getLabel();
        if (either.isRight()) {
            var tup = either.getRight();
            int s = tup.getFirst();
            int e = tup.getSecond();
            if (s < 0 || e > label.length() || s >= e) return null;
            return new int[]{s, e};
        }
        String pl = either.getLeft();
        if (pl == null || pl.isEmpty()) return null;
        int idx = label.indexOf(pl);
        if (idx < 0) return null;
        return new int[]{idx, idx + pl.length()};
    }

    private static String paramDoc(SignatureInformation sig, int activeParam) {
        if (activeParam < 0) return null;
        List<ParameterInformation> params = sig.getParameters();
        if (params == null || activeParam >= params.size()) return null;
        ParameterInformation p = params.get(activeParam);
        if (p == null) return null;
        return docText(p.getDocumentation());
    }

    private static String docText(Either<String, org.eclipse.lsp4j.MarkupContent> doc) {
        if (doc == null) return null;
        if (doc.isLeft()) return doc.getLeft();
        return doc.getRight() == null ? null : doc.getRight().getValue();
    }
}
