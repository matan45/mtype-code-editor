package org.mtype.editor.ui.editor;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@link CompletionView} as a {@link ContextMenu}: one row per item (kind badge, label, detail), at most
 * {@value #MAX_ROWS}. Like the Quick Fix popup it doesn't take focus from the code area, so Enter / Esc
 * keep reaching {@code EditorTab.handleKey}. Replaced by a list-based popup in MYT-436.
 */
final class ContextMenuCompletionView implements CompletionView {
    private static final int MAX_ROWS = 30;

    private final Node owner;
    private final Supplier<Optional<Bounds>> caretScreenBounds;
    private final ContextMenu menu = new ContextMenu();
    /** The items behind {@code menu.getItems()}, same order. */
    private final List<CompletionItem> shown = new ArrayList<>();
    private Listener listener;

    ContextMenuCompletionView(Node owner, Supplier<Optional<Bounds>> caretScreenBounds) {
        this.owner = owner;
        this.caretScreenBounds = caretScreenBounds;
        menu.getStyleClass().add("mt-completion");
        // Auto-hide = the popup closed itself (outside click, its own Esc): the controller must drop the
        // request too, or a late reply would open it again.
        menu.setOnAutoHide(_ -> {
            if (listener != null) listener.onDismissed();
        });
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void show(List<CompletionItem> ranked) {
        List<CompletionItem> rows = ranked.size() > MAX_ROWS ? ranked.subList(0, MAX_ROWS) : ranked;
        if (menu.isShowing() && sameItems(rows)) return; // nothing changed: keep the highlighted row
        shown.clear();
        shown.addAll(rows);
        List<MenuItem> items = new ArrayList<>(rows.size());
        for (CompletionItem ci : rows) items.add(buildRow(ci));
        menu.getItems().setAll(items);
        if (!menu.isShowing()) {
            caretScreenBounds.get().ifPresent(b -> menu.show(owner, b.getMinX(), b.getMaxY()));
        }
    }

    @Override
    public void hide() {
        menu.hide();
    }

    @Override
    public boolean isShowing() {
        return menu.isShowing();
    }

    @Override
    public CompletionItem selected() {
        if (!menu.isShowing() || shown.isEmpty()) return null;
        List<MenuItem> items = menu.getItems();
        for (int i = 0; i < items.size() && i < shown.size(); i++) {
            Node node = items.get(i).getStyleableNode();
            if (node != null && node.isFocused()) return shown.get(i);
        }
        return shown.getFirst();
    }

    @Override
    public boolean ownsFocus() {
        return menu.isShowing() && menu.isFocused();
    }

    @Override
    public void dispose() {
        menu.hide();
        menu.getItems().clear();
        shown.clear();
        listener = null;
    }

    private boolean sameItems(List<CompletionItem> rows) {
        if (rows.size() != shown.size()) return false;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) != shown.get(i)) return false;
        }
        return true;
    }

    private MenuItem buildRow(CompletionItem ci) {
        String label = ci.getLabel() == null ? "" : ci.getLabel();
        String detail = ci.getDetail();
        Label kindBadge = new Label(kindShort(ci.getKind()));
        kindBadge.getStyleClass().add("mt-completion-kind");
        kindBadge.getStyleClass().add("mt-completion-kind-" + (ci.getKind() == null ? "Other" : ci.getKind().name()));

        MenuItem row = new MenuItem(detail == null || detail.isBlank() ? label : (label + "   " + detail));
        row.setGraphic(kindBadge);
        row.setOnAction(_ -> {
            if (listener != null) listener.onAccept(ci);
        });
        return row;
    }

    private static String kindShort(CompletionItemKind k) {
        if (k == null) return "·";
        return switch (k) {
            case Class -> "C";
            case Interface -> "I";
            case Struct -> "S";
            case Enum -> "E";
            case EnumMember -> "e";
            case Method, Function, Constructor -> "ƒ";
            case Property -> "p";
            case Field, Variable -> "v";
            case Constant -> "c";
            case Keyword -> "k";
            case Module -> "m";
            case Snippet -> "»";
            case File -> "□";
            case Folder -> "▥";
            case TypeParameter -> "T";
            case Text -> "a";
            default -> "·";
        };
    }
}
