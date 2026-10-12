package com.aigreentick.migration.core;

/** One unit of the migration (MIGRATION_PLAN.md section 8). Steps always run in {@link #order()}. */
public interface MigrationStep {
    /** e.g. "03c-departments" — used by --migration.steps */
    String id();

    /** sort key, e.g. 330 */
    int order();

    /** one line for the log */
    String title();

    void run(StepContext ctx);
}
