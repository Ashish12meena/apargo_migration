package com.aigreentick.migration.steps.messaging;

import com.aigreentick.migration.config.MigrationProperties;
import com.aigreentick.migration.core.*;
import com.aigreentick.migration.util.Enums;
import com.aigreentick.migration.util.Json;
import com.aigreentick.migration.util.PhoneNormalizer;
import com.aigreentick.migration.util.Text;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 09j — reports (rows of a migrated broadcast; D7: created on/after migration.history.reports-since) -> broadcast_recipients.
 * <ul>
 *   <li>contact = live contact with the report mobile's E.164 in the campaign's organization; none -> not migrated (WARN count)</li>
 *   <li>UNIQUE (campaign_id, contact_id): the first report row of a number wins; existing recipients are kept</li>
 *   <li>state SENT / FAILED / CANCELLED, always terminal (never PENDING / QUEUED)</li>
 *   <li>message_id = the migrated message with the same wamid, when there is one</li>
 * </ul>
 */
@Component
public class RecipientsStep implements MigrationStep {

    @Override public String id() { return "09j-recipients"; }
    @Override public int order() { return 990; }
    @Override public String title() { return "reports -> broadcast_recipients"; }

    @Override
    public void run(StepContext ctx) {
        Db db = ctx.db();
        Sql sql = ctx.sql();
        MigrationProperties.Messaging cfg = ctx.props().getMessaging();
        PhoneNormalizer phones = new PhoneNormalizer(ctx.props().getContacts().getDefaultDialCode());
        boolean plus = ctx.props().getContacts().isPhoneWithPlus();
        String since = ctx.props().getHistory().getReportsSince();
        Map<Long, Long> orgOfCampaign = new HashMap<>();
        StepStats.Entity s = ctx.stats().entity("broadcast_recipient");
        long[] noContact = {0}, noCampaign = {0};

        ctx.pages("main", "SELECT r.id, r.broadcast_id, r.mobile, r.message_id, r.status, r.payload, r.created_at, r.updated_at, "
                + "mm.new_id AS campaign_new FROM " + sql.old("reports") + " r "
                + "LEFT JOIN " + sql.mig("migration_id_map") + " mm ON mm.entity = 'campaign' AND mm.old_id = r.broadcast_id "
                + "WHERE r.id > :lastId AND r.broadcast_id IS NOT NULL AND r.deleted_at IS NULL"
                + (since.isBlank() ? "" : " AND r.created_at >= :since") + " ORDER BY r.id LIMIT :limit",
                since.isBlank() ? Map.of() : Map.of("since", since), "id", page -> {
            Map<Long, Set<String>> phonesByOrg = new HashMap<>();
            List<Object[]> work = new ArrayList<>();   // [row, campaignId, orgId, normalized]
            Set<Long> campaigns = new HashSet<>();
            for (Row r : page) if (r.lng("campaign_new") != null && !orgOfCampaign.containsKey(r.lng("campaign_new"))) campaigns.add(r.lng("campaign_new"));
            if (!campaigns.isEmpty())
                for (Row c : db.rows("SELECT id, organization_id, project_id FROM " + sql.tgt("broadcast_campaigns") + " WHERE id IN (:ids)", Map.of("ids", campaigns))) {
                    if (ctx.scope().includesProject(c.lng("project_id"))) orgOfCampaign.put(c.lng("id"), c.lng("organization_id"));
                    else orgOfCampaign.put(c.lng("id"), -1L);
                }
            Set<String> wamids = new HashSet<>();
            for (Row r : page) {
                s.read++;
                Long campaign = r.lng("campaign_new");
                if (campaign == null) { noCampaign[0]++; continue; }
                Long org = orgOfCampaign.get(campaign);
                if (org == null || org < 0) continue;
                PhoneNormalizer.Phone p = phones.normalize(r.str("mobile"), null);
                if (p == null) { noContact[0]++; continue; }
                String norm = p.normalized(plus);
                phonesByOrg.computeIfAbsent(org, k -> new HashSet<>()).add(norm);
                String w = Text.cut(Text.trimToNull(r.str("message_id")), 150);
                if (w != null) wamids.add(w);
                work.add(new Object[]{r, campaign, org, norm});
            }
            Map<String, Long> contactByPhone = new HashMap<>();
            for (Map.Entry<Long, Set<String>> e : phonesByOrg.entrySet())
                for (Row c : db.rows("SELECT id, dedupe_phone FROM " + sql.tgt("contacts") + " WHERE organization_id = :o AND dedupe_phone IN (:p)",
                        Map.of("o", e.getKey(), "p", e.getValue())))
                    contactByPhone.put(e.getKey() + ":" + c.str("dedupe_phone"), c.lng("id"));
            Map<String, Long> messageByWamid = new HashMap<>();
            if (!wamids.isEmpty())
                for (Row m : db.rows("SELECT provider_message_id, message_id FROM " + sql.tgt("message_wamid") + " WHERE provider_message_id IN (:w)",
                        Map.of("w", wamids)))
                    messageByWamid.put(m.str("provider_message_id"), m.lng("message_id"));

            Map<String, Map<String, ?>> candidates = new LinkedHashMap<>();   // campaign:contact -> row (first report wins)
            for (Object[] x : work) {
                Row r = (Row) x[0];
                Long contact = contactByPhone.get(x[2] + ":" + x[3]);
                if (contact == null) { noContact[0]++; continue; }
                if (candidates.containsKey(x[1] + ":" + contact)) { s.matchedExisting++; continue; }
                String raw = Text.trimToNull(r.str("status"));
                String result = Enums.map(cfg.getReportStatus(), raw);
                if (result == null) {
                    result = "FAILED";
                    ctx.problems().warn("reports", r.lng("id"), "ENUM_UNKNOWN", "status '" + raw + "' -> FAILED");
                }
                String state = switch (result) {
                    case "SENT", "DELIVERED", "READ" -> "SENT";
                    case "CANCELLED" -> "CANCELLED";
                    default -> "FAILED";
                };
                String wamid = Text.cut(Text.trimToNull(r.str("message_id")), 150);
                LocalDateTime created = r.dtOr("created_at", StepContext.now());
                String params = Json.normalize(r.str("payload"));
                if (params != null && params.length() > 60000) params = null;
                candidates.put(x[1] + ":" + contact, Db.vals().with("attempts", "CANCELLED".equals(state) ? 0 : 1).with("campaign_id", x[1]).with("contact_id", contact)
                        .with("created_at", created).with("fail_code", null)
                        .with("fail_reason", "FAILED".equals(state) ? Text.cut("legacy status: " + raw, 255) : "CANCELLED".equals(state) ? "legacy: never sent" : null)
                        .with("message_id", wamid == null ? null : messageByWamid.get(wamid)).with("next_run_at", null)
                        .with("normalized_phone", x[3]).with("provider_message_id", wamid).with("queued_at", null)
                        .with("sent_at", "SENT".equals(state) ? created : null).with("skip_reason", null).with("state", state)
                        .with("template_params", params).with("is_terminal", true).with("terminal_at", r.dtOr("updated_at", created))
                        .with("trace_id", null));
            }
            if (!candidates.isEmpty()) {
                List<Object[]> pairs = candidates.values().stream().map(m -> new Object[]{m.get("campaign_id"), m.get("contact_id")}).toList();
                for (Row e : db.rows("SELECT campaign_id, contact_id FROM " + sql.tgt("broadcast_recipients")
                        + " WHERE (campaign_id, contact_id) IN (:p)", Map.of("p", pairs))) {
                    if (candidates.remove(e.lng("campaign_id") + ":" + e.lng("contact_id")) != null) s.matchedExisting++;
                }
                s.inserted += ctx.insertBatchSafe(sql.tgt("broadcast_recipients"), new ArrayList<>(candidates.values()), null, "reports", "campaign_id", "contact_id");
            }
        });
        ctx.problems().aggregate(ProblemLog.Severity.WARN, "reports", "CONTACT_NOT_FOUND", noContact[0],
                "report rows whose mobile has no contact in the campaign's organization (not migrated)");
        ctx.problems().aggregate(ProblemLog.Severity.INFO, "reports", "CAMPAIGN_NOT_MIGRATED", noCampaign[0],
                "report rows of broadcasts that were not migrated (deleted, out of D7 scope, or unplaceable)");
    }
}
