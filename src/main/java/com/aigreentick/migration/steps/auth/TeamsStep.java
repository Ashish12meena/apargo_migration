package com.aigreentick.migration.steps.auth;

import com.aigreentick.migration.config.MigrationProperties;
import com.aigreentick.migration.core.*;
import com.aigreentick.migration.util.Text;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 03d (D8, server schema S6) — {@code agent_teams} -> {@code project_teams}, {@code agent_team_members} ->
 * {@code team_members}. team_members.team_role_id is NOT NULL and project_team_roles is empty on the server, so two
 * project-level roles (team_id NULL) are seeded per project first: Team Leader (old role 'admin') and Support Agent
 * (old role 'agent', is_default). Members must already be members of the team's project.
 */
@Component
public class TeamsStep implements MigrationStep {

    @Override public String id() { return "03d-teams"; }
    @Override public int order() { return 340; }
    @Override public String title() { return "agent_teams / members -> project_teams / team_members"; }

    @Override
    public void run(StepContext ctx) {
        Db db = ctx.db();
        Sql sql = ctx.sql();
        MigrationProperties.Teams cfg = ctx.props().getTeams();
        Map<Long, long[]> rolesByProject = new HashMap<>();   // project -> [leadRoleId, memberRoleId]
        StepStats.Entity s = ctx.stats().entity("team");

        for (Row t : db.rows("SELECT * FROM " + sql.old("agent_teams") + " ORDER BY id")) {
            long oldId = t.lng("id");
            s.read++;
            if (ctx.idMap().has("team", oldId)) { s.skippedMapped++; continue; }
            Tenant ten = ctx.place("agent_teams", oldId, t.lng("created_by"));
            if (ten == null) continue;
            // team roles first, in their own unit, so a failed team row cannot roll back cached role ids
            if (!rolesByProject.containsKey(ten.projectId())
                    && !ctx.tx().row(ctx, "project_team_roles", ten.projectId(), () -> rolesFor(ctx, ten.projectId(), ten.userId(), cfg, rolesByProject))) {
                rolesByProject.remove(ten.projectId());
                continue;
            }
            ctx.tx().row(ctx, "agent_teams", oldId, () -> {
                String name = Text.cut(Text.firstNonBlank(t.str("name"), "Team " + oldId), 150);
                Long existing = db.findId(sql.tgt("project_teams"), Map.of("project_id", ten.projectId(), "name", name));
                if (existing != null) {
                    ctx.idMap().put("team", oldId, existing);
                    s.matchedExisting++;
                    return;
                }
                String slug = uniqueSlug(ctx, ten.projectId(), Text.slugify(name).isEmpty() ? "team-" + oldId : Text.slugify(name));
                Long dept = ctx.idMap().get("department", t.lng("department_id"));
                if (dept != null) {
                    Row d = db.findRow(sql.tgt("project_departments"), Map.of("id", dept), "project_id");
                    if (d == null || !Objects.equals(d.lng("project_id"), ten.projectId())) dept = null;
                }
                boolean active = t.bool("is_active") && t.isNull("deleted_at");
                long id = db.insert(sql.tgt("project_teams"), Db.vals().with("project_id", ten.projectId())
                        .with("name", name).with("slug", slug).with("description", t.str("description"))
                        .with("status", active ? "active" : "archived").with("access_level", "team").with("level", 0)
                        .with("department_id", dept).with("created_by", ten.userId())
                        .with("created_at", t.dtOr("created_at", StepContext.nowSec()))
                        .with("updated_at", t.dtOr("updated_at", StepContext.nowSec())));
                db.update(sql.tgt("project_teams"), id, Db.vals().with("path", "/" + id + "/"));
                ctx.idMap().put("team", oldId, id);
                s.inserted++;
            });
        }

        StepStats.Entity m = ctx.stats().entity("team_member");
        for (Row r : db.rows("SELECT * FROM " + sql.old("agent_team_members") + " ORDER BY id")) {
            m.read++;
            long oldId = r.lng("id");
            Long teamId = ctx.idMap().get("team", r.lng("team_id"));
            if (teamId == null) {
                if (!ctx.scope().isPilot()) {
                    ctx.problems().error("agent_team_members", oldId, "PARENT_MISSING", "team " + r.lng("team_id") + " not migrated");
                    m.errors++;
                }
                continue;
            }
            Row team = db.findRow(sql.tgt("project_teams"), Map.of("id", teamId), "id, project_id, created_by");
            if (team == null || !ctx.scope().includesProject(team.lng("project_id"))) continue;
            Long userId = ctx.idMap().get("user", r.lng("agent_id"));
            if (userId == null) {
                ctx.problems().error("agent_team_members", oldId, "PARENT_MISSING", "agent user " + r.lng("agent_id") + " not migrated");
                m.errors++;
                continue;
            }
            long projectId = team.lng("project_id");
            if (db.findId(sql.tgt("team_members"), Map.of("team_id", teamId, "user_id", userId)) != null) { m.matchedExisting++; continue; }
            if (db.findId(sql.tgt("project_members"), Map.of("project_id", projectId, "user_id", userId)) == null) {
                ctx.problems().error("agent_team_members", oldId, "NOT_PROJECT_MEMBER", "user " + userId + " is not a member of project " + projectId);
                m.errors++;
                continue;
            }
            if (!rolesByProject.containsKey(projectId)
                    && !ctx.tx().row(ctx, "project_team_roles", projectId, () -> rolesFor(ctx, projectId, team.lng("created_by"), cfg, rolesByProject))) {
                rolesByProject.remove(projectId);
                continue;
            }
            long[] roles = rolesByProject.get(projectId);
            ctx.tx().row(ctx, "agent_team_members", oldId, () -> {
                boolean removed = !r.isNull("deleted_at");
                db.insert(sql.tgt("team_members"), Db.vals().with("team_id", teamId).with("user_id", userId)
                        .with("team_role_id", "admin".equalsIgnoreCase(r.str("role")) ? roles[0] : roles[1])
                        .with("status", removed ? "removed" : "active").with("added_by", team.lng("created_by"))
                        .with("added_at", r.dtOr("joined_at", r.dt("created_at")))
                        .with("removed_at", removed ? r.dt("deleted_at") : null));
                m.inserted++;
            });
        }
    }

