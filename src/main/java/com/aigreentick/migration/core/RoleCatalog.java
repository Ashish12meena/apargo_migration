package com.aigreentick.migration.core;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** Seed roles looked up exactly like Phase 1: (slug, scope, organization_id NULL, project_id NULL). */
@Component
public class RoleCatalog {
    private final Db db;
    private final Sql sql;
    private final Map<String, Long> cache = new HashMap<>();

    public RoleCatalog(Db db, Sql sql) {
        this.db = db;
        this.sql = sql;
    }

    public long id(String slug, String scope) {
        return cache.computeIfAbsent(scope + "/" + slug, k -> {
            Long id = db.longValue("SELECT id FROM " + sql.tgt("roles")
                    + " WHERE slug = :s AND scope = :sc AND organization_id IS NULL AND project_id IS NULL ORDER BY id LIMIT 1",
                    Map.of("s", slug, "sc", scope));
            if (id == null) throw new IllegalStateException("Role not found: " + scope + "/" + slug + " (run the master seed first)");
            return id;
        });
    }
}
