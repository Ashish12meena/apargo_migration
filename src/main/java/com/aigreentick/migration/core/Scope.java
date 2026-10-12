package com.aigreentick.migration.core;

import java.util.Set;
import java.util.stream.Collectors;

/** Pilot scope (--migration.tenants): only rows whose tenant project is one of these are migrated. */
public final class Scope {
    private final Set<Long> projects;   // null = everything

    private Scope(Set<Long> projects) { this.projects = projects; }

    public static Scope all() { return new Scope(null); }

    public static Scope of(Set<Long> projects) { return new Scope(Set.copyOf(projects)); }

    public boolean isPilot() { return projects != null; }

    public Set<Long> projects() { return projects; }

    public boolean includes(Tenant t) { return projects == null || (t != null && t.projectId() != null && projects.contains(t.projectId())); }

    public boolean includesProject(Long projectId) { return projects == null || (projectId != null && projects.contains(projectId)); }

    /** " AND col IN (...)" for SQL steps, or "" when not a pilot run. */
    public String sql(String projectColumn) {
        if (projects == null) return "";
        if (projects.isEmpty()) return " AND 1 = 0";
        return " AND " + projectColumn + " IN (" + projects.stream().map(String::valueOf).collect(Collectors.joining(",")) + ")";
    }
}
