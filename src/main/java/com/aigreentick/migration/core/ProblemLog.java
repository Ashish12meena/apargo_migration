package com.aigreentick.migration.core;

import com.aigreentick.migration.config.MigrationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Unplaceable or changed rows go to {@code <mig>.mig_errors} instead of crashing the run.
 * Rows are buffered and written in their own transaction (REQUIRES_NEW), so they survive a dry-run rollback.
 * Per (step, code) at most {@code migration.problems.max-rows-per-code} rows are stored one by one;
 * the rest are counted and stored as one SUMMARY row at the end of the step.
 */
@Component
public class ProblemLog {
    private static final Logger log = LoggerFactory.getLogger(ProblemLog.class);

    public enum Severity { ERROR, WARN, INFO }

    private final Db db;
    private final Sql sql;
    private final MigrationProperties props;
    private Tx tx;   // set by the engine (avoids a constructor cycle Tx -> ProblemLog)

    private Long runId;
    private String step = "init";
    private final List<SqlParameterSource> buffer = new ArrayList<>();
    private final Map<String, long[]> perCode = new LinkedHashMap<>();   // key severity|code|table -> [total, stored]
    private final Set<String> aggregated = new HashSet<>();               // keys written by aggregate(): no summary row

    public ProblemLog(Db db, Sql sql, MigrationProperties props) {
        this.db = db;
        this.sql = sql;
        this.props = props;
    }

    void bind(Tx tx, Long runId) { this.tx = tx; this.runId = runId; }

    void beginStep(String step) {
        flush();
        this.step = step;
        perCode.clear();
        aggregated.clear();
    }

    public void error(String table, Long oldId, String code, String reason) { add(Severity.ERROR, table, oldId, code, reason); }

    public void warn(String table, Long oldId, String code, String reason) { add(Severity.WARN, table, oldId, code, reason); }

    public void info(String table, Long oldId, String code, String reason) { add(Severity.INFO, table, oldId, code, reason); }

    /** One row standing for many (bulk SQL steps): reason should contain the count. */
    public void aggregate(Severity sev, String table, String code, long count, String reason) {
        if (count <= 0) return;
        String key = sev + "|" + code + "|" + table;
        add(sev, table, null, code, reason + " (" + count + " rows)");
        perCode.computeIfAbsent(key, k -> new long[2])[0] += count - 1;
        aggregated.add(key);
    }

    public synchronized void add(Severity sev, String table, Long oldId, String code, String reason) {
        long[] c = perCode.computeIfAbsent(sev + "|" + code + "|" + table, k -> new long[2]);
        c[0]++;
        if (c[1] >= props.getProblems().getMaxRowsPerCode()) return;
        c[1]++;
        String r = reason == null ? "" : reason;
        if (r.length() > 1000) r = r.substring(0, 1000);
        buffer.add(new MapSqlParameterSource()
                .addValue("run", runId).addValue("step", step).addValue("t", table).addValue("o", oldId)
                .addValue("s", sev.name()).addValue("c", code).addValue("r", r));
        if (sev == Severity.ERROR && c[1] <= 3) log.warn("  [{}] {} {} {}: {}", sev, table, oldId == null ? "" : oldId, code, r);
        if (buffer.size() >= 500) flush();
    }

    /** End of step: write summary rows for capped codes and flush. Returns the totals per key. */
    public Map<String, long[]> endStep() {
        for (Map.Entry<String, long[]> e : perCode.entrySet()) {
            long total = e.getValue()[0], stored = e.getValue()[1];
            if (total > stored && !aggregated.contains(e.getKey())) {
                String[] k = e.getKey().split("\\|", 3);
                buffer.add(new MapSqlParameterSource()
                        .addValue("run", runId).addValue("step", step).addValue("t", k[2]).addValue("o", null)
                        .addValue("s", k[0]).addValue("c", "SUMMARY_" + k[1])
                        .addValue("r", total + " rows in total with code " + k[1] + "; only the first " + stored + " stored individually"));
            }
        }
        flush();
        Map<String, long[]> copy = new LinkedHashMap<>(perCode);
        perCode.clear();
        aggregated.clear();
        return copy;
    }

    public synchronized void flush() {
        if (buffer.isEmpty()) return;
        List<SqlParameterSource> rows = new ArrayList<>(buffer);
        buffer.clear();
        Runnable write = () -> db.jdbc().batchUpdate("INSERT INTO " + sql.mig("mig_errors")
                        + " (run_id, step, source_table, old_id, severity, code, reason) VALUES (:run, :step, :t, :o, :s, :c, :r)",
                rows.toArray(new SqlParameterSource[0]));
        if (tx != null) tx.requiresNew(write); else write.run();
    }
}
