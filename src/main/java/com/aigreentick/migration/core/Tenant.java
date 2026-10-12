package com.aigreentick.migration.core;

/** Where an old user's rows belong in the new hierarchy (MIGRATION_PLAN.md section 3.1). */
public record Tenant(boolean ok, Kind kind, Long orgId, Long projectId, Long userId, String reason) {

    public enum Kind { CUSTOMER, AGENT, RESELLER_ADMIN, NO_RESELLER, OTHER, UNKNOWN }

    public boolean hasProject() { return ok && projectId != null; }

    public static Tenant fail(Kind kind, Long userId, String reason) {
        return new Tenant(false, kind, null, null, userId, reason);
    }

    /** Error code for mig_errors when a row of this tenant cannot be placed. */
    public String code() {
        if (ok && projectId == null) return kind == Kind.RESELLER_ADMIN ? "RESELLER_ADMIN_NO_PROJECT" : "NO_PROJECT";
        return switch (kind) {
            case NO_RESELLER -> "NO_RESELLER";
            case RESELLER_ADMIN -> "RESELLER_ADMIN_NO_PROJECT";
            case OTHER -> "ROLE_NOT_MIGRATED";
            case AGENT -> "AGENT_UNPLACED";
            case CUSTOMER -> "CUSTOMER_NO_PROJECT";
            case UNKNOWN -> "USER_UNKNOWN";
        };
    }
}
