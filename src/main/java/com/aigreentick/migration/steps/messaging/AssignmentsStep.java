package com.aigreentick.migration.steps.messaging;

import com.aigreentick.migration.core.*;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 09f — agent_assignments -> conversation_assignments. The conversation is the contact's most recent conversation in
 * the contact owner's project. self / admin -> MANUAL, bot -> AUTO, reassigned -> TRANSFER; status other than active
 * -> unassigned_at. Natural key (conversation_id, assigned_to_id, assigned_at).
 * Afterwards conversations created by the migration take their newest open assignment: assigned USER, and OPEN when
 * migration.messaging.open-if-active-assignment=true (same for their session).
 */
@Component
public class AssignmentsStep implements MigrationStep {

    @Override public String id() { return "09f-assignments"; }
    @Override public int order() { return 960; }
    @Override public String title() { return "agent_assignments -> conversation_assignments"; }

    @Override
    public void run(StepContext ctx) {
        Db db = ctx.db();
        Sql sql = ctx.sql();
        StepStats.Entity s = ctx.stats().entity("conversation_assignment");
        ctx.pages("main", "SELECT aa.*, m.project_id, mc.new_id AS contact_new FROM " + sql.old("agent_assignments") + " aa "
                + "JOIN " + sql.mig("mig_contact") + " m ON m.old_id = aa.contact_id "
                + "JOIN " + sql.mig("migration_id_map") + " mc ON mc.entity = 'contact' AND mc.old_id = aa.contact_id "
                + "WHERE aa.id > :lastId AND aa.deleted_at IS NULL" + ctx.scope().sql("m.project_id") + " ORDER BY aa.id LIMIT :limit",
                Map.of(), "id", page -> {
            for (Row a : page) {
                long oldId = a.lng("id");
                s.read++;
                if (ctx.idMap().has("assignment", oldId)) { s.skippedMapped++; continue; }
                Row conv = db.row("SELECT c.id, mcv.session_id FROM " + sql.tgt("conversations") + " c LEFT JOIN " + sql.mig("mig_conversation")
                        + " mcv ON mcv.conversation_id = c.id AND mcv.inserted = 1 WHERE c.project_id = :p AND c.contact_id = :c "
                        + "ORDER BY c.last_message_at DESC, c.id DESC LIMIT 1", Map.of("p", a.lng("project_id"), "c", a.lng("contact_new")));
                if (conv == null) {
                    ctx.problems().warn("agent_assignments", oldId, "NO_CONVERSATION", "contact has no conversation in project " + a.lng("project_id"));
                    continue;
                }
                Long agent = ctx.idMap().get("user", a.lng("agent_id"));
                if (agent == null) {
                    ctx.problems().error("agent_assignments", oldId, "PARENT_MISSING", "agent user " + a.lng("agent_id") + " not migrated");
                    s.errors++;
                    continue;
                }
                String type = switch (a.str("assignment_type") == null ? "" : a.str("assignment_type")) {
                    case "bot" -> "AUTO";
                    case "reassigned" -> "TRANSFER";
                    default -> "MANUAL";
                };
                ctx.tx().row(ctx, "agent_assignments", oldId, () -> {
                    Long existing = db.findId(sql.tgt("conversation_assignments"), Map.of("conversation_id", conv.lng("id"),
                            "assigned_to_id", agent, "assigned_at", a.dt("assigned_at")));
                    if (existing != null) { ctx.idMap().put("assignment", oldId, existing); s.matchedExisting++; return; }
                    long id = db.insert(sql.tgt("conversation_assignments"), Db.vals().with("assigned_at", a.dt("assigned_at"))
                            .with("assigned_by", ctx.idMap().get("user", a.lng("assigned_by"))).with("assigned_to_id", agent)
                            .with("assigned_to_type", "USER").with("assignment_type", type).with("project_id", a.lng("project_id"))
                            .with("session_id", conv.lng("session_id"))
                            .with("unassigned_at", "active".equals(a.str("status")) ? null : a.dtOr("updated_at", a.dt("assigned_at")))
                            .with("conversation_id", conv.lng("id")));
                    ctx.idMap().put("assignment", oldId, id);
                    s.inserted++;
                });
            }
        });

        boolean open = ctx.props().getMessaging().isOpenIfActiveAssignment();
        StepStats.Entity c = ctx.stats().entity("conversation_assigned");
        ctx.tx().inTx(() -> {
            String latest = "(SELECT a.conversation_id, a.assigned_to_id, a.assigned_at FROM " + sql.tgt("conversation_assignments") + " a "
                    + "JOIN (SELECT conversation_id, MAX(id) aid FROM " + sql.tgt("conversation_assignments") + " WHERE unassigned_at IS NULL "
                    + "GROUP BY conversation_id) x ON x.aid = a.id)";
            c.refreshed += db.exec("UPDATE " + sql.tgt("conversations") + " cv JOIN " + sql.mig("mig_conversation") + " mcv "
                    + "ON mcv.conversation_id = cv.id AND mcv.inserted = 1 JOIN " + latest + " l ON l.conversation_id = cv.id "
                    + "SET cv.assigned_type = 'USER', cv.assigned_id = l.assigned_to_id, cv.assigned_at = l.assigned_at"
                    + (open ? ", cv.status = 'OPEN'" : "") + " WHERE cv.assigned_type = 'UNASSIGNED'", Map.of());
            db.exec("UPDATE " + sql.tgt("conversation_sessions") + " ss JOIN " + sql.mig("mig_conversation") + " mcv "
                    + "ON mcv.session_id = ss.id AND mcv.inserted = 1 JOIN " + latest + " l ON l.conversation_id = ss.conversation_id "
                    + "SET ss.assigned_type = 'USER', ss.assigned_id = l.assigned_to_id"
                    + (open ? ", ss.status = 'OPEN', ss.resolved_at = NULL, ss.close_reason = NULL" : "")
                    + " WHERE ss.assigned_type = 'UNASSIGNED'", Map.of());
        });
    }
}
