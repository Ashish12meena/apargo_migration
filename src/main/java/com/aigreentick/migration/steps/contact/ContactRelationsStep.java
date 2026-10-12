package com.aigreentick.migration.steps.contact;

import com.aigreentick.migration.core.*;
import com.aigreentick.migration.util.Text;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 08c — everything that hangs off a contact, always in the OLD OWNER's project:
 * <ol>
 *   <li>project_contacts (+ IMPORT consent rows for new ones, D6) and the assignee from the active agent_assignments row</li>
 *   <li>tags -> contact_tags (scope PROJECT) and tag_contacts -> contact_tag_assignments</li>
 *   <li>groups -> contact_lists (scope PROJECT) and group_members -> contact_list_contacts</li>
 *   <li>attribute_masters + contact_attributes names -> attribute_definitions (per organization, TEXT);
 *       contact_attributes -> contact_attribute_values (latest old row wins)</li>
 *   <li>chat_contact_notes -> contact_notes</li>
 * </ol>
 * Duplicate old contacts resolve to their master contact through entity {@code contact}. A link whose contact
 * belongs to another project than its tag / list is never created (cross-tenant guard).
 */
@Component
public class ContactRelationsStep implements MigrationStep {
    private static final String MIGRATION_MARK = "migration";

    @Override public String id() { return "08c-contact-relations"; }
    @Override public int order() { return 830; }
    @Override public String title() { return "project_contacts, consent, tags, lists, attributes, notes"; }

    @Override
    public void run(StepContext ctx) {
        projectContacts(ctx);
        assignees(ctx);
        tags(ctx);
        tagAssignments(ctx);
        lists(ctx);
        listMembers(ctx);
        attributes(ctx);
        notes(ctx);
    }

    // ------------------------------------------------------------------------------------------- project_contacts

    private void projectContacts(StepContext ctx) {
        Db db = ctx.db();
        Sql sql = ctx.sql();
        StepStats.Entity s = ctx.stats().entity("project_contact");
        StepStats.Entity cs = ctx.stats().entity("consent");
        long source = ctx.props().getContacts().getSourceId();
        String scope = ctx.scope().sql("m.project_id");
        Long maxId = db.longValue("SELECT MAX(old_id) FROM " + sql.mig("mig_contact"), Map.of());
        if (maxId == null) return;
        long step = Math.max(1000, ctx.props().getBatchSize() * 5L);
        for (long a = ctx.checkpoints().get(ctx.stepId(), "project_contacts"); a < maxId; a += step) {
            long lo = a, hi = Math.min(a + step, maxId);
            ctx.tx().inTx(() -> {
                List<Row> groups = db.rows("SELECT m.project_id, m.organization_id, mm.new_id AS contact_id, MAX(m.opt_in) opt_in, "
                        + "MAX(m.opt_out) opt_out, MIN(c.created_at) created_at, MIN(m.new_user_id) user_id FROM " + sql.mig("mig_contact") + " m "
                        + "JOIN " + sql.mig("migration_id_map") + " mm ON mm.entity = 'contact' AND mm.old_id = m.old_id "
                        + "JOIN " + sql.old("chat_contacts") + " c ON c.id = m.old_id "
                        + "WHERE m.old_id > :a AND m.old_id <= :b AND m.placeable = 1" + scope
                        + " GROUP BY m.project_id, m.organization_id, mm.new_id", Map.of("a", lo, "b", hi));
                s.read += groups.size();
                if (!groups.isEmpty()) {
                    List<Object[]> pairs = groups.stream().map(g -> new Object[]{g.lng("project_id"), g.lng("contact_id")}).toList();
                    Set<String> existing = new HashSet<>();
                    for (Row e : db.rows("SELECT project_id, contact_id FROM " + sql.tgt("project_contacts")
                            + " WHERE (project_id, contact_id) IN (:pairs)", Map.of("pairs", pairs)))
                        existing.add(e.lng("project_id") + ":" + e.lng("contact_id"));
                    List<Map<String, ?>> rows = new ArrayList<>();
                    List<Map<String, ?>> consent = new ArrayList<>();
                    for (Row g : groups) {
                        if (existing.contains(g.lng("project_id") + ":" + g.lng("contact_id"))) { s.matchedExisting++; continue; }
                        long org = g.lng("organization_id");
                        boolean out = g.bool("opt_out");
                        boolean in = g.bool("opt_in") && !out;
                        LocalDateTime at = g.dtOr("created_at", StepContext.now());
                        rows.add(Db.vals().with("created_at", at).with("updated_at", StepContext.now()).with("assigned_at", null)
                                .with("assignee_id", null).with("assignment_status", "UNASSIGNED").with("opted_in", in)
                                .with("opted_in_at", in ? at : null).with("opted_out", out).with("opted_out_at", out ? at : null)
                                .with("organization_id", org).with("is_primary", null).with("project_id", g.lng("project_id"))
                                .with("removed_at", null).with("contact_id", g.lng("contact_id")).with("source_id", source)
                                .with("stage_changed_at", null));
                        if (in || out)
                            consent.add(Db.vals().with("channel", "WHATSAPP").with("consent_type", in ? "OPT_IN" : "OPT_OUT")
                                    .with("created_at", at).with("created_by", ctx.userRef(g.lng("user_id"))).with("organization_id", org)
                                    .with("project_id", g.lng("project_id"))
                                    .with("remarks", in ? "Migrated from legacy contact (allowed_broadcast = 1)" : "Migrated from legacy contact (blocked / blacklisted)")
                                    .with("source", "IMPORT").with("contact_id", g.lng("contact_id")));
                    }
                    s.inserted += ctx.insertBatchSafe(sql.tgt("project_contacts"), rows, null, "chat_contacts", "contact_id", "project_id");
                    cs.inserted += ctx.insertBatchSafe(sql.tgt("contact_consent_history"), consent, null, "chat_contacts", "contact_id", "project_id");
                }
                ctx.checkpoints().save(ctx.stepId(), "project_contacts", hi);
            });
        }
    }

