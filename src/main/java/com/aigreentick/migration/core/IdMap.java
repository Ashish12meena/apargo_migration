package com.aigreentick.migration.core;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * old id -> new id per entity, stored in {@code <mig>.migration_id_map} (the service's copy of the Phase 1/2 table).
 * This is what keeps relations intact while ids change: a child never copies an old id, it asks this map
 * for its parent's new id.
 *
 * Cached per entity. Entries whose target row no longer exists (DB reset / re-seeded) are ignored for the
 * entities in {@link #CHECK}, exactly like Phase 1/2 did.
 *
 * Writes made inside a row unit (see {@link Tx#row}) are journaled, so a rolled-back savepoint also drops
 * them from the cache.
 */
@Component
public class IdMap {
    private static final Map<String, String> CHECK = Map.of(
            "user", "users", "org", "organizations", "project", "projects", "reseller_project", "projects",
            // WABA tables were rebuilt by the WABA team: entries of run 1 may point to rows that no longer exist
            "business_manager", "business_managers", "meta_token", "meta_oauth_tokens",
            "waba", "waba_accounts", "waba_phone", "waba_phone_numbers", "pinnacle_credential", "pinacle_credentials",
            "messaging_team", "teams");

    private final Db db;
    private final Sql sql;
    private final Map<String, Map<Long, Long>> cache = new ConcurrentHashMap<>();
    private final Deque<List<String[]>> journal = new ArrayDeque<>();   // single-threaded batch job
    private String currentStep = null;

    public IdMap(Db db, Sql sql) {
        this.db = db;
        this.sql = sql;
    }

    void setCurrentStep(String step) { this.currentStep = step; }

    public Long get(String entity, Long oldId) {
        if (oldId == null) return null;
        return load(entity).get(oldId);
    }

    public boolean has(String entity, Long oldId) { return get(entity, oldId) != null; }

    /** All mappings of an entity (read only view). */
    public Map<Long, Long> all(String entity) { return Collections.unmodifiableMap(load(entity)); }

    /** true when some old row of this entity already maps to this new id (reverse lookup). */
    public boolean isTargetOf(String entity, long newId) {
        return db.count("SELECT COUNT(*) FROM " + sql.mig("migration_id_map") + " WHERE entity = :e AND new_id = :n",
                Map.of("e", entity, "n", newId)) > 0;
    }

    public void put(String entity, long oldId, long newId) {
        db.exec("INSERT INTO " + sql.mig("migration_id_map") + " (entity, old_id, new_id, step) VALUES (:e, :o, :n, :s) "
                        + "ON DUPLICATE KEY UPDATE new_id = VALUES(new_id), step = VALUES(step)",
                Db.vals().with("e", entity).with("o", oldId).with("n", newId).with("s", currentStep));
        Map<Long, Long> loaded = cache.get(entity);   // only a loaded cache needs updating; otherwise the next load reads the DB
        if (loaded == null) {
            // if the cache gets loaded later inside this unit and the unit rolls back, drop it again
            if (!journal.isEmpty()) journal.peek().add(new String[]{entity, null, null});
            return;
        }
        Long previous = loaded.put(oldId, newId);
        if (!journal.isEmpty()) journal.peek().add(new String[]{entity, String.valueOf(oldId),
                previous == null ? null : String.valueOf(previous)});
    }

    /** One batch statement for many pairs (bulk steps). Not journaled: use only outside row units. */
    public void putAll(String entity, Map<Long, Long> pairs) {
        if (pairs.isEmpty()) return;
        List<SqlParameterSource> batch = new ArrayList<>(pairs.size());
        pairs.forEach((o, n) -> batch.add(new MapSqlParameterSource()
                .addValue("e", entity).addValue("o", o).addValue("n", n).addValue("s", currentStep)));
        db.jdbc().batchUpdate("INSERT INTO " + sql.mig("migration_id_map") + " (entity, old_id, new_id, step) VALUES (:e, :o, :n, :s) "
                + "ON DUPLICATE KEY UPDATE new_id = VALUES(new_id), step = VALUES(step)", batch.toArray(new SqlParameterSource[0]));
        Map<Long, Long> loaded = cache.get(entity);
        if (loaded != null) loaded.putAll(pairs);
    }

    /** Drop the cached copy of an entity, e.g. after an INSERT ... SELECT wrote the map in SQL. */
    public void invalidate(String entity) { cache.remove(entity); }

    public void invalidateAll() { cache.clear(); }

    // ---------------------------------------------------------------- journal for row units

    void beginUnit() { journal.push(new ArrayList<>()); }

    void commitUnit() {
        List<String[]> done = journal.pop();
        if (!journal.isEmpty()) journal.peek().addAll(done);   // an outer unit may still roll back
    }

    void rollbackUnit() {
        List<String[]> undo = journal.pop();
        for (int i = undo.size() - 1; i >= 0; i--) {
            String[] e = undo.get(i);
            if (e[1] == null) { cache.remove(e[0]); continue; }
            Map<Long, Long> m = cache.get(e[0]);
            if (m == null) continue;
            if (e[2] == null) m.remove(Long.parseLong(e[1]));
            else m.put(Long.parseLong(e[1]), Long.parseLong(e[2]));
        }
    }

    // ---------------------------------------------------------------- load

    private Map<Long, Long> load(String entity) {
        return cache.computeIfAbsent(entity, e -> {
            String check = CHECK.get(e);
            String q = check == null
                    ? "SELECT old_id, new_id FROM " + sql.mig("migration_id_map") + " WHERE entity = :e"
                    : "SELECT m.old_id, m.new_id FROM " + sql.mig("migration_id_map") + " m JOIN " + sql.tgt(check)
                      + " x ON x.id = m.new_id WHERE m.entity = :e";
            Map<Long, Long> m = new HashMap<>();
            db.jdbc().query(q, Map.of("e", e), rs -> {
                m.put(rs.getLong(1), rs.getLong(2));
            });
            return Collections.synchronizedMap(m);
        });
    }
}
