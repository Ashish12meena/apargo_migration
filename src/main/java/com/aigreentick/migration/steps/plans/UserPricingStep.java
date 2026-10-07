package com.aigreentick.migration.steps.plans;

import com.aigreentick.migration.config.MigrationProperties;
import com.aigreentick.migration.core.*;
import com.aigreentick.migration.util.Text;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * 04c — user_messages_pricing -> org_messaging_charges (target_type='user'). Natural key (target_type, user_id,
 * country_code): Phase 2 already wrote one row per customer from users.*_msg_charge, so existing rows are only
 * mapped. --migration.refresh=true makes user_messages_pricing override those charges.
 */
@Component
public class UserPricingStep implements MigrationStep {

    @Override public String id() { return "04c-user-pricing"; }
    @Override public int order() { return 430; }
    @Override public String title() { return "user_messages_pricing -> org_messaging_charges (user)"; }

    @Override
    public void run(StepContext ctx) {
        MigrationProperties.Pricing cfg = ctx.props().getPricing();
        String table = ctx.sql().tgt("org_messaging_charges");
        StepStats.Entity s = ctx.stats().entity("user_charge");
        for (Row r : ctx.db().rows("SELECT * FROM " + ctx.sql().old("user_messages_pricing") + " ORDER BY id")) {
            long oldId = r.lng("id");
            s.read++;
            Long mapped = ctx.idMap().get("user_charge", oldId);
            if (mapped != null && !ctx.refresh()) { s.skippedMapped++; continue; }
            Tenant t = ctx.placeOrg("user_messages_pricing", oldId, r.lng("user_id"));
            if (t == null) continue;
            Long ownerOrg = ctx.tenants().orgOfReseller(r.lng("reseller_id"));
            if (ownerOrg == null) ownerOrg = t.orgId();
            Long owner = ownerOrg;
            Db.Vals charges = Db.vals().with("marketing_charge", r.dec("market_msg_charge"))
                    .with("utility_charge", r.dec("utility_msg_charge")).with("authentication_charge", r.dec("auth_msg_charge"))
                    .with("service_charge", r.dec("service_msg_charge"));
            ctx.tx().row(ctx, "user_messages_pricing", oldId, () -> {
                Long existing = mapped != null ? mapped : ctx.db().findId(table,
                        Map.of("target_type", "user", "user_id", t.userId(), "country_code", cfg.getDefaultCountryCode()));
                if (existing != null) {
                    if (mapped == null) { ctx.idMap().put("user_charge", oldId, existing); s.matchedExisting++; }
                    else s.skippedMapped++;
                    if (ctx.refresh()) {
                        Db.Vals u = Db.vals();
                        u.putAll(charges);
                        u.with("is_active", r.bool("is_active")).with("updated_at", StepContext.nowSec());
                        ctx.db().update(table, existing, u);
                        s.refreshed++;
                    }
                    return;
                }
                Db.Vals v = Db.vals().with("uuid", UUID.randomUUID().toString()).with("owner_organization_id", owner)
                        .with("target_type", "user").with("organization_id", null).with("project_id", null).with("user_id", t.userId())
                        .with("country_code", cfg.getDefaultCountryCode()).with("country", cfg.getDefaultCountryName())
                        .with("currency", Text.cut(Text.firstNonBlank(r.str("currency"), cfg.getCurrency()), 3));
                v.putAll(charges);
                v.with("is_active", r.bool("is_active"))
                        .with("notes", "Migrated from user_messages_pricing " + oldId + " (margin " + r.dec("user_margin_percentage") + "%)")
                        .with("created_at", r.dtOr("created_at", StepContext.nowSec())).with("updated_at", r.dtOr("updated_at", StepContext.nowSec()));
                ctx.idMap().put("user_charge", oldId, ctx.db().insert(table, v));
                s.inserted++;
            });
        }
    }
}
