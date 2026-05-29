package org.mtype.editor.debug;

import java.util.LinkedHashMap;
import java.util.Map;

public record DebugMessage(String command, Map<String, String> params) {
    public DebugMessage(String command, Map<String, String> params) {
        this.command = command == null ? "" : command;
        this.params = params == null ? new LinkedHashMap<>() : params;
    }

    public String get(String key) {
        return params.get(key);
    }

    public String get(String key, String fallback) {
        String v = params.get(key);
        return v == null ? fallback : v;
    }

    public int getInt(String key, int fallback) {
        String v = params.get(key);
        if (v == null) return fallback;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public long getLong(String key, long fallback) {
        String v = params.get(key);
        if (v == null) return fallback;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
