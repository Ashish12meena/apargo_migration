package com.aigreentick.migration.steps.waba;

import com.aigreentick.migration.config.MigrationProperties;
import com.aigreentick.migration.core.*;
import com.aigreentick.migration.util.Text;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 05 — old {@code whatsapp_accounts} (one row per customer phone number) -> the WABA service tree (schema of 2026-10-07):
 * <pre>
 *  Organization -> Project -> Business Manager -> ACTIVE token
 *                                               -> WABA -> phone numbers
 * </pre>
 * <ul>
 *   <li>{@code whatsapp_biz_id} is the WABA id. The old DB has no Business Manager id, so every Business Manager gets a
 *       placeholder {@code meta_business_id = "legacy-<uuid>"} (NOT NULL, unique). Business Managers are grouped per
 *       (customer project, token): WABAs of one customer that used the same token share one Business Manager, and
 *       every WABA keeps a working token. Business Managers are never shared between projects.</li>
 *   <li>The token is copied as it is (no encryption), {@code status = ACTIVE}, one per Business Manager.</li>
 *   <li>A WABA belongs to one project: when customers of different projects used the same WABA, the first (lowest old
 *       id) keeps it, the others get ERROR WABA_OTHER_PROJECT. A WABA that already exists in another project -> ERROR.</li>
 *   <li>Each project's first WABA becomes {@code is_project_default} (when the project has no default yet).</li>
 *   <li>{@code project_refs} rows (project -> organization) are created for every project that gets a WABA.</li>
 *   <li>Pinnacle (old {@code waba_accounts} with api_key): {@code pinacle_credentials} (credentials as they are) +
 *       {@code pinacle_billing_config}, WABA {@code onboarding_provider = PINNACLE} with {@code bsp_credential_id}.</li>
 *   <li>Phone status '1' ACTIVE, '0' DISABLED, '2' BLOCKED.</li>
 *   <li>Lookups use the plain columns (waba_id, phone_number_id, status, is_project_default) + deleted_at, not the
 *       generated live_* columns, so the step also works where those columns were not created.</li>
 * </ul>
 * Map entities: business_manager, meta_token, waba, waba_phone (old whatsapp_accounts.id), pinnacle_credential (old waba_accounts.id).
 * Refresh: WABA status, phone status / display number.
 */
