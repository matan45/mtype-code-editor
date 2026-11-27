package org.mtype.editor.ui.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mtype.editor.ui.editor.LexicalContext.Kind.CODE;
import static org.mtype.editor.ui.editor.LexicalContext.Kind.COMMENT;
import static org.mtype.editor.ui.editor.LexicalContext.Kind.IMPORT_PATH;
import static org.mtype.editor.ui.editor.LexicalContext.Kind.STRING;

/** MYT-424: the lexical context at a caret, mirroring the core lexer's strings, comments and holes. */
class LexicalContextTest {

    /** The context at '|' in {@code marked}. */
    private static LexicalContext.Kind at(String marked) {
        int caret = marked.indexOf('|');
        return LexicalContext.at(marked.replace("|", ""), caret);
    }

    /* ----- code ----- */

    @Test
    void plainCodeIsCode() {
        assertEquals(CODE, at("|"));
        assertEquals(CODE, at("d.|"));
        assertEquals(CODE, at("Dog d = new Dog();\nd.ba|"));
    }

    @Test
    void offsetsAreClamped() {
        assertEquals(CODE, LexicalContext.at("d.", -5));
        assertEquals(STRING, LexicalContext.at("\"abc", 100));
    }

    @Test
    void twoCharacterTokensNeverLookPastTheCaret() {
        assertEquals(CODE, at("a /|"));
        assertEquals(COMMENT, at("a //|"));
        assertEquals(CODE, at("$|"));
        assertEquals(STRING, at("$\"|"));
    }

    /* ----- comments ----- */

    @Test
    void lineCommentRunsToTheEndOfItsLine() {
        assertEquals(COMMENT, at("// d.|"));
        assertEquals(COMMENT, at("x = 1; // d.|"));
        assertEquals(CODE, at("x = 1; // note\nd.|"));
    }

    @Test
    void blockComments() {
        assertEquals(COMMENT, at("/* d.|"));
        assertEquals(COMMENT, at("/* line one\n   d.|"));
        assertEquals(CODE, at("/* x */ d.|"));
        assertEquals(COMMENT, at("/** docs *|"));
        assertEquals(COMMENT, at("/*/ d.|"));
    }

    @Test
    void blockCommentsDoNotNest() {
        assertEquals(CODE, at("/* a /* b */ c.|"));
    }

    @Test
    void quotesInsideCommentsDoNotOpenStrings() {
        assertEquals(CODE, at("// say \"hi\nd.|"));
        assertEquals(CODE, at("/* \" */ d.|"));
    }

    /* ----- strings ----- */

    @Test
    void stringsIncludingUnterminatedOnes() {
        assertEquals(STRING, at("\"d.|"));
        assertEquals(STRING, at("x = \"abc|"));
        assertEquals(CODE, at("x = \"abc\" + d.|"));
        assertEquals(CODE, at("\"http://x\" + d.|"));
    }

    @Test
    void backslashEscapesTheNextCharacter() {
        assertEquals(STRING, at("\"a\\\" d.|"));
        assertEquals(CODE, at("\"a\\\\\" d.|"));
        assertEquals(STRING, at("\"a\\|"));
    }

    @Test
    void stringsSpanLines() {
        assertEquals(STRING, at("\"line one\nline two d.|"));
    }

    @Test
    void commentMarkersInsideStringsAreText() {
        assertEquals(STRING, at("\"// not a comment d.|"));
        assertEquals(CODE, at("\"/*\" + d.|"));
    }

    @Test
    void apostrophesAreNotQuotes() {
        assertEquals(CODE, at("x = 'a'; d.|"));
        assertEquals(CODE, at("// it's\nd.|"));
    }

    /* ----- interpolated strings ----- */

    @Test
    void interpolationTextIsStringAndHolesAreCode() {
        assertEquals(STRING, at("$\"value d.|"));
        assertEquals(CODE, at("$\"{d.|"));
        assertEquals(CODE, at("$\"name: {user.na|"));
        assertEquals(STRING, at("$\"a {x} b.|"));
    }

    @Test
    void holesTrackNestedBracesStringsAndComments() {
        assertEquals(CODE, at("$\"{ {a} b.|"));
        assertEquals(STRING, at("$\"a {f(\"x.|"));
        assertEquals(STRING, at("$\"a {f(\"x\")} b.|"));
        assertEquals(COMMENT, at("$\"{ /* d.|"));
        assertEquals(CODE, at("$\"{ $\"{d.|"));
    }

    @Test
    void escapedBraceDoesNotOpenAHole() {
        assertEquals(STRING, at("$\"\\{ d.|"));
    }

    /* ----- import paths ----- */

    @Test
    void importPathStrings() {
        assertEquals(IMPORT_PATH, at("import { A } from \"|"));
        assertEquals(IMPORT_PATH, at("import { A, B as C } from \"./mod|"));
        assertEquals(IMPORT_PATH, at("import * from \"../../lib/|"));
        assertEquals(IMPORT_PATH, at("import *, { X } from \"./x|"));
        assertEquals(IMPORT_PATH, at("import lib \"|"));
        assertEquals(IMPORT_PATH, at("import lib { X } from \"Some|"));
        assertEquals(IMPORT_PATH, at("import { X } from \"@|"));
    }

    @Test
    void importPathAcrossLinesAndComments() {
        assertEquals(IMPORT_PATH, at("import {\n    A,\n    B\n} from \"./|"));
        assertEquals(IMPORT_PATH, at("import { A } from /* where */ \"|"));
        assertEquals(IMPORT_PATH, at("class X {}\nimport { A } from \"|"));
    }

    @Test
    void otherStringsAreNotImportPaths() {
        assertEquals(STRING, at("x = \"|"));
        assertEquals(STRING, at("call(lib \"|"));
        assertEquals(STRING, at("import { A } from $\"|"));
        assertEquals(STRING, at("fromage \"|"));
        assertEquals(STRING, at("import { A } from \"./A.mt\";\nprint(\"|"));
    }

    @Test
    void afterTheImportPathIsCode() {
        assertEquals(CODE, at("import { A } from \"./A.mt\"|"));
        assertEquals(CODE, at("import { A } from \"./A.mt\";|"));
    }
}
