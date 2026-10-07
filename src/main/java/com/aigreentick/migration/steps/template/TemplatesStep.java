package com.aigreentick.migration.steps.template;

import com.aigreentick.migration.core.*;
import com.aigreentick.migration.util.Enums;
import com.aigreentick.migration.util.Json;
import com.aigreentick.migration.util.Text;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 07a — templates (+ components, buttons, variables, examples, carousel cards) -> whatsapp_templates and children.
 * <ul>
 *   <li>waba_id = the WABA assigned to the template owner's project (default first); none -> ERROR NO_WABA</li>
 *   <li>skip-if-exists: same meta_template_id in the organization, or the live (waba_id, name, language) row;
 *       if that live row was written by this migration, the older duplicate is inserted soft-deleted (newest first)</li>
 *   <li>status / category -> ENUM; unknown status -> UNKNOWN with the raw value in meta_status_raw</li>
 *   <li>children are written only together with a newly inserted template; FLOW buttons have no ENUM value -> WARN</li>
 * </ul>
 * Refresh: status, meta_status_raw, category.
 */
@Component
public class TemplatesStep implements MigrationStep {
    private static final Map<String, Integer> TYPE_RANK = Map.of("HEADER", 0, "BODY", 1, "FOOTER", 2, "BUTTONS", 3,
            "CAROUSEL", 4, "LIMITED_TIME_OFFER", 5);
    private static final Map<String, String> STATUS_ALIAS = Map.of("IN_APPEAL", "PENDING", "PENDING_DELETION", "DISABLED",
            "DELETED", "DISABLED", "LIMIT_EXCEEDED", "PAUSED", "ARCHIVED", "DISABLED", "ACTIVE", "APPROVED");
    private static final Map<String, String> BUTTON_ALIAS = Map.ofEntries(Map.entry("PHONE", "PHONE_NUMBER"),
            Map.entry("CALL", "PHONE_NUMBER"), Map.entry("CALL_PHONE", "PHONE_NUMBER"), Map.entry("VISIT_WEBSITE", "URL"),
            Map.entry("WEBSITE", "URL"), Map.entry("QUICKREPLY", "QUICK_REPLY"), Map.entry("REPLY", "QUICK_REPLY"),
            Map.entry("COPYCODE", "COPY_CODE"), Map.entry("COUPON", "COPY_CODE"));

    @Override public String id() { return "07a-templates"; }
    @Override public int order() { return 710; }
    @Override public String title() { return "templates (+ children) -> whatsapp_templates"; }

