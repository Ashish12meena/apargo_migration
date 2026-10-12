package com.aigreentick.migration.steps.messaging;

import com.aigreentick.migration.config.MigrationProperties;
import com.aigreentick.migration.core.*;
import com.aigreentick.migration.util.Enums;
import com.aigreentick.migration.util.Json;
import com.aigreentick.migration.util.Text;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 09l — every migrated campaign recipient (old {@code reports} row) becomes the campaign's template message in the
 * contact's chat, as the new system does for every campaign send:
 * <pre>
 *  broadcast_recipients (campaign_id, contact_id) ──message_id──> messages (campaign_id, contact_id)  [UNIQUE pair]
 *                                                                    └── conversation (project, campaign number, contact)
 * </pre>
 * <ul>
 *   <li>Only recipients of migrated campaigns with state SENT / FAILED and {@code message_id} NULL (CANCELLED = never sent).</li>
 *   <li>Link instead of insert: a message with the same (campaign_id, contact_id), or with the same wamid (e.g. the send
 *       is also in old {@code chats}), is reused.</li>
 *   <li>Conversation = (campaign project, campaign phone number, contact); created when missing (RESOLVED, one session,
 *       {@code source_campaign_id}), recorded in {@code mig_conversation} like 09d.</li>
 *   <li>Status SENT / DELIVERED / READ / FAILED from the old report (status, else message_status) found by wamid; without
 *       one, from the recipient state. delivered_at / read_at / failed_at = old report updated_at.</li>
 *   <li>At the end, conversations created by the migration get their last-message times / session counts moved forward
 *       from all their messages.</li>
 * </ul>
 * Re-runnable: processed recipients have message_id set; a second run finds nothing.
 */
@Component
public class CampaignMessagesStep implements MigrationStep {

    @Override public String id() { return "09l-campaign-messages"; }
    @Override public int order() { return 992; }
    @Override public String title() { return "broadcast_recipients (old reports) -> messages in the contacts' conversations"; }

    private static final Map<String, Integer> RANK = Map.of("SENT", 1, "DELIVERED", 2, "READ", 3, "FAILED", 0);

