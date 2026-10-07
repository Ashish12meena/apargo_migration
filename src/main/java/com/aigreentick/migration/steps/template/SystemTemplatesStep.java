package com.aigreentick.migration.steps.template;

import com.aigreentick.migration.core.*;
import com.aigreentick.migration.util.Enums;
import com.aigreentick.migration.util.Json;
import com.aigreentick.migration.util.Text;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 07b — template_library rows without owner (global library) -> system_templates (natural key name + language).
 * payload = {"template": &lt;old payload&gt;, "variables": &lt;variable_data&gt;, "sub_category", "template_type"}.
 * Owned library rows (user_id set) are user copies and are not migrated (INFO).
 */
@Component
public class SystemTemplatesStep implements MigrationStep {

    @Override public String id() { return "07b-system-templates"; }
    @Override public int order() { return 720; }
    @Override public String title() { return "template_library -> system_templates"; }

    @Override
    public void run(StepContext ctx) {
        if (ctx.scope().isPilot()) return;   // global data
        Db db = ctx.db();
        StepStats.Entity s = ctx.stats().entity("system_template");
        long owned = db.count("SELECT COUNT(*) FROM " + ctx.sql().old("template_library") + " WHERE user_id IS NOT NULL AND deleted_at IS NULL");
        ctx.problems().aggregate(ProblemLog.Severity.INFO, "template_library", "OWNED_LIBRARY_ROW", owned,
                "template_library rows with user_id are not system templates and are not migrated");

        for (Row r : db.rows("SELECT * FROM " + ctx.sql().old("template_library") + " WHERE user_id IS NULL AND deleted_at IS NULL ORDER BY id")) {
            long oldId = r.lng("id");
            s.read++;
            if (ctx.idMap().has("system_template", oldId)) { s.skippedMapped++; continue; }
            String name = Text.cut(r.text("name"), 150);
            String language = Text.cut(Text.firstNonBlank(r.str("language"), "en"), 10);
            String category = Enums.pick(r.str("category"), Enums.TEMPLATE_CATEGORY);
            if (name == null || category == null) {
                ctx.problems().error("template_library", oldId, "INVALID", "name or category missing");
                s.errors++;
                continue;
            }
            ctx.tx().row(ctx, "template_library", oldId, () -> {
                Long existing = db.findId(ctx.sql().tgt("system_templates"), Map.of("name", name, "language", language));
                if (existing != null) {
                    ctx.idMap().put("system_template", oldId, existing);
                    s.matchedExisting++;
                    return;
                }
                ObjectNode payload = Json.obj();
                JsonNode tpl = Json.parse(r.str("payload"));
                if (tpl != null) payload.set("template", tpl);
                else {
                    payload.put("template_raw", r.str("payload"));
                    ctx.problems().warn("template_library", oldId, "INVALID_JSON", "payload is not valid JSON -> kept as template_raw");
                }
                JsonNode vars = Json.parse(r.str("variable_data"));
                payload.set("variables", vars == null ? Json.arr() : vars);
                payload.put("sub_category", r.str("sub_category"));
                payload.put("template_type", r.str("template_type"));
                payload.put("legacy_wa_id", r.str("wa_id"));
                long id = db.insert(ctx.sql().tgt("system_templates"), Db.vals().with("is_active", "APPROVED".equals(r.str("status")))
                        .with("category", category).with("created_at", r.dtOr("created_at", StepContext.now()))
                        .with("description", Text.cut(r.str("sub_category") + " / " + r.str("template_type"), 500))
                        .with("language", language).with("name", name).with("payload", Json.write(payload))
                        .with("updated_at", r.dtOr("updated_at", StepContext.now())));
                ctx.idMap().put("system_template", oldId, id);
                s.inserted++;
            });
        }
    }
}
