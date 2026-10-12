package com.aigreentick.migration.steps.plans;

import com.aigreentick.migration.core.*;
import com.aigreentick.migration.util.Text;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** 04a — platform_meta_pricing -> meta_messaging_charges (natural key country_code). Refresh: the four charges. */
@Component
public class MetaPricingStep implements MigrationStep {

    @Override public String id() { return "04a-meta-pricing"; }
    @Override public int order() { return 410; }
    @Override public String title() { return "platform_meta_pricing -> meta_messaging_charges"; }

    @Override
    public void run(StepContext ctx) {
        if (ctx.scope().isPilot()) return;   // platform-wide data, not tenant data
        String table = ctx.sql().tgt("meta_messaging_charges");
        StepStats.Entity s = ctx.stats().entity("meta_charge");
        for (Row r : ctx.db().rows("SELECT * FROM " + ctx.sql().old("platform_meta_pricing") + " ORDER BY id")) {
            long oldId = r.lng("id");
            s.read++;
            Long mapped = ctx.idMap().get("meta_charge", oldId);
            if (mapped != null && !ctx.refresh()) { s.skippedMapped++; continue; }
            String[] c = Countries.resolve(r.str("country_code"));
            if (c == null) {
                ctx.problems().error("platform_meta_pricing", oldId, "COUNTRY_UNKNOWN", "country_code '" + r.str("country_code") + "'");
                s.errors++;
                continue;
            }
            Db.Vals charges = Db.vals().with("marketing_charge", r.dec("market_msg_charge"))
                    .with("utility_charge", r.dec("utility_msg_charge")).with("authentication_charge", r.dec("auth_msg_charge"))
                    .with("service_charge", r.dec("service_msg_charge"));
            ctx.tx().row(ctx, "platform_meta_pricing", oldId, () -> {
                Long existing = mapped != null ? mapped : ctx.db().findId(table, Map.of("country_code", c[0]));
                if (existing != null) {
                    if (mapped == null) { ctx.idMap().put("meta_charge", oldId, existing); s.matchedExisting++; }
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
                Db.Vals v = Db.vals().with("uuid", UUID.randomUUID().toString()).with("country_code", c[0])
                        .with("country", Text.cut(Text.firstNonBlank(r.str("country_name"), Countries.name(c[0])), 100))
                        .with("dialing_code", c[1]).with("currency", Text.cut(Text.firstNonBlank(r.str("currency"), "INR"), 3));
                v.putAll(charges);
                v.with("is_active", r.bool("is_active")).with("notes", "Migrated from platform_meta_pricing " + oldId)
                        .with("created_at", r.dtOr("created_at", StepContext.nowSec())).with("updated_at", r.dtOr("updated_at", StepContext.nowSec()));
                ctx.idMap().put("meta_charge", oldId, ctx.db().insert(table, v));
                s.inserted++;
            });
        }
    }
}