    @Override
    public void run(StepContext ctx) {
        Db db = ctx.db();
        Sql sql = ctx.sql();
        MigrationProperties.Messaging cfg = ctx.props().getMessaging();
        StepStats.Entity sm = ctx.stats().entity("campaign_message");
        StepStats.Entity sc = ctx.stats().entity("conversation");
        StepStats.Entity sw = ctx.stats().entity("message_wamid");
        long[] unknown = {0};

        ctx.pages("main", "SELECT br.id, br.campaign_id, br.contact_id, br.normalized_phone, br.provider_message_id, br.state, "
                + "br.created_at, br.sent_at, br.terminal_at, br.fail_reason, br.template_params, "
                + "b.project_id, b.organization_id, b.waba_account_id, b.waba_phone_number_id, b.template_name, b.template_language, "
                + "b.created_by FROM " + sql.tgt("broadcast_recipients") + " br "
                + "JOIN " + sql.tgt("broadcast_campaigns") + " b ON b.id = br.campaign_id "
                + "WHERE br.id > :lastId AND br.message_id IS NULL AND br.state IN ('SENT', 'FAILED') "
                + "AND br.campaign_id IN (SELECT new_id FROM " + sql.mig("migration_id_map") + " WHERE entity = 'campaign')"
                + ctx.scope().sql("b.project_id") + " ORDER BY br.id LIMIT :limit", Map.of(), "id", page -> {

            // 1. reuse: message with the same (campaign, contact) or the same wamid
            List<Object[]> pairs = page.stream().map(r -> new Object[]{r.lng("campaign_id"), r.lng("contact_id")}).toList();
            Map<String, Long> byPair = new HashMap<>();
            for (Row m : db.rows("SELECT id, campaign_id, contact_id FROM " + sql.tgt("messages") + " WHERE (campaign_id, contact_id) IN (:p)",
                    Map.of("p", pairs)))
                byPair.put(m.lng("campaign_id") + ":" + m.lng("contact_id"), m.lng("id"));
            Set<String> wamids = new HashSet<>();
            for (Row r : page) if (r.text("provider_message_id") != null) wamids.add(r.text("provider_message_id"));
            Map<String, Long> byWamid = new HashMap<>();
            Map<String, Row> report = new HashMap<>();
            if (!wamids.isEmpty()) {
                for (Row w : db.rows("SELECT provider_message_id, message_id FROM " + sql.tgt("message_wamid") + " WHERE provider_message_id IN (:w)",
                        Map.of("w", wamids)))
                    byWamid.put(w.str("provider_message_id"), w.lng("message_id"));
                // old report of each wamid (best status wins when a wamid has several rows)
                for (Row o : db.rows("SELECT id, message_id, status, message_status, updated_at FROM " + sql.old("reports")
                        + " WHERE message_id IN (:w)", Map.of("w", wamids))) {
                    String k = o.str("message_id");
                    Row prev = report.get(k);
                    if (prev == null || rank(cfg, o) > rank(cfg, prev)) report.put(k, o);
                }
            }

            Map<Long, Long> link = new LinkedHashMap<>();        // recipient id -> existing message id
            List<Row> todo = new ArrayList<>();
            for (Row r : page) {
                sm.read++;
                Long m = byPair.get(r.lng("campaign_id") + ":" + r.lng("contact_id"));
                if (m == null && r.text("provider_message_id") != null) m = byWamid.get(r.text("provider_message_id"));
                if (m != null) { link.put(r.lng("id"), m); sm.matchedExisting++; }
                else todo.add(r);
            }

            // 2. conversations (project, number, contact): find, create the missing ones
            Map<String, long[]> conv = conversations(ctx, todo, sc);     // key -> [conversation id, session id]

            // 3. messages
            List<Map<String, ?>> rows = new ArrayList<>();
            for (Row r : todo) {
                long[] c = conv.get(key(r));
                if (c == null) continue;
                Row old = r.text("provider_message_id") == null ? null : report.get(r.text("provider_message_id"));
                String status = status(cfg, r, old);
                if (old != null && status == null) unknown[0]++;
                if (status == null) status = "FAILED".equals(r.str("state")) ? "FAILED" : "SENT";
                LocalDateTime created = r.dtOr("created_at", StepContext.now());
                LocalDateTime changed = old != null ? old.dtOr("updated_at", r.dtOr("terminal_at", created)) : r.dtOr("terminal_at", created);
                boolean sent = !"FAILED".equals(status);

                ObjectNode payload = Json.obj();
                ObjectNode tpl = payload.putObject("template");
                tpl.put("name", r.str("template_name"));
                tpl.put("language", r.str("template_language"));
                JsonNode params = Json.parse(r.str("template_params"));
                if (params != null) payload.set("template_params", params);
                ObjectNode legacy = payload.putObject("legacy");
                if (old != null) {
                    legacy.put("report_id", old.lng("id"));
                    legacy.put("status", old.str("status"));
                    legacy.put("message_status", old.str("message_status"));
                }
                legacy.put("source", "reports");

                rows.add(Db.vals().with("attempts", 1).with("body_text", Text.cut(r.str("template_name"), 4096))
                        .with("campaign_id", r.lng("campaign_id")).with("contact_id", r.lng("contact_id"))
                        .with("context_wamid", null).with("created_at", created).with("created_by_id", r.lng("created_by"))
                        .with("created_by_type", "CAMPAIGN")
                        .with("delivered_at", "DELIVERED".equals(status) || "READ".equals(status) ? changed : null)
                        .with("direction", "OUTBOUND")
                        .with("error_title", sent ? null : Text.cut(Text.firstNonBlank(r.str("fail_reason"), "legacy: failed"), 255))
                        .with("failed_at", sent ? null : changed).with("media_status", null).with("media_url", null)
                        .with("message_type", "TEMPLATE").with("organization_id", r.lng("organization_id"))
                        .with("payload", Json.write(payload)).with("project_id", r.lng("project_id")).with("provider_media_id", null)
                        .with("provider_message_id", r.text("provider_message_id")).with("read_at", "READ".equals(status) ? changed : null)
                        .with("sent_at", sent ? r.dtOr("sent_at", created) : null).with("session_id", c[1] == 0 ? null : c[1]).with("status", status)
                        .with("updated_at", changed).with("waba_account_id", r.lng("waba_account_id"))
                        .with("waba_phone_number_id", r.lng("waba_phone_number_id")).with("conversation_id", c[0]));
            }
            sm.inserted += ctx.insertBatchSafe(sql.tgt("messages"), rows, null, "broadcast_recipients", "campaign_id", "contact_id");

            // 4. ids of the new messages (UNIQUE campaign_id + contact_id), wamid rows, recipient links
            if (!todo.isEmpty()) {
                List<Object[]> newPairs = todo.stream().map(r -> new Object[]{r.lng("campaign_id"), r.lng("contact_id")}).toList();
                Map<String, Row> created = new HashMap<>();
                for (Row m : db.rows("SELECT id, campaign_id, contact_id, conversation_id, provider_message_id, created_at FROM " + sql.tgt("messages")
                        + " WHERE (campaign_id, contact_id) IN (:p)", Map.of("p", newPairs)))
                    created.put(m.lng("campaign_id") + ":" + m.lng("contact_id"), m);
                List<Map<String, ?>> wam = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                for (Row r : todo) {
                    Row m = created.get(r.lng("campaign_id") + ":" + r.lng("contact_id"));
                    if (m == null) continue;
                    link.put(r.lng("id"), m.lng("id"));
                    String w = m.text("provider_message_id");
                    if (w != null && !byWamid.containsKey(w) && seen.add(w))
                        wam.add(Db.vals().with("provider_message_id", w).with("conversation_id", m.lng("conversation_id"))
                                .with("created_at", m.dt("created_at")).with("direction", "OUTBOUND").with("message_id", m.lng("id")));
                }
                sw.inserted += ctx.insertBatchSafe(sql.tgt("message_wamid"), wam, "ON DUPLICATE KEY UPDATE message_id = message_id",
                        "broadcast_recipients", "provider_message_id");
            }
            if (!link.isEmpty()) {
                StringBuilder cases = new StringBuilder();
                for (Map.Entry<Long, Long> e : link.entrySet()) cases.append(" WHEN ").append(e.getKey()).append(" THEN ").append(e.getValue());
                db.exec("UPDATE " + sql.tgt("broadcast_recipients") + " SET message_id = CASE id" + cases + " END "
                        + "WHERE id IN (:ids) AND message_id IS NULL", Map.of("ids", link.keySet()));
            }
        });
        ctx.problems().aggregate(ProblemLog.Severity.WARN, "reports", "ENUM_UNKNOWN", unknown[0],
                "report status / message_status not in migration.messaging.report-status: status taken from the recipient state");
        refreshSummaries(ctx);
    }

