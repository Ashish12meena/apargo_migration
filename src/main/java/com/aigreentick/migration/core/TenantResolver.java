package com.aigreentick.migration.core;

import com.aigreentick.migration.config.MigrationProperties;
import com.aigreentick.migration.config.MigrationProperties.NoResellerPolicy;
import com.aigreentick.migration.core.Tenant.Kind;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single implementation of the tenant rule decided by Phase 1 (plan section 3.1):
 * <pre>
 *  customer (role 3)        -> org of users.reseller_id, the customer's project
 *  agent (role 7)           -> creator's project (created_by, else account_admin_id)
 *  reseller admin           -> reseller's org, project only with D2 = OWN_PROJECT
 *  customer without reseller-> D1 (SKIP, or ASSIGN to no-reseller-org-id after step 03a)
 *  anything else            -> not migrated (D3)
 * </pre>
 * Every step asks this class; no step derives tenants on its own.
 */
@Component
public class TenantResolver {

    public record OldUser(long id, int roleId, Long resellerId, Long createdBy, Long accountAdminId,
                          String email, boolean deleted, String name, String companyName) {}

    private final Db db;
    private final Sql sql;
    private final IdMap idMap;
    private final MigrationProperties props;

    private Map<Long, OldUser> users;
    private Map<Long, Long> resellerByAdmin;
    private final Map<Long, Tenant> cache = new ConcurrentHashMap<>();

    public TenantResolver(Db db, Sql sql, IdMap idMap, MigrationProperties props) {
        this.db = db;
        this.sql = sql;
        this.idMap = idMap;
        this.props = props;
    }

    /** Forget resolved tenants (a step such as 03a may have created projects). */
    public void clearCache() { cache.clear(); }

    public Map<Long, OldUser> users() { load(); return users; }

    public OldUser user(Long oldUserId) { load(); return oldUserId == null ? null : users.get(oldUserId); }

    public Map<Long, Long> resellerByAdmin() { load(); return resellerByAdmin; }

    public Tenant resolve(Long oldUserId) {
        if (oldUserId == null) return Tenant.fail(Kind.UNKNOWN, null, "user_id is NULL");
        load();
        return cache.computeIfAbsent(oldUserId, id -> doResolve(id, 0));
    }

    private Tenant doResolve(long id, int depth) {
        OldUser u = users.get(id);
        if (u == null) return Tenant.fail(Kind.UNKNOWN, null, "old user " + id + " does not exist");
        Long newUser = idMap.get("user", id);
        if (newUser == null) return Tenant.fail(Kind.UNKNOWN, null, "old user " + id + " was not migrated (no 'user' mapping)");

        Long resellerId = resellerByAdmin.get(id);
        if (resellerId != null) {
            Long org = idMap.get("org", resellerId);
            if (org == null) return Tenant.fail(Kind.RESELLER_ADMIN, newUser, "reseller " + resellerId + " not migrated");
            Long project = idMap.get("reseller_project", resellerId);
            return new Tenant(true, Kind.RESELLER_ADMIN, org, project, newUser,
                    project == null ? "reseller admin " + id + " has no project (D2 = SKIP)" : null);
        }

        if (u.roleId() == props.getRoles().getCustomerRoleId()) {
            if (u.resellerId() == null) {
                if (props.getTenant().getNoResellerPolicy() == NoResellerPolicy.SKIP)
                    return Tenant.fail(Kind.NO_RESELLER, newUser, "customer " + id + " has no reseller_id (D1 = SKIP)");
                Long project = idMap.get("project", id);
                if (project == null)
                    return Tenant.fail(Kind.NO_RESELLER, newUser, "customer " + id + " has no reseller_id and no project yet (run step 03a)");
                return new Tenant(true, Kind.CUSTOMER, props.getTenant().getNoResellerOrgId(), project, newUser, null);
            }
            Long org = idMap.get("org", u.resellerId());
            if (org == null) return Tenant.fail(Kind.CUSTOMER, newUser, "reseller " + u.resellerId() + " of customer " + id + " not migrated");
            Long project = idMap.get("project", id);
            if (project == null)
                return Tenant.fail(Kind.CUSTOMER, newUser, "customer " + id + " has no project (merged by e-mail into another user, D4)");
            return new Tenant(true, Kind.CUSTOMER, org, project, newUser, null);
        }

        if (u.roleId() == props.getRoles().getAgentRoleId()) {
            Long creator = u.createdBy() != null ? u.createdBy() : u.accountAdminId();
            if (creator == null) return Tenant.fail(Kind.AGENT, newUser, "agent " + id + " has no creator");
            if (depth > 3 || creator == id) return Tenant.fail(Kind.AGENT, newUser, "agent " + id + ": creator chain too deep");
            Tenant c = doResolve(creator, depth + 1);
            if (c.ok() && c.projectId() != null && (c.kind() == Kind.CUSTOMER || c.kind() == Kind.RESELLER_ADMIN))
                return new Tenant(true, Kind.AGENT, c.orgId(), c.projectId(), newUser, null);
            return Tenant.fail(Kind.AGENT, newUser, "agent " + id + ": creator " + creator + " -> " + c.reason());
        }

        return Tenant.fail(Kind.OTHER, newUser, "old role " + u.roleId() + " of user " + id + " is not migrated (D3)");
    }

    /** Org of an old reseller (or null). */
    public Long orgOfReseller(Long resellerId) { return resellerId == null ? null : idMap.get("org", resellerId); }

    /** Rewrites mig_tenant_map (old user -> org, project, new user) so SQL-only steps can JOIN it. */
    public int writeTenantMap() {
        load();
        clearCache();
        db.exec("DELETE FROM " + sql.mig("mig_tenant_map"));
        List<Map<String, ?>> rows = new ArrayList<>();
        for (OldUser u : users.values()) {
            Tenant t = resolve(u.id());
            rows.add(Db.vals().with("old_user_id", u.id()).with("kind", t.kind().name())
                    .with("organization_id", t.orgId()).with("project_id", t.projectId())
                    .with("new_user_id", t.userId()).with("reason", t.reason() == null ? null : cut(t.reason(), 255)));
            if (rows.size() == 1000) { db.insertBatch(sql.mig("mig_tenant_map"), rows); rows.clear(); }
        }
        db.insertBatch(sql.mig("mig_tenant_map"), rows);
        return users.size();
    }

    private static String cut(String s, int n) { return s.length() <= n ? s : s.substring(0, n); }

    private synchronized void load() {
        if (users != null) return;
        Map<Long, OldUser> u = new HashMap<>();
        for (Row r : db.rows("SELECT id, role_id, reseller_id, created_by, account_admin_id, email, deleted_at, name, company_name FROM "
                + sql.old("users"))) {
            u.put(r.lng("id"), new OldUser(r.lng("id"), r.integer("role_id"), r.lng("reseller_id"), r.lng("created_by"),
                    r.lng("account_admin_id"), r.str("email"), !r.isNull("deleted_at"), r.str("name"), r.str("company_name")));
        }
        Map<Long, Long> admins = new HashMap<>();
        for (Row r : db.rows("SELECT id, admin_user_id FROM " + sql.old("resellers") + " WHERE deleted_at IS NULL ORDER BY id")) {
            admins.putIfAbsent(r.lng("admin_user_id"), r.lng("id"));
        }
        this.users = u;
        this.resellerByAdmin = admins;
    }
}
