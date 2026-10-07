package com.aigreentick.migration.steps.auth;

import com.aigreentick.migration.core.Db;
import com.aigreentick.migration.core.Row;
import com.aigreentick.migration.core.StepContext;
import com.aigreentick.migration.util.Json;
import com.aigreentick.migration.util.Text;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/** Organization / project membership writes, same conventions as Phase 1 (migrate-to-auth-db.mjs). */
final class Memberships {
    private Memberships() {}

    enum OrgResult { EXISTS, CREATED, CONFLICT }

    /** organization_users has UNIQUE(user_id): a user can be in one organization only. */
    static OrgResult addOrgMember(StepContext ctx, long orgId, long userId, long roleId) {
        Row existing = ctx.db().findRow(ctx.sql().tgt("organization_users"), Map.of("user_id", userId), "id, organization_id");
        if (existing != null) return existing.lng("organization_id") == orgId ? OrgResult.EXISTS : OrgResult.CONFLICT;
        LocalDateTime now = StepContext.nowSec();
        ctx.db().insert(ctx.sql().tgt("organization_users"), Db.vals().with("organization_id", orgId).with("user_id", userId)
                .with("role_id", roleId).with("status", "active").with("joined_at", now).with("created_at", now).with("updated_at", now));
        return OrgResult.CREATED;
    }

    static Long orgOfUser(StepContext ctx, long userId) {
        Row r = ctx.db().findRow(ctx.sql().tgt("organization_users"), Map.of("user_id", userId), "organization_id");
        return r == null ? null : r.lng("organization_id");
    }

    /** find (organization_id, slug) or create; returns [projectId, created?1:0] */
    static long[] findOrCreateProject(StepContext ctx, long orgId, String slug, long ownerUserId, String name,
                                      String description, Map<String, Object> metadata) {
        Long id = ctx.db().findId(ctx.sql().tgt("projects"), Map.of("organization_id", orgId, "slug", slug));
        if (id != null) return new long[]{id, 0};
        LocalDateTime now = StepContext.nowSec();
        long newId = ctx.db().insert(ctx.sql().tgt("projects"), Db.vals()
                .with("uuid", UUID.randomUUID().toString()).with("organization_id", orgId).with("user_id", ownerUserId)
                .with("name", Text.cut(name, 200)).with("slug", Text.cut(slug, 150)).with("description", description)
                .with("status", "active").with("visibility", "private").with("settings", "{}")
                .with("metadata", Json.write(metadata)).with("created_by", ownerUserId)
                .with("created_at", now).with("updated_at", now));
        return new long[]{newId, 1};
    }

    /** find (project_id, user_id) or create; true when created */
    static boolean addProjectMember(StepContext ctx, long projectId, long userId, long roleId, Long invitedBy) {
        if (ctx.db().findId(ctx.sql().tgt("project_members"), Map.of("project_id", projectId, "user_id", userId)) != null) return false;
        LocalDateTime now = StepContext.nowSec();
        ctx.db().insert(ctx.sql().tgt("project_members"), Db.vals().with("project_id", projectId).with("user_id", userId)
                .with("role_id", roleId).with("status", "active").with("invited_by", invitedBy).with("invited_at", now)
                .with("joined_at", now).with("created_at", now).with("updated_at", now));
        return true;
    }
}
