package com.aigreentick.migration.steps.messaging;

import com.aigreentick.migration.core.*;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 09k — per migrated campaign, counters from ALL its old report rows (not limited by D7) -> broadcast_campaign_counters
 * (shard 0) and the cached counts on broadcast_campaigns. Natural key (campaign_id, shard): existing counters are kept
 * unless --migration.refresh=true. Status values are mapped with migration.messaging.report-status.
 */
@Component
public class CampaignCountersStep implements MigrationStep {

    @Override public String id() { return "09k-campaign-counters"; }
    @Override public int order() { return 995; }
    @Override public String title() { return "reports -> broadcast_campaign_counters + campaign counts"; }

    @Override
    public void run(StepContext ctx) {
        Sql sql = ctx.sql();
        Db db = ctx.db();
        // CASE expression from the configured status map
        String cases = ctx.props().getMessaging().getReportStatus().entrySet().stream()
                .map(e -> "WHEN '" + e.getKey().replace("'", "''") + "' THEN '" + e.getValue().replace("'", "''") + "'")
                .collect(Collectors.joining(" "));
        String st = cases.isEmpty() ? "'FAILED'" : "(CASE LOWER(TRIM(r.status)) " + cases + " ELSE 'FAILED' END)";
        String agg = "SELECT mm.new_id AS campaign_id, "
                + "SUM(" + st + " IN ('DELIVERED','READ')) delivered, SUM(" + st + " = 'FAILED') failed, SUM(" + st + " = 'READ') rd, "
                + "SUM(" + st + " IN ('SENT','DELIVERED','READ')) sent, SUM(" + st + " = 'CANCELLED') cancelled "
                + "FROM " + sql.old("reports") + " r JOIN " + sql.mig("migration_id_map") + " mm ON mm.entity = 'campaign' AND mm.old_id = r.broadcast_id "
                + "JOIN " + sql.tgt("broadcast_campaigns") + " bc ON bc.id = mm.new_id "
                + "WHERE r.deleted_at IS NULL" + ctx.scope().sql("bc.project_id") + " GROUP BY mm.new_id";
        StepStats.Entity s = ctx.stats().entity("campaign_counter");
        StepStats.Entity c = ctx.stats().entity("campaign_counts");
        ctx.tx().inTx(() -> {
            s.inserted += db.exec("INSERT INTO " + sql.tgt("broadcast_campaign_counters")
                    + " (campaign_id, shard, delivered_count, failed_count, read_count, sent_count, skipped_count) "
                    + "SELECT a.campaign_id, 0, a.delivered, a.failed, a.rd, a.sent, 0 FROM (" + agg + ") a "
                    + "WHERE NOT EXISTS (SELECT 1 FROM " + sql.tgt("broadcast_campaign_counters") + " x WHERE x.campaign_id = a.campaign_id AND x.shard = 0)",
                    Map.of());
            if (ctx.refresh())
                s.refreshed += db.exec("UPDATE " + sql.tgt("broadcast_campaign_counters") + " x JOIN (" + agg + ") a ON a.campaign_id = x.campaign_id "
                        + "SET x.delivered_count = a.delivered, x.failed_count = a.failed, x.read_count = a.rd, x.sent_count = a.sent WHERE x.shard = 0",
                        Map.of());
            c.refreshed += db.exec("UPDATE " + sql.tgt("broadcast_campaigns") + " bc JOIN (" + agg + ") a ON a.campaign_id = bc.id "
                    + "SET bc.delivered_count = a.delivered, bc.failed_count = a.failed, bc.read_count = a.rd, bc.sent_count = a.sent, "
                    + "bc.cancelled_count = a.cancelled, bc.counters_synced_at = NOW(6) "
                    + "WHERE bc.counters_synced_at IS NULL" + (ctx.refresh() ? " OR 1 = 1" : ""), Map.of());
        });
    }
}
