package com.aigreentick.migration.steps.validate;

import com.aigreentick.migration.core.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 99 — relation checks (every one must return 0 for migrated rows: ids changed, relations must not) plus a
 * reconciliation of old rows vs mapped rows per entity. Results go to mig_validation; a failed relation check ends the
 * run as VALIDATION_FAILED (exit 1 with migration.validate.fail-on-error=true).
 */
@Component
public class ValidateStep implements MigrationStep {
    private static final Logger log = LoggerFactory.getLogger(ValidateStep.class);

    @Override public String id() { return "99-validate"; }
    @Override public int order() { return 9900; }
    @Override public String title() { return "relation checks + reconciliation"; }

    @Override
    public void run(StepContext ctx) {
        Sql q = ctx.sql();
        String map = q.mig("migration_id_map");
        Map<String, String> checks = new LinkedHashMap<>();

        // tenant consistency: organization_id of a migrated row = organization of its project
        checks.put("tenant.templates", "SELECT COUNT(*) FROM " + q.tgt("whatsapp_templates") + " t JOIN " + map + " m ON m.entity = 'template' AND m.new_id = t.id "
                + "JOIN " + q.tgt("projects") + " p ON p.id = t.project_id WHERE p.organization_id <> t.organization_id");
        // whatsapp_templates.waba_id is utf8mb4_0900_ai_ci, waba_accounts.waba_id is utf8mb4_unicode_ci
        checks.put("tenant.template_waba", "SELECT COUNT(*) FROM " + q.tgt("whatsapp_templates") + " t JOIN " + map + " m ON m.entity = 'template' AND m.new_id = t.id "
                + "LEFT JOIN " + q.tgt("waba_accounts") + " w ON w.waba_id = t.waba_id COLLATE utf8mb4_unicode_ci AND w.deleted_at IS NULL "
                + "WHERE w.id IS NULL OR w.project_id <> t.project_id OR w.organization_id <> t.organization_id");
        checks.put("tenant.waba_project", "SELECT COUNT(*) FROM " + q.tgt("waba_accounts") + " w JOIN " + q.tgt("projects") + " p ON p.id = w.project_id "
                + "JOIN " + q.tgt("business_managers") + " b ON b.id = w.business_manager_account_id "
                + "WHERE (w.organization_id <> p.organization_id OR b.project_id <> w.project_id OR b.organization_id <> w.organization_id) "
                + "AND w.id IN (SELECT new_id FROM " + map + " WHERE entity = 'waba')");
        checks.put("tenant.project_contacts", "SELECT COUNT(*) FROM " + q.tgt("project_contacts") + " pc JOIN " + q.tgt("contacts") + " c ON c.id = pc.contact_id "
                + "JOIN " + q.tgt("projects") + " p ON p.id = pc.project_id WHERE (c.organization_id <> p.organization_id OR pc.organization_id <> p.organization_id) "
                + "AND pc.contact_id IN (SELECT new_id FROM " + map + " WHERE entity = 'contact')");
        checks.put("tenant.tag_links", "SELECT COUNT(*) FROM " + q.tgt("contact_tag_assignments") + " a JOIN " + q.tgt("contact_tags") + " t ON t.id = a.tag_id "
                + "JOIN " + q.tgt("contacts") + " c ON c.id = a.contact_id WHERE c.organization_id <> t.organization_id "
                + "AND a.tag_id IN (SELECT new_id FROM " + map + " WHERE entity = 'tag')");
        checks.put("tenant.list_links", "SELECT COUNT(*) FROM " + q.tgt("contact_list_contacts") + " a JOIN " + q.tgt("contact_lists") + " l ON l.id = a.list_id "
                + "JOIN " + q.tgt("contacts") + " c ON c.id = a.contact_id WHERE c.organization_id <> l.organization_id "
                + "AND a.list_id IN (SELECT new_id FROM " + map + " WHERE entity = 'list')");
        checks.put("tenant.conversations", "SELECT COUNT(*) FROM " + q.tgt("conversations") + " cv JOIN " + q.mig("mig_conversation") + " x ON x.conversation_id = cv.id "
                + "JOIN " + q.tgt("projects") + " p ON p.id = cv.project_id JOIN " + q.tgt("contacts") + " c ON c.id = cv.contact_id "
                + "JOIN " + q.tgt("waba_accounts") + " w ON w.id = cv.waba_account_id "
                + "WHERE p.organization_id <> cv.organization_id OR c.organization_id <> cv.organization_id OR w.organization_id <> cv.organization_id "
                + "OR w.project_id <> cv.project_id");
        checks.put("tenant.campaigns", "SELECT COUNT(*) FROM " + q.tgt("broadcast_campaigns") + " b JOIN " + map + " m ON m.entity = 'campaign' AND m.new_id = b.id "
                + "JOIN " + q.tgt("projects") + " p ON p.id = b.project_id JOIN " + q.tgt("waba_accounts") + " w ON w.id = b.waba_account_id "
                + "WHERE p.organization_id <> b.organization_id OR w.organization_id <> b.organization_id OR w.project_id <> b.project_id");
        checks.put("tenant.campaign_messages", "SELECT COUNT(*) FROM " + q.tgt("messages") + " m JOIN " + q.tgt("broadcast_recipients") + " r ON r.message_id = m.id "
                + "JOIN " + q.tgt("broadcast_campaigns") + " b ON b.id = r.campaign_id JOIN " + q.tgt("conversations") + " c ON c.id = m.conversation_id "
                + "JOIN " + map + " mm ON mm.entity = 'campaign' AND mm.new_id = b.id "
                + "WHERE m.contact_id <> r.contact_id OR m.project_id <> b.project_id OR c.contact_id <> m.contact_id OR c.project_id <> m.project_id");
        checks.put("relation.recipient_message", "SELECT COUNT(*) FROM " + q.tgt("broadcast_recipients") + " r JOIN " + map
                + " mm ON mm.entity = 'campaign' AND mm.new_id = r.campaign_id WHERE r.state IN ('SENT', 'FAILED') AND r.message_id IS NULL");
        checks.put("tenant.recipients", "SELECT COUNT(*) FROM " + q.tgt("broadcast_recipients") + " r JOIN " + map + " m ON m.entity = 'campaign' AND m.new_id = r.campaign_id "
                + "JOIN " + q.tgt("broadcast_campaigns") + " b ON b.id = r.campaign_id JOIN " + q.tgt("contacts") + " c ON c.id = r.contact_id "
                + "WHERE c.organization_id <> b.organization_id");
        checks.put("tenant.canned", "SELECT COUNT(*) FROM " + q.tgt("canned_responses") + " x JOIN " + map + " m ON m.entity = 'canned' AND m.new_id = x.id "
                + "JOIN " + q.tgt("projects") + " p ON p.id = x.project_id WHERE p.organization_id <> x.organization_id");
        checks.put("tenant.team_members", "SELECT COUNT(*) FROM " + q.tgt("team_members") + " tm JOIN " + map + " m ON m.entity = 'team' AND m.new_id = tm.team_id "
                + "JOIN " + q.tgt("project_teams") + " t ON t.id = tm.team_id LEFT JOIN " + q.tgt("project_members") + " pm "
                + "ON pm.project_id = t.project_id AND pm.user_id = tm.user_id WHERE pm.id IS NULL");

        // parent -> child relations
        checks.put("orphan.template_components", "SELECT COUNT(*) FROM " + q.tgt("whatsapp_template_components") + " x JOIN " + map
                + " m ON m.entity = 'template_component' AND m.new_id = x.id LEFT JOIN " + q.tgt("whatsapp_templates") + " p ON p.id = x.template_id WHERE p.id IS NULL");
        checks.put("relation.template_component_parent", "SELECT COUNT(*) FROM " + q.old("template_components") + " oc "
                + "JOIN " + map + " mc ON mc.entity = 'template_component' AND mc.old_id = oc.id "
                + "JOIN " + map + " mt ON mt.entity = 'template' AND mt.old_id = oc.template_id "
                + "JOIN " + q.tgt("whatsapp_template_components") + " c ON c.id = mc.new_id WHERE c.template_id <> mt.new_id");
        checks.put("relation.messages_conversation", "SELECT COUNT(*) FROM " + q.tgt("messages") + " m JOIN " + map + " mm ON mm.entity = 'message' AND mm.new_id = m.id "
                + "LEFT JOIN " + q.tgt("conversations") + " cv ON cv.id = m.conversation_id "
                + "WHERE cv.id IS NULL OR cv.contact_id <> m.contact_id OR cv.project_id <> m.project_id");
        checks.put("relation.chat_contact", "SELECT COUNT(*) FROM " + q.old("chats") + " ch "
                + "JOIN " + map + " mm ON mm.entity = 'message' AND mm.old_id = ch.id "
                + "JOIN " + map + " mc ON mc.entity = 'contact' AND mc.old_id = ch.contact_id "
                + "JOIN " + q.mig("mig_chat") + " x ON x.old_id = ch.id "
                + "JOIN " + q.tgt("messages") + " m ON m.id = mm.new_id JOIN " + q.tgt("contacts") + " c ON c.id = mc.new_id "
                + "WHERE c.organization_id = x.organization_id AND m.contact_id <> mc.new_id AND m.conversation_id = x.conversation_id");
        checks.put("relation.tag_links", "SELECT COUNT(*) FROM " + q.old("tag_contacts") + " tc "
                + "JOIN " + map + " mt ON mt.entity = 'tag' AND mt.old_id = tc.tag_id JOIN " + map + " mc ON mc.entity = 'contact' AND mc.old_id = tc.contact_id "
                + "JOIN " + q.mig("mig_contact") + " x ON x.old_id = tc.contact_id JOIN " + q.tgt("contact_tags") + " t ON t.id = mt.new_id AND t.project_id = x.project_id "
                + "LEFT JOIN " + q.tgt("contact_tag_assignments") + " a ON a.tag_id = mt.new_id AND a.contact_id = mc.new_id "
                + "WHERE tc.deleted_at IS NULL AND a.id IS NULL");
        checks.put("relation.waba_phone", "SELECT COUNT(*) FROM " + q.old("whatsapp_accounts") + " w JOIN " + map + " mp ON mp.entity = 'waba_phone' AND mp.old_id = w.id "
                + "JOIN " + map + " mw ON mw.entity = 'waba' AND mw.old_id = w.id JOIN " + q.tgt("waba_phone_numbers") + " p ON p.id = mp.new_id "
                + "WHERE p.waba_account_id <> mw.new_id");

        int failed = 0;
        for (Map.Entry<String, String> c : checks.entrySet()) {
            long n;
            try {
                n = ctx.db().count(c.getValue());
            } catch (RuntimeException e) {
                record(ctx, c.getKey(), 0L, null, false, "query failed: " + Tx.rootMessage(e));
                failed++;
                continue;
            }
            boolean ok = n == 0;
            if (!ok) failed++;
            record(ctx, c.getKey(), 0L, n, ok, ok ? null : n + " rows break this relation");
            log.info("  {} {} -> {}", ok ? "OK  " : "FAIL", c.getKey(), n);
        }

        // reconciliation (report only): old rows vs mapped rows per entity
        String[][] recon = {
                {"department", "SELECT COUNT(*) FROM " + q.old("departments")},
                {"team", "SELECT COUNT(*) FROM " + q.old("agent_teams")},
                {"waba_phone", "SELECT COUNT(*) FROM " + q.old("whatsapp_accounts") + " WHERE deleted_at IS NULL"},
                {"template", "SELECT COUNT(*) FROM " + q.old("templates") + " WHERE deleted_at IS NULL"},
                {"contact", "SELECT COUNT(*) FROM " + q.old("chat_contacts") + " WHERE deleted_at IS NULL"},
                {"tag", "SELECT COUNT(*) FROM " + q.old("tags") + " WHERE deleted_at IS NULL"},
                {"list", "SELECT COUNT(*) FROM " + q.old("groups") + " WHERE deleted_at IS NULL"},
                {"contact_note", "SELECT COUNT(*) FROM " + q.old("chat_contact_notes")},
                {"message", "SELECT COUNT(*) FROM " + q.old("chats") + " WHERE deleted_at IS NULL"},
                {"canned", "SELECT COUNT(*) FROM " + q.old("canned_messages") + " WHERE deleted_at IS NULL"},
                {"campaign", "SELECT COUNT(*) FROM " + q.old("broadcasts") + " WHERE deleted_at IS NULL"},
                {"assignment", "SELECT COUNT(*) FROM " + q.old("agent_assignments") + " WHERE deleted_at IS NULL"}};
        for (String[] r : recon) {
            long old = ctx.db().count(r[1]);
            long mapped = ctx.db().count("SELECT COUNT(*) FROM " + map + " WHERE entity = :e", Map.of("e", r[0]));
            record(ctx, "count." + r[0], old, mapped, true, "old rows vs mapped rows (difference = skipped / unplaceable, see mig_errors)");
            log.info("  count.{}: old {} mapped {}", r[0], old, mapped);
        }
        // report only: migrated Business Managers (non Pinnacle) without an ACTIVE token cannot send (old row had no token)
        long noToken = ctx.db().count("SELECT COUNT(*) FROM " + q.tgt("business_managers") + " b LEFT JOIN " + q.tgt("meta_oauth_tokens") + " t "
                + "ON t.business_manager_account_id = b.id AND t.status = 'ACTIVE' WHERE t.id IS NULL AND b.onboarding_provider <> 'PINNACLE' "
                + "AND b.id IN (SELECT new_id FROM " + map + " WHERE entity = 'business_manager')");
        record(ctx, "count.business_manager_without_token", 0L, noToken, true, "Meta Business Managers without an ACTIVE token (old account had no token)");
        log.info("  count.business_manager_without_token: {}", noToken);
        if (failed > 0) throw new MigrationEngine.ValidationFailed(failed + " relation checks failed");
    }

    private static void record(StepContext ctx, String name, Long expected, Long actual, boolean passed, String detail) {
        ctx.tx().requiresNew(() -> ctx.db().exec("INSERT INTO " + ctx.sql().mig("mig_validation")
                        + " (run_id, check_name, expected, actual, passed, detail) VALUES (:r, :c, :e, :a, :p, :d)",
                Db.vals().with("r", ctx.runId()).with("c", name).with("e", expected).with("a", actual).with("p", passed).with("d", detail)));
        if (!passed) ctx.problems().error("validation", null, "CHECK_FAILED", name + ": " + detail);
    }
}
