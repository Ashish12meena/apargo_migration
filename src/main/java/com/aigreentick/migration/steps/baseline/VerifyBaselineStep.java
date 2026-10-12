package com.aigreentick.migration.steps.baseline;

import com.aigreentick.migration.config.MigrationProperties;
import com.aigreentick.migration.core.*;
import com.aigreentick.migration.core.TenantResolver.OldUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 00 — reproduces the Phase 1 numbers (plan section 5.2), stops the run when they differ from
 * {@code migration.baseline.*} (enforce=true), and reports follow-ups F1-F5 as INFO rows in mig_errors.
 * The project-map backfill and mig_tenant_map are done by {@link Bootstrap} before every run.
 */
@Component
public class VerifyBaselineStep implements MigrationStep {
    private static final Logger log = LoggerFactory.getLogger(VerifyBaselineStep.class);

    @Override public String id() { return "00-baseline"; }
    @Override public int order() { return 0; }
    @Override public String title() { return "verify Phase 1/2 baseline, report F1-F5"; }

    @Override
    public void run(StepContext ctx) {
        Db db = ctx.db();
        Sql sql = ctx.sql();
        MigrationProperties.Baseline exp = ctx.props().getBaseline();
        int customer = ctx.props().getRoles().getCustomerRoleId();
        int agent = ctx.props().getRoles().getAgentRoleId();
        ctx.stats().entity("baseline");

        // ---------------- section 5.2 numbers
        long oldUsers = db.count("SELECT COUNT(*) FROM " + sql.old("users"));
        long selected = db.count("SELECT COUNT(*) FROM " + sql.old("users") + " WHERE role_id IN (" + customer + ", " + agent
                + ") OR id IN (SELECT admin_user_id FROM " + sql.old("resellers") + " WHERE deleted_at IS NULL)");
        long mapped = db.count("SELECT COUNT(*) FROM " + sql.mig("migration_id_map") + " WHERE entity = 'user'");
        long distinct = db.count("SELECT COUNT(DISTINCT new_id) FROM " + sql.mig("migration_id_map") + " WHERE entity = 'user'");
        long newTotal = db.count("SELECT COUNT(*) FROM " + sql.tgt("users"));
        long seed = db.count("SELECT COUNT(*) FROM " + sql.tgt("users") + " u WHERE NOT EXISTS (SELECT 1 FROM "
                + sql.mig("migration_id_map") + " m WHERE m.entity = 'user' AND m.new_id = u.id)");
        long orgs = db.count("SELECT COUNT(*) FROM " + sql.mig("migration_id_map") + " WHERE entity = 'org'");
        long projects = db.count("SELECT COUNT(*) FROM " + sql.mig("migration_id_map") + " WHERE entity = 'project'");

        log.info("  old users {} | selected by Phase 1 {} | mapped {} | distinct new {} | new users total {} | not from migration {}",
                oldUsers, selected, mapped, distinct, newTotal, seed);
        log.info("  orgs mapped {} | customer projects mapped {}", orgs, projects);

        List<String> mismatches = new ArrayList<>();
        check(ctx, "baseline.old_users", exp.getOldUsers(), oldUsers, mismatches);
        check(ctx, "baseline.mapped_users", exp.getMappedUsers(), mapped, mismatches);
        check(ctx, "baseline.distinct_new_users", exp.getDistinctNewUsers(), distinct, mismatches);
        check(ctx, "baseline.new_users_total", exp.getNewUsersTotal(), newTotal, mismatches);
        check(ctx, "baseline.selected_equals_mapped", selected, mapped, mismatches);
        if (mapped == 0) mismatches.add("no 'user' rows in migration_id_map: Phase 1 output not found");

        // roles needed by the auth steps
        for (String[] r : new String[][]{{ctx.props().getRoles().getOrgOwnerSlug(), "organization"},
                {ctx.props().getRoles().getProjectAdminSlug(), "project"}, {ctx.props().getRoles().getProjectAgentSlug(), "project"}}) {
            try { ctx.roles().id(r[0], r[1]); } catch (IllegalStateException e) { mismatches.add(e.getMessage()); }
        }

        // ---------------- F1..F5 follow-ups
        TenantResolver tr = ctx.tenants();
        Map<Long, List<Long>> customersByNewUser = new TreeMap<>();
        int f2 = 0, f3 = 0, f4 = 0, noProject = 0;
        for (OldUser u : tr.users().values()) {
            Long newId = ctx.idMap().get("user", u.id());
            if (newId == null) continue;
            if (u.deleted()) f2++;
            if (u.roleId() == customer) {
                customersByNewUser.computeIfAbsent(newId, k -> new ArrayList<>()).add(u.id());
                if (u.resellerId() == null) {
                    f3++;
                    ctx.problems().info("users", u.id(), "F3_NO_RESELLER", "customer " + u.email() + " has no reseller_id (D1 = "
                            + ctx.props().getTenant().getNoResellerPolicy() + ")");
                } else if (!tr.resolve(u.id()).hasProject()) {
                    noProject++;
                    ctx.problems().info("users", u.id(), "CUSTOMER_NO_PROJECT", tr.resolve(u.id()).reason());
                }
            }
            if (u.roleId() == agent && !tr.resolve(u.id()).hasProject()) {
                f4++;
                ctx.problems().info("users", u.id(), "F4_AGENT_UNPLACED", tr.resolve(u.id()).reason());
            }
        }
        int f1 = 0;
        for (Map.Entry<Long, List<Long>> e : customersByNewUser.entrySet()) {
            if (e.getValue().size() < 2) continue;
            f1++;
            ctx.problems().info("users", e.getValue().get(0), "F1_MERGED_CUSTOMERS",
                    "new user " + e.getKey() + " absorbed old customers " + e.getValue() + " (D4)");
        }
        if (f2 > 0) ctx.problems().info("users", null, "F2_SOFT_DELETED", f2 + " migrated old users are soft-deleted in the old DB (D5)");

        int f5 = 0;
        for (Row r : db.rows("SELECT r.id, r.admin_user_id, "
                + "(SELECT COUNT(*) FROM " + sql.old("whatsapp_accounts") + " w WHERE w.user_id = r.admin_user_id AND w.deleted_at IS NULL) wabas, "
                + "(SELECT COUNT(*) FROM " + sql.old("templates") + " t WHERE t.user_id = r.admin_user_id AND t.deleted_at IS NULL) templates, "
                + "(SELECT COUNT(*) FROM " + sql.old("chat_contacts") + " c WHERE c.user_id = r.admin_user_id AND c.deleted_at IS NULL) contacts, "
                + "(SELECT COUNT(*) FROM " + sql.old("broadcasts") + " b WHERE b.user_id = r.admin_user_id) broadcasts "
                + "FROM " + sql.old("resellers") + " r WHERE r.deleted_at IS NULL")) {
            long sum = r.lng("wabas", 0) + r.lng("templates", 0) + r.lng("contacts", 0) + r.lng("broadcasts", 0);
            if (sum == 0) continue;
            f5++;
            ctx.problems().info("resellers", r.lng("id"), "F5_RESELLER_ADMIN_DATA", "admin user " + r.lng("admin_user_id")
                    + " owns wabas=" + r.lng("wabas") + " templates=" + r.lng("templates") + " contacts=" + r.lng("contacts")
                    + " broadcasts=" + r.lng("broadcasts") + " (D2 = " + ctx.props().getTenant().getResellerAdminPolicy() + ")");
        }
        log.info("  F1 merged customers {} | F2 soft-deleted {} | F3 no reseller {} | F4 agents unplaced {} | F5 reseller admins with data {} | customers without project {}",
                f1, f2, f3, f4, f5, noProject);

        // ---------------- target prerequisites for later steps
        String seedProblem = com.aigreentick.migration.steps.contact.SeedRows.check(ctx);
        if (seedProblem != null) ctx.problems().warn("contact_sources", null, "SEED_MISSING", seedProblem + " - step 08b will stop");
        Row fmt = db.row("SELECT SUM(normalized_phone LIKE '+%') plus, COUNT(*) n FROM (SELECT normalized_phone FROM "
                + sql.tgt("contacts") + " WHERE deleted_at IS NULL LIMIT 1000) x", Map.of());
        if (fmt != null && fmt.lng("n", 0) > 0) {
            boolean existingPlus = fmt.lng("plus", 0) * 2 > fmt.lng("n", 0);
            if (existingPlus != ctx.props().getContacts().isPhoneWithPlus())
                ctx.problems().warn("contacts", null, "PHONE_FORMAT", "existing contacts store normalized_phone "
                        + (existingPlus ? "WITH" : "WITHOUT") + " '+', but migration.contacts.phone-with-plus="
                        + ctx.props().getContacts().isPhoneWithPlus() + ": duplicates would not be detected");
            log.info("  existing contacts: normalized_phone {} '+'", existingPlus ? "with" : "without");
        }

        if (!mismatches.isEmpty()) {
            mismatches.forEach(m -> ctx.problems().error("baseline", null, "BASELINE_MISMATCH", m));
            if (exp.isEnforce())
                throw new IllegalStateException("baseline differs from migration.baseline.* : " + mismatches
                        + " - fix Phase 1/2 or update the expected values (or --migration.baseline.enforce=false)");
            log.warn("  baseline differs (enforce=false): {}", mismatches);
        }
    }

    private static void check(StepContext ctx, String name, Long expected, long actual, List<String> mismatches) {
        boolean ok = expected == null || expected == actual;
        ctx.tx().requiresNew(() -> ctx.db().exec("INSERT INTO " + ctx.sql().mig("mig_validation")
                        + " (run_id, check_name, expected, actual, passed, detail) VALUES (:r, :c, :e, :a, :p, :d)",
                Db.vals().with("r", ctx.runId()).with("c", name).with("e", expected).with("a", actual).with("p", ok)
                        .with("d", expected == null ? "report only" : null)));
        if (!ok) mismatches.add(name + ": expected " + expected + ", actual " + actual);
    }
}
