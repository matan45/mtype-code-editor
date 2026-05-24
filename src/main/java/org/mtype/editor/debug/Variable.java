package org.mtype.editor.debug;

public record Variable(String name, String value, String type, long reference) {
    public boolean expandable() { return reference != 0; }
}