    /** project_contacts.assignee_id from the newest active old agent assignment (only where still unassigned) */
    private void assignees(StepContext ctx) {
        Sql sql = ctx.sql();
        StepStats.Entity s = ctx.stats().entity("project_contact_assignee");
        ctx.tx().inTx(() -> s.refreshed += ctx.db().exec("UPDATE " + sql.tgt("project_contacts") + " pc JOIN ("
                + "  SELECT m.project_id, mc.new_id AS contact_id, MAX(aa.assigned_at) AS at, "
                + "         CAST(SUBSTRING_INDEX(GROUP_CONCAT(aa.agent_id ORDER BY aa.assigned_at DESC, aa.id DESC), ',', 1) AS UNSIGNED) AS agent_old "
                + "  FROM " + sql.old("agent_assignments") + " aa "
                + "  JOIN " + sql.mig("mig_contact") + " m ON m.old_id = aa.contact_id "
                + "  JOIN " + sql.mig("migration_id_map") + " mc ON mc.entity = 'contact' AND mc.old_id = aa.contact_id "
                + "  WHERE aa.status = 'active' AND aa.deleted_at IS NULL" + ctx.scope().sql("m.project_id")
                + "  GROUP BY m.project_id, mc.new_id) x ON x.project_id = pc.project_id AND x.contact_id = pc.contact_id "
                + "JOIN " + sql.mig("migration_id_map") + " mu ON mu.entity = 'user' AND mu.old_id = x.agent_old "
                + "SET pc.assignee_id = " + ctx.userRefs().refSql("mu.new_id") + ", pc.assigned_at = x.at, pc.assignment_status = 'ACTIVE' "
                + "WHERE pc.assignee_id IS NULL", Map.of()));
    }

    // ------------------------------------------------------------------------------------------- tags

