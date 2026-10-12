package com.aigreentick.migration.steps.messaging;

import com.aigreentick.migration.core.*;
import com.aigreentick.migration.util.Enums;
import com.aigreentick.migration.util.PhoneNormalizer;
import com.aigreentick.migration.util.Text;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 09c — every live old chat (D7: created on/after migration.history.chats-since) -> mig_chat:
 * tenant project, direction, OUR phone number (waba_phone_numbers) and the contact.
 * <ul>
 *   <li>direction from chats.type (migration.messaging.chat-direction), else by matching our numbers</li>
 *   <li>our number: send_from(_id) for OUTBOUND, send_to(_id) for INBOUND, matched against the project's numbers
 *       (phone_number_id first, then digits); a project with one number uses it; no match -> ERROR NUMBER_UNMATCHED</li>
 *   <li>contact: chats.contact_id via entity contact; else the other party's E.164 in the same organization;
 *       none -> ERROR CONTACT_MISSING</li>
 * </ul>
 */
@Component
public class ChatsPrepareStep implements MigrationStep {

    record OurPhone(long projectId, long phoneId, long wabaId, String phoneNumberId, String digits) {}

    @Override public String id() { return "09c-chats-prepare"; }
    @Override public int order() { return 930; }
    @Override public String title() { return "chats -> mig_chat (direction, number, contact)"; }

    @Override
    public void run(StepContext ctx) {
        Db db = ctx.db();
        Sql sql = ctx.sql();
        Map<Long, List<OurPhone>> phonesByProject = ourPhones(ctx);
        Map<String, String> dirMap = ctx.props().getMessaging().getChatDirection();
        PhoneNormalizer phones = new PhoneNormalizer(ctx.props().getContacts().getDefaultDialCode());
        boolean plus = ctx.props().getContacts().isPhoneWithPlus();
        String since = ctx.props().getHistory().getChatsSince();
        Map<String, Object> params = since.isBlank() ? Map.of() : Map.of("since", since);

        StepStats.Entity s = ctx.stats().entity("mig_chat");
        ctx.pages("main", "SELECT ch.id, ch.user_id, ch.contact_id, ch.send_from, ch.send_to, ch.send_from_id, ch.send_to_id, ch.type, "
                + "ch.created_at, mc.new_id AS contact_new, x.organization_id AS contact_org, x.normalized_phone AS contact_phone "
                + "FROM " + sql.old("chats") + " ch "
                + "LEFT JOIN " + sql.mig("migration_id_map") + " mc ON mc.entity = 'contact' AND mc.old_id = ch.contact_id "
                + "LEFT JOIN " + sql.tgt("contacts") + " x ON x.id = mc.new_id "
                + "WHERE ch.id > :lastId AND ch.deleted_at IS NULL" + (since.isBlank() ? "" : " AND ch.created_at >= :since")
                + " ORDER BY ch.id LIMIT :limit", params, "id", page -> {
            // pass 1: tenant, direction, number; collect phones that still need a contact lookup
            record Pending(Row r, Tenant t, String dir, OurPhone our, String otherNormalized) {}
            List<Pending> pend = new ArrayList<>();
            Map<Long, Set<String>> lookup = new HashMap<>();
            for (Row ch : page) {
                s.read++;
                long oldId = ch.lng("id");
                Tenant t = ctx.place("chats", oldId, ch.lng("user_id"));
                if (t == null) continue;
                List<OurPhone> ours = phonesByProject.getOrDefault(t.projectId(), List.of());
                if (ours.isEmpty()) {
                    ctx.problems().error("chats", oldId, "NO_WABA_PHONE", "project " + t.projectId() + " has no migrated phone number");
                    s.errors++;
                    continue;
                }
                String dir = Enums.map(dirMap, ch.str("type"));
                OurPhone fromMatch = match(ours, ch.str("send_from_id"), ch.str("send_from"));
                OurPhone toMatch = match(ours, ch.str("send_to_id"), ch.str("send_to"));
                if (dir == null) dir = fromMatch != null ? "OUTBOUND" : toMatch != null ? "INBOUND" : null;
                if (dir == null) {
                    ctx.problems().error("chats", oldId, "DIRECTION_UNKNOWN", "type '" + ch.str("type") + "' and neither number is ours");
                    s.errors++;
                    continue;
                }
                OurPhone our = "OUTBOUND".equals(dir) ? fromMatch : toMatch;
                if (our == null && ours.size() == 1) our = ours.get(0);
                if (our == null) {
                    ctx.problems().error("chats", oldId, "NUMBER_UNMATCHED", "our number " + ("OUTBOUND".equals(dir) ? ch.str("send_from") : ch.str("send_to"))
                            + " is not one of project " + t.projectId() + "'s numbers");
                    s.errors++;
                    continue;
                }
                String other = "OUTBOUND".equals(dir) ? ch.str("send_to") : ch.str("send_from");
                PhoneNormalizer.Phone p = phones.normalize(other, null);
                String otherNorm = p == null ? null : p.normalized(plus);
                boolean contactOk = ch.lng("contact_new") != null && Objects.equals(ch.lng("contact_org"), t.orgId());
                if (!contactOk && otherNorm != null) lookup.computeIfAbsent(t.orgId(), k -> new HashSet<>()).add(otherNorm);
                pend.add(new Pending(ch, t, dir, our, otherNorm));
            }
            // pass 2: contacts by (organization, phone) for chats without a usable contact_id
            Map<String, Long> byPhone = new HashMap<>();
            for (Map.Entry<Long, Set<String>> e : lookup.entrySet()) {
                for (Row c : db.rows("SELECT id, normalized_phone FROM " + sql.tgt("contacts") + " WHERE organization_id = :o AND dedupe_phone IN (:p)",
                        Map.of("o", e.getKey(), "p", e.getValue())))
                    byPhone.put(e.getKey() + ":" + c.str("normalized_phone"), c.lng("id"));
            }
            List<Map<String, ?>> out = new ArrayList<>();
            for (Pending pd : pend) {
                Row ch = pd.r();
                long oldId = ch.lng("id");
                Long contact;
                String phone;
                if (ch.lng("contact_new") != null && Objects.equals(ch.lng("contact_org"), pd.t().orgId())) {
                    contact = ch.lng("contact_new");
                    phone = ch.str("contact_phone");
                } else {
                    contact = pd.otherNormalized() == null ? null : byPhone.get(pd.t().orgId() + ":" + pd.otherNormalized());
                    phone = pd.otherNormalized();
                }
                if (contact == null) {
                    ctx.problems().error("chats", oldId, "CONTACT_MISSING", "no contact for " + (pd.otherNormalized() == null ? "?" : pd.otherNormalized())
                            + " in organization " + pd.t().orgId() + " (old contact_id " + ch.lng("contact_id") + ")");
                    s.errors++;
                    continue;
                }
                LocalDateTime created = ch.dt("created_at");
                out.add(Db.vals().with("old_id", oldId).with("organization_id", pd.t().orgId()).with("project_id", pd.t().projectId())
                        .with("contact_id", contact).with("waba_account_id", pd.our().wabaId()).with("waba_phone_number_id", pd.our().phoneId())
                        .with("direction", pd.dir()).with("normalized_phone", Text.cut(phone, 30)).with("created_at", created));
                s.inserted++;
            }
            ctx.insertBatchSafe(sql.mig("mig_chat"), out, "ON DUPLICATE KEY UPDATE organization_id = VALUES(organization_id), "
                    + "project_id = VALUES(project_id), contact_id = VALUES(contact_id), waba_account_id = VALUES(waba_account_id), "
                    + "waba_phone_number_id = VALUES(waba_phone_number_id), direction = VALUES(direction), "
                    + "normalized_phone = VALUES(normalized_phone), created_at = VALUES(created_at)", "chats", "old_id");
        });
    }