    private static String key(Row r) {
        return r.lng("project_id") + ":" + r.lng("waba_phone_number_id") + ":" + r.lng("contact_id");
    }

    private static int rank(MigrationProperties.Messaging cfg, Row o) {
        String s = mapped(cfg, o);
        return s == null ? -1 : RANK.getOrDefault(s, -1);
    }

    private static String mapped(MigrationProperties.Messaging cfg, Row o) {
        String a = Enums.map(cfg.getReportStatus(), o.str("status"));
        String b = Enums.map(cfg.getReportStatus(), o.str("message_status"));
        // the better of the two columns (read > delivered > sent); FAILED only when nothing better
        String best = null;
        for (String s : new String[]{a, b}) {
            if (s == null || "CANCELLED".equals(s)) continue;
            if (best == null || RANK.getOrDefault(s, -1) > RANK.getOrDefault(best, -1)) best = s;
        }
        return best;
    }

    /** message status from the old report; null = unknown (caller falls back to the recipient state) */
    private static String status(MigrationProperties.Messaging cfg, Row r, Row old) {
        if (old == null) return null;
        String s = mapped(cfg, old);
        if (s == null) return null;
        if ("FAILED".equals(r.str("state")) && !"FAILED".equals(s)) return "FAILED";   // recipient says failed: keep it
        return s;
    }

