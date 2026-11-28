package org.mtype.editor.ui.editor;

import com.google.gson.JsonObject;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuickFixesTest {

    private static Diagnostic onLines(int startLine, int endLine, String message) {
        return new Diagnostic(new Range(new Position(startLine, 0), new Position(endLine, 6)), message);
    }

    @Test
    void keepsDiagnosticsCoveringTheLine() {
        Diagnostic above = onLines(0, 0, "above");
        Diagnostic here = onLines(2, 2, "here");
        Diagnostic spanning = onLines(1, 3, "spanning");

        List<Diagnostic> result = QuickFixes.containingLine(List.of(above, here, spanning), 2);

        assertEquals(List.of(here, spanning), result);
    }

    @Test
    void sendsTheServersDiagnosticObjectsUnchanged() {
        // The server's `data` (e.g. missingName for Add import) must round-trip as-is.
        Diagnostic unresolved = onLines(0, 0, "use of undeclared type 'Circle'");
        JsonObject data = new JsonObject();
        data.addProperty("exceptionType", "ClassNotFoundException");
        data.addProperty("missingName", "Circle");
        unresolved.setData(data);

        List<Diagnostic> result = QuickFixes.containingLine(List.of(unresolved), 0);

        assertSame(unresolved, result.getFirst());
    }

    @Test
    void toleratesMissingRangesAndNullLists() {
        Diagnostic noRange = new Diagnostic();
        assertTrue(QuickFixes.containingLine(List.of(noRange), 0).isEmpty());
        assertTrue(QuickFixes.containingLine(null, 0).isEmpty());
    }

    @Test
    void retriesOnlyWhenTheLinesDiagnosticsChanged() {
        Diagnostic circle = onLines(0, 0, "use of undeclared type 'Circle'");

        assertFalse(QuickFixes.shouldRetry(List.of(circle), List.of(onLines(0, 0, "use of undeclared type 'Circle'"))));
        assertTrue(QuickFixes.shouldRetry(List.of(), List.of(circle)));
        assertTrue(QuickFixes.shouldRetry(List.of(circle), List.of()));
    }
}
