package com.aigreentick.migration.steps.auth;

import com.aigreentick.migration.config.MigrationProperties.ResellerAdminPolicy;
import com.aigreentick.migration.core.*;
import com.aigreentick.migration.core.TenantResolver.OldUser;
import com.aigreentick.migration.util.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 03b (D2 = OWN_PROJECT only) — one "own" project per reseller organization for the business data the reseller
 * admin owns directly (F5). Map entity {@code reseller_project} (old reseller id -> project id). Agents created by
 * the reseller admin become agents of that project.
 */
@Component
public class ResellerOwnProjectStep implements MigrationStep {
    private static final Logger log = LoggerFactory.getLogger(ResellerOwnProjectStep.class);

    @Override public String id() { return "03b-reseller-own-project"; }
    @Override public int order() { return 320; }
    @Override public String title() { return "reseller admins -> own project (D2)"; }

    @Override
    public void run(StepContext ctx) {
        if (ctx.props().getTenant().getResellerAdminPolicy() != ResellerAdminPolicy.OWN_PROJECT) {
            log.info("  D2 = SKIP: nothing to do");
            return;
        }
        long adminRole = ctx.roles().id(ctx.props().getRoles().getProjectAdminSlug(), "project");
        long agentRole = ctx.roles().id(ctx.props().getRoles().getProjectAgentSlug(), "project");
        StepStats.Entity s = ctx.stats().entity("reseller_project");
        Map<Long, long[]> projectByAdmin = new LinkedHashMap<>();   // old admin user id -> [project, new admin user, org]

        for (Row r : ctx.db().rows("SELECT id, slug, name, admin_user_id FROM " + ctx.sql().old("resellers")
                + " WHERE deleted_at IS NULL ORDER BY id")) {
            long resellerId = r.lng("id");
            s.read++;
            Long orgId = ctx.idMap().get("org", resellerId);
            Long adminUser = ctx.idMap().get("user", r.lng("admin_user_id"));
            if (orgId == null || adminUser == null) {
                ctx.problems().error("resellers", resellerId, "PARENT_MISSING", "reseller org or admin user not migrated");
                s.errors++;
                continue;
            }
            Long mapped = ctx.idMap().get("reseller_project", resellerId);
            if (mapped != null) {
                s.skippedMapped++;
                projectByAdmin.put(r.lng("admin_user_id"), new long[]{mapped, adminUser, orgId});
                continue;
            }
            ctx.tx().row(ctx, "resellers", resellerId, () -> {
                Map<String, Object> md = new LinkedHashMap<>();
                md.put("migrated_from_reseller_id", resellerId);
                md.put("migration_reason", "RESELLER_OWN_PROJECT");
                long[] p = Memberships.findOrCreateProject(ctx, orgId, Text.slugify(r.str("slug") + "-own-" + resellerId),
                        adminUser, Text.cut(r.str("name"), 180) + " (own)", "Business data owned by the reseller admin", md);
                Memberships.addProjectMember(ctx, p[0], adminUser, adminRole, null);
                ctx.idMap().put("reseller_project", resellerId, p[0]);
                if (p[1] == 1) s.inserted++; else s.matchedExisting++;
                projectByAdmin.put(r.lng("admin_user_id"), new long[]{p[0], adminUser, orgId});
            });
        }

        StepStats.Entity a = ctx.stats().entity("agent");
        for (OldUser ag : ctx.tenants().users().values()) {
            if (ag.roleId() != ctx.props().getRoles().getAgentRoleId()) continue;
            Long creator = ag.createdBy() != null ? ag.createdBy() : ag.accountAdminId();
            long[] target = creator == null ? null : projectByAdmin.get(creator);
            if (target == null) continue;
            a.read++;
            Long agentUser = ctx.idMap().get("user", ag.id());
            if (agentUser == null) { a.errors++; continue; }
            ctx.tx().row(ctx, "users", ag.id(), () -> {
                if (Memberships.addOrgMember(ctx, target[2], agentUser, adminRole) == Memberships.OrgResult.CONFLICT) {
                    ctx.problems().error("users", ag.id(), "ORG_CONFLICT", "agent user " + agentUser + " belongs to another organization");
                    a.errors++;
                    throw new Tx.SkipRow();
                }
                if (Memberships.addProjectMember(ctx, target[0], agentUser, agentRole, target[1])) a.inserted++; else a.matchedExisting++;
            });
        }
        ctx.tenants().writeTenantMap();
    }
}