    private void tags(StepContext ctx) {
        Db db = ctx.db();
        Sql sql = ctx.sql();
        StepStats.Entity s = ctx.stats().entity("tag");
        for (Row t : db.rows("SELECT * FROM " + sql.old("tags") + " ORDER BY id")) {
            long oldId = t.lng("id");
            s.read++;
            if (ctx.idMap().has("tag", oldId)) { s.skippedMapped++; continue; }
            if (!t.isNull("deleted_at")) { ctx.problems().info("tags", oldId, "SOFT_DELETED_SKIPPED", "deleted tag not migrated"); continue; }
            Tenant ten = ctx.place("tags", oldId, t.lng("user_id"));
            if (ten == null) continue;
            String name = Text.cut(Text.firstNonBlank(t.str("name"), "Tag " + oldId), 120);
            ctx.tx().row(ctx, "tags", oldId, () -> {
                Long existing = db.findId(sql.tgt("contact_tags"), Db.vals().with("organization_id", ten.orgId())
                        .with("project_id", ten.projectId()).with("dedupe_name", name));
                if (existing != null) { ctx.idMap().put("tag", oldId, existing); s.matchedExisting++; return; }
                String color = t.text("tag_color");
                if (Text.longer(color, 15)) ctx.problems().warn("tags", oldId, "TRUNCATED", "tag_color '" + color + "' longer than 15 -> NULL");
                long id = db.insert(sql.tgt("contact_tags"), Db.vals().with("created_at", t.dtOr("created_at", StepContext.now()))
                        .with("updated_at", t.dtOr("updated_at", StepContext.now())).with("deleted_at", null)
                        .with("is_active", "active".equalsIgnoreCase(t.str("status"))).with("color", Text.longer(color, 15) ? null : color)
                        .with("created_by", ctx.userRef(ten.userId())).with("dedupe_name", name).with("name", name)
                        .with("organization_id", ten.orgId()).with("project_id", ten.projectId()).with("scope", "PROJECT")
                        .with("is_system", false).with("usage_count", 0).with("uuid", UUID.randomUUID().toString()));
                ctx.idMap().put("tag", oldId, id);
                s.inserted++;
            });
        }
    }

    private void tagAssignments(StepContext ctx) {
        Sql sql = ctx.sql();
        String insert = "INSERT INTO " + sql.tgt("contact_tag_assignments") + " (assigned_at, assigned_by, organization_id, project_id, contact_id, tag_id) "
                + "SELECT MIN(COALESCE(tc.created_at, NOW(6))), NULL, t.organization_id, t.project_id, mc.new_id, t.id "
                + "FROM " + sql.old("tag_contacts") + " tc "
                + "JOIN " + sql.mig("migration_id_map") + " mt ON mt.entity = 'tag' AND mt.old_id = tc.tag_id "
                + "JOIN " + sql.tgt("contact_tags") + " t ON t.id = mt.new_id "
                + "JOIN " + sql.mig("mig_contact") + " mco ON mco.old_id = tc.contact_id AND mco.project_id = t.project_id "
                + "JOIN " + sql.mig("migration_id_map") + " mc ON mc.entity = 'contact' AND mc.old_id = tc.contact_id "
                + "WHERE tc.deleted_at IS NULL AND tc.id > :a AND tc.id <= :b" + ctx.scope().sql("t.project_id")
                + " AND NOT EXISTS (SELECT 1 FROM " + sql.tgt("contact_tag_assignments") + " y WHERE y.contact_id = mc.new_id AND y.tag_id = t.id) "
                + "GROUP BY mc.new_id, t.id, t.organization_id, t.project_id";
        long n = rangeInsert(ctx, "tag_assignments", sql.old("tag_contacts"), insert, ctx.stats().entity("contact_tag_assignment"));
        if (!ctx.scope().isPilot()) {
            long missing = ctx.db().count("SELECT COUNT(*) FROM " + sql.old("tag_contacts") + " tc WHERE tc.deleted_at IS NULL AND NOT EXISTS ("
                    + "SELECT 1 FROM " + sql.mig("migration_id_map") + " mt JOIN " + sql.mig("migration_id_map") + " mc ON mc.entity = 'contact' "
                    + "JOIN " + sql.tgt("contact_tag_assignments") + " y ON y.contact_id = mc.new_id AND y.tag_id = mt.new_id "
                    + "WHERE mt.entity = 'tag' AND mt.old_id = tc.tag_id AND mc.old_id = tc.contact_id)");
            ctx.problems().aggregate(ProblemLog.Severity.WARN, "tag_contacts", "NOT_LINKED", missing,
                    "tag links not migrated: tag or contact not migrated, or contact of another project");
        }
    }

    // ------------------------------------------------------------------------------------------- lists

