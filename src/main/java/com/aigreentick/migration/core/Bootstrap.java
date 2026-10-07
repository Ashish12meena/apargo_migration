package com.aigreentick.migration.core;

import com.aigreentick.migration.config.MigrationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs before the first step of every run (cheap, idempotent):
 * <ol>
 *   <li>copies Phase 1/2's {@code migration_id_map} from the target schema into the tracking schema;</li>
 *   <li>backfills entity {@code project} (old customer id -> project id), which Phase 1 never wrote:
 *       {@code projects.metadata.migrated_from_user_id} first, slug {@code …-project-{oldId}} second,
 *       each cross-checked against the project's owner user and organization;</li>
 *   <li>forgets the WABA map entries of runs made before the WABA schema of 2026-10-07 (business managers per project):
 *       those tables were rebuilt, so the old ids are meaningless and step 05 must place everything again;</li>
 *   <li>rewrites {@code mig_tenant_map}.</li>
 * </ol>
 */
@Component
public class Bootstrap {
    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);
    private static final Pattern SLUG_ID = Pattern.compile("-project-(\\d+)$");

    private final Db db;
    private final Sql sql;
    private final IdMap idMap;
    private final TenantResolver tenants;
    private final ProblemLog problems;
    private final MigrationProperties props;

    public Bootstrap(Db db, Sql sql, IdMap idMap, TenantResolver tenants, ProblemLog problems, MigrationProperties props) {
        this.db = db;
        this.sql = sql;
        this.idMap = idMap;
        this.tenants = tenants;
        this.problems = problems;
        this.props = props;
    }

    public void prepare() {
        problems.beginStep("bootstrap");
        importLegacyMap();
        forgetOldWabaSchema();
        backfillProjects();
        int n = tenants.writeTenantMap();
        log.info("  mig_tenant_map rewritten for {} old users", n);
        problems.endStep();
    }

    void importLegacyMap() {
        String table = props.getLegacyMap().getTable();
        if (!props.getLegacyMap().isImportOnStart()) return;
        if (!db.tableExists(sql.tgtSchema(), table)) {
            log.warn("  {}.{} does not exist: Phase 1/2 output not found", sql.tgtSchema(), table);
            return;
        }
        String t = sql.mig("migration_id_map");
        // rows the service wrote itself are kept; Phase 1/2 rows follow the source table
        int n = db.exec("INSERT INTO " + t + " (entity, old_id, new_id, step) "
                + "SELECT l.entity, l.old_id, l.new_id, 'phase1-2' FROM " + sql.tgt(table) + " l "
                + "ON DUPLICATE KEY UPDATE new_id = IF(" + t + ".step = 'phase1-2', VALUES(new_id), " + t + ".new_id)");
        idMap.invalidateAll();
        log.info("  legacy migration_id_map imported ({} rows inserted/changed)", n);
    }

    /** entity 'project_waba_assignment' was only written by step 05 for the retired schema (project_waba_assignments) */
    void forgetOldWabaSchema() {
        String t = sql.mig("migration_id_map");
        if (db.count("SELECT COUNT(*) FROM " + t + " WHERE entity = 'project_waba_assignment'") == 0) return;
        int n = db.exec("DELETE FROM " + t + " WHERE entity IN ('project_waba_assignment', 'waba', 'waba_phone', 'meta_token')");
        idMap.invalidateAll();
        log.warn("  {} WABA map rows of the retired WABA schema removed: step 05 places all WhatsApp accounts again", n);
    }

    void backfillProjects() {
        int customerRole = props.getRoles().getCustomerRoleId();
        Map<Long, List<Row>> byOldId = new HashMap<>();
        boolean hasMeta = db.columnExists(sql.tgtSchema(), "projects", "metadata");
        if (hasMeta) {
            for (Row r : db.rows("SELECT id, organization_id, user_id, slug, "
                    + "CAST(JSON_UNQUOTE(JSON_EXTRACT(metadata, '$.migrated_from_user_id')) AS UNSIGNED) AS old_uid FROM "
                    + sql.tgt("projects") + " WHERE JSON_EXTRACT(metadata, '$.migrated_from_user_id') IS NOT NULL")) {
                byOldId.computeIfAbsent(r.lng("old_uid"), k -> new ArrayList<>()).add(r);
            }
        }
        for (Row r : db.rows("SELECT id, organization_id, user_id, slug FROM " + sql.tgt("projects")
                + " WHERE slug REGEXP '-project-[0-9]+$'")) {
            Matcher m = SLUG_ID.matcher(r.str("slug"));
            if (!m.find()) continue;
            long oldId = Long.parseLong(m.group(1));
            List<Row> list = byOldId.computeIfAbsent(oldId, k -> new ArrayList<>());
            if (list.stream().noneMatch(x -> Objects.equals(x.lng("id"), r.lng("id")))) list.add(r);
        }

        int added = 0, mismatched = 0;
        Map<Long, Long> toPut = new LinkedHashMap<>();
        for (TenantResolver.OldUser u : tenants.users().values()) {
            if (u.roleId() != customerRole) continue;
            if (idMap.get("project", u.id()) != null) continue;
            List<Row> candidates = byOldId.get(u.id());
            if (candidates == null) continue;
            Long newUser = idMap.get("user", u.id());
            Long expectedOrg = u.resellerId() == null ? props.getTenant().getNoResellerOrgId() : idMap.get("org", u.resellerId());
            Row match = null;
            for (Row c : candidates) {
                if (Objects.equals(c.lng("user_id"), newUser) && Objects.equals(c.lng("organization_id"), expectedOrg)) { match = c; break; }
            }
            if (match == null) {
                mismatched++;
                Row c = candidates.get(0);
                problems.error("projects", c.lng("id"), "PROJECT_MISMATCH", "project " + c.lng("id") + " (" + c.str("slug")
                        + ") looks like old customer " + u.id() + " but owner/org differ: user " + c.lng("user_id") + " vs " + newUser
                        + ", org " + c.lng("organization_id") + " vs " + expectedOrg);
                continue;
            }
            toPut.put(u.id(), match.lng("id"));
            added++;
        }
        idMap.putAll("project", toPut);
        log.info("  project map backfilled: {} added, {} mismatched, {} total", added, mismatched, idMap.all("project").size());
    }
}
