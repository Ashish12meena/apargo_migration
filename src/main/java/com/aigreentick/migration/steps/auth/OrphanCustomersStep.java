package com.aigreentick.migration.steps.auth;

import com.aigreentick.migration.config.MigrationProperties.NoResellerPolicy;
import com.aigreentick.migration.core.*;
import com.aigreentick.migration.core.TenantResolver.OldUser;
import com.aigreentick.migration.util.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 03a (D1 = ASSIGN only) — customers without reseller_id get organization membership + one project + project
 * membership inside organization {@code migration.tenant.no-reseller-org-id}, exactly like Phase 1 did for the
 * others (slug {@code {emailLocal}-project-{oldId}}). Their agents are added as project agents.
 * With D1 = SKIP the step does nothing; their data stays logged as NO_RESELLER and is picked up when D1 changes.
 */
@Component
public class OrphanCustomersStep implements MigrationStep {
    private static final Logger log = LoggerFactory.getLogger(OrphanCustomersStep.class);

    @Override public String id() { return "03a-orphan-customers"; }
    @Override public int order() { return 310; }
    @Override public String title() { return "customers without reseller -> projects in the fallback organization (D1)"; }

    @Override
    public void run(StepContext ctx) {
        if (ctx.props().getTenant().getNoResellerPolicy() != NoResellerPolicy.ASSIGN) {
            log.info("  D1 = SKIP: nothing to do");
            return;
        }
        long orgId = ctx.props().getTenant().getNoResellerOrgId();
        if (ctx.db().findId(ctx.sql().tgt("organizations"), Map.of("id", orgId)) == null)
            throw new IllegalStateException("migration.tenant.no-reseller-org-id=" + orgId + " does not exist in organizations");

        long adminRole = ctx.roles().id(ctx.props().getRoles().getProjectAdminSlug(), "project");
        long agentRole = ctx.roles().id(ctx.props().getRoles().getProjectAgentSlug(), "project");
        int customerRole = ctx.props().getRoles().getCustomerRoleId();
        int agentRoleOld = ctx.props().getRoles().getAgentRoleId();
        StepStats.Entity s = ctx.stats().entity("project");
        Map<Long, long[]> placed = new LinkedHashMap<>();   // old customer id -> [projectId, newUserId]

        for (OldUser u : ctx.tenants().users().values()) {
            if (u.roleId() != customerRole || u.resellerId() != null) continue;
            if (ctx.tenants().resellerByAdmin().containsKey(u.id())) continue;
            s.read++;
            Long userId = ctx.idMap().get("user", u.id());
            if (userId == null) {
                ctx.problems().error("users", u.id(), "USER_NOT_MIGRATED", "customer " + u.email() + " has no 'user' mapping");
                s.errors++;
                continue;
            }
            Long mapped = ctx.idMap().get("project", u.id());
            if (mapped != null) { s.skippedMapped++; placed.put(u.id(), new long[]{mapped, userId}); continue; }

            ctx.tx().row(ctx, "users", u.id(), () -> {
                Memberships.OrgResult om = Memberships.addOrgMember(ctx, orgId, userId, adminRole);
                if (om == Memberships.OrgResult.CONFLICT) {
                    ctx.problems().error("users", u.id(), "ORG_CONFLICT", "new user " + userId + " already belongs to organization "
                            + Memberships.orgOfUser(ctx, userId) + " (organization_users.user_id is unique)");
                    s.errors++;
                    throw new Tx.SkipRow();
                }
                String slug = Text.slugify(Text.emailLocal(u.email()) + "-project-" + u.id());
                String name = Text.firstNonBlank(u.companyName(), (u.name() == null ? "Customer" : u.name()) + "'s Project");
                Map<String, Object> md = new LinkedHashMap<>();
                md.put("migrated_from_user_id", u.id());
                md.put("migration_reason", "NO_RESELLER");
                long[] p = Memberships.findOrCreateProject(ctx, orgId, slug, userId, name, "Migrated project for " + u.name(), md);
                Memberships.addProjectMember(ctx, p[0], userId, adminRole, null);
                ctx.idMap().put("project", u.id(), p[0]);
                if (p[1] == 1) s.inserted++; else s.matchedExisting++;
                placed.put(u.id(), new long[]{p[0], userId});
            });
        }

        // agents created by these customers (Phase 1 could not place them)
        StepStats.Entity a = ctx.stats().entity("agent");
        for (OldUser ag : ctx.tenants().users().values()) {
            if (ag.roleId() != agentRoleOld) continue;
            Long creator = ag.createdBy() != null ? ag.createdBy() : ag.accountAdminId();
            long[] target = creator == null ? null : placed.get(creator);
            if (target == null) continue;
            a.read++;
            Long agentUser = ctx.idMap().get("user", ag.id());
            if (agentUser == null) { a.errors++; continue; }
            ctx.tx().row(ctx, "users", ag.id(), () -> {
                if (Memberships.addOrgMember(ctx, orgId, agentUser, adminRole) == Memberships.OrgResult.CONFLICT) {
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