    /** the project's migrated numbers (old whatsapp_accounts rows -> entities waba_phone / waba) */
    static Map<Long, List<OurPhone>> ourPhones(StepContext ctx) {
        Map<Long, List<OurPhone>> out = new HashMap<>();
        for (Row r : ctx.db().rows("SELECT id, user_id, whatsapp_no, whatsapp_no_id FROM " + ctx.sql().old("whatsapp_accounts") + " ORDER BY id")) {
            Long phone = ctx.idMap().get("waba_phone", r.lng("id"));
            Long waba = ctx.idMap().get("waba", r.lng("id"));
            if (phone == null || waba == null) continue;
            Tenant t = ctx.tenants().resolve(r.lng("user_id"));
            if (!t.hasProject()) continue;
            List<OurPhone> list = out.computeIfAbsent(t.projectId(), k -> new ArrayList<>());
            if (list.stream().noneMatch(p -> p.phoneId() == phone))
                list.add(new OurPhone(t.projectId(), phone, waba, Text.trimToNull(r.str("whatsapp_no_id")), Text.digits(r.str("whatsapp_no"))));
        }
        return out;
    }

    static OurPhone match(List<OurPhone> ours, String numberId, String number) {
        String id = Text.trimToNull(numberId);
        if (id != null) for (OurPhone p : ours) if (id.equals(p.phoneNumberId())) return p;
        String d = Text.digits(number);
        if (d.length() < 6) return null;
        for (OurPhone p : ours) {
            if (p.digits().isEmpty()) continue;
            if (d.equals(p.digits()) || d.endsWith(p.digits()) || p.digits().endsWith(d)) return p;
        }
        return null;
    }
}
