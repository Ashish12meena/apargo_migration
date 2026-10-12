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
 * 09e — chats (via mig_chat) -> messages + message_wamid. Entity {@code message} (old chats.id -> messages.id).
 * <ul>
 *   <li>skip-if-exists: wamid already in message_wamid (also collapses duplicate wamids of the old DB)</li>
 *   <li>type from chats.method, status from chats.status (config maps); never QUEUED / PROCESSING</li>
 *   <li>body_text = text / caption cut to 4096 (full text kept in payload.legacy.full_text)</li>
 *   <li>payload = {"legacy": {payload, response, reactions, contact, method, type, status, template_id}}</li>
 *   <li>created_by: INBOUND -> SYSTEM, bot session -> BOT, agent -> USER(agent), else USER(owner)</li>
 * </ul>
 * Afterwards: last_message_id / preview of conversations created by the migration, contact first/last message times.
 */
@Component
public class MessagesStep implements MigrationStep {

    @Override public String id() { return "09e-messages"; }
    @Override public int order() { return 950; }
    @Override public String title() { return "chats -> messages + message_wamid"; }

    @Override
    public void run(StepContext ctx) {
        Db db = ctx.db();
        Sql sql = ctx.sql();
        MigrationProperties.Messaging cfg = ctx.props().getMessaging();
        StepStats.Entity s = ctx.stats().entity("message");
        StepStats.Entity w = ctx.stats().entity("message_wamid");

        ctx.pages("main", "SELECT mc.old_id, mc.organization_id, mc.project_id, mc.contact_id, mc.waba_account_id, mc.waba_phone_number_id, "
                + "mc.direction, mc.conversation_id, mcv.session_id, ch.user_id, ch.text, ch.caption, ch.type, ch.method, ch.status, "
                + "ch.message_id, ch.image_id, ch.reply_message_id, ch.agent_id, ch.chat_bot_session_id, ch.payload, ch.response, "
                + "ch.reactions, ch.contact AS contact_json, ch.template_id, ch.created_at, ch.updated_at, mm.new_id AS mapped_id "
                + "FROM " + sql.mig("mig_chat") + " mc JOIN " + sql.old("chats") + " ch ON ch.id = mc.old_id "
                + "LEFT JOIN " + sql.mig("migration_id_map") + " mm ON mm.entity = 'message' AND mm.old_id = mc.old_id "
                + "LEFT JOIN " + sql.mig("mig_conversation") + " mcv ON mcv.conversation_id = mc.conversation_id AND mcv.inserted = 1 "
                + "WHERE mc.old_id > :lastId AND mc.conversation_id IS NOT NULL" + ctx.scope().sql("mc.project_id")
                + " ORDER BY mc.old_id LIMIT :limit", Map.of(), "old_id", page -> {
            Set<String> wamids = new HashSet<>();
            for (Row r : page) { String x = wamid(r); if (x != null) wamids.add(x); }
            Map<String, Long> existingWamid = new HashMap<>();
            if (!wamids.isEmpty())
                for (Row e : db.rows("SELECT provider_message_id, message_id FROM " + sql.tgt("message_wamid") + " WHERE provider_message_id IN (:w)",
                        Map.of("w", wamids)))
                    existingWamid.put(e.str("provider_message_id"), e.lng("message_id"));

            for (Row r : page) {
                long oldId = r.lng("old_id");
                s.read++;
                if (r.lng("mapped_id") != null) { s.skippedMapped++; continue; }
                String wamid = wamid(r);
                if (wamid != null && existingWamid.containsKey(wamid)) {
                    ctx.idMap().put("message", oldId, existingWamid.get(wamid));
                    s.matchedExisting++;
                    continue;
                }
                ctx.tx().row(ctx, "chats", oldId, () -> {
                    long id = db.insert(sql.tgt("messages"), message(ctx, cfg, r));
                    ctx.idMap().put("message", oldId, id);
                    s.inserted++;
                    if (wamid != null) {
                        db.insertNoKey(sql.tgt("message_wamid"), Db.vals().with("provider_message_id", wamid)
                                .with("conversation_id", r.lng("conversation_id")).with("created_at", r.dt("created_at"))
                                .with("direction", r.str("direction")).with("message_id", id));
                        existingWamid.put(wamid, id);
                        w.inserted++;
                    }
                });
            }
        });

        // summaries of conversations created by the migration
        ctx.tx().inTx(() -> {
            db.exec("UPDATE " + sql.tgt("conversations") + " c JOIN " + sql.mig("mig_conversation") + " mcv ON mcv.conversation_id = c.id AND mcv.inserted = 1 "
                    + "JOIN (SELECT conversation_id, MAX(id) mid FROM " + sql.tgt("messages") + " WHERE conversation_id IN (SELECT conversation_id FROM "
                    + sql.mig("mig_conversation") + " WHERE inserted = 1) GROUP BY conversation_id) x ON x.conversation_id = c.id "
                    + "JOIN " + sql.tgt("messages") + " m ON m.id = x.mid "
                    + "SET c.last_message_id = m.id, c.last_message_preview = LEFT(m.body_text, 300)");
            db.exec("UPDATE " + sql.tgt("contacts") + " c JOIN (SELECT contact_id, MIN(created_at) f, MAX(created_at) l FROM " + sql.mig("mig_chat")
                    + " GROUP BY contact_id) x ON x.contact_id = c.id SET "
                    + "c.first_message_at = CASE WHEN c.first_message_at IS NULL OR c.first_message_at > x.f THEN x.f ELSE c.first_message_at END, "
                    + "c.last_message_at = CASE WHEN c.last_message_at IS NULL OR c.last_message_at < x.l THEN x.l ELSE c.last_message_at END");
        });
    }

