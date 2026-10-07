package com.aigreentick.migration.core;

import com.aigreentick.migration.config.MigrationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * High-water marks for the big keyset-paged steps ({@code mig_checkpoint}). Saved in the same transaction as the
 * batch they describe, so a crash resumes after the last committed batch. Pilot runs never read or write them.
 */
@Component
public class Checkpoints {
    private final Db db;
    private final Sql sql;
    private final MigrationProperties props;

    public Checkpoints(Db db, Sql sql, MigrationProperties props) {
        this.db = db;
        this.sql = sql;
        this.props = props;
    }

    public long get(String step, String phase) {
        if (props.isPilot()) return 0;
        Long v = db.longValue("SELECT last_old_id FROM " + sql.mig("mig_checkpoint") + " WHERE step = :s AND phase = :p",
                Map.of("s", step, "p", phase));
        return v == null ? 0 : v;
    }

    public void save(String step, String phase, long lastOldId) {
        if (props.isPilot()) return;
        db.exec("INSERT INTO " + sql.mig("mig_checkpoint") + " (step, phase, last_old_id) VALUES (:s, :p, :l) "
                + "ON DUPLICATE KEY UPDATE last_old_id = VALUES(last_old_id)", Map.of("s", step, "p", phase, "l", lastOldId));
    }

    public void reset(String step) {
        db.exec("DELETE FROM " + sql.mig("mig_checkpoint") + " WHERE step = :s", Map.of("s", step));
    }
}
