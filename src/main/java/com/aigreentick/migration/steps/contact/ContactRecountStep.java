package com.aigreentick.migration.steps.contact;

import com.aigreentick.migration.core.*;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 08d — recomputes cached counters of everything the migration touched: contacts.total_tags / total_notes /
 * total_projects, contact_tags.usage_count, contact_lists.contact_count. (contact_statistics does not exist on the
 * server, S15.) Counts are recomputed from the real rows, so the step is idempotent.
 */
@Component
public class ContactRecountStep implements MigrationStep {

    @Override public String id() { return "08d-contact-recount"; }
    @Override public int order() { return 840; }
    @Override public String title() { return "recount contact / tag / list counters"; }

    @Override
    public void run(StepContext ctx) {
        Sql sql = ctx.sql();
        Db db = ctx.db();
        String map = sql.mig("migration_id_map");
        StepStats.Entity s = ctx.stats().entity("counters");
        // contacts in chunks of new ids (millions of rows -> no single giant transaction)
        Row range = db.row("SELECT MIN(new_id) lo, MAX(new_id) hi FROM " + map + " WHERE entity = 'contact'", Map.of());
        if (range != null && range.lng("lo") != null) {
            long chunk = 50_000;
            for (long a = range.lng("lo") - 1; a < range.lng("hi"); a += chunk) {
                long lo = a, hi = a + chunk;
                ctx.tx().inTx(() -> s.refreshed += db.exec("UPDATE " + sql.tgt("contacts") + " c JOIN (SELECT DISTINCT new_id FROM " + map
                        + " WHERE entity = 'contact' AND new_id > :lo AND new_id <= :hi) m ON m.new_id = c.id SET "
                        + "c.total_tags = (SELECT COUNT(*) FROM " + sql.tgt("contact_tag_assignments") + " a WHERE a.contact_id = c.id), "
                        + "c.total_notes = (SELECT COUNT(*) FROM " + sql.tgt("contact_notes") + " n WHERE n.contact_id = c.id AND n.deleted_at IS NULL), "
                        + "c.total_projects = (SELECT COUNT(*) FROM " + sql.tgt("project_contacts") + " p WHERE p.contact_id = c.id AND p.removed_at IS NULL)",
                        Map.of("lo", lo, "hi", hi)));
            }
        }
        ctx.tx().inTx(() -> {
            s.refreshed += db.exec("UPDATE " + sql.tgt("contact_tags") + " t JOIN " + map + " m ON m.entity = 'tag' AND m.new_id = t.id "
                    + "SET t.usage_count = (SELECT COUNT(*) FROM " + sql.tgt("contact_tag_assignments") + " a WHERE a.tag_id = t.id)", Map.of());
            s.refreshed += db.exec("UPDATE " + sql.tgt("contact_lists") + " l JOIN " + map + " m ON m.entity = 'list' AND m.new_id = l.id "
                    + "SET l.contact_count = (SELECT COUNT(*) FROM " + sql.tgt("contact_list_contacts") + " a WHERE a.list_id = l.id)", Map.of());
        });
    }
}