    /** project-level team roles (team_id NULL), created once per project */
    private static long[] rolesFor(StepContext ctx, long projectId, Long createdBy, MigrationProperties.Teams cfg,
                                   Map<Long, long[]> cache) {
        long[] cached = cache.get(projectId);
        if (cached != null) return cached;
        long lead = role(ctx, projectId, cfg.getLeadRoleSlug(), cfg.getLeadRoleName(), false, createdBy);
        long member = role(ctx, projectId, cfg.getMemberRoleSlug(), cfg.getMemberRoleName(), true, createdBy);
        long[] r = {lead, member};
        cache.put(projectId, r);
        return r;
    }

    private static long role(StepContext ctx, long projectId, String slug, String name, boolean isDefault, Long createdBy) {
        Long id = ctx.db().findId(ctx.sql().tgt("project_team_roles"),
                Db.vals().with("slug", slug).with("project_id", projectId).with("team_id", null).with("deleted_at", null));
        if (id != null) return id;
        LocalDateTime now = StepContext.nowSec();
        return ctx.db().insert(ctx.sql().tgt("project_team_roles"), Db.vals().with("uuid", UUID.randomUUID().toString())
                .with("team_id", null).with("project_id", projectId).with("name", name).with("slug", slug)
                .with("description", "Created by the legacy migration").with("created_by", createdBy)
                .with("is_default", isDefault).with("is_system", true).with("status", "active")
                .with("created_at", now).with("updated_at", now));
    }

    private static String uniqueSlug(StepContext ctx, long projectId, String base) {
        String slug = Text.cut(base, 140);
        for (int i = 2; ctx.db().findId(ctx.sql().tgt("project_teams"), Map.of("project_id", projectId, "slug", slug)) != null; i++)
            slug = Text.cut(base, 140) + "-" + i;
        return slug;
    }
}
