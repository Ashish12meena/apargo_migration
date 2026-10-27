package com.aigreentick.migration.steps.contact;

import com.aigreentick.migration.core.Row;
import com.aigreentick.migration.core.StepContext;

import java.util.Map;

/** The contact steps use seeded rows by id; stop early with a clear message when one is missing. */
public final class SeedRows {
    private SeedRows() {}

    public static void require(StepContext ctx, long activeStatusId, long invalidStatusId, long sourceId) {
        for (long id : new long[]{activeStatusId, invalidStatusId}) {
            if (ctx.db().count("SELECT COUNT(*) FROM " + ctx.sql().tgt("contact_statuses") + " WHERE id = :id", Map.of("id", id)) == 0)
                throw new IllegalStateException("contact_statuses id " + id + " does not exist (migration.contacts.active-status-id / invalid-status-id)");
        }
        Row src = ctx.db().row("SELECT id, uuid, source_key, organization_id FROM " + ctx.sql().tgt("contact_sources") + " WHERE id = :id",
                Map.of("id", sourceId));
        if (src == null)
            throw new IllegalStateException("contact_sources id " + sourceId + " does not exist (migration.contacts.source-id)");
    }

    /**
     * Seeded rows with a zero date ('0000-00-00 00:00:00', from the Node master seed) -> now. Every migrated contact points at
     * the Migration source, and Contact's driver throws "Zero date value prohibited" reading it, which fails every contact
     * lookup (docs/architecture/09-inbound-zero-date-contact-source.md). CAST ... LIKE because comparing with a zero literal
     * can itself fail under strict sql_mode.
     */
    public static void repairZeroDates(StepContext ctx) {
        int fixed = 0;
        for (String table : new String[]{"contact_sources", "contact_statuses"}) {
            for (String column : new String[]{"created_at", "updated_at"}) {
                if (!ctx.db().columnExists(ctx.sql().tgtSchema(), table, column)) continue;
                fixed += ctx.db().exec("UPDATE " + ctx.sql().tgt(table) + " SET " + column + " = NOW(6) "
                        + "WHERE CAST(" + column + " AS CHAR) LIKE '0000%'", Map.of());
            }
        }
        if (fixed > 0)
            ctx.problems().info("contact_sources", null, "ZERO_DATE_REPAIRED", fixed + " zero created_at/updated_at value(s) in seeded contact_sources / contact_statuses -> now");
    }

    /** one line for the baseline report, null when everything exists */
    public static String check(StepContext ctx) {
        try {
            require(ctx, ctx.props().getContacts().getActiveStatusId(), ctx.props().getContacts().getInvalidStatusId(),
                    ctx.props().getContacts().getSourceId());
            return null;
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
    }
}
