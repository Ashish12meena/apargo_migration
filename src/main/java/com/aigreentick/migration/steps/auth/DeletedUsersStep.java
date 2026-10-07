package com.aigreentick.migration.steps.auth;

import com.aigreentick.migration.core.*;
import com.aigreentick.migration.core.TenantResolver.OldUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 03h (D5, only with migration.auth.fix-deleted-users=true) — Phase 1 migrated soft-deleted old users as active.
 * Sets users.status = 'deleted' (+ deleted_at) for new users whose EVERY old source user is soft-deleted, and archives
 * the customer projects of soft-deleted customers. Users already changed in the new system (status not 'active' /
 * 'pending_verification', or deleted_at set) are left alone.
 */
@Component
public class DeletedUsersStep implements MigrationStep {
    private static final Logger log = LoggerFactory.getLogger(DeletedUsersStep.class);

    @Override public String id() { return "03h-deleted-users"; }
    @Override public int order() { return 380; }
    @Override public String title() { return "soft-deleted old users -> status deleted (D5)"; }

    @Override
    public void run(StepContext ctx) {
        if (!ctx.props().getAuth().isFixDeletedUsers()) {
            log.info("  D5 off (migration.auth.fix-deleted-users=false): nothing to do");
            return;
        }
        Map<Long, List<OldUser>> byNew = new HashMap<>();
        for (OldUser u : ctx.tenants().users().values()) {
            Long n = ctx.idMap().get("user", u.id());
            if (n != null) byNew.computeIfAbsent(n, k -> new ArrayList<>()).add(u);
        }
        StepStats.Entity s = ctx.stats().entity("user");
        StepStats.Entity p = ctx.stats().entity("project");
        for (Map.Entry<Long, List<OldUser>> e : byNew.entrySet()) {
            if (!e.getValue().stream().allMatch(OldUser::deleted)) continue;
            s.read++;
            long newId = e.getKey();
            ctx.tx().row(ctx, "users", e.getValue().get(0).id(), () -> {
                Row u = ctx.db().findRow(ctx.sql().tgt("users"), Map.of("id", newId), "id, status, deleted_at");
                if (u == null || !u.isNull("deleted_at") || !Set.of("active", "pending_verification").contains(u.str("status"))) {
                    s.skippedMapped++;
                    return;
                }
                Row old = ctx.db().row("SELECT MAX(deleted_at) d FROM " + ctx.sql().old("users") + " WHERE id IN (:ids)",
                        Map.of("ids", e.getValue().stream().map(OldUser::id).toList()));
                ctx.db().update(ctx.sql().tgt("users"), newId, Db.vals().with("status", "deleted")
                        .with("deleted_at", old.dtOr("d", StepContext.nowSec())).with("updated_at", StepContext.nowSec()));
                s.refreshed++;
                for (OldUser ou : e.getValue()) {
                    Long project = ctx.idMap().get("project", ou.id());
                    if (project == null) continue;
                    p.read++;
                    p.refreshed += ctx.db().exec("UPDATE " + ctx.sql().tgt("projects") + " SET status = 'archived', archived_at = :now, "
                            + "updated_at = :now WHERE id = :id AND status = 'active'", Map.of("now", StepContext.nowSec(), "id", project));
                }
            });
        }
    }
}
