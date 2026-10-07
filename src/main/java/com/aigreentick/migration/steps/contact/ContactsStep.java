package com.aigreentick.migration.steps.contact;

import java.util.HashMap;
import java.util.List;
import com.aigreentick.migration.core.*;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 08b — mig_contact -> contacts (bulk SQL, one batch per old-id range).
 * <ul>
 *   <li>contacts are per ORGANIZATION: one contact per (organization_id, E.164); the lowest old id is the master</li>
 *   <li>skip-if-exists: a live contact with the same (organization_id, dedupe_phone) — including contacts created in the
 *       new system — is reused, never overwritten</li>
 *   <li>every old contact (master or duplicate) is mapped to that one contact (entity {@code contact}), so tags, lists,
 *       notes, chats and recipients of duplicates follow the master</li>
 *   <li>dedupe_phone is a plain column on the server: written = normalized_phone (live rows)</li>
 *   <li>source = the seeded "Migration" contact source (migration.contacts.source-id, 18); status active-status-id (1), invalid-status-id (4) for
 *       numbers libphonenumber does not accept</li>
 * </ul>
 */
@Component
public class ContactsStep implements MigrationStep {

    @Override public String id() { return "08b-contacts"; }
    @Override public int order() { return 820; }
    @Override public String title() { return "mig_contact -> contacts (one per organization + phone)"; }

