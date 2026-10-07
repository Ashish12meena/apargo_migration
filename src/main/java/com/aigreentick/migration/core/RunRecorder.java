package com.aigreentick.migration.core;

import com.aigreentick.migration.config.MigrationProperties;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;


/** mig_run and mig_step_stats, written in their own transactions (survive dry-run rollbacks). */
@Component
public class RunRecorder {
    private final Db db;
    private final Sql sql;
    private final MigrationProperties props;

    public RunRecorder(Db db, Sql sql, MigrationProperties props) {
        this.db = db;
        this.sql = sql;
        this.props = props;
    }

    public long start(Tx tx) {
        long[] id = new long[1];
        tx.requiresNew(() -> {
            GeneratedKeyHolder kh = new GeneratedKeyHolder();
            db.jdbc().update("INSERT INTO " + sql.mig("mig_run") + " (started_at, dry_run, refresh, steps, tenants, status) "
                            + "VALUES (:s, :d, :r, :st, :t, 'RUNNING')",
                    new MapSqlParameterSource().addValue("s", StepContext.now()).addValue("d", props.isDryRun())
                            .addValue("r", props.isRefresh()).addValue("st", cut(props.getSteps(), 500))
                            .addValue("t", cut(props.getTenants(), 500)), kh, new String[]{"id"});
            id[0] = kh.getKey().longValue();
        });
        return id[0];
    }

    public void saveStats(Tx tx, long runId, StepStats stats) {
        tx.requiresNew(() -> stats.all().forEach((entity, e) -> db.exec("INSERT INTO " + sql.mig("mig_step_stats")
                        + " (run_id, step, entity, read_rows, inserted, matched_existing, skipped_mapped, refreshed, errors, started_at, finished_at) "
                        + "VALUES (:run, :step, :e, :rd, :ins, :m, :sk, :rf, :er, :sa, :fa) ON DUPLICATE KEY UPDATE "
                        + "read_rows = VALUES(read_rows), inserted = VALUES(inserted), matched_existing = VALUES(matched_existing), "
                        + "skipped_mapped = VALUES(skipped_mapped), refreshed = VALUES(refreshed), errors = VALUES(errors), finished_at = VALUES(finished_at)",
                Db.vals().with("run", runId).with("step", stats.step()).with("e", entity).with("rd", e.read)
                        .with("ins", e.inserted).with("m", e.matchedExisting).with("sk", e.skippedMapped)
                        .with("rf", e.refreshed).with("er", e.errors).with("sa", e.startedAt)
                        .with("fa", e.finishedAt == null ? StepContext.now() : e.finishedAt))));
    }

    public void finish(Tx tx, long runId, String status, String error) {
        tx.requiresNew(() -> db.exec("UPDATE " + sql.mig("mig_run") + " SET finished_at = :f, status = :s, error = :e WHERE id = :id",
                Db.vals().with("f", StepContext.now()).with("s", status).with("e", error).with("id", runId)));
    }

    private static String cut(String s, int n) { return s == null ? null : s.length() <= n ? s : s.substring(0, n); }

}
