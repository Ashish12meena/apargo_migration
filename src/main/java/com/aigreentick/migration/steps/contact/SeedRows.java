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