    /** finds or creates the conversation (+ first session) of every (project, number, contact) of the page */
    private Map<String, long[]> conversations(StepContext ctx, List<Row> todo, StepStats.Entity sc) {
        Db db = ctx.db();
        Sql sql = ctx.sql();
        Map<String, List<Row>> byKey = new LinkedHashMap<>();
        for (Row r : todo) byKey.computeIfAbsent(key(r), k -> new ArrayList<>()).add(r);
        Map<String, long[]> out = new HashMap<>();
        if (byKey.isEmpty()) return out;
        List<Object[]> keys = byKey.values().stream()
                .map(l -> new Object[]{l.get(0).lng("project_id"), l.get(0).lng("waba_phone_number_id"), l.get(0).lng("contact_id")}).toList();
        String find = "SELECT id, current_session_id, project_id, waba_phone_number_id, contact_id FROM " + sql.tgt("conversations")
                + " WHERE (project_id, waba_phone_number_id, contact_id) IN (:k)";
        for (Row c : db.rows(find, Map.of("k", keys)))
            out.put(c.lng("project_id") + ":" + c.lng("waba_phone_number_id") + ":" + c.lng("contact_id"),
                    new long[]{c.lng("id"), c.lng("current_session_id") == null ? 0 : c.lng("current_session_id")});
        sc.matchedExisting += out.size();

        List<Map<String, ?>> convRows = new ArrayList<>();
        for (Map.Entry<String, List<Row>> e : byKey.entrySet()) {
            if (out.containsKey(e.getKey())) continue;
            List<Row> l = e.getValue();
            LocalDateTime first = l.stream().map(r -> r.dtOr("created_at", StepContext.now())).min(Comparator.naturalOrder()).orElseThrow();
            LocalDateTime last = l.stream().map(r -> r.dtOr("created_at", StepContext.now())).max(Comparator.naturalOrder()).orElseThrow();
            Row r = l.get(0);
            convRows.add(Db.vals().with("created_at", first).with("updated_at", last).with("assigned_type", "UNASSIGNED")
                    .with("contact_id", r.lng("contact_id")).with("last_inbound_at", null).with("last_message_at", last)
                    .with("last_message_direction", "OUTBOUND").with("last_outbound_at", last)
                    .with("normalized_phone", Text.cut(r.str("normalized_phone"), 30)).with("organization_id", r.lng("organization_id"))
                    .with("project_id", r.lng("project_id")).with("status", "RESOLVED").with("unread_count", 0)
                    .with("uuid", UUID.randomUUID().toString()).with("version", 0).with("waba_account_id", r.lng("waba_account_id"))
                    .with("waba_phone_number_id", r.lng("waba_phone_number_id")).with("window_expires_at", null));
        }
        if (convRows.isEmpty()) return out;
        sc.inserted += ctx.insertBatchSafe(sql.tgt("conversations"), convRows, null, "broadcast_recipients", "contact_id", "project_id");

        // ids of the new conversations, then one RESOLVED session each
        List<Map<String, ?>> sessions = new ArrayList<>();
        Map<Long, String> keyOfConv = new HashMap<>();
        for (Row c : db.rows(find, Map.of("k", keys))) {
            String k = c.lng("project_id") + ":" + c.lng("waba_phone_number_id") + ":" + c.lng("contact_id");
            if (out.containsKey(k)) continue;
            List<Row> l = byKey.get(k);
            LocalDateTime first = l.stream().map(r -> r.dtOr("created_at", StepContext.now())).min(Comparator.naturalOrder()).orElseThrow();
            LocalDateTime last = l.stream().map(r -> r.dtOr("created_at", StepContext.now())).max(Comparator.naturalOrder()).orElseThrow();
            keyOfConv.put(c.lng("id"), k);
            out.put(k, new long[]{c.lng("id"), 0});
            sessions.add(Db.vals().with("assigned_type", "UNASSIGNED").with("close_reason", "AUTO_CLOSED").with("inbound_count", 0)
                    .with("last_activity_at", last).with("opened_at", first).with("opened_reason", "AGENT_OUTREACH")
                    .with("organization_id", l.get(0).lng("organization_id")).with("outbound_count", l.size())
                    .with("project_id", l.get(0).lng("project_id")).with("resolved_at", last).with("session_number", 1)
                    .with("source_campaign_id", l.get(0).lng("campaign_id")).with("status", "RESOLVED")
                    .with("uuid", UUID.randomUUID().toString()).with("version", 0).with("conversation_id", c.lng("id")));
        }
        if (sessions.isEmpty()) return out;
        ctx.insertBatchSafe(sql.tgt("conversation_sessions"), sessions, null, "broadcast_recipients", "conversation_id");
        List<Map<String, ?>> mc = new ArrayList<>();
        for (Row s : db.rows("SELECT id, conversation_id FROM " + sql.tgt("conversation_sessions") + " WHERE conversation_id IN (:c) AND session_number = 1",
                Map.of("c", keyOfConv.keySet()))) {
            out.get(keyOfConv.get(s.lng("conversation_id")))[1] = s.lng("id");
            mc.add(Db.vals().with("conversation_id", s.lng("conversation_id"))
                    .with("project_id", Long.parseLong(keyOfConv.get(s.lng("conversation_id")).split(":")[0]))
                    .with("session_id", s.lng("id")).with("inserted", 1));
        }
        db.exec("UPDATE " + sql.tgt("conversations") + " c JOIN " + sql.tgt("conversation_sessions") + " s ON s.conversation_id = c.id "
                + "AND s.session_number = 1 SET c.current_session_id = s.id WHERE c.id IN (:c) AND c.current_session_id IS NULL",
                Map.of("c", keyOfConv.keySet()));
        ctx.insertBatchSafe(sql.mig("mig_conversation"), mc, "ON DUPLICATE KEY UPDATE session_id = VALUES(session_id)",
                "broadcast_recipients", "conversation_id");
        return out;
    }

