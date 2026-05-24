package org.mtype.editor.debug;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encodes outgoing and decodes incoming lines for the mType debug protocol.
 * Wire format: COMMAND key1=value1 key2="quoted value with \"escapes\""
 * Mirrors C:\matan\mType\mType\debugger\DebugProtocol.cpp.
 */
public final class DebugProtocolCodec {
    private DebugProtocolCodec() {}

    public static String encode(String command, Map<String, String> params) {
        StringBuilder sb = new StringBuilder(command);
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                sb.append(' ').append(e.getKey()).append('=').append(escapeValue(e.getValue()));
            }
        }
        return sb.toString();
    }

    public static DebugMessage decode(String line) {
        if (line == null) return new DebugMessage("", new LinkedHashMap<>());
        String trimmed = line.strip();
        if (trimmed.isEmpty()) return new DebugMessage("", new LinkedHashMap<>());

        int cmdEnd = trimmed.indexOf(' ');
        if (cmdEnd < 0) return new DebugMessage(trimmed, new LinkedHashMap<>());

        String command = trimmed.substring(0, cmdEnd);
        Map<String, String> params = new LinkedHashMap<>();

        int pos = cmdEnd + 1;
        int len = trimmed.length();
        while (pos < len) {
            while (pos < len && (trimmed.charAt(pos) == ' ' || trimmed.charAt(pos) == '\t')) pos++;
            if (pos >= len) break;

            int eq = trimmed.indexOf('=', pos);
            if (eq < 0) break;
            String key = trimmed.substring(pos, eq);
            pos = eq + 1;

            StringBuilder val = new StringBuilder();
            if (pos < len && trimmed.charAt(pos) == '"') {
                pos++;
                boolean escaped = false;
                while (pos < len) {
                    char c = trimmed.charAt(pos);
                    if (escaped) {
                        switch (c) {
                            case 'n' -> val.append('\n');
                            case 'r' -> val.append('\r');
                            case 't' -> val.append('\t');
                            default -> val.append(c);
                        }
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '"') {
                        pos++;
                        break;
                    } else {
                        val.append(c);
                    }
                    pos++;
                }
            } else {
                int sp = trimmed.indexOf(' ', pos);
                if (sp < 0) {
                    val.append(trimmed, pos, len);
                    pos = len;
                } else {
                    val.append(trimmed, pos, sp);
                    pos = sp;
                }
            }
            params.put(key, val.toString());
        }
        return new DebugMessage(command, params);
    }

    private static String escapeValue(String value) {
        if (value == null) return "";
        if (needsQuoting(value)) {
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    case '"', '\\' -> { sb.append('\\'); sb.append(c); }
                    default -> sb.append(c);
                }
            }
            sb.append('"');
            return sb.toString();
        }
        return value;
    }

    private static boolean needsQuoting(String v) {
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == ' ' || c == '=' || c == ':' || c == '"' || c == '\\'
                    || c == '\n' || c == '\r' || c == '\t') return true;
        }
        return false;
    }
}
