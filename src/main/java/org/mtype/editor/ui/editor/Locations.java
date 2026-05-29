package org.mtype.editor.ui.editor;

import org.eclipse.lsp4j.Location;
import org.mtype.editor.app.AppContext;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Conversions and navigation for LSP {@link Location}s, shared by go-to-definition and code lens. */
final class Locations {
    private Locations() {}

    /** Parse a {@code file://} URI to a {@link Path}, or null if it can't be parsed. */
    static Path uriToPath(String uri) {
        try {
            return Paths.get(URI.create(uri));
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Open {@code loc} in the editor at its start position; reports a status message on bad input. */
    static void openLocation(AppContext ctx, Location loc, String label) {
        if (loc == null || loc.getUri() == null || loc.getRange() == null || loc.getRange().getStart() == null) {
            ctx.getStatusBar().setMessage("Bad " + label + " location");
            return;
        }
        try {
            Path targetPath = Paths.get(URI.create(loc.getUri()));
            ctx.getTabPane().openAt(targetPath,
                    loc.getRange().getStart().getLine(), loc.getRange().getStart().getCharacter());
        } catch (Exception ex) {
            ctx.getStatusBar().setMessage("Bad " + label + " URI: " + loc.getUri());
        }
    }
}
