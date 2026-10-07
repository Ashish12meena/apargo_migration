package com.aigreentick.migration.util;

import java.text.Normalizer;
import java.util.Locale;

public final class Text {
    private Text() {}

    /** null-safe cut to max characters (code points are not split). */
    public static String cut(String s, int max) {
        if (s == null) return null;
        if (s.length() <= max) return s;
        int end = max;
        if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    public static boolean longer(String s, int max) { return s != null && s.length() > max; }

    public static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Same as Phase 1: lower-case, non [a-z0-9] runs -> '-', trimmed dashes. */
    public static String slugify(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT).trim().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    /** attribute keys: lower snake case */
    public static String snake(String s) {
        String slug = slugify(s).replace('-', '_');
        return slug.isEmpty() ? "attr" : slug;
    }

    public static String emailLocal(String email) {
        if (email == null) return "";
        int at = email.indexOf('@');
        return at < 0 ? email : email.substring(0, at);
    }

    public static String digits(String s) { return s == null ? "" : s.replaceAll("[^0-9]", ""); }

    public static String upper(String s) { return s == null ? null : s.trim().toUpperCase(Locale.ROOT); }

    public static String lower(String s) { return s == null ? null : s.trim().toLowerCase(Locale.ROOT); }

    public static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v.trim();
        return null;
    }
}