    private void lists(StepContext ctx) {
        Db db = ctx.db();
        Sql sql = ctx.sql();
        StepStats.Entity s = ctx.stats().entity("list");
        for (Row g : db.rows("SELECT * FROM " + sql.old("groups") + " ORDER BY id")) {
            long oldId = g.lng("id");
            s.read++;
            if (ctx.idMap().has("list", oldId)) { s.skippedMapped++; continue; }
            if (!g.isNull("deleted_at")) { ctx.problems().info("groups", oldId, "SOFT_DELETED_SKIPPED", "deleted group not migrated"); continue; }
            Tenant ten = ctx.place("groups", oldId, g.lng("user_id"));
            if (ten == null) continue;
            String name = Text.cut(Text.firstNonBlank(g.str("name"), "List " + oldId), 200);
            ctx.tx().row(ctx, "groups", oldId, () -> {
                Long existing = db.findId(sql.tgt("contact_lists"), Db.vals().with("organization_id", ten.orgId())
                        .with("project_id", ten.projectId()).with("dedupe_name", name));
                if (existing != null) { ctx.idMap().put("list", oldId, existing); s.matchedExisting++; return; }
                long id = db.insert(sql.tgt("contact_lists"), Db.vals().with("created_at", g.dtOr("created_at", StepContext.now()))
                        .with("updated_at", g.dtOr("updated_at", StepContext.now())).with("deleted_at", null)
                        .with("is_active", "1".equals(g.str("status"))).with("contact_count", 0).with("created_by", ctx.userRef(ten.userId()))
                        .with("dedupe_name", name).with("description", g.text("type") == null ? null : "Legacy group type: " + g.text("type"))
                        .with("name", name).with("organization_id", ten.orgId()).with("project_id", ten.projectId())
                        .with("scope", "PROJECT").with("is_system", false).with("uuid", UUID.randomUUID().toString()));
                ctx.idMap().put("list", oldId, id);
                s.inserted++;
            });
        }
    }

    private void listMembers(StepContext ctx) {
        Sql sql = ctx.sql();
        String insert = "INSERT INTO " + sql.tgt("contact_list_contacts") + " (added_at, added_by, organization_id, contact_id, list_id) "
                + "SELECT MIN(COALESCE(gm.created_at, NOW(6))), NULL, l.organization_id, mc.new_id, l.id "
                + "FROM " + sql.old("group_members") + " gm "
                + "JOIN " + sql.mig("migration_id_map") + " ml ON ml.entity = 'list' AND ml.old_id = gm.group_id "
                + "JOIN " + sql.tgt("contact_lists") + " l ON l.id = ml.new_id "
                + "JOIN " + sql.mig("mig_contact") + " mco ON mco.old_id = gm.contact_id AND mco.project_id = l.project_id "
                + "JOIN " + sql.mig("migration_id_map") + " mc ON mc.entity = 'contact' AND mc.old_id = gm.contact_id "
                + "WHERE gm.deleted_at IS NULL AND gm.id > :a AND gm.id <= :b" + ctx.scope().sql("l.project_id")
                + " AND NOT EXISTS (SELECT 1 FROM " + sql.tgt("contact_list_contacts") + " y WHERE y.list_id = l.id AND y.contact_id = mc.new_id) "
                + "GROUP BY mc.new_id, l.id, l.organization_id";
        rangeInsert(ctx, "list_members", sql.old("group_members"), insert, ctx.stats().entity("contact_list_contact"));
        if (!ctx.scope().isPilot()) {
            long missing = ctx.db().count("SELECT COUNT(*) FROM " + sql.old("group_members") + " gm WHERE gm.deleted_at IS NULL AND NOT EXISTS ("
                    + "SELECT 1 FROM " + sql.mig("migration_id_map") + " ml JOIN " + sql.mig("migration_id_map") + " mc ON mc.entity = 'contact' "
                    + "JOIN " + sql.tgt("contact_list_contacts") + " y ON y.list_id = ml.new_id AND y.contact_id = mc.new_id "
                    + "WHERE ml.entity = 'list' AND ml.old_id = gm.group_id AND mc.old_id = gm.contact_id)");
            ctx.problems().aggregate(ProblemLog.Severity.WARN, "group_members", "NOT_LINKED", missing,
                    "group members not migrated: group or contact not migrated, or contact of another project");
        }
    }

    // ------------------------------------------------------------------------------------------- attributes