    @Override
    public void run(StepContext ctx) {
        Db db = ctx.db();
        Sql sql = ctx.sql();
        String where = ctx.props().getTemplates().isIncludeDeleted() ? "" : " WHERE deleted_at IS NULL";
        Map<Long, List<Row>> wabasByProject = new HashMap<>();
        Set<Long> warnedMulti = new HashSet<>();
        StepStats.Entity s = ctx.stats().entity("template");

        for (Row tpl : db.rows("SELECT * FROM " + sql.old("templates") + where + " ORDER BY id DESC")) {
            long oldId = tpl.lng("id");
            s.read++;
            Long mapped = ctx.idMap().get("template", oldId);
            if (mapped != null && !ctx.refresh()) { s.skippedMapped++; continue; }
            Tenant t = ctx.place("templates", oldId, tpl.lng("user_id"));
            if (t == null) continue;

            List<Row> wabas = wabasByProject.computeIfAbsent(t.projectId(), p -> db.rows("SELECT w.id, w.waba_id FROM "
                    + sql.tgt("waba_accounts") + " w WHERE w.project_id = :p AND w.deleted_at IS NULL "
                    + "ORDER BY w.is_project_default DESC, w.id", Map.of("p", p)));
            if (wabas.isEmpty()) {
                ctx.problems().error("templates", oldId, "NO_WABA", "project " + t.projectId() + " has no WABA (step 05)");
                s.errors++;
                continue;
            }
            if (wabas.size() > 1 && warnedMulti.add(t.projectId()))
                ctx.problems().warn("templates", oldId, "MULTIPLE_WABA", "project " + t.projectId() + " has " + wabas.size()
                        + " WABAs: templates go to the default one (" + wabas.get(0).str("waba_id") + ")");
            String wabaId = wabas.get(0).str("waba_id");

            String rawStatus = tpl.text("status");
            String up = rawStatus == null ? null : Text.upper(rawStatus).replace(' ', '_');
            String status = up == null ? "UNKNOWN" : Enums.TEMPLATE_STATUS.contains(up) ? up : STATUS_ALIAS.getOrDefault(up, "UNKNOWN");
            boolean metaDeleted = "DELETED".equals(up);
            String category = Enums.pick(tpl.str("category"), Enums.TEMPLATE_CATEGORY);
            if (category == null) {
                category = ctx.props().getTemplates().getDefaultCategory();
                ctx.problems().warn("templates", oldId, "CATEGORY_UNKNOWN", "category '" + tpl.str("category") + "' -> " + category);
            }
            String cat = category;
            String name = Text.cut(tpl.text("name"), 150);
            String language = Text.cut(Text.firstNonBlank(tpl.str("language"), "en"), 10);
            if (name == null) {
                ctx.problems().error("templates", oldId, "NAME_MISSING", "template has no name");
                s.errors++;
                continue;
            }
            if (Text.longer(tpl.text("name"), 150)) ctx.problems().warn("templates", oldId, "TRUNCATED", "name cut to 150");

            ctx.tx().row(ctx, "templates", oldId, () -> {
                if (mapped != null) {   // refresh
                    db.update(sql.tgt("whatsapp_templates"), mapped, Db.vals().with("status", status)
                            .with("meta_status_raw", Text.cut(rawStatus, 64)).with("category", cat).with("updated_at", StepContext.now()));
                    s.refreshed++;
                    return;
                }
                String waId = Text.cut(tpl.text("wa_id"), 150);
                Long byMetaId = waId == null ? null : db.findId(sql.tgt("whatsapp_templates"),
                        Map.of("organization_id", t.orgId(), "meta_template_id", waId));
                if (byMetaId != null && !ctx.idMap().isTargetOf("template", byMetaId)) {
                    ctx.idMap().put("template", oldId, byMetaId);
                    s.matchedExisting++;
                    return;
                }
                boolean live = tpl.isNull("deleted_at") && !metaDeleted && !Set.of("DRAFT", "FAILED").contains(status);
                LocalDateTime deletedAt = tpl.dt("deleted_at") != null ? tpl.dt("deleted_at")
                        : metaDeleted ? tpl.dtOr("updated_at", StepContext.now()) : null;
                if (live) {
                    Row existingLive = db.row("SELECT id FROM " + sql.tgt("whatsapp_templates") + " WHERE waba_id = :w AND name = :n "
                            + "AND language = :l AND deleted_at IS NULL AND status NOT IN ('DRAFT','FAILED') LIMIT 1",
                            Map.of("w", wabaId, "n", name, "l", language));
                    if (existingLive != null) {
                        if (!ctx.idMap().isTargetOf("template", existingLive.lng("id"))) {
                            ctx.idMap().put("template", oldId, existingLive.lng("id"));
                            s.matchedExisting++;
                            return;
                        }
                        // a newer old template already holds the live slot -> keep this one as history
                        deletedAt = tpl.dtOr("updated_at", StepContext.now());
                        ctx.problems().info("templates", oldId, "DUPLICATE_SOFT_DELETED", "older duplicate of " + name + "/" + language + " inserted soft-deleted");
                    }
                }
                String payload = tpl.str("payload");
                String submission = Json.normalize(payload);
                if (payload != null && !payload.isBlank() && submission == null)
                    ctx.problems().warn("templates", oldId, "INVALID_JSON", "templates.payload is not valid JSON -> submission_payload NULL");
                long newId = db.insert(sql.tgt("whatsapp_templates"), Db.vals()
                        .with("category", cat).with("created_at", tpl.dtOr("created_at", StepContext.now()))
                        .with("created_by", t.userId()).with("deleted_at", deletedAt).with("language", language)
                        .with("meta_response", Json.normalize(tpl.str("response"))).with("meta_status_raw", Text.cut(rawStatus, 64))
                        .with("meta_template_id", waId).with("name", name).with("organization_id", t.orgId())
                        .with("previous_category", Enums.pick(tpl.str("previous_category"), Enums.TEMPLATE_CATEGORY))
                        .with("project_id", t.projectId()).with("quality_rating", "UNKNOWN").with("status", status)
                        .with("submission_payload", submission).with("updated_at", tpl.dtOr("updated_at", StepContext.now()))
                        .with("waba_id", wabaId));
                ctx.idMap().put("template", oldId, newId);
                s.inserted++;
                children(ctx, oldId, newId);
            });
        }
    }

    // ------------------------------------------------------------------------------------------------ children

