package com.aigreentick.migration.core;

import org.slf4j.Logger;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/** Counters per (step, entity); persisted to mig_step_stats and printed at the end of each step. */
public class StepStats {

    public static class Entity {
        public long read, inserted, matchedExisting, skippedMapped, refreshed, errors;
        public LocalDateTime startedAt = LocalDateTime.now();
        public LocalDateTime finishedAt;
    }

    private final String step;
    private final Map<String, Entity> entities = new LinkedHashMap<>();
    private Entity current;

    public StepStats(String step) { this.step = step; }

    public String step() { return step; }

    /** Select (and create) the counter group the next calls count into. */
    public Entity entity(String name) {
        current = entities.computeIfAbsent(name, k -> new Entity());
        return current;
    }

    public Entity current() {
        if (current == null) current = entity("main");
        return current;
    }

    public Map<String, Entity> all() { return entities; }

    public long totalInserted() { return entities.values().stream().mapToLong(e -> e.inserted).sum(); }

    public void print(Logger log) {
        log.info(String.format("  %-28s %9s %9s %9s %9s %9s %7s", "entity", "read", "inserted", "matched", "mapped", "refreshed", "errors"));
        entities.forEach((k, e) -> log.info(String.format("  %-28s %9d %9d %9d %9d %9d %7d",
                k, e.read, e.inserted, e.matchedExisting, e.skippedMapped, e.refreshed, e.errors)));
    }
}
