package com.aigreentick.migration.steps.messaging;

import com.aigreentick.migration.core.*;
import com.aigreentick.migration.util.Json;
import com.aigreentick.migration.util.Text;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 09h — broadcasts -> broadcast_campaigns (entity {@code campaign}). D7: broadcasts created on/after
 * migration.history.broadcasts-since.
 * <ul>
 *   <li>template = entity template (required: template_name / language / category are NOT NULL snapshots)</li>
 *   <li>WABA + number = broadcasts.whatsapp (old whatsapp_accounts.id), else the project's default WABA and its first number;
 *       a number of another organization -> ERROR</li>
 *   <li>never SCHEDULED / READY / RUNNING after migration: schedule in the future -> CANCELLED, else COMPLETED</li>
 *   <li>template_payload = broadcasts.data (valid JSON), else the first element of broadcasts.requests, else {} (WARN)</li>
 *   <li>counters are filled by 09k</li>
 * </ul>
 */
@Component
public class CampaignsStep implements MigrationStep {

    @Override public String id() { return "09h-campaigns"; }
    @Override public int order() { return 980; }
    @Override public String title() { return "broadcasts -> broadcast_campaigns"; }

    @Override
    public void run(StepContext ctx) {
        Db db = ctx.db();
        Sql sql = ctx.sql();
        String since = ctx.props().getHistory().getBroadcastsSince();
        Map<Long, Row> templates = new HashMap<>();
        Map<Long, Long[]> defaultWaba = new HashMap<>();
        LocalDateTime now = StepContext.now();
        StepStats.Entity s = ctx.stats().entity("campaign");

        ctx.pages("main", "SELECT id, user_id, agent_id, template_id, template_category, whatsapp, campname, total, schedule_at, status, "
                + "data, SUBSTRING(requests, 1, 200000) AS requests_head, created_at, updated_at FROM " + sql.old("broadcasts")
                + " WHERE id > :lastId AND deleted_at IS NULL" + (since.isBlank() ? "" : " AND created_at >= :since") + " ORDER BY id LIMIT :limit",
                since.isBlank() ? Map.of() : Map.of("since", since), "id", page -> {
            for (Row b : page) {
                long oldId = b.lng("id");
                s.read++;
                if (ctx.idMap().has("campaign", oldId)) { s.skippedMapped++; continue; }
                Tenant t = ctx.place("broadcasts", oldId, b.lng("user_id"));
                if (t == null) continue;
                Long tplId = ctx.idMap().get("template", b.lng("template_id"));
                Row tpl = tplId == null ? null : templates.computeIfAbsent(tplId, id -> db.row("SELECT id, name, language, category, project_id FROM "
                        + sql.tgt("whatsapp_templates") + " WHERE id = :id", Map.of("id", id)));
                if (tpl == null) {
                    ctx.problems().error("broadcasts", oldId, "TEMPLATE_MISSING", "template " + b.lng("template_id") + " not migrated");
                    s.errors++;
                    continue;
                }
                Long waba = null, phone = null;
                if (b.lng("whatsapp") != null) {
                    waba = ctx.idMap().get("waba", b.lng("whatsapp"));
                    phone = ctx.idMap().get("waba_phone", b.lng("whatsapp"));
                    if (waba != null && db.count("SELECT COUNT(*) FROM " + sql.tgt("waba_accounts") + " WHERE id = :w AND project_id = :p AND organization_id = :o",
                            Map.of("w", waba, "p", t.projectId(), "o", t.orgId())) == 0) {
                        ctx.problems().error("broadcasts", oldId, "WABA_OTHER_PROJECT", "whatsapp account " + b.lng("whatsapp") + " belongs to another project");
                        s.errors++;
                        continue;
                    }
                }
                if (waba == null || phone == null) {
                    Long[] d = defaultWaba.computeIfAbsent(t.projectId(), p -> {
                        Row r = db.row("SELECT w.id waba_account_id, (SELECT n.id FROM " + sql.tgt("waba_phone_numbers") + " n WHERE n.waba_account_id = w.id "
                                + "AND n.deleted_at IS NULL ORDER BY n.id LIMIT 1) phone FROM " + sql.tgt("waba_accounts")
                                + " w WHERE w.project_id = :p AND w.deleted_at IS NULL ORDER BY w.is_project_default DESC, w.id LIMIT 1", Map.of("p", p));
                        return r == null ? new Long[]{null, null} : new Long[]{r.lng("waba_account_id"), r.lng("phone")};
                    });
                    waba = d[0];
                    phone = d[1];
                }
                if (waba == null || phone == null) {
                    ctx.problems().error("broadcasts", oldId, "NO_WABA_PHONE", "no WABA / phone number for project " + t.projectId());
                    s.errors++;
                    continue;
                }
                String payload = Json.normalize(b.str("data"));
                if (payload == null) {
                    JsonNode req = Json.parse(b.str("requests_head"));
                    if (req != null && req.isArray() && req.size() > 0) payload = Json.write(req.get(0));
                    else if (req != null && req.isObject()) payload = Json.write(req);
                }
                if (payload == null) {
                    payload = "{}";
                    ctx.problems().warn("broadcasts", oldId, "TEMPLATE_PAYLOAD_EMPTY", "neither data nor requests is valid JSON -> {}");
                }
                LocalDateTime scheduled = b.dt("schedule_at");
                boolean future = scheduled != null && scheduled.isAfter(now);
                Long createdBy = ctx.idMap().get("user", b.lng("agent_id"));
                if (createdBy == null) createdBy = t.userId();
                LocalDateTime created = b.dtOr("created_at", now);
                Db.Vals v = Db.vals().with("created_at", created).with("updated_at", b.dtOr("updated_at", created))
                        .with("audience_ref", "legacy-broadcast-" + oldId).with("audience_total", b.integer("total"))
                        .with("audience_type", "CSV_UPLOAD").with("cancelled_count", 0)
                        .with("completed_at", future ? null : b.dtOr("updated_at", created)).with("created_by", createdBy)
                        .with("delivered_count", 0).with("discrepancy_count", 0).with("failed_count", 0)
                        .with("max_attempts", ctx.props().getMessaging().getCampaignMaxAttempts())
                        .with("name", Text.cut(Text.firstNonBlank(b.str("campname"), "Broadcast " + oldId), 200))
                        .with("organization_id", t.orgId()).with("project_id", t.projectId()).with("read_count", 0)
                        .with("reconcile_attempts", 0).with("resolve_more", false).with("resolve_page", 0).with("resolved_count", 0)
                        .with("respect_quiet_hours", false).with("scheduled_at", scheduled).with("sent_count", 0).with("skipped_count", 0)
                        .with("started_at", future ? null : (scheduled != null ? scheduled : created))
                        .with("status", future ? "CANCELLED" : "COMPLETED").with("template_category", tpl.str("category"))
                        .with("template_id", tpl.lng("id")).with("template_language", tpl.str("language"))
                        .with("template_name", Text.cut(tpl.str("name"), 200)).with("template_payload", payload)
                        .with("total_recipients", b.lng("total", 0)).with("uuid", UUID.randomUUID().toString()).with("version", 0)
                        .with("waba_account_id", waba).with("waba_phone_number_id", phone);
                if (future) ctx.problems().info("broadcasts", oldId, "FUTURE_CANCELLED", "scheduled for " + scheduled + " -> CANCELLED");
                ctx.tx().row(ctx, "broadcasts", oldId, () -> {
                    ctx.idMap().put("campaign", oldId, db.insert(sql.tgt("broadcast_campaigns"), v));
                    s.inserted++;
                });
            }
        });
    }
}
