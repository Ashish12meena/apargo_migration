package com.aigreentick.migration.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Old TEXT/LONGTEXT "JSON" columns are often invalid; MySQL JSON columns reject invalid JSON under strict mode. */
public final class Json {
    public static final ObjectMapper M = new ObjectMapper();

    private Json() {}

    /** Parsed JSON or null when blank / invalid. */
    public static JsonNode parse(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            JsonNode n = M.readTree(s);
            // double-encoded JSON ("{\"a\":1}") is common in the old DB
            if (n != null && n.isTextual()) {
                String inner = n.asText().trim();
                if (inner.startsWith("{") || inner.startsWith("[")) {
                    try { return M.readTree(inner); } catch (JsonProcessingException ignore) { /* keep string */ }
                }
            }
            return n;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    public static boolean valid(String s) { return parse(s) != null; }

    /** JSON text for a JSON column, or null when the input is blank or invalid. */
    public static String normalize(String s) {
        JsonNode n = parse(s);
        return n == null ? null : write(n);
    }

    public static String write(Object o) {
        try { return M.writeValueAsString(o); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }

    public static ObjectNode obj() { return M.createObjectNode(); }

    public static ArrayNode arr() { return M.createArrayNode(); }

    /** textual value of a field (null when missing / null) */
    public static String text(JsonNode n, String... fields) {
        if (n == null) return null;
        for (String f : fields) {
            JsonNode v = n.get(f);
            if (v != null && !v.isNull()) return v.isValueNode() ? v.asText() : v.toString();
        }
        return null;
    }
}
