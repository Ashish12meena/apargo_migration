package com.aigreentick.migration.steps.plans;

import com.aigreentick.migration.config.MigrationProperties;
import com.aigreentick.migration.core.*;
import com.aigreentick.migration.util.Text;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * 04b — reseller_messages_pricing -> org_default_messaging_charges (natural key organization_id + country_code).
 * Charge = *_set, else *_min. Old minimums and margins have no column: they go into notes (and WARN when they differ).
 * Refresh: the four charges.
 */
@Component
public class ResellerPricingStep implements MigrationStep {

    @Override public String id() { return "04b-reseller-pricing"; }
    @Override public int order() { return 420; }
    @Override public String title() { return "reseller_messages_pricing -> org_default_messaging_charges"; }

    @Override
    public void run(StepContext ctx) {
        if (ctx.scope().isPilot()) return;   // organization-level data
        MigrationProperties.Pricing cfg = ctx.props().getPricing();
        String table = ctx.sql().tgt("org_default_messaging_charges");
        StepStats.Entity s = ctx.stats().entity("reseller_charge");
        for (Row r : ctx.db().rows("SELECT * FROM " + ctx.sql().old("reseller_messages_pricing") + " ORDER BY id")) {
            long oldId = r.lng("id");
            s.read++;
            Long mapped = ctx.idMap().get("reseller_charge", oldId);
            if (mapped != null && !ctx.refresh()) { s.skippedMapped++; continue; }
            Long orgId = ctx.tenants().orgOfReseller(r.lng("reseller_id"));
            if (orgId == null) {
                ctx.problems().error("reseller_messages_pricing", oldId, "PARENT_MISSING", "reseller " + r.lng("reseller_id") + " not migrated");
                s.errors++;
                continue;
            }
            Db.Vals charges = Db.vals()
                    .with("marketing_charge", pick(r, "market_msg_charge")).with("utility_charge", pick(r, "utility_msg_charge"))
                    .with("authentication_charge", pick(r, "auth_msg_charge")).with("service_charge", pick(r, "service_msg_charge"));
            String notes = "Migrated from reseller_messages_pricing " + oldId + "; minimums m/u/a/s = "
                    + r.dec("market_msg_charge_min") + "/" + r.dec("utility_msg_charge_min") + "/" + r.dec("auth_msg_charge_min")
                    + "/" + r.dec("service_msg_charge_min") + "; margin " + r.dec("margin_percentage") + "%, set margin "
                    + r.dec("set_margin_percentage") + "%";
            ctx.tx().row(ctx, "reseller_messages_pricing", oldId, () -> {
                Long existing = mapped != null ? mapped
                        : ctx.db().findId(table, Map.of("organization_id", orgId, "country_code", cfg.getDefaultCountryCode()));
                if (existing != null) {
                    if (mapped == null) { ctx.idMap().put("reseller_charge", oldId, existing); s.matchedExisting++; }
                    else s.skippedMapped++;
                    if (ctx.refresh()) {
                        Db.Vals u = Db.vals();
                        u.putAll(charges);
                        u.with("updated_at", StepContext.nowSec());
                        ctx.db().update(table, existing, u);
                        s.refreshed++;
                    }
                    return;
                }
                Db.Vals v = Db.vals().with("uuid", UUID.randomUUID().toString()).with("organization_id", orgId)
                        .with("country_code", cfg.getDefaultCountryCode()).with("country", cfg.getDefaultCountryName())
                        .with("currency", Text.cut(Text.firstNonBlank(r.str("currency"), cfg.getCurrency()), 3));
                v.putAll(charges);
                v.with("is_active", r.bool("is_active")).with("notes", notes)
                        .with("created_at", r.dtOr("created_at", StepContext.nowSec())).with("updated_at", r.dtOr("updated_at", StepContext.nowSec()));
                ctx.idMap().put("reseller_charge", oldId, ctx.db().insert(table, v));
                s.inserted++;
            });
        }
    }

    private static BigDecimal pick(Row r, String base) {
        BigDecimal set = r.dec(base + "_set");
        BigDecimal v = set != null ? set : r.dec(base + "_min");
        return v == null ? BigDecimal.ZERO : v;
    }
}