    private static String wamid(Row r) { return Text.cut(Text.trimToNull(r.str("message_id")), 150); }

    private Map<String, ?> message(StepContext ctx, MigrationProperties.Messaging cfg, Row r) {
        long oldId = r.lng("old_id");
        boolean inbound = "INBOUND".equals(r.str("direction"));
        String text = Text.firstNonBlank(r.str("text"), r.str("caption"));
        boolean cut = Text.longer(text, 4096);
        if (cut) ctx.problems().warn("chats", oldId, "TRUNCATED", "text longer than 4096 -> full text in payload.legacy.full_text");

        String method = Text.trimToNull(r.str("method"));
        String type = Enums.map(cfg.getChatType(), method);
        if (type == null) type = Enums.pick(method, Enums.MESSAGE_TYPE);
        if (type == null) {
            if (method == null) type = r.lng("template_id") != null ? "TEMPLATE" : r.text("image_id") != null ? "IMAGE" : "TEXT";
            else {
                type = "UNSUPPORTED";
                ctx.problems().warn("chats", oldId, "ENUM_UNKNOWN", "method '" + method + "' -> UNSUPPORTED");
            }
        }
        String rawStatus = Text.trimToNull(r.str("status"));
        String status = Enums.map(cfg.getChatStatus(), rawStatus);
        if (status == null) status = Enums.pick(rawStatus, Enums.MESSAGE_STATUS);
        if (status == null) {
            status = inbound ? "READ" : "SENT";
            ctx.problems().warn("chats", oldId, "ENUM_UNKNOWN", "status '" + rawStatus + "' -> " + status);
        }
        if ("QUEUED".equals(status) || "PROCESSING".equals(status)) status = "FAILED";

        String createdByType;
        Long createdById;
        if (inbound) { createdByType = "SYSTEM"; createdById = null; }
        else if (r.lng("chat_bot_session_id") != null) { createdByType = "BOT"; createdById = null; }
        else if (r.lng("agent_id") != null && ctx.idMap().get("user", r.lng("agent_id")) != null) {
            createdByType = "USER"; createdById = ctx.idMap().get("user", r.lng("agent_id"));
        } else { createdByType = "USER"; createdById = ctx.idMap().get("user", r.lng("user_id")); }

        ObjectNode legacy = Json.obj();
        legacy.put("chat_id", oldId);
        putJson(legacy, "payload", r.str("payload"));
        putJson(legacy, "response", r.str("response"));
        putJson(legacy, "reactions", r.str("reactions"));
        putJson(legacy, "contact", r.str("contact_json"));
        legacy.put("method", method);
        legacy.put("type", r.str("type"));
        legacy.put("status", rawStatus);
        if (r.lng("template_id") != null) legacy.put("template_id", r.lng("template_id"));
        if (r.lng("agent_id") != null) legacy.put("agent_id", r.lng("agent_id"));
        if (cut) legacy.put("full_text", text);
        ObjectNode payload = Json.obj();
        payload.set("legacy", legacy);

        // media keeps its old URL (files are not re-uploaded); the old Meta media id is kept as provider_media_id
        String mediaUrl = mediaLink(Json.parse(r.str("payload")));
        String mediaStatus = mediaUrl != null ? "READY" : r.text("image_id") != null ? "EXPIRED" : null;
        LocalDateTime created = r.dtOr("created_at", StepContext.now());
        LocalDateTime updated = r.dtOr("updated_at", created);
        boolean sent = Set.of("SENT", "DELIVERED", "READ").contains(status);
        return Db.vals().with("attempts", inbound ? 0 : 1).with("body_text", Text.cut(text, 4096)).with("campaign_id", null)
                .with("contact_id", r.lng("contact_id")).with("context_wamid", Text.cut(Text.trimToNull(r.str("reply_message_id")), 150))
                .with("created_at", created).with("created_by_id", createdById).with("created_by_type", createdByType)
                .with("delivered_at", !inbound && ("DELIVERED".equals(status) || "READ".equals(status)) ? updated : null)
                .with("direction", r.str("direction"))
                .with("error_title", "FAILED".equals(status) ? Text.cut("legacy status: " + rawStatus, 255) : null)
                .with("failed_at", "FAILED".equals(status) ? updated : null)
                .with("media_status", mediaStatus).with("media_url", Text.longer(mediaUrl, 1024) ? null : mediaUrl)
                .with("message_type", type).with("organization_id", r.lng("organization_id")).with("payload", Json.write(payload))
                .with("project_id", r.lng("project_id")).with("provider_media_id", Text.cut(r.text("image_id"), 128))
                .with("provider_message_id", wamid(r)).with("read_at", !inbound && "READ".equals(status) ? updated : null)
                .with("sent_at", !inbound && sent ? created : null).with("session_id", r.lng("session_id")).with("status", status)
                .with("updated_at", updated).with("waba_account_id", r.lng("waba_account_id"))
                .with("waba_phone_number_id", r.lng("waba_phone_number_id")).with("conversation_id", r.lng("conversation_id"));
    }

    /** old URL of the media in a chat payload: {"image":{"link":…}}, {"document":{"url":…}}, {"link":…} */
    static String mediaLink(JsonNode n) {
        if (n == null || !n.isObject()) return null;
        for (String type : new String[]{"image", "video", "document", "audio", "sticker", "media"}) {
            JsonNode t = n.get(type);
            if (t != null && t.isObject()) {
                String u = Json.text(t, "link", "url", "media_url");
                if (u != null && u.startsWith("http")) return u;
            }
        }
        String u = Json.text(n, "link", "url", "media_url", "file_url");
        return u != null && u.startsWith("http") ? u : null;
    }

    private static void putJson(ObjectNode target, String field, String raw) {
        if (raw == null || raw.isBlank()) return;
        JsonNode n = Json.parse(raw);
        if (n != null) target.set(field, n);
        else target.put(field + "_raw", raw);
    }
}