    private void attributes(StepContext ctx) {
        Db db = ctx.db();
        Sql sql = ctx.sql();
        StepStats.Entity s = ctx.stats().entity("attribute_definition");
        Map<String, Long> defs = new HashMap<>();   // org:key -> id

        // names per organization: attribute_masters (owner) + names used on contacts
        Map<String, String[]> wanted = new LinkedHashMap<>();   // org:key -> [displayName, oldMasterId or null]
        for (Row a : db.rows("SELECT id, user_id, attribute FROM " + sql.old("attribute_masters") + " ORDER BY id")) {
            Tenant t = ctx.tenants().resolve(a.lng("user_id"));
            if (!t.ok() || t.orgId() == null || !ctx.scope().includes(t)) continue;
            String name = Text.trimToNull(a.str("attribute"));
            if (name == null) continue;
            wanted.putIfAbsent(t.orgId() + ":" + Text.cut(Text.snake(name), 120), new String[]{name, String.valueOf(a.lng("id"))});
        }
        for (Row a : db.rows("SELECT DISTINCT m.organization_id, ca.attribute FROM " + sql.old("contact_attributes") + " ca JOIN "
                + sql.mig("mig_contact") + " m ON m.old_id = ca.contact_id WHERE m.placeable = 1" + ctx.scope().sql("m.project_id"))) {
            String name = Text.trimToNull(a.str("attribute"));
            if (name == null) continue;
            wanted.putIfAbsent(a.lng("organization_id") + ":" + Text.cut(Text.snake(name), 120), new String[]{name, null});
        }
        for (Map.Entry<String, String[]> e : wanted.entrySet()) {
            s.read++;
            String[] k = e.getKey().split(":", 2);
            long org = Long.parseLong(k[0]);
            String key = k[1];
            String display = Text.cut(e.getValue()[0], 200);
            Long oldMaster = e.getValue()[1] == null ? null : Long.parseLong(e.getValue()[1]);
            ctx.tx().row(ctx, "attribute_masters", oldMaster, () -> {
                Long existing = db.findId(sql.tgt("attribute_definitions"), Map.of("organization_id", org, "attribute_key", key));
                long id;
                if (existing != null) { id = existing; s.matchedExisting++; }
                else {
                    id = db.insert(sql.tgt("attribute_definitions"), Db.vals().with("created_at", StepContext.now())
                            .with("updated_at", StepContext.now()).with("allow_bulk_update", true).with("attribute_key", key)
                            .with("created_by", null).with("data_type", "TEXT").with("display_name", display)
                            .with("is_editable", true).with("is_filterable", true).with("organization_id", org)
                            .with("is_required", false).with("is_searchable", true).with("is_sortable", false).with("is_system", false)
                            .with("is_unique", false).with("uuid", UUID.randomUUID().toString()).with("is_visible", true)
                            .with("description", "Migrated from legacy contact attribute '" + Text.cut(e.getValue()[0], 900) + "'"));
                    s.inserted++;
                }
                if (oldMaster != null) ctx.idMap().put("attribute", oldMaster, id);
                defs.put(e.getKey(), id);
            });
        }

        // values: latest old row wins; rows written by the migration are marked updated_by = 'migration'
        StepStats.Entity v = ctx.stats().entity("attribute_value");
        String upsert = " ON DUPLICATE KEY UPDATE value_text = IF(updated_by = '" + MIGRATION_MARK + "', VALUES(value_text), value_text), "
                + "updated_at = IF(updated_by = '" + MIGRATION_MARK + "', VALUES(updated_at), updated_at)";
        ctx.pages("attribute_values", "SELECT ca.id, ca.contact_id, ca.attribute, ca.attribute_value, ca.created_at, ca.updated_at, "
                + "m.organization_id, m.project_id, mc.new_id AS contact_new FROM " + sql.old("contact_attributes") + " ca "
                + "JOIN " + sql.mig("mig_contact") + " m ON m.old_id = ca.contact_id "
                + "JOIN " + sql.mig("migration_id_map") + " mc ON mc.entity = 'contact' AND mc.old_id = ca.contact_id "
                + "WHERE ca.id > :lastId" + ctx.scope().sql("m.project_id") + " ORDER BY ca.id LIMIT :limit", Map.of(), "id", page -> {
            Map<String, Map<String, ?>> latest = new LinkedHashMap<>();
            for (Row r : page) {
                v.read++;
                String name = Text.trimToNull(r.str("attribute"));
                Long def = name == null ? null : defs.get(r.lng("organization_id") + ":" + Text.cut(Text.snake(name), 120));
                if (def == null) {
                    if (name != null) {
                        def = db.longValue("SELECT id FROM " + sql.tgt("attribute_definitions") + " WHERE organization_id = :o AND attribute_key = :k",
                                Map.of("o", r.lng("organization_id"), "k", Text.cut(Text.snake(name), 120)));
                        if (def != null) defs.put(r.lng("organization_id") + ":" + Text.cut(Text.snake(name), 120), def);
                    }
                    if (def == null) { v.errors++; continue; }
                }
                latest.put(r.lng("project_id") + ":" + r.lng("contact_new") + ":" + def, Db.vals()
                        .with("created_at", r.dtOr("created_at", StepContext.now())).with("organization_id", r.lng("organization_id"))
                        .with("project_id", r.lng("project_id")).with("updated_at", r.dtOr("updated_at", StepContext.now()))
                        .with("updated_by", MIGRATION_MARK).with("updated_source", "IMPORT").with("value_text", Text.cut(r.str("attribute_value"), 16000))
                        .with("contact_id", r.lng("contact_new")).with("attribute_definition_id", def));
            }
            if (latest.isEmpty()) return;
            List<Object[]> keys = latest.values().stream().map(m -> new Object[]{m.get("project_id"), m.get("contact_id"), m.get("attribute_definition_id")}).toList();
            long existing = db.count("SELECT COUNT(*) FROM " + sql.tgt("contact_attribute_values")
                    + " WHERE (project_id, contact_id, attribute_definition_id) IN (:k)", Map.of("k", keys));
            ctx.insertBatchSafe(sql.tgt("contact_attribute_values"), new ArrayList<>(latest.values()), upsert, "contact_attributes", "contact_id", "attribute_definition_id");
            v.inserted += latest.size() - existing;
            v.matchedExisting += existing;
        });
    }