    /** conversations created by the migration: last-message times / session counts from all their messages (only forward) */
    private void refreshSummaries(StepContext ctx) {
        Db db = ctx.db();
        Sql sql = ctx.sql();
        String li = "GREATEST(COALESCE(c.last_inbound_at, a.last_in), COALESCE(a.last_in, c.last_inbound_at))";
        String lo = "GREATEST(COALESCE(c.last_outbound_at, a.last_out), COALESCE(a.last_out, c.last_outbound_at))";
        String agg = "(SELECT m.conversation_id, MIN(m.created_at) first_at, MAX(m.created_at) last_at, "
                + "MAX(CASE WHEN m.direction = 'INBOUND' THEN m.created_at END) last_in, MAX(CASE WHEN m.direction = 'OUTBOUND' THEN m.created_at END) last_out, "
                + "SUM(m.direction = 'INBOUND') inbound, SUM(m.direction = 'OUTBOUND') outbound FROM " + sql.tgt("messages") + " m "
                + "JOIN " + sql.mig("mig_conversation") + " x ON x.conversation_id = m.conversation_id AND x.inserted = 1 GROUP BY m.conversation_id) a";
        int[] n = new int[2];
        ctx.tx().inTx(() -> {
            n[0] = db.exec("UPDATE " + sql.tgt("conversations") + " c JOIN " + agg + " ON a.conversation_id = c.id "
                    + "SET c.last_message_direction = IF(" + li + " IS NOT NULL AND (" + lo + " IS NULL OR " + li + " > " + lo + "), 'INBOUND', 'OUTBOUND'), "
                    + "c.last_inbound_at = " + li + ", c.last_outbound_at = " + lo + ", "
                    + "c.last_message_at = GREATEST(COALESCE(c.last_message_at, a.last_at), COALESCE(a.last_at, c.last_message_at)), "
                    + "c.created_at = LEAST(c.created_at, COALESCE(a.first_at, c.created_at)) "
                    + "WHERE NOT (c.last_inbound_at <=> " + li + " AND c.last_outbound_at <=> " + lo + " AND c.last_message_at >= a.last_at "
                    + "AND c.created_at <= a.first_at)", Map.of());
            n[1] = db.exec("UPDATE " + sql.tgt("conversation_sessions") + " s JOIN " + sql.mig("mig_conversation") + " x ON x.session_id = s.id AND x.inserted = 1 "
                    + "JOIN " + agg + " ON a.conversation_id = x.conversation_id "
                    + "SET s.inbound_count = GREATEST(s.inbound_count, a.inbound), s.outbound_count = GREATEST(s.outbound_count, a.outbound), "
                    + "s.opened_at = LEAST(s.opened_at, COALESCE(a.first_at, s.opened_at)), "
                    + "s.last_activity_at = GREATEST(COALESCE(s.last_activity_at, a.last_at), COALESCE(a.last_at, s.last_activity_at)), "
                    + "s.resolved_at = GREATEST(COALESCE(s.resolved_at, a.last_at), COALESCE(a.last_at, s.resolved_at)) "
                    + "WHERE s.inbound_count < a.inbound OR s.outbound_count < a.outbound OR s.last_activity_at < a.last_at "
                    + "OR s.opened_at > a.first_at", Map.of());
        });
        ctx.stats().entity("conversation").refreshed += n[0];
        ctx.stats().entity("conversation_session").refreshed += n[1];
    }
}
