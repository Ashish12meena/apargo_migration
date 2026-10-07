package com.aigreentick.migration.steps.messaging;

import com.aigreentick.migration.core.*;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 09d — one conversation per (project, our phone number, contact) found in mig_chat, plus one session.
 * Natural key = the server's UNIQUE (project_id, waba_phone_number_id, contact_id): an existing conversation is reused
 * and gets no new session. New ones start RESOLVED / UNASSIGNED (09f opens and assigns those with an active old
 * assignment). mig_chat.conversation_id is filled for every chat afterwards.
 */
@Component
public class ConversationsStep implements MigrationStep {

    @Override public String id() { return "09d-conversations"; }
    @Override public int order() { return 940; }
    @Override public String title() { return "mig_chat -> conversations + conversation_sessions"; }

    @Override
    public void run(StepContext ctx) {
        Db db = ctx.db();
        Sql sql = ctx.sql();
        StepStats.Entity s = ctx.stats().entity("conversation");
        StepStats.Entity ss = ctx.stats().entity("conversation_session");
        String scope = ctx.scope().sql("project_id");
        List<Row> groups = db.rows("SELECT project_id, waba_phone_number_id, contact_id, MIN(organization_id) org, MIN(waba_account_id) waba, "
                + "MIN(normalized_phone) phone, MIN(created_at) first_at, MAX(created_at) last_at, SUM(direction = 'INBOUND') inbound, "
                + "SUM(direction = 'OUTBOUND') outbound, MAX(CASE WHEN direction = 'INBOUND' THEN created_at END) last_in, "
                + "MAX(CASE WHEN direction = 'OUTBOUND' THEN created_at END) last_out, "
                + "SUBSTRING_INDEX(GROUP_CONCAT(direction ORDER BY created_at, old_id), ',', 1) first_dir "
                + "FROM " + sql.mig("mig_chat") + " WHERE conversation_id IS NULL" + scope
                + " GROUP BY project_id, waba_phone_number_id, contact_id ORDER BY MIN(old_id)");

        int batch = Math.max(100, ctx.props().getBatchSize() / 4);
        for (int i = 0; i < groups.size(); i += batch) {
            List<Row> slice = groups.subList(i, Math.min(groups.size(), i + batch));
            ctx.tx().inTx(() -> {
                for (Row g : slice) {
                    s.read++;
                    ctx.tx().row(ctx, "mig_chat", g.lng("contact_id"), () -> one(ctx, g, s, ss));
                }
            });
        }
        // every chat -> its conversation
        ctx.tx().inTx(() -> db.exec("UPDATE " + sql.mig("mig_chat") + " mc JOIN " + sql.tgt("conversations") + " c "
                + "ON c.project_id = mc.project_id AND c.waba_phone_number_id = mc.waba_phone_number_id AND c.contact_id = mc.contact_id "
                + "SET mc.conversation_id = c.id WHERE mc.conversation_id IS NULL"));
        long orphan = db.count("SELECT COUNT(*) FROM " + sql.mig("mig_chat") + " WHERE conversation_id IS NULL" + scope);
        ctx.problems().aggregate(ProblemLog.Severity.ERROR, "mig_chat", "NO_CONVERSATION", orphan, "chats without a conversation");
    }

    private void one(StepContext ctx, Row g, StepStats.Entity s, StepStats.Entity ss) {
        Db db = ctx.db();
        Sql sql = ctx.sql();
        long project = g.lng("project_id"), phone = g.lng("waba_phone_number_id"), contact = g.lng("contact_id");
        Long existing = db.findId(sql.tgt("conversations"), Map.of("project_id", project, "waba_phone_number_id", phone, "contact_id", contact));
        if (existing != null) {
            s.matchedExisting++;
            db.exec("INSERT INTO " + sql.mig("mig_conversation") + " (conversation_id, project_id, session_id, inserted) VALUES (:c, :p, NULL, 0) "
                    + "ON DUPLICATE KEY UPDATE project_id = project_id", Map.of("c", existing, "p", project));
            return;
        }
        LocalDateTime first = g.dtOr("first_at", StepContext.now()), last = g.dtOr("last_at", first);
        LocalDateTime lastIn = g.dt("last_in"), lastOut = g.dt("last_out");
        String lastDir = lastIn == null ? "OUTBOUND" : lastOut == null ? "INBOUND" : lastIn.isAfter(lastOut) ? "INBOUND" : "OUTBOUND";
        long conv = db.insert(sql.tgt("conversations"), Db.vals().with("created_at", first).with("updated_at", last)
                .with("assigned_type", "UNASSIGNED").with("contact_id", contact).with("last_inbound_at", lastIn)
                .with("last_message_at", last).with("last_message_direction", lastDir).with("last_outbound_at", lastOut)
                .with("normalized_phone", g.str("phone")).with("organization_id", g.lng("org")).with("project_id", project)
                .with("status", "RESOLVED").with("unread_count", 0).with("uuid", UUID.randomUUID().toString()).with("version", 0)
                .with("waba_account_id", g.lng("waba")).with("waba_phone_number_id", phone)
                .with("window_expires_at", lastIn == null ? null : lastIn.plusHours(24)));
        long session = db.insert(sql.tgt("conversation_sessions"), Db.vals().with("assigned_type", "UNASSIGNED")
                .with("close_reason", "AUTO_CLOSED").with("inbound_count", g.lng("inbound", 0)).with("last_activity_at", last)
                .with("opened_at", first).with("opened_reason", "INBOUND".equals(g.str("first_dir")) ? "INBOUND" : "AGENT_OUTREACH")
                .with("organization_id", g.lng("org")).with("outbound_count", g.lng("outbound", 0)).with("project_id", project)
                .with("resolved_at", last).with("session_number", 1).with("status", "RESOLVED").with("uuid", UUID.randomUUID().toString())
                .with("version", 0).with("conversation_id", conv));
        db.update(sql.tgt("conversations"), conv, Db.vals().with("current_session_id", session));
        db.exec("INSERT INTO " + sql.mig("mig_conversation") + " (conversation_id, project_id, session_id, inserted) VALUES (:c, :p, :s, 1)",
                Map.of("c", conv, "p", project, "s", session));
        s.inserted++;
        ss.inserted++;
    }
}
