package com.aigreentick.migration.steps.contact;

import com.aigreentick.migration.core.*;
import com.aigreentick.migration.util.PhoneNormalizer;
import com.aigreentick.migration.util.PhoneNormalizer.Phone;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 08a — every live old chat_contacts row -> mig_contact: tenant (organization, project, new user), E.164 phone
 * (libphonenumber), opt-in / opt-out flags. Duplicates are resolved later by (organization, normalized phone).
 * <ul>
 *   <li>opt_in  = D6 and allowed_broadcast = 1 and not is_blocked and not blacklisted by that customer</li>
 *   <li>opt_out = is_blocked or blacklisted (blacklists rows of the same customer project, matched by E.164)</li>
 *   <li>soft-deleted old contacts are not migrated (counted INFO); unusable phones -> ERROR INVALID_PHONE</li>
 * </ul>
 * Keyset-paged with checkpoint. After changing D6 / the phone settings re-run with --migration.reset-checkpoints=true.
 */
@Component
public class ContactsPrepareStep implements MigrationStep {

    @Override public String id() { return "08a-contacts-prepare"; }
    @Override public int order() { return 810; }
    @Override public String title() { return "chat_contacts -> mig_contact (tenant + E.164 + consent flags)"; }

    @Override
    public void run(StepContext ctx) {
        Db db = ctx.db();
        Sql sql = ctx.sql();
        PhoneNormalizer phones = new PhoneNormalizer(ctx.props().getContacts().getDefaultDialCode());
        boolean plus = ctx.props().getContacts().isPhoneWithPlus();
        boolean d6 = ctx.props().getContacts().isOptInFromAllowedBroadcast();

        // blacklist per project
        Set<String> blacklisted = new HashSet<>();
        for (Row b : db.rows("SELECT id, user_id, mobile, country_id FROM " + sql.old("blacklists") + " WHERE deleted_at IS NULL")) {
            Tenant t = ctx.tenants().resolve(b.lng("user_id"));
            if (!t.hasProject()) continue;
            Phone p = phones.normalize(b.str("mobile"), b.str("country_id"));
            if (p != null) blacklisted.add(t.projectId() + ":" + p.normalized(plus));
        }

        long deleted = db.count("SELECT COUNT(*) FROM " + sql.old("chat_contacts") + " WHERE deleted_at IS NOT NULL");
        if (!ctx.scope().isPilot())
            ctx.problems().aggregate(ProblemLog.Severity.INFO, "chat_contacts", "SOFT_DELETED_SKIPPED", deleted,
                    "soft-deleted old contacts are not migrated");

        StepStats.Entity s = ctx.stats().entity("mig_contact");
        ctx.pages("main", "SELECT id, user_id, mobile, country_id, is_blocked, allowed_broadcast FROM " + sql.old("chat_contacts")
                + " WHERE id > :lastId AND deleted_at IS NULL ORDER BY id LIMIT :limit", Map.of(), "id", page -> {
            List<Map<String, ?>> out = new ArrayList<>(page.size());
            for (Row c : page) {
                s.read++;
                long oldId = c.lng("id");
                Tenant t = ctx.place("chat_contacts", oldId, c.lng("user_id"));
                if (t == null) continue;
                Phone p = phones.normalize(c.str("mobile"), c.str("country_id"));
                Db.Vals v = Db.vals().with("old_id", oldId).with("old_user_id", c.lng("user_id"))
                        .with("organization_id", t.orgId()).with("project_id", t.projectId()).with("new_user_id", t.userId());
                if (p == null) {
                    ctx.problems().error("chat_contacts", oldId, "INVALID_PHONE", "mobile '" + c.str("mobile") + "' is not a phone number");
                    s.errors++;
                    v.with("dial_code", null).with("national", null).with("normalized", null).with("country_code", null)
                            .with("phone_valid", false).with("opt_in", false).with("opt_out", false).with("is_deleted", false)
                            .with("placeable", false).with("reason", "INVALID_PHONE");
                } else {
                    String normalized = p.normalized(plus);
                    boolean bl = blacklisted.contains(t.projectId() + ":" + normalized);
                    boolean blocked = c.bool("is_blocked");
                    boolean optOut = blocked || bl;
                    boolean optIn = d6 && c.bool("allowed_broadcast") && !optOut;
                    if (!p.valid()) ctx.problems().warn("chat_contacts", oldId, "PHONE_NOT_VALID",
                            "'" + c.str("mobile") + "' -> " + normalized + " is not a valid number: contact status INVALID");
                    v.with("dial_code", p.dialCode()).with("national", p.national()).with("normalized", normalized)
                            .with("country_code", p.region()).with("phone_valid", p.valid()).with("opt_in", optIn)
                            .with("opt_out", optOut).with("is_deleted", false).with("placeable", true)
                            .with("reason", bl ? "BLACKLISTED" : blocked ? "BLOCKED" : null);
                }
                out.add(v);
                s.inserted++;
            }
            ctx.insertBatchSafe(sql.mig("mig_contact"), out, "ON DUPLICATE KEY UPDATE old_user_id = VALUES(old_user_id), "
                    + "organization_id = VALUES(organization_id), project_id = VALUES(project_id), new_user_id = VALUES(new_user_id), "
                    + "dial_code = VALUES(dial_code), national = VALUES(national), normalized = VALUES(normalized), "
                    + "country_code = VALUES(country_code), phone_valid = VALUES(phone_valid), opt_in = VALUES(opt_in), "
                    + "opt_out = VALUES(opt_out), placeable = VALUES(placeable), reason = VALUES(reason), prepared_at = CURRENT_TIMESTAMP(6)", "chat_contacts", "old_id");
        });
    }
}
