package com.aigreentick.migration.core;

import com.aigreentick.migration.config.MigrationProperties;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Everything a step needs, plus the shared helpers that make every step behave the same way. */
public final class StepContext {
    private final String stepId;
    private final long runId;
    private final MigrationProperties props;
    private final Db db;
    private final Sql sql;
    private final Tx tx;
    private final IdMap idMap;
    private final TenantResolver tenants;
    private final ProblemLog problems;
    private final Checkpoints checkpoints;
    private final RoleCatalog roles;
    private final UserRefs userRefs;
    private final Scope scope;
    private final StepStats stats;

    public StepContext(String stepId, long runId, MigrationProperties props, Db db, Sql sql, Tx tx, IdMap idMap,
                       TenantResolver tenants, ProblemLog problems, Checkpoints checkpoints, RoleCatalog roles,
                       UserRefs userRefs, Scope scope) {
        this.stepId = stepId;
        this.runId = runId;
        this.props = props;
        this.db = db;
        this.sql = sql;
        this.tx = tx;
        this.idMap = idMap;
        this.tenants = tenants;
        this.problems = problems;
        this.checkpoints = checkpoints;
        this.roles = roles;
        this.userRefs = userRefs;
        this.scope = scope;
        this.stats = new StepStats(stepId);
    }

    public String stepId() { return stepId; }
    public long runId() { return runId; }
    public MigrationProperties props() { return props; }
    public Db db() { return db; }
    public Sql sql() { return sql; }
    public Tx tx() { return tx; }
    public IdMap idMap() { return idMap; }
    public TenantResolver tenants() { return tenants; }
    public ProblemLog problems() { return problems; }
    public Checkpoints checkpoints() { return checkpoints; }
    public RoleCatalog roles() { return roles; }
    public UserRefs userRefs() { return userRefs; }
    public Scope scope() { return scope; }
    public StepStats stats() { return stats; }
    public boolean dryRun() { return props.isDryRun(); }
    public boolean refresh() { return props.isRefresh(); }

    /** UTC wall clock with microsecond precision (DATETIME(6)). */
    public static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS); }

    /** now() at second precision for DATETIME (no fraction) columns. */
    public static LocalDateTime nowSec() { return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS); }

    /**
     * Resolve the tenant of an old row. Returns null when the row is out of the pilot scope (silently) or cannot be
     * placed in a project (logged as ERROR with the tenant's reason).
     */
    public Tenant place(String sourceTable, Long oldId, Long oldUserId) {
        Tenant t = tenants.resolve(oldUserId);
        if (!t.hasProject()) {
            if (scope.isPilot()) return null;
            problems.error(sourceTable, oldId, t.code(), t.reason() == null ? "no project" : t.reason());
            stats.current().errors++;
            return null;
        }
        if (!scope.includes(t)) return null;
        return t;
    }

    /** Same as {@link #place} but only an organization is required. */
    public Tenant placeOrg(String sourceTable, Long oldId, Long oldUserId) {
        Tenant t = tenants.resolve(oldUserId);
        if (!t.ok() || t.orgId() == null) {
            if (scope.isPilot()) return null;
            problems.error(sourceTable, oldId, t.code(), t.reason());
            stats.current().errors++;
            return null;
        }
        if (scope.isPilot() && !scope.includes(t)) return null;
        return t;
    }

    /**
     * Keyset paging over an old table with checkpoints. {@code selectSql} must contain {@code :lastId} and
     * {@code :limit} and return the key column {@code idColumn} in ascending order. Each page is processed in one
     * transaction together with its checkpoint.
     */
    public void pages(String phase, String selectSql, Map<String, ?> params, String idColumn, Consumer<List<Row>> page) {
        long last = checkpoints.get(stepId, phase);
        int limit = Math.max(1, props.getBatchSize());
        while (true) {
            Map<String, Object> p = new HashMap<>(params);
            p.put("lastId", last);
            p.put("limit", limit);
            List<Row> rows = db.rows(selectSql, p);
            if (rows.isEmpty()) break;
            long maxId = rows.get(rows.size() - 1).lng(idColumn);
            tx.inTx(() -> {
                page.accept(rows);
                checkpoints.save(stepId, phase, maxId);
            });
            last = maxId;
            if (rows.size() < limit) break;
        }
    }

    public String userRef(Long newUserId) { return userRefs.ref(newUserId); }

    /**
     * Batch insert that never stops the run because of one bad row: the batch runs in a savepoint; when it fails
     * (length, ENUM, JSON, unique race with the live system, ...) every row is inserted alone in its own savepoint and
     * the failing ones are logged as ERROR SQL_ERROR with {@code keyColumns} in the message.
     */
    public int insertBatchSafe(String table, List<? extends Map<String, ?>> rows, String tail, String sourceTable, String... keyColumns) {
        if (rows.isEmpty()) return 0;
        try {
            return tx.nested(() -> db.insertBatch(table, rows, tail));
        } catch (org.springframework.dao.DataAccessException batchError) {
            int n = 0;
            for (Map<String, ?> r : rows) {
                try {
                    n += tx.nested(() -> db.insertBatch(table, List.of(r), tail));
                } catch (org.springframework.dao.DataAccessException e) {
                    StringBuilder key = new StringBuilder();
                    for (String k : keyColumns) key.append(k).append('=').append(r.get(k)).append(' ');
                    Object oldId = keyColumns.length > 0 ? r.get(keyColumns[0]) : null;
                    problems.error(sourceTable, oldId instanceof Number num ? num.longValue() : null, "SQL_ERROR",
                            table + " " + key.toString().trim() + ": " + Tx.rootMessage(e));
                    stats.current().errors++;
                }
            }
            return n;
        }
    }
}
