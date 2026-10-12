package com.aigreentick.migration.core;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Typed, driver-independent view of one result row (keys are column labels, case-insensitive via Spring's map).
 * TIMESTAMP and DATETIME both come back as LocalDateTime in UTC (the JVM runs in UTC, see MigrationApplication).
 */
public final class Row {
    private final Map<String, Object> m;

    public Row(Map<String, Object> m) { this.m = m; }

    public Map<String, Object> map() { return m; }

    public boolean has(String k) { return m.containsKey(k); }

    public Object get(String k) { return m.get(k); }

    public boolean isNull(String k) { return m.get(k) == null; }

    public String str(String k) {
        Object v = m.get(k);
        if (v == null) return null;
        if (v instanceof byte[] b) return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        return v.toString();
    }

    /** trimmed string, null when blank */
    public String text(String k) {
        String s = str(k);
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    public Long lng(String k) {
        Object v = m.get(k);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        if (v instanceof Boolean b) return b ? 1L : 0L;
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
    }

    public long lng(String k, long dflt) { Long v = lng(k); return v == null ? dflt : v; }

    public Integer integer(String k) { Long v = lng(k); return v == null ? null : v.intValue(); }

    public BigDecimal dec(String k) {
        Object v = m.get(k);
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        try { return new BigDecimal(v.toString().trim()); } catch (NumberFormatException e) { return null; }
    }

    /** tinyint(1), BIT(1), enum('0','1'), 'on'/'off', "true" */
    public boolean bool(String k) {
        Object v = m.get(k);
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        if (v instanceof byte[] b) return b.length > 0 && b[0] != 0;
        String s = v.toString().trim().toLowerCase();
        return s.equals("1") || s.equals("true") || s.equals("on") || s.equals("yes") || s.equals("y");
    }

    public LocalDateTime dt(String k) {
        Object v = m.get(k);
        if (v == null) return null;
        if (v instanceof LocalDateTime l) return l;
        if (v instanceof Timestamp t) return t.toLocalDateTime();
        if (v instanceof java.sql.Date d) return d.toLocalDate().atStartOfDay();
        if (v instanceof LocalDate d) return d.atStartOfDay();
        if (v instanceof OffsetDateTime o) return o.toLocalDateTime();
        if (v instanceof java.util.Date d) return new Timestamp(d.getTime()).toLocalDateTime();
        return null;
    }

    public LocalDateTime dtOr(String k, LocalDateTime dflt) { LocalDateTime v = dt(k); return v == null ? dflt : v; }

    @Override public String toString() { return m.toString(); }
}
