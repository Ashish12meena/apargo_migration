package com.aigreentick.migration.steps.messaging;

import com.aigreentick.migration.core.*;
import com.aigreentick.migration.util.Json;
import com.aigreentick.migration.util.Text;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 09a — live_chat_settings -> project_messaging_settings (PK project_id).
 * auto_resolve_enabled -> auto_close_after_mins (configured minutes, else 0). The old per-day working_hours become ONE
 * quiet-hours range (quiet = end..start) only when every open day has the same hours; otherwise quiet hours stay
 * disabled and a WARN is written. Refresh: the whole row.
 */
@Component
public class MessagingSettingsStep implements MigrationStep {

    @Override public String id() { return "09a-messaging-settings"; }
    @Override public int order() { return 910; }
    @Override public String title() { return "live_chat_settings -> project_messaging_settings"; }

    @Override
    public void run(StepContext ctx) {
        Db db = ctx.db();
        Sql sql = ctx.sql();
        StepStats.Entity s = ctx.stats().entity("messaging_settings");
        Set<Long> done = new HashSet<>();
        for (Row r : db.rows("SELECT * FROM " + sql.old("live_chat_settings") + " WHERE deleted_at IS NULL ORDER BY id")) {
            long oldId = r.lng("id");
            s.read++;
            Tenant t = ctx.place("live_chat_settings", oldId, r.lng("user_id"));
            if (t == null) continue;
            if (!done.add(t.projectId())) {
                ctx.problems().warn("live_chat_settings", oldId, "DUPLICATE", "project " + t.projectId() + " already has settings from an earlier row");
                continue;
            }
            int[] quiet = quietHours(r.str("working_hours"));
            if (quiet == null && r.text("working_hours") != null)
                ctx.problems().warn("live_chat_settings", oldId, "WORKING_HOURS_LOSSY", "working_hours cannot be expressed as one quiet range -> quiet hours disabled");
            String tz = Text.firstNonBlank(r.str("timezone"), ctx.props().getMessaging().getDefaultTimezone());
            Db.Vals v = Db.vals().with("auto_close_after_mins", r.bool("auto_resolve_enabled") ? ctx.props().getMessaging().getAutoCloseAfterMins() : 0)
                    .with("organization_id", t.orgId()).with("quiet_end_hour", quiet == null ? 0 : quiet[1])
                    .with("quiet_hours_enabled", quiet != null).with("quiet_start_hour", quiet == null ? 0 : quiet[0])
                    .with("timezone", Text.cut(tz, 64)).with("updated_at", r.dtOr("updated_at", StepContext.now()));
            ctx.tx().row(ctx, "live_chat_settings", oldId, () -> {
                if (db.count("SELECT COUNT(*) FROM " + sql.tgt("project_messaging_settings") + " WHERE project_id = :p",
                        Map.of("p", t.projectId())) > 0) {
                    s.matchedExisting++;
                    if (ctx.refresh()) {
                        StringBuilder set = new StringBuilder();
                        v.keySet().forEach(k -> set.append(set.length() == 0 ? "" : ", ").append('`').append(k).append("` = :").append(k));
                        Map<String, Object> p = new HashMap<>(v);
                        p.put("pid", t.projectId());
                        db.exec("UPDATE " + sql.tgt("project_messaging_settings") + " SET " + set + " WHERE project_id = :pid", p);
                        s.refreshed++;
                    }
                    return;
                }
                Db.Vals ins = Db.vals().with("project_id", t.projectId()).with("created_at", r.dtOr("created_at", StepContext.now()));
                ins.putAll(v);
                db.insertNoKey(sql.tgt("project_messaging_settings"), ins);
                s.inserted++;
            });
        }
    }

    /** [quietStart, quietEnd] or null when working hours are missing / differ per day */
    static int[] quietHours(String json) {
        JsonNode n = Json.parse(json);
        if (n == null) return null;
        List<int[]> ranges = new ArrayList<>();
        if (n.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = n.fields();
            while (it.hasNext()) collect(it.next().getValue(), ranges);
        } else if (n.isArray()) {
            n.forEach(d -> collect(d, ranges));
        }
        if (ranges.isEmpty()) return null;
        int[] first = ranges.get(0);
        for (int[] r : ranges) if (r[0] != first[0] || r[1] != first[1]) return null;
        if (first[0] == first[1]) return null;
        return new int[]{first[1] % 24, first[0] % 24};   // quiet from closing hour to opening hour
    }

    private static void collect(JsonNode day, List<int[]> out) {
        if (day == null) return;
        if (day.isArray()) { day.forEach(d -> collect(d, out)); return; }
        if (!day.isObject()) return;
        String enabled = Json.text(day, "enabled", "isOpen", "is_open", "open", "active", "status");
        if (enabled != null && (enabled.equals("false") || enabled.equals("0") || enabled.equalsIgnoreCase("closed"))) return;
        Integer start = hour(Json.text(day, "start", "from", "open_time", "startTime", "start_time", "opening"));
        Integer end = hour(Json.text(day, "end", "to", "close_time", "endTime", "end_time", "closing"));
        if (start != null && end != null) out.add(new int[]{start, end});
    }

    private static Integer hour(String v) {
        if (v == null) return null;
        String t = v.trim();
        int colon = t.indexOf(':');
        try {
            int h = Integer.parseInt(colon > 0 ? t.substring(0, colon) : t);
            return h >= 0 && h <= 24 ? h % 24 : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