    // ------------------------------------------------------------------------------------------- notes

    private void notes(StepContext ctx) {
        Db db = ctx.db();
        Sql sql = ctx.sql();
        StepStats.Entity s = ctx.stats().entity("contact_note");
        ctx.pages("notes", "SELECT n.id, n.user_id, n.chat_contact_id, n.note, n.created_at, n.updated_at, m.organization_id, m.project_id, "
                + "mc.new_id AS contact_new FROM " + sql.old("chat_contact_notes") + " n "
                + "JOIN " + sql.mig("mig_contact") + " m ON m.old_id = n.chat_contact_id "
                + "JOIN " + sql.mig("migration_id_map") + " mc ON mc.entity = 'contact' AND mc.old_id = n.chat_contact_id "
                + "WHERE n.id > :lastId" + ctx.scope().sql("m.project_id") + " ORDER BY n.id LIMIT :limit", Map.of(), "id", page -> {
            for (Row n : page) {
                long oldId = n.lng("id");
                s.read++;
                if (ctx.idMap().has("contact_note", oldId)) { s.skippedMapped++; continue; }
                if (Text.trimToNull(n.str("note")) == null) continue;
                Long author = ctx.idMap().get("user", n.lng("user_id"));
                ctx.tx().row(ctx, "chat_contact_notes", oldId, () -> {
                    long id = db.insert(sql.tgt("contact_notes"), Db.vals().with("created_at", n.dtOr("created_at", StepContext.now()))
                            .with("updated_at", n.dtOr("updated_at", n.dtOr("created_at", StepContext.now()))).with("deleted_at", null)
                            .with("is_completed", false).with("created_by", ctx.userRef(author)).with("note_text", n.str("note"))
                            .with("note_type", "NOTE").with("organization_id", n.lng("organization_id")).with("is_pinned", false)
                            .with("project_id", n.lng("project_id")).with("updated_by", ctx.userRef(author))
                            .with("uuid", UUID.randomUUID().toString()).with("visibility", "TEAM").with("contact_id", n.lng("contact_new")));
                    ctx.idMap().put("contact_note", oldId, id);
                    s.inserted++;
                });
            }
        });
    }

    // ------------------------------------------------------------------------------------------- helpers

    /** runs an INSERT ... SELECT over id ranges of an old table, with a checkpoint per range */
    private long rangeInsert(StepContext ctx, String phase, String oldTable, String insertSql, StepStats.Entity s) {
        Long maxId = ctx.db().longValue("SELECT MAX(id) FROM " + oldTable, Map.of());
        if (maxId == null) return 0;
        long step = Math.max(1000, ctx.props().getBatchSize() * 10L);
        long total = 0;
        for (long a = ctx.checkpoints().get(ctx.stepId(), phase); a < maxId; a += step) {
            long lo = a, hi = Math.min(a + step, maxId);
            long[] n = new long[1];
            ctx.tx().inTx(() -> {
                n[0] = ctx.db().exec(insertSql, Map.of("a", lo, "b", hi));
                ctx.checkpoints().save(ctx.stepId(), phase, hi);
            });
            total += n[0];
        }
        s.inserted += total;
        return total;
    }
}