@Component
public class WabaStep implements MigrationStep {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WabaStep.class);

    @Override public String id() { return "05-waba"; }
    @Override public int order() { return 500; }
    @Override public String title() { return "whatsapp_accounts -> business managers, tokens, WABAs, phone numbers"; }

    private record Acc(Row r, Tenant t) {
        long id() { return r.lng("id"); }
        String waba() { return Text.cut(Text.trimToNull(r.str("whatsapp_biz_id")), 100); }
        String token() { return Text.trimToNull(r.str("parmenent_token")); }
        LocalDateTime touched() { return r.dtOr("updated_at", r.dt("created_at")); }
    }

    /** one WABA of the owner project, with all its old rows */
    private static final class WabaGroup {
        final String wabaId;
        final List<Acc> rows = new ArrayList<>();
        Long existingId;        // live waba_accounts row of the same project (new system / earlier run)
        WabaGroup(String wabaId) { this.wabaId = wabaId; }
        Acc first() { return rows.get(0); }
        Tenant tenant() { return first().t(); }
        String token() {
            return rows.stream().filter(a -> a.token() != null)
                    .max(Comparator.comparing(Acc::touched, Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(Acc::id))
                    .map(Acc::token).orElse(null);
        }
    }

    @Override
    public void run(StepContext ctx) {
        MigrationProperties.Waba cfg = ctx.props().getWaba();
        Db db = ctx.db();
        Sql sql = ctx.sql();
        pinnacleTables = checkSchema(ctx);

        // ---------------- read and place
        String where = cfg.isIncludeDeleted() ? "" : " WHERE deleted_at IS NULL";
        List<Acc> accs = new ArrayList<>();
        StepStats.Entity read = ctx.stats().entity("whatsapp_account");
        for (Row r : db.rows("SELECT id, user_id, whatsapp_no, whatsapp_no_id, whatsapp_biz_id, parmenent_token, status, "
                + "created_at, updated_at, deleted_at FROM " + sql.old("whatsapp_accounts") + where + " ORDER BY id")) {
            read.read++;
            Tenant t = ctx.place("whatsapp_accounts", r.lng("id"), r.lng("user_id"));
            if (t == null) continue;
            if (Text.trimToNull(r.str("whatsapp_biz_id")) == null || Text.trimToNull(r.str("whatsapp_no_id")) == null) {
                ctx.problems().error("whatsapp_accounts", r.lng("id"), "MISSING_META_IDS", "whatsapp_biz_id (WABA id) / whatsapp_no_id empty");
                read.errors++;
                continue;
            }
            accs.add(new Acc(r, t));
        }

        // old Pinnacle rows
        Map<Long, Row> pinByAccount = new HashMap<>();
        Map<String, Row> pinByWaba = new HashMap<>();
        for (Row p : db.rows("SELECT * FROM " + sql.old("waba_accounts") + " ORDER BY id")) {
            if (Text.trimToNull(p.str("api_key")) == null) continue;
            if (p.lng("whatsapp_account_id") != null) pinByAccount.putIfAbsent(p.lng("whatsapp_account_id"), p);
            if (p.text("waba_id") != null) pinByWaba.putIfAbsent(p.text("waba_id"), p);
        }

        // ---------------- one owner project per WABA
        StepStats.Entity sw = ctx.stats().entity("waba");
        Map<String, WabaGroup> groups = new LinkedHashMap<>();
        for (Acc a : accs) {
            WabaGroup g = groups.computeIfAbsent(a.waba(), WabaGroup::new);
            if (g.rows.isEmpty() || Objects.equals(g.tenant().projectId(), a.t().projectId())) {
                g.rows.add(a);
            } else {
                ctx.problems().error("whatsapp_accounts", a.id(), "WABA_OTHER_PROJECT", "WABA " + a.waba() + " is already used by project "
                        + g.tenant().projectId() + " (old row " + g.first().id() + "); a WABA belongs to one project");
                sw.errors++;
            }
        }
        for (Iterator<WabaGroup> it = groups.values().iterator(); it.hasNext(); ) {
            WabaGroup g = it.next();
            sw.read++;
            Row ex = db.row("SELECT id, project_id FROM " + sql.tgt("waba_accounts")
                    + " WHERE waba_id = :w AND deleted_at IS NULL ORDER BY id LIMIT 1", Map.of("w", g.wabaId));
            if (ex == null) continue;
            if (Objects.equals(ex.lng("project_id"), g.tenant().projectId())) {
                g.existingId = ex.lng("id");
            } else {
                for (Acc a : g.rows) ctx.problems().error("whatsapp_accounts", a.id(), "WABA_OTHER_PROJECT", "WABA " + g.wabaId
                        + " already exists in project " + ex.lng("project_id") + " (waba_accounts " + ex.lng("id") + ")");
                sw.errors++;
                it.remove();
            }
        }

        // ---------------- Business Manager per (project, token)
        Map<String, List<WabaGroup>> byBm = new LinkedHashMap<>();
        for (WabaGroup g : groups.values()) {
            String key = g.tenant().projectId() + "|" + (g.token() == null ? "<no token>" : g.token());
            byBm.computeIfAbsent(key, k -> new ArrayList<>()).add(g);
        }
        StepStats.Entity sbm = ctx.stats().entity("business_manager");
        StepStats.Entity st = ctx.stats().entity("meta_token");
        StepStats.Entity sp = ctx.stats().entity("pinnacle_credential");
        Set<Long> projectsTouched = new LinkedHashSet<>();
        for (List<WabaGroup> bmGroups : byBm.values()) {
            sbm.read++;
            long bmOldId = bmGroups.stream().flatMap(g -> g.rows.stream()).mapToLong(Acc::id).min().orElseThrow();
            Tenant t = bmGroups.get(0).tenant();
            String token = bmGroups.get(0).token();
            boolean anyNew = bmGroups.stream().anyMatch(g -> g.existingId == null);
            ctx.tx().row(ctx, "whatsapp_accounts", bmOldId, () -> {
                Long bmId = null;
                if (anyNew) {
                    projectRef(ctx, t);
                    bmId = ctx.idMap().get("business_manager", bmOldId);
                    if (bmId == null) {
                        boolean pinnacle = bmGroups.stream().anyMatch(g -> pinFor(g, pinByAccount, pinByWaba) != null);
                        bmId = db.insert(sql.tgt("business_managers"), Db.vals().with("organization_id", t.orgId())
                                .with("project_id", t.projectId()).with("meta_business_id", "legacy-" + UUID.randomUUID())
                                .with("name", bmName(ctx, bmGroups.get(0))).with("onboarding_provider", pinnacle ? "PINNACLE" : "META_DIRECT")
                                .with("status", "ACTIVE").with("created_at", bmGroups.get(0).first().r().dtOr("created_at", StepContext.now()))
                                .with("updated_at", StepContext.now()));
                        ctx.idMap().put("business_manager", bmOldId, bmId);
                        sbm.inserted++;
                    } else sbm.skippedMapped++;
                } else {
                    bmId = ctx.idMap().get("business_manager", bmOldId);   // created by an earlier run (token may still be missing)
                }
                if (bmId != null && token != null) {
                        long bm = bmId;
                        Long active = db.longValue("SELECT id FROM " + sql.tgt("meta_oauth_tokens") + " WHERE business_manager_account_id = :b "
                                        + "AND status = 'ACTIVE' AND deleted_at IS NULL ORDER BY id DESC LIMIT 1",
                                Map.of("b", bm));
                        if (active == null && tokenRoom != null && token.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > tokenRoom) {
                            // never truncated: a cut token is useless. Business Manager + WABAs are kept, sending needs the token
                            ctx.problems().error("whatsapp_accounts", bmOldId, "TOKEN_TOO_LONG", "token of " + token.length() + " chars does not fit "
                                    + "meta_oauth_tokens.access_token (" + tokenRoom + " bytes); Business Manager " + bm + " has no token");
                            st.errors++;
                        } else if (active == null) {
                            Acc src = bmGroups.stream().flatMap(g -> g.rows.stream()).filter(a -> token.equals(a.token())).findFirst().orElseThrow();
                            active = db.insert(sql.tgt("meta_oauth_tokens"), Db.vals().with("business_manager_account_id", bm)
                                    .with("organization_id", t.orgId()).with("access_token", token).with("token_type", ctx.props().getWaba().getTokenType())
                                    .with("status", "ACTIVE").with("granted_at", src.r().dt("created_at"))
                                    .with("created_at", src.r().dtOr("created_at", StepContext.now())).with("updated_at", StepContext.now()));
                            st.inserted++;
                        } else st.matchedExisting++;
                        if (active != null) ctx.idMap().put("meta_token", bmOldId, active);
                }
                for (WabaGroup g : bmGroups) wabaRow(ctx, g, bmId, pinByAccount, pinByWaba, sw, sp);
                projectsTouched.add(t.projectId());
            });
        }

        // ---------------- default WABA per project
        StepStats.Entity sd = ctx.stats().entity("project_default_waba");
        for (Long project : projectsTouched) {
            ctx.tx().inTx(() -> {
                if (db.count("SELECT COUNT(*) FROM " + sql.tgt("waba_accounts") + " WHERE project_id = :p AND is_project_default = TRUE "
                        + "AND deleted_at IS NULL", Map.of("p", project)) > 0) return;
                sd.refreshed += db.exec("UPDATE " + sql.tgt("waba_accounts") + " SET is_project_default = TRUE WHERE id = (SELECT id FROM (SELECT MIN(id) id FROM "
                        + sql.tgt("waba_accounts") + " WHERE project_id = :p AND deleted_at IS NULL) x)", Map.of("p", project));
            });
        }

        // ---------------- phone numbers
        StepStats.Entity sph = ctx.stats().entity("waba_phone");
        for (Acc a : accs) {
            Long wabaId = ctx.idMap().get("waba", a.id());
            if (wabaId == null) continue;
            sph.read++;
            Long mapped = ctx.idMap().get("waba_phone", a.id());
            if (mapped != null && !ctx.refresh()) { sph.skippedMapped++; continue; }
            String phoneNumberId = Text.cut(a.r().text("whatsapp_no_id"), 100);
            String status = phoneStatus(a.r().str("status"));
            ctx.tx().row(ctx, "whatsapp_accounts", a.id(), () -> {
                Row existing = db.row("SELECT id, waba_account_id FROM " + sql.tgt("waba_phone_numbers")
                        + " WHERE phone_number_id = :p AND deleted_at IS NULL ORDER BY id LIMIT 1", Map.of("p", phoneNumberId));
                if (existing != null) {
                    if (existing.lng("waba_account_id").longValue() != wabaId) {
                        ctx.problems().error("whatsapp_accounts", a.id(), "PHONE_OTHER_WABA", "phone_number_id " + phoneNumberId
                                + " already belongs to waba_accounts " + existing.lng("waba_account_id"));
                        sph.errors++;
                        throw new Tx.SkipRow();
                    }
                    if (mapped == null) { ctx.idMap().put("waba_phone", a.id(), existing.lng("id")); sph.matchedExisting++; }
                    else sph.skippedMapped++;
                    if (ctx.refresh()) {
                        db.update(sql.tgt("waba_phone_numbers"), existing.lng("id"), Db.vals().with("status", status)
                                .with("display_phone_number", Text.cut(a.r().text("whatsapp_no"), 255)).with("updated_at", StepContext.now()));
                        sph.refreshed++;
                    }
                    return;
                }
                long id = db.insert(sql.tgt("waba_phone_numbers"), Db.vals().with("waba_account_id", wabaId)
                        .with("phone_number_id", phoneNumberId).with("display_phone_number", Text.cut(a.r().text("whatsapp_no"), 255))
                        .with("status", status).with("is_official_business_account", false)
                        .with("created_at", a.r().dtOr("created_at", StepContext.now())).with("updated_at", StepContext.now()));
                ctx.idMap().put("waba_phone", a.id(), id);
                sph.inserted++;
            });
        }
    }

    /** columns this step writes (WABA schema of 2026-10-07); fails before anything is written when one is missing */
    private static final Map<String, List<String>> REQUIRED = new LinkedHashMap<>();
    static {
        REQUIRED.put("project_refs", List.of("project_id", "organization_id"));
        REQUIRED.put("business_managers", List.of("id", "organization_id", "project_id", "meta_business_id", "name", "onboarding_provider", "status", "deleted_at"));
        REQUIRED.put("meta_oauth_tokens", List.of("id", "business_manager_account_id", "organization_id", "access_token", "token_type", "status", "granted_at", "deleted_at"));
        REQUIRED.put("waba_accounts", List.of("id", "business_manager_account_id", "project_id", "organization_id", "onboarding_provider",
                "bsp_credential_id", "waba_id", "status", "message_template_namespace", "is_project_default", "deleted_at"));
        REQUIRED.put("waba_phone_numbers", List.of("id", "waba_account_id", "phone_number_id", "display_phone_number", "status",
                "is_official_business_account", "deleted_at"));
        REQUIRED.put("pinacle_credentials", List.of("id", "organization_id", "partner_id", "api_key_encrypted", "password_encrypted", "username", "status", "granted_at"));
        REQUIRED.put("pinacle_billing_config", List.of("pinacle_credential_id", "billing_type", "billing_enabled", "minimum_balance_limit",
                "credit_limit_assigned", "credit_limit", "current_balance", "billing_contact_name", "billing_email", "billing_mobile", "gst_no"));
    }

    /** pinacle_credentials / pinacle_billing_config exist on the target (they are missing where Hibernate created the WABA tables) */
    private boolean pinnacleTables;

    /** bytes meta_oauth_tokens.access_token can hold (TINYTEXT on the server = 255) */
    private static Long tokenRoom;

    /** @return true when the Pinnacle tables exist (optional as a pair) */
    private static boolean checkSchema(StepContext ctx) {
        String schema = ctx.sql().tgtSchema();
        List<String> missing = new ArrayList<>();
        boolean pinnacle = ctx.db().tableExists(schema, "pinacle_credentials") || ctx.db().tableExists(schema, "pinacle_billing_config");
        for (Map.Entry<String, List<String>> e : REQUIRED.entrySet()) {
            if (!pinnacle && e.getKey().startsWith("pinacle_")) continue;
            Set<String> have = new HashSet<>();
            ctx.db().jdbc().query("SELECT LOWER(column_name) FROM information_schema.columns WHERE table_schema = :s AND table_name = :t",
                    Map.of("s", schema, "t", e.getKey()), rs -> { have.add(rs.getString(1)); });
            if (have.isEmpty()) { missing.add(e.getKey() + " (table)"); continue; }
            for (String c : e.getValue()) if (!have.contains(c)) missing.add(e.getKey() + "." + c);
        }
        if (!missing.isEmpty())
            throw new IllegalStateException("target schema " + schema + " is not the WABA schema of 2026-10-07, missing: " + missing
                    + ". Apply the WABA team's SQL first; nothing was written by step 05");

        // the token must fit: Hibernate creates access_token as TINYTEXT (255 bytes), Meta tokens are often longer
        Long room = ctx.db().longValue("SELECT character_octet_length FROM information_schema.columns WHERE table_schema = :s "
                + "AND table_name = 'meta_oauth_tokens' AND column_name = 'access_token'", Map.of("s", schema));
        Long longest = ctx.db().longValue("SELECT MAX(LENGTH(parmenent_token)) FROM " + ctx.sql().old("whatsapp_accounts"), Map.of());
        tokenRoom = room;
        if (room != null && longest != null && longest > room) {
            long tooLong = ctx.db().count("SELECT COUNT(DISTINCT parmenent_token) FROM " + ctx.sql().old("whatsapp_accounts")
                    + " WHERE LENGTH(parmenent_token) > :r", Map.of("r", room));
            log.warn("  meta_oauth_tokens.access_token holds {} bytes; {} old tokens are longer (max {}) and are NOT stored (ERROR TOKEN_TOO_LONG). "
                    + "To store them: ALTER TABLE {} MODIFY access_token LONGTEXT NOT NULL; then re-run step 05", room, tooLong, longest,
                    ctx.sql().tgt("meta_oauth_tokens"));
        }

        if (!pinnacle) {
            log.warn("  pinacle_credentials / pinacle_billing_config do not exist: Pinnacle WABAs are stored as PINNACLE without "
                    + "credentials (WARN PINNACLE_CREDENTIALS_NOT_MIGRATED); re-run step 05 after the tables are created to fill them");
            ctx.problems().warn("waba_accounts", null, "PINNACLE_TABLES_MISSING", "pinacle_credentials / pinacle_billing_config do not exist on the target");
        }
        return pinnacle;
    }

    /** waba_accounts row of one WABA (new, or the existing live row of the same project) + map of all its old rows */
    private void wabaRow(StepContext ctx, WabaGroup g, Long bmId, Map<Long, Row> pinByAccount, Map<String, Row> pinByWaba,
                         StepStats.Entity sw, StepStats.Entity sp) {
        Db db = ctx.db();
        Sql sql = ctx.sql();
        Tenant t = g.tenant();
        String status = wabaStatus(g.rows);
        long wabaId;
        if (g.existingId != null) {
            wabaId = g.existingId;
            if (g.rows.stream().anyMatch(a -> ctx.idMap().has("waba", a.id()))) sw.skippedMapped++; else sw.matchedExisting++;
            Row pin = pinnacleTables ? pinFor(g, pinByAccount, pinByWaba) : null;
            if (pin != null && db.count("SELECT COUNT(*) FROM " + sql.tgt("waba_accounts") + " WHERE id = :id AND bsp_credential_id IS NULL",
                    Map.of("id", wabaId)) > 0) {
                // stored earlier without credentials (Pinnacle tables were missing): fill them now
                long credential = pinnacleCredential(ctx, pin, t, sp);
                db.update(sql.tgt("waba_accounts"), wabaId, Db.vals().with("onboarding_provider", "PINNACLE").with("bsp_credential_id", credential)
                        .with("updated_at", StepContext.now()));
                ctx.problems().info("waba_accounts", pin.lng("id"), "PINNACLE_MIGRATED", "credentials added to existing waba_accounts " + wabaId);
            }
            if (ctx.refresh()) {
                db.update(sql.tgt("waba_accounts"), wabaId, Db.vals().with("status", status).with("updated_at", StepContext.now()));
                sw.refreshed++;
            }
        } else {
            Row pin = pinFor(g, pinByAccount, pinByWaba);
            Long credential = pin == null || !pinnacleTables ? null : pinnacleCredential(ctx, pin, t, sp);
            if (credential != null) ctx.problems().info("waba_accounts", pin.lng("id"), "PINNACLE_MIGRATED",
                    "Pinnacle credentials copied as they are; partner_id = '" + partnerId(ctx, pin) + "'");
            else if (pin != null) ctx.problems().warn("waba_accounts", pin.lng("id"), "PINNACLE_CREDENTIALS_NOT_MIGRATED",
                    "Pinnacle tables missing: WABA " + g.wabaId + " stored as PINNACLE without credentials");
            wabaId = db.insert(sql.tgt("waba_accounts"), Db.vals().with("business_manager_account_id", bmId)
                    .with("project_id", t.projectId()).with("organization_id", t.orgId())
                    .with("onboarding_provider", pin != null ? "PINNACLE" : "META_DIRECT").with("bsp_credential_id", credential)
                    .with("waba_id", g.wabaId).with("status", status)
                    .with("message_template_namespace", pin == null ? null : Text.cut(pin.str("template_namespace"), 255))
                    .with("is_project_default", false)
                    .with("created_at", g.first().r().dtOr("created_at", StepContext.now())).with("updated_at", StepContext.now()));
            sw.inserted++;
        }
        for (Acc a : g.rows) ctx.idMap().put("waba", a.id(), wabaId);
    }

    private static Row pinFor(WabaGroup g, Map<Long, Row> pinByAccount, Map<String, Row> pinByWaba) {
        return g.rows.stream().map(a -> pinByAccount.get(a.id())).filter(Objects::nonNull).findFirst().orElse(pinByWaba.get(g.wabaId));
    }

    /** pinacle_credentials (+ billing config) for one old waba_accounts row, created once */
    private Long pinnacleCredential(StepContext ctx, Row pin, Tenant t, StepStats.Entity sp) {
        long oldId = pin.lng("id");
        Long mapped = ctx.idMap().get("pinnacle_credential", oldId);
        if (mapped != null) { sp.skippedMapped++; return mapped; }
        Db db = ctx.db();
        Sql sql = ctx.sql();
        long id = db.insert(sql.tgt("pinacle_credentials"), Db.vals().with("organization_id", t.orgId())
                .with("partner_id", partnerId(ctx, pin)).with("api_key_encrypted", Text.cut(pin.text("api_key"), 500))
                .with("password_encrypted", Text.cut(pin.text("password"), 500)).with("username", Text.cut(pin.text("username"), 150))
                .with("status", "ACTIVE").with("granted_at", pin.dt("created_at"))
                .with("created_at", pin.dtOr("created_at", StepContext.now())).with("updated_at", StepContext.now()));
        Set<String> postpaid = new HashSet<>(Arrays.asList(ctx.props().getWaba().getPinnaclePostpaidBillingTypes().split("\\s*,\\s*")));
        BigDecimal limit = pin.dec("credit_limit");
        boolean limitAssigned = pin.bool("credit_limit_assign") && limit != null;
        BigDecimal minBalance = pin.dec("minimum_balance_limit");
        db.insert(sql.tgt("pinacle_billing_config"), Db.vals().with("pinacle_credential_id", id)
                .with("billing_type", postpaid.contains(String.valueOf(pin.lng("billing_type"))) ? "POSTPAID" : "PREPAID")
                .with("billing_enabled", pin.bool("billingon"))
                .with("minimum_balance_limit", minBalance == null ? BigDecimal.ZERO : minBalance)
                .with("credit_limit_assigned", limitAssigned).with("credit_limit", limitAssigned ? limit : null)
                .with("current_balance", BigDecimal.ZERO)
                .with("billing_contact_name", Text.cut(Text.firstNonBlank(pin.str("billingname"),
                        (Text.firstNonBlank(pin.str("firstname"), "") + " " + Text.firstNonBlank(pin.str("lastname"), "")).trim()), 150))
                .with("billing_email", Text.cut(pin.text("email"), 150)).with("billing_mobile", Text.cut(pin.text("mobileno"), 20))
                .with("gst_no", Text.cut(pin.text("gst_no"), 50))
                .with("created_at", pin.dtOr("created_at", StepContext.now())).with("updated_at", StepContext.now()));
        ctx.idMap().put("pinnacle_credential", oldId, id);
        sp.inserted++;
        return id;
    }

    private static String partnerId(StepContext ctx, Row pin) {
        return Text.cut(Text.firstNonBlank(ctx.props().getWaba().getPinnaclePartnerId(), pin.str("username"), "unknown"), 100);
    }

    /** project_refs (project -> its one organization), created on first use */
    private static void projectRef(StepContext ctx, Tenant t) {
        Row ref = ctx.db().findRow(ctx.sql().tgt("project_refs"), Map.of("project_id", t.projectId()), "project_id, organization_id");
        if (ref == null) {
            ctx.db().insertNoKey(ctx.sql().tgt("project_refs"), Db.vals().with("project_id", t.projectId()).with("organization_id", t.orgId())
                    .with("created_at", StepContext.now()).with("updated_at", StepContext.now()));
        } else if (!Objects.equals(ref.lng("organization_id"), t.orgId())) {
            throw new IllegalStateException("project_refs says project " + t.projectId() + " belongs to organization "
                    + ref.lng("organization_id") + ", but projects says " + t.orgId());
        }
    }

    private static String bmName(StepContext ctx, WabaGroup g) {
        TenantResolver.OldUser u = ctx.tenants().user(g.first().r().lng("user_id"));
        String who = u == null ? null : Text.firstNonBlank(u.companyName(), u.name());
        return Text.cut("Legacy " + (who == null ? "business" : who), 255);
    }

    private static String wabaStatus(List<Acc> rows) {
        if (rows.stream().anyMatch(a -> "1".equals(a.r().str("status")))) return "ACTIVE";
        if (rows.stream().anyMatch(a -> "2".equals(a.r().str("status")))) return "SUSPENDED";
        return "DISCONNECTED";
    }

    private static String phoneStatus(String old) {
        if ("1".equals(old)) return "ACTIVE";
        if ("2".equals(old)) return "BLOCKED";
        return "DISABLED";
    }
}
