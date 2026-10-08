package com.aigreentick.migration.steps.auth;

import com.aigreentick.migration.config.MigrationProperties;
import com.aigreentick.migration.core.*;
import com.aigreentick.migration.util.Text;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 03d (decided 2026-10-08) — old live-chat agent teams go to the MESSAGING service's teams:
 * <pre>
 *  agent_teams        -> teams        (project of the team creator, routing_strategy MANUAL)
 *  agent_team_members -> team_member  (role admin -> LEAD, agent -> MEMBER; table name: migration.teams.member-table)
 * </pre>
 * The messaging member table is called {@code team_member} (not {@code team_members}): the Organization service owns a
 * different {@code team_members} table (referenced by project_team_member_permissions), so the two cannot share a name.
 * <ul>
 *   <li>Natural keys: team = (project_id, live name); member = (team_id, user_id).</li>
 *   <li>Removed members (old deleted_at set) are not migrated: the messaging table has no "removed" state (INFO).</li>
 *   <li>A member must be a member of the team's project (project_members), else ERROR NOT_PROJECT_MEMBER.</li>
 *   <li>Missing tables -> ERROR TABLE_MISSING and the step ends; the rest of the run goes on.</li>
 * </ul>
 * Map entity: messaging_team (old agent_teams.id -> teams.id).
 */
@Component
public class TeamsStep implements MigrationStep {

    @Override public String id() { return "03d-teams"; }
    @Override public int order() { return 340; }
    @Override public String title() { return "agent_teams / members -> messaging teams / team_member"; }

    @Override
    public void run(StepContext ctx) {
        Db db = ctx.db();
        Sql sql = ctx.sql();
        MigrationProperties.Teams cfg = ctx.props().getTeams();
        String memberTable = cfg.getMemberTable();
        if (!db.tableExists(sql.tgtSchema(), "teams")) {
            ctx.problems().error("agent_teams", null, "TABLE_MISSING", "messaging table teams does not exist: teams not migrated");
            return;
        }
        StepStats.Entity s = ctx.stats().entity("team");

        for (Row t : db.rows("SELECT * FROM " + sql.old("agent_teams") + " ORDER BY id")) {
            long oldId = t.lng("id");
            s.read++;
            if (ctx.idMap().has("messaging_team", oldId)) { s.skippedMapped++; continue; }
            Tenant ten = ctx.place("agent_teams", oldId, t.lng("created_by"));
            if (ten == null) continue;
            ctx.tx().row(ctx, "agent_teams", oldId, () -> {
                String name = Text.cut(Text.firstNonBlank(t.str("name"), "Team " + oldId), 150);
                Long existing = db.longValue("SELECT id FROM " + sql.tgt("teams") + " WHERE project_id = :p AND name = :n "
                        + "AND deleted_at IS NULL ORDER BY id LIMIT 1", Map.of("p", ten.projectId(), "n", name));
                if (existing != null) {
                    ctx.idMap().put("messaging_team", oldId, existing);
                    s.matchedExisting++;
                    return;
                }
                boolean active = t.bool("is_active") && t.isNull("deleted_at");
                long id = db.insert(sql.tgt("teams"), Db.vals().with("created_at", t.dtOr("created_at", StepContext.now()))
                        .with("updated_at", t.dtOr("updated_at", StepContext.now())).with("deleted_at", null)
                        .with("is_active", active).with("description", Text.cut(t.str("description"), 500))
                        .with("max_per_agent", null).with("name", name).with("organization_id", ten.orgId())
                        .with("project_id", ten.projectId()).with("routing_strategy", cfg.getRoutingStrategy())
                        .with("uuid", UUID.randomUUID().toString()));
                ctx.idMap().put("messaging_team", oldId, id);
                s.inserted++;
            });
        }

        StepStats.Entity m = ctx.stats().entity("team_member");
        if (!db.tableExists(sql.tgtSchema(), memberTable)) {
            ctx.problems().error("agent_team_members", null, "TABLE_MISSING",
                    memberTable + " does not exist on the target: team members not migrated (create it, re-run 03d)");
            return;
        }
        for (Row r : db.rows("SELECT * FROM " + sql.old("agent_team_members") + " ORDER BY id")) {
            m.read++;
            long oldId = r.lng("id");
            if (!r.isNull("deleted_at")) {
                ctx.problems().info("agent_team_members", oldId, "REMOVED_SKIPPED", "member was removed from the team in the old system");
                continue;
            }
            Long teamId = ctx.idMap().get("messaging_team", r.lng("team_id"));
            if (teamId == null) {
                if (!ctx.scope().isPilot()) {
                    ctx.problems().error("agent_team_members", oldId, "PARENT_MISSING", "team " + r.lng("team_id") + " not migrated");
                    m.errors++;
                }
                continue;
            }
            Row team = db.findRow(sql.tgt("teams"), Map.of("id", teamId), "id, project_id");
            if (team == null || !ctx.scope().includesProject(team.lng("project_id"))) continue;
            Long userId = ctx.idMap().get("user", r.lng("agent_id"));
            if (userId == null) {
                ctx.problems().error("agent_team_members", oldId, "PARENT_MISSING", "agent user " + r.lng("agent_id") + " not migrated");
                m.errors++;
                continue;
            }
            long projectId = team.lng("project_id");
            if (db.count("SELECT COUNT(*) FROM " + sql.tgt(memberTable) + " WHERE team_id = :t AND user_id = :u",
                    Map.of("t", teamId, "u", userId)) > 0) { m.matchedExisting++; continue; }
            if (db.findId(sql.tgt("project_members"), Map.of("project_id", projectId, "user_id", userId)) == null) {
                ctx.problems().error("agent_team_members", oldId, "NOT_PROJECT_MEMBER", "user " + userId + " is not a member of project " + projectId);
                m.errors++;
                continue;
            }
            ctx.tx().row(ctx, "agent_team_members", oldId, () -> {
                db.insertNoKey(sql.tgt(memberTable), Db.vals().with("team_id", teamId).with("user_id", userId)
                        .with("role", "admin".equalsIgnoreCase(r.str("role")) ? "LEAD" : "MEMBER")
                        .with("active_conversations", 0).with("is_available", true)
                        .with("added_at", r.dtOr("joined_at", r.dtOr("created_at", StepContext.now()))));
                m.inserted++;
            });
        }
    }
}
