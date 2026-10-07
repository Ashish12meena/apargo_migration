package com.aigreentick.migration.steps.auth;

import com.aigreentick.migration.core.*;
import com.aigreentick.migration.util.Text;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/**
 * 03c — old {@code departments} -> {@code project_departments}; {@code users.department_id} ->
 * {@code project_members.department_id}. Port of migrate-03-departments.mjs.
 * <ul>
 *   <li>owner = departments.created_by -> tenant project (customer, or agent's creator's project)</li>
 *   <li>UNIQUE(project_id, name): two old departments with the same name in one project map to ONE row</li>
 *   <li>soft-deleted old departments come over as status = 'archived'</li>
 *   <li>member department is only set where it is NULL, and never across projects</li>
 * </ul>
 * Refresh: description, status.
 */
@Component
public class DepartmentsStep implements MigrationStep {

    @Override public String id() { return "03c-departments"; }
    @Override public int order() { return 330; }
    @Override public String title() { return "departments -> project_departments, member departments"; }

    @Override
    public void run(StepContext ctx) {
        Db db = ctx.db();
        Sql sql = ctx.sql();
        String table = sql.tgt("project_departments");
        StepStats.Entity s = ctx.stats().entity("department");

        for (Row d : db.rows("SELECT * FROM " + sql.old("departments") + " ORDER BY id")) {
            long oldId = d.lng("id");
            s.read++;
            Long mapped = ctx.idMap().get("department", oldId);
            if (mapped != null && !ctx.refresh()) { s.skippedMapped++; continue; }
            Tenant t = ctx.place("departments", oldId, d.lng("created_by"));
            if (t == null) continue;

            String name = Text.cut(Text.firstNonBlank(d.str("name"), "Department " + oldId), 150);
            String status = d.isNull("deleted_at") ? "active" : "archived";
            ctx.tx().row(ctx, "departments", oldId, () -> {
                Long existing = mapped != null ? mapped : db.findId(table, Map.of("project_id", t.projectId(), "name", name));
                if (existing != null) {
                    if (mapped == null) { ctx.idMap().put("department", oldId, existing); s.matchedExisting++; }
                    else s.skippedMapped++;
                    if (ctx.refresh()) {
                        db.update(table, existing, Db.vals().with("description", d.str("description")).with("status", status)
                                .with("updated_at", StepContext.nowSec()));
                        s.refreshed++;
                    }
                    return;
                }
                long id = db.insert(table, Db.vals().with("project_id", t.projectId()).with("name", name)
                        .with("description", d.str("description")).with("status", status).with("created_by", t.userId())
                        .with("created_at", d.dtOr("created_at", StepContext.nowSec()))
                        .with("updated_at", d.dtOr("updated_at", StepContext.nowSec())));
                ctx.idMap().put("department", oldId, id);
                s.inserted++;
            });
        }

        // users.department_id -> project_members.department_id
        StepStats.Entity m = ctx.stats().entity("member_department");
        for (Row u : db.rows("SELECT id, department_id FROM " + sql.old("users") + " WHERE department_id IS NOT NULL ORDER BY id")) {
            m.read++;
            Tenant t = ctx.tenants().resolve(u.lng("id"));
            if (!t.hasProject()) {
                if (!ctx.scope().isPilot()) ctx.problems().warn("users", u.lng("id"), t.code(), "department not linked: " + t.reason());
                continue;
            }
            if (!ctx.scope().includes(t)) continue;
            Long deptId = ctx.idMap().get("department", u.lng("department_id"));
            if (deptId == null) {
                ctx.problems().warn("users", u.lng("id"), "PARENT_MISSING", "department " + u.lng("department_id") + " not migrated");
                m.errors++;
                continue;
            }
            Row pm = db.findRow(sql.tgt("project_members"), Map.of("project_id", t.projectId(), "user_id", t.userId()), "id, department_id");
            if (pm == null) {
                ctx.problems().warn("users", u.lng("id"), "NO_PROJECT_MEMBER", "no project_members row in project " + t.projectId());
                m.errors++;
                continue;
            }
            if (!pm.isNull("department_id")) { m.skippedMapped++; continue; }
            Row dep = db.findRow(sql.tgt("project_departments"), Map.of("id", deptId), "project_id");
            if (dep == null || !Objects.equals(dep.lng("project_id"), t.projectId())) {
                ctx.problems().warn("users", u.lng("id"), "CROSS_PROJECT", "department " + u.lng("department_id")
                        + " belongs to another project -> not linked");
                m.errors++;
                continue;
            }
            ctx.tx().row(ctx, "users", u.lng("id"), () -> {
                db.update(sql.tgt("project_members"), pm.lng("id"), Db.vals().with("department_id", deptId).with("updated_at", StepContext.nowSec()));
                m.inserted++;
            });
        }
    }
}
