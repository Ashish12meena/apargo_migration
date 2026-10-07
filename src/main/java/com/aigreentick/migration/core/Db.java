package com.aigreentick.migration.core;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Small JDBC helpers over the single DataSource. Table names passed in are already schema-qualified
 * (see {@link Sql}). Everything joins the current Spring transaction.
 *
 * No INSERT IGNORE anywhere: skip-if-exists is always an explicit lookup, so strict SQL mode still reports
 * truncation, bad dates and bad ENUM values.
 */
@Component
public class Db {
    private final NamedParameterJdbcTemplate jdbc;

    public Db(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public NamedParameterJdbcTemplate jdbc() { return jdbc; }

    // ---------------------------------------------------------------- reads

    public List<Row> rows(String sql, Map<String, ?> params) {
        List<Map<String, Object>> list = jdbc.queryForList(sql, params);
        List<Row> out = new ArrayList<>(list.size());
        for (Map<String, Object> m : list) out.add(new Row(m));
        return out;
    }

    public List<Row> rows(String sql) { return rows(sql, Map.of()); }

    public Row row(String sql, Map<String, ?> params) {
        List<Row> r = rows(sql, params);
        return r.isEmpty() ? null : r.get(0);
    }

    public Long longValue(String sql, Map<String, ?> params) {
        List<Map<String, Object>> r = jdbc.queryForList(sql, params);
        if (r.isEmpty()) return null;
        Object v = r.get(0).values().iterator().next();
        return v == null ? null : ((Number) v).longValue();
    }

    public long count(String sql, Map<String, ?> params) {
        Long v = longValue(sql, params);
        return v == null ? 0 : v;
    }

    public long count(String sql) { return count(sql, Map.of()); }

    /** id of the first row matching all columns (null value -> IS NULL) */
    public Long findId(String table, Map<String, ?> where) {
        Row r = findRow(table, where, "id");
        return r == null ? null : r.lng("id");
    }

    public Row findRow(String table, Map<String, ?> where) { return findRow(table, where, "*"); }

    public Row findRow(String table, Map<String, ?> where, String columns) {
        StringBuilder sb = new StringBuilder("SELECT ").append(columns).append(" FROM ").append(table).append(" WHERE ");
        MapSqlParameterSource p = new MapSqlParameterSource();
        int i = 0;
        for (Map.Entry<String, ?> e : where.entrySet()) {
            if (i > 0) sb.append(" AND ");
            if (e.getValue() == null) {
                sb.append('`').append(e.getKey()).append("` IS NULL");
            } else {
                sb.append('`').append(e.getKey()).append("` = :w").append(i);
                p.addValue("w" + i, e.getValue());
            }
            i++;
        }
        sb.append(" ORDER BY 1 LIMIT 1");
        List<Map<String, Object>> r = jdbc.queryForList(sb.toString(), p);
        return r.isEmpty() ? null : new Row(r.get(0));
    }

    public boolean tableExists(String schema, String table) {
        return count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = :s AND table_name = :t",
                Map.of("s", schema, "t", table)) > 0;
    }

    public boolean columnExists(String schema, String table, String column) {
        return count("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = :s AND table_name = :t AND column_name = :c",
                Map.of("s", schema, "t", table, "c", column)) > 0;
    }

    // ---------------------------------------------------------------- writes

    /** INSERT one row into a table with an AUTO_INCREMENT id, returns the new id. */
    public long insert(String table, Map<String, ?> values) {
        String[] cols = values.keySet().toArray(new String[0]);
        String sql = insertSql(table, cols);
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(sql, params(values), kh, new String[]{"id"});
        Number key = firstKey(kh);
        if (key == null) throw new IllegalStateException("no generated key for insert into " + table);
        return key.longValue();
    }

    /** INSERT one row into a table without AUTO_INCREMENT (or when the key is not needed). */
    public void insertNoKey(String table, Map<String, ?> values) {
        String[] cols = values.keySet().toArray(new String[0]);
        jdbc.update(insertSql(table, cols), params(values));
    }

    /** Batch INSERT of rows that all have the same columns (order of the first row). Returns rows sent. */
    public int insertBatch(String table, List<? extends Map<String, ?>> rows) {
        return insertBatch(table, rows, null);
    }

    /** Batch INSERT with an optional tail such as "ON DUPLICATE KEY UPDATE id = id". */
    public int insertBatch(String table, List<? extends Map<String, ?>> rows, String tail) {
        if (rows.isEmpty()) return 0;
        String[] cols = rows.get(0).keySet().toArray(new String[0]);
        String sql = insertSql(table, cols) + (tail == null ? "" : " " + tail);
        SqlParameterSource[] batch = new SqlParameterSource[rows.size()];
        for (int i = 0; i < rows.size(); i++) batch[i] = params(rows.get(i));
        jdbc.batchUpdate(sql, batch);
        return rows.size();
    }

    public int update(String table, long id, Map<String, ?> set) {
        if (set.isEmpty()) return 0;
        StringBuilder sb = new StringBuilder("UPDATE ").append(table).append(" SET ");
        int i = 0;
        for (String k : set.keySet()) {
            if (i++ > 0) sb.append(", ");
            sb.append('`').append(k).append("` = :").append(k);
        }
        sb.append(" WHERE id = :__id");
        MapSqlParameterSource p = params(set);
        p.addValue("__id", id);
        return jdbc.update(sb.toString(), p);
    }

    public int exec(String sql, Map<String, ?> params) { return jdbc.update(sql, params); }

    public int exec(String sql) { return jdbc.update(sql, Map.of()); }

    // ---------------------------------------------------------------- internals

    private static String insertSql(String table, String[] cols) {
        StringBuilder c = new StringBuilder(), v = new StringBuilder();
        for (int i = 0; i < cols.length; i++) {
            if (i > 0) { c.append(", "); v.append(", "); }
            c.append('`').append(cols[i]).append('`');
            v.append(':').append(cols[i]);
        }
        return "INSERT INTO " + table + " (" + c + ") VALUES (" + v + ")";
    }

    private static MapSqlParameterSource params(Map<String, ?> values) {
        MapSqlParameterSource p = new MapSqlParameterSource();
        values.forEach(p::addValue);
        return p;
    }

    private static Number firstKey(KeyHolder kh) {
        if (kh.getKeyList().isEmpty()) return null;
        Map<String, Object> keys = kh.getKeyList().get(0);
        for (Object v : keys.values()) if (v instanceof Number n) return n;
        return null;
    }

    /** Insertion-ordered map builder that accepts null values (Map.of does not). */
    public static Vals vals() { return new Vals(); }

    public static final class Vals extends LinkedHashMap<String, Object> {
        public Vals with(String k, Object v) { put(k, v); return this; }
    }
}