    private void children(StepContext ctx, long oldTemplateId, long templateId) {
        Db db = ctx.db();
        Sql sql = ctx.sql();
        List<Row> comps = new ArrayList<>(db.rows("SELECT * FROM " + sql.old("template_components")
                + " WHERE template_id = :t AND deleted_at IS NULL ORDER BY id", Map.of("t", oldTemplateId)));
        comps.removeIf(c -> {
            if (Enums.pick(c.str("type"), Enums.COMPONENT_TYPE) != null) return false;
            ctx.problems().warn("template_components", c.lng("id"), "ENUM_UNKNOWN", "component type '" + c.str("type") + "' skipped");
            return true;
        });
        comps.sort(Comparator.comparing((Row c) -> TYPE_RANK.get(Enums.pick(c.str("type"), Enums.COMPONENT_TYPE)))
                .thenComparing(c -> c.lng("id")));

        Map<Long, String> typeOfOldComp = new HashMap<>();
        Map<Long, Long> newComp = new HashMap<>();
        int order = 0;
        for (Row c : comps) {
            String type = Enums.pick(c.str("type"), Enums.COMPONENT_TYPE);
            String url = c.text("image_url");
            if (Text.longer(url, 500)) ctx.problems().warn("template_components", c.lng("id"), "TRUNCATED", "image_url longer than 500 -> NULL");
            long id = db.insert(sql.tgt("whatsapp_template_components"), Db.vals()
                    .with("component_order", order++).with("component_type", type).with("created_at", c.dtOr("created_at", StepContext.now()))
                    .with("format", Enums.pick(c.str("format"), Enums.COMPONENT_FORMAT))
                    .with("media_url", Text.longer(url, 500) ? null : url).with("text", c.str("text")).with("template_id", templateId));
            ctx.idMap().put("template_component", c.lng("id"), id);
            typeOfOldComp.put(c.lng("id"), type);
            newComp.put(c.lng("id"), id);
        }

        // buttons
        for (Map.Entry<Long, Long> e : newComp.entrySet()) {
            if (!"BUTTONS".equals(typeOfOldComp.get(e.getKey()))) continue;
            int idx = 0;
            for (Row b : db.rows("SELECT * FROM " + sql.old("template_component_buttons")
                    + " WHERE component_id = :c AND deleted_at IS NULL ORDER BY id", Map.of("c", e.getKey()))) {
                String raw = Text.upper(b.str("type"));
                String type = raw == null ? null : Enums.BUTTON_TYPE.contains(raw.replace(' ', '_')) ? raw.replace(' ', '_') : BUTTON_ALIAS.get(raw.replace(' ', '_'));
                if (type == null) {
                    ctx.problems().warn("template_component_buttons", b.lng("id"), "FLOW".equals(raw) ? "FLOW_BUTTON" : "ENUM_UNKNOWN",
                            "button type '" + b.str("type") + "' has no target ENUM value -> skipped");
                    continue;
                }
                long id = db.insert(sql.tgt("whatsapp_template_buttons"), Db.vals().with("button_index", idx++)
                        .with("button_type", type).with("created_at", b.dtOr("created_at", StepContext.now()))
                        .with("phone_number", Text.cut(b.text("number"), 30))
                        .with("text", Text.cut(Text.firstNonBlank(b.str("text"), type), 150))
                        .with("url", Text.cut(b.text("url"), 500)).with("component_id", e.getValue()));
                ctx.idMap().put("template_button", b.lng("id"), id);
            }
        }

        // variables + examples
        if (!newComp.isEmpty()) {
            Map<Long, TreeMap<Integer, String>> examples = new HashMap<>();
            Set<String> seen = new HashSet<>();
            for (Row v : db.rows("SELECT * FROM " + sql.old("template_texts") + " WHERE component_id IN (:c) AND deleted_at IS NULL "
                    + "ORDER BY component_id, text_index, id", Map.of("c", newComp.keySet()))) {
                String ctype = typeOfOldComp.get(v.lng("component_id"));
                String vtype = "BODY".equals(ctype) ? "BODY" : "HEADER".equals(ctype) ? "HEADER" : "BUTTONS".equals(ctype) ? "BUTTON" : null;
                if (vtype == null) continue;
                int index = v.integer("text_index");
                if (!seen.add(vtype + ":" + index)) {
                    ctx.problems().warn("template_texts", v.lng("id"), "DUPLICATE", vtype + " variable " + index + " repeated -> first kept");
                    continue;
                }
                db.insert(sql.tgt("whatsapp_template_variables"), Db.vals().with("button_index", 0).with("card_index", 0)
                        .with("component_type", vtype).with("created_at", v.dtOr("created_at", StepContext.now()))
                        .with("label", Text.cut(v.str("text"), 100)).with("label_value", Text.cut(v.str("default_value"), 255))
                        .with("variable_index", index).with("template_id", templateId));
                if (!"BUTTON".equals(vtype))
                    examples.computeIfAbsent(v.lng("component_id"), k -> new TreeMap<>())
                            .put(index, Text.firstNonBlank(v.str("default_value"), v.str("text"), ""));
            }
            for (Map.Entry<Long, TreeMap<Integer, String>> e : examples.entrySet()) {
                ArrayNode values = Json.arr();
                e.getValue().values().forEach(values::add);
                boolean body = "BODY".equals(typeOfOldComp.get(e.getKey()));
                ArrayNode bodyText = Json.arr();
                if (body) bodyText.add(values);
                db.insert(sql.tgt("whatsapp_template_examples"), Db.vals().with("body_text", body ? Json.write(bodyText) : null)
                        .with("created_at", StepContext.now()).with("header_handle", null)
                        .with("header_text", body ? null : Json.write(values)).with("component_id", newComp.get(e.getKey())));
            }
        }

        // carousel
        for (Map.Entry<Long, Long> e : newComp.entrySet()) {
            if (!"CAROUSEL".equals(typeOfOldComp.get(e.getKey()))) continue;
            int cardIdx = 0;
            for (Row card : db.rows("SELECT * FROM " + sql.old("template_carousel_cards")
                    + " WHERE component_id = :c AND deleted_at IS NULL ORDER BY card_index, id", Map.of("c", e.getKey()))) {
                long cardId = db.insert(sql.tgt("whatsapp_template_carousel_cards"), Db.vals().with("card_index", cardIdx++)
                        .with("created_at", card.dtOr("created_at", StepContext.now())).with("component_id", e.getValue()));
                ctx.idMap().put("carousel_card", card.lng("id"), cardId);
                String fmt = Enums.pick(card.str("media_type"), Enums.CARD_FORMAT);
                String url = card.text("image_url");
                if (url != null || card.text("header") != null)
                    db.insert(sql.tgt("whatsapp_template_carousel_card_components"), Db.vals().with("component_type", "HEADER")
                            .with("created_at", StepContext.now()).with("format", fmt == null ? "IMAGE" : fmt)
                            .with("media_url", Text.longer(url, 500) ? null : url).with("text", card.str("header")).with("card_id", cardId));
                if (card.text("body") != null)
                    db.insert(sql.tgt("whatsapp_template_carousel_card_components"), Db.vals().with("component_type", "BODY")
                            .with("created_at", StepContext.now()).with("text", card.str("body")).with("card_id", cardId));
                List<Row> buttons = db.rows("SELECT * FROM " + sql.old("template_carousel_card_buttons")
                        + " WHERE card_id = :c AND deleted_at IS NULL ORDER BY id", Map.of("c", card.lng("id")));
                if (buttons.isEmpty()) continue;
                long holder = db.insert(sql.tgt("whatsapp_template_carousel_card_components"), Db.vals().with("component_type", "BUTTONS")
                        .with("created_at", StepContext.now()).with("card_id", cardId));
                int bi = 0;
                for (Row b : buttons) {
                    String raw = Text.upper(b.str("type"));
                    String type = raw == null ? null : Enums.CAROUSEL_BUTTON_TYPE.contains(raw.replace(' ', '_')) ? raw.replace(' ', '_')
                            : BUTTON_ALIAS.get(raw.replace(' ', '_'));
                    if (type == null || !Enums.CAROUSEL_BUTTON_TYPE.contains(type)) {
                        ctx.problems().warn("template_carousel_card_buttons", b.lng("id"), "ENUM_UNKNOWN", "carousel button type '" + b.str("type") + "' skipped");
                        continue;
                    }
                    db.insert(sql.tgt("whatsapp_template_carousel_buttons"), Db.vals().with("button_index", bi++)
                            .with("button_type", type).with("created_at", b.dtOr("created_at", StepContext.now()))
                            .with("phone_number", Text.cut(b.text("phone_number"), 30))
                            .with("text", Text.cut(Text.firstNonBlank(b.str("text"), type), 150))
                            .with("url", Text.cut(b.text("url"), 500)).with("card_component_id", holder));
                }
            }
        }
    }
}