    @Override
    public void run(StepContext ctx) {
        Db db = ctx.db();
        Sql sql = ctx.sql();
        long active = ctx.props().getContacts().getActiveStatusId();
        long invalid = ctx.props().getContacts().getInvalidStatusId();
        long source = ctx.props().getContacts().getSourceId();
        SeedRows.require(ctx, active, invalid, source);
        String scope = ctx.scope().sql("m.project_id");

        // 2. contacts, batch per old-id range
        StepStats.Entity s = ctx.stats().entity("contact");
        Long maxId = db.longValue("SELECT MAX(old_id) FROM " + sql.mig("mig_contact"), Map.of());
        if (maxId == null) return;
        long step = Math.max(1000, ctx.props().getBatchSize() * 5L);
        long from = ctx.checkpoints().get(ctx.stepId(), "main");
        String createdBy = ctx.userRefs().refSql("m.new_user_id");
        String c0900 = " COLLATE utf8mb4_0900_ai_ci";
        String insert = "INSERT INTO " + sql.tgt("contacts") + " (created_at, updated_at, deleted_at, country_code, created_by, dedupe_phone, "
                + "dial_code, display_name, email, normalized_phone, organization_id, phone_number, search_text, is_starred, total_notes, "
                + "total_projects, total_tags, updated_by, uuid, version, wa_number_status, source_id, status_id, inbound_policy) "
                + "SELECT IF(c.created_at IS NULL OR c.created_at < '1970-01-02', NOW(6), c.created_at), "
                + "IF(c.updated_at IS NULL OR c.updated_at < '1970-01-02', IF(c.created_at IS NULL OR c.created_at < '1970-01-02', NOW(6), c.created_at), c.updated_at), "
                + "NULL, m.country_code, " + createdBy + ", "
                + "m.normalized, m.dial_code, LEFT(NULLIF(TRIM(CONVERT(c.name USING utf8mb4)" + c0900 + "), ''), 200), "
                + "CASE WHEN TRIM(c.email) REGEXP '^[^@[:space:]]+@[^@[:space:]]+[.][^@[:space:]]+$' AND CHAR_LENGTH(TRIM(c.email)) <= 200 "
                + "     THEN TRIM(CONVERT(c.email USING utf8mb4)" + c0900 + ") END, "
                + "m.normalized, m.organization_id, LEFT(m.national, 20), "
                + "LEFT(CONCAT_WS(' ', CONVERT(c.name USING utf8mb4)" + c0900 + ", m.normalized, CONVERT(c.email USING utf8mb4)" + c0900 + "), 2000), "
                + "FALSE, 0, 0, 0, NULL, UUID(), 0, 'UNKNOWN', "
                + ":source, "
                + "IF(m.phone_valid = 1, :active, :invalid), 'ALLOWED' "
                + "FROM " + sql.mig("mig_contact") + " m JOIN " + sql.old("chat_contacts") + " c ON c.id = m.old_id "
                + "WHERE m.old_id > :a AND m.old_id <= :b AND m.placeable = 1" + scope
                + " AND m.old_id = (SELECT MIN(m2.old_id) FROM " + sql.mig("mig_contact") + " m2 WHERE m2.organization_id = m.organization_id "
                + "                 AND m2.normalized = m.normalized AND m2.placeable = 1)"
                + " AND NOT EXISTS (SELECT 1 FROM " + sql.mig("migration_id_map") + " mm WHERE mm.entity = 'contact' AND mm.old_id = m.old_id)"
                + " AND NOT EXISTS (SELECT 1 FROM " + sql.tgt("contacts") + " x WHERE x.organization_id = m.organization_id AND x.dedupe_phone = m.normalized)";
        String map = "INSERT INTO " + sql.mig("migration_id_map") + " (entity, old_id, new_id, step) "
                + "SELECT 'contact', m.old_id, x.id, :step FROM " + sql.mig("mig_contact") + " m JOIN " + sql.tgt("contacts") + " x "
                + "ON x.organization_id = m.organization_id AND x.dedupe_phone = m.normalized "
                + "WHERE m.old_id > :a AND m.old_id <= :b AND m.placeable = 1" + scope
                + " AND NOT EXISTS (SELECT 1 FROM " + sql.mig("migration_id_map") + " mm WHERE mm.entity = 'contact' AND mm.old_id = m.old_id)";
        String counts = "SELECT COUNT(*) total, SUM(EXISTS (SELECT 1 FROM " + sql.mig("migration_id_map") + " mm WHERE mm.entity = 'contact' AND mm.old_id = m.old_id)) mapped, "
                + "SUM(NOT EXISTS (SELECT 1 FROM " + sql.mig("migration_id_map") + " mm WHERE mm.entity = 'contact' AND mm.old_id = m.old_id) "
                + "    AND m.old_id <> (SELECT MIN(m2.old_id) FROM " + sql.mig("mig_contact") + " m2 WHERE m2.organization_id = m.organization_id AND m2.normalized = m.normalized AND m2.placeable = 1)) dups "
                + "FROM " + sql.mig("mig_contact") + " m WHERE m.old_id > :a AND m.old_id <= :b AND m.placeable = 1" + scope;

        long duplicates = 0;
        for (long a = from; a < maxId; a += step) {
            long lo = a, hi = Math.min(a + step, maxId);
            Map<String, Object> p = Db.vals().with("a", lo).with("b", hi).with("source", source).with("active", active)
                    .with("invalid", invalid).with("step", ctx.stepId());
            long[] r = new long[3];
            try {
                ctx.tx().inTx(() -> {
                    Row before = db.row(counts, p);
                    r[0] = db.exec(insert, p);
                    r[1] = db.exec(map, p);
                    r[2] = before.lng("dups", 0);
                    s.read += before.lng("total", 0);
                    s.skippedMapped += before.lng("mapped", 0);
                    ctx.checkpoints().save(ctx.stepId(), "main", hi);
                });
            } catch (org.springframework.dao.DataAccessException rangeError) {
                // one bad row (or a live contact created meanwhile) must not stop the run: redo this range row by row
                org.slf4j.LoggerFactory.getLogger(ContactsStep.class).warn("  contacts {}..{} failed as a batch ({}), retrying row by row",
                        lo, hi, Tx.rootMessage(rangeError));
                r[0] = r[1] = r[2] = 0;
                List<Long> ids = db.jdbc().queryForList("SELECT m.old_id FROM " + sql.mig("mig_contact") + " m WHERE m.old_id > :a AND m.old_id <= :b "
                        + "AND m.placeable = 1" + scope + " ORDER BY m.old_id", p, Long.class);
                ctx.tx().inTx(() -> {
                    for (Long id : ids) {
                        Map<String, Object> one = new HashMap<>(p);
                        one.put("a", id - 1);
                        one.put("b", id);
                        s.read++;
                        ctx.tx().row(ctx, "chat_contacts", id, () -> {
                            r[0] += db.exec(insert, one);
                            r[1] += db.exec(map, one);
                        });
                    }
                    ctx.checkpoints().save(ctx.stepId(), "main", hi);
                });
            }
            s.inserted += r[0];
            s.matchedExisting += Math.max(0, r[1] - r[0] - r[2]);
            duplicates += r[2];
        }
        ctx.idMap().invalidate("contact");
        ctx.problems().aggregate(ProblemLog.Severity.INFO, "chat_contacts", "DUPLICATE_MERGED", duplicates,
                "old contacts with the same organization + E.164 phone were mapped to one contact");
        long unmapped = db.count("SELECT COUNT(*) FROM " + sql.mig("mig_contact") + " m WHERE m.placeable = 1" + scope
                + " AND NOT EXISTS (SELECT 1 FROM " + sql.mig("migration_id_map") + " mm WHERE mm.entity = 'contact' AND mm.old_id = m.old_id)");
        ctx.problems().aggregate(ProblemLog.Severity.ERROR, "chat_contacts", "NOT_MAPPED", unmapped,
                "placeable contacts without a contact (e.g. the matching live contact was soft-deleted meanwhile)");
    }
}
