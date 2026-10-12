package com.aigreentick.migration.steps.messaging;

import com.aigreentick.migration.core.*;
import com.aigreentick.migration.util.Json;
import com.aigreentick.migration.util.Text;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 09g — canned_messages -> canned_responses (project of canned_messages.user_id; old project_id refers to the empty
 * old projects table and is ignored). message_sets JSON is flattened to text (media parts dropped, WARN).
 * shortcut = keyword, else slug(title), max 50; a live shortcut with the same title + body is reused, another one gets
 * a "-2", "-3", … suffix. Entity {@code canned}.
 */
@Component
public class CannedResponsesStep implements MigrationStep {
    private static final List<String> TEXT_KEYS = List.of("text", "message", "body", "caption", "content", "msg");
    private static final List<String> MEDIA_KEYS = List.of("url", "media", "media_url", "image", "file", "document", "video", "audio");

    @Override public String id() { return "09g-canned-responses"; }
    @Override public int order() { return 970; }
    @Override public String title() { return "canned_messages -> canned_responses"; }

    @Override
    public void run(StepContext ctx) {
        Db db = ctx.db();
        Sql sql = ctx.sql();
        StepStats.Entity s = ctx.stats().entity("canned");
        for (Row c : db.rows("SELECT * FROM " + sql.old("canned_messages") + " ORDER BY id")) {
            long oldId = c.lng("id");
            s.read++;
            if (ctx.idMap().has("canned", oldId)) { s.skippedMapped++; continue; }
            if (!c.isNull("deleted_at")) { ctx.problems().info("canned_messages", oldId, "SOFT_DELETED_SKIPPED", "deleted canned message"); continue; }
            Tenant t = ctx.place("canned_messages", oldId, c.lng("user_id"));
            if (t == null) continue;
            boolean[] media = {false};
            String body = flatten(Json.parse(c.str("message_sets")), media);
            if (body == null) body = Text.firstNonBlank(c.str("message_sets"), c.str("title"), "-");
            if (media[0]) ctx.problems().warn("canned_messages", oldId, "MEDIA_DROPPED", "media parts of message_sets are not migrated");
            String title = Text.cut(Text.firstNonBlank(c.str("title"), "Quick reply " + oldId), 150);
            String base = Text.cut(Text.firstNonBlank(c.str("keyword"), Text.slugify(title), "reply-" + oldId), 50);
            String text = body;
            ctx.tx().row(ctx, "canned_messages", oldId, () -> {
                String shortcut = base;
                for (int i = 2; ; i++) {
                    Row ex = db.row("SELECT id, title, body FROM " + sql.tgt("canned_responses")
                            + " WHERE project_id = :p AND shortcut = :s AND deleted_at IS NULL LIMIT 1", Map.of("p", t.projectId(), "s", shortcut));
                    if (ex == null) break;
                    if (Objects.equals(ex.str("title"), title) && Objects.equals(ex.str("body"), text)) {
                        ctx.idMap().put("canned", oldId, ex.lng("id"));
                        s.matchedExisting++;
                        return;
                    }
                    String suffix = "-" + i;
                    shortcut = Text.cut(base, 50 - suffix.length()) + suffix;
                }
                if (!shortcut.equals(base)) ctx.problems().info("canned_messages", oldId, "SHORTCUT_RENAMED", base + " -> " + shortcut);
                long id = db.insert(sql.tgt("canned_responses"), Db.vals().with("created_at", c.dtOr("created_at", StepContext.now()))
                        .with("updated_at", c.dtOr("updated_at", StepContext.now())).with("deleted_at", null).with("body", text)
                        .with("created_by", t.userId()).with("organization_id", t.orgId()).with("project_id", t.projectId())
                        .with("shortcut", shortcut).with("team_id", null).with("title", title).with("usage_count", 0)
                        .with("uuid", UUID.randomUUID().toString()));
                ctx.idMap().put("canned", oldId, id);
                s.inserted++;
            });
        }
    }

    /** texts of a message_sets JSON joined by blank lines; null when nothing textual */
    static String flatten(JsonNode n, boolean[] media) {
        if (n == null) return null;
        List<String> parts = new ArrayList<>();
        collect(n, parts, media);
        return parts.isEmpty() ? null : String.join("\n\n", parts);
    }

    private static void collect(JsonNode n, List<String> parts, boolean[] media) {
        if (n == null || n.isNull()) return;
        if (n.isTextual()) { if (!n.asText().isBlank()) parts.add(n.asText().trim()); return; }
        if (n.isArray()) { n.forEach(x -> collect(x, parts, media)); return; }
        if (!n.isObject()) return;
        for (String k : MEDIA_KEYS) if (n.hasNonNull(k) && !n.get(k).asText().isBlank()) media[0] = true;
        for (String k : TEXT_KEYS) {
            JsonNode v = n.get(k);
            if (v != null && !v.isNull()) { collect(v, parts, media); return; }
        }
        if (n.has("messages")) collect(n.get("messages"), parts, media);
    }
}
