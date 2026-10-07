package com.aigreentick.migration.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Every decision of MIGRATION_PLAN.md section 6 is a property here, so a later answer is a config change
 * plus a re-run, never a code change. Bound from {@code migration.*} in application.yml / command line.
 */
@ConfigurationProperties(prefix = "migration")
public class MigrationProperties {

    /** Comma list of step ids to run (e.g. "03c-departments,05-waba"). Empty = all steps. */
    private String steps = "";
    /** Comma list of step ids to leave out. */
    private String skipSteps = "";
    /** Run everything in ONE transaction and roll it back at the end. */
    private boolean dryRun = false;
    /** Update the per-step list of refreshable columns on rows that already exist. */
    private boolean refresh = false;
    /** Pilot: comma list of OLD customer user ids (role 3). Only their data is migrated; checkpoints are ignored. */
    private String tenants = "";
    /** Rows per batch transaction for the big steps. */
    private int batchSize = 2000;
    /** Forget the saved high-water marks of the selected steps and re-scan from the first old id. */
    private boolean resetCheckpoints = false;

    private Schemas schemas = new Schemas();
    private LegacyMap legacyMap = new LegacyMap();
    private Baseline baseline = new Baseline();
    private Roles roles = new Roles();
    private Tenant tenant = new Tenant();
    private Auth auth = new Auth();
    private Teams teams = new Teams();
    private Pricing pricing = new Pricing();
    private Waba waba = new Waba();
    private Templates templates = new Templates();
    private Contacts contacts = new Contacts();
    private History history = new History();
    private Messaging messaging = new Messaging();
    private Problems problems = new Problems();
    private Validate validate = new Validate();

    // ------------------------------------------------------------------ helpers

    public Set<String> selectedSteps() { return csv(steps); }

    public Set<String> skippedSteps() { return csv(skipSteps); }

    public List<Long> pilotTenantIds() {
        return csv(tenants).stream().map(Long::parseLong).toList();
    }

    public boolean isPilot() { return !pilotTenantIds().isEmpty(); }

    private static Set<String> csv(String s) {
        if (s == null || s.isBlank()) return Set.of();
        return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // ------------------------------------------------------------------ nested groups

    public static class Schemas {
        /** Old monolith (read only). */
        private String old = "aigreentick_2nd";
        /** New DB: every service's tables (users, organizations, waba, templates, contacts, messaging, ...). */
        private String target = "apargo_wa_messaging";
        /** The service's own tracking schema (Flyway-managed), same MySQL server. */
        private String mig = "aigreentick_migration";
        public String getOld() { return old; }
        public void setOld(String old) { this.old = old; }
        public String getTarget() { return target; }
        public void setTarget(String target) { this.target = target; }
        public String getMig() { return mig; }
        public void setMig(String mig) { this.mig = mig; }
    }

    public static class LegacyMap {
        /** Copy Phase 1/2's migration_id_map (in the target schema) into the tracking schema at start-up. */
        private boolean importOnStart = true;
        private String table = "migration_id_map";
        public boolean isImportOnStart() { return importOnStart; }
        public void setImportOnStart(boolean importOnStart) { this.importOnStart = importOnStart; }
        public String getTable() { return table; }
        public void setTable(String table) { this.table = table; }
    }

    public static class Baseline {
        /** Stop the run when the Phase 1 numbers differ from the expected values below. */
        private boolean enforce = true;
        private Long oldUsers = 539L;
        private Long mappedUsers = 529L;
        private Long distinctNewUsers = 487L;
        private Long newUsersTotal = null;   // grows once the new system is live; report only by default
        public boolean isEnforce() { return enforce; }
        public void setEnforce(boolean enforce) { this.enforce = enforce; }
        public Long getOldUsers() { return oldUsers; }
        public void setOldUsers(Long oldUsers) { this.oldUsers = oldUsers; }
        public Long getMappedUsers() { return mappedUsers; }
        public void setMappedUsers(Long mappedUsers) { this.mappedUsers = mappedUsers; }
        public Long getDistinctNewUsers() { return distinctNewUsers; }
        public void setDistinctNewUsers(Long distinctNewUsers) { this.distinctNewUsers = distinctNewUsers; }
        public Long getNewUsersTotal() { return newUsersTotal; }
        public void setNewUsersTotal(Long newUsersTotal) { this.newUsersTotal = newUsersTotal; }
    }

    public static class Roles {
        private int customerRoleId = 3;
        private int agentRoleId = 7;
        private String orgOwnerSlug = "owner";
        private String projectAdminSlug = "admin";
        private String projectAgentSlug = "agent";
        public int getCustomerRoleId() { return customerRoleId; }
        public void setCustomerRoleId(int customerRoleId) { this.customerRoleId = customerRoleId; }
        public int getAgentRoleId() { return agentRoleId; }
        public void setAgentRoleId(int agentRoleId) { this.agentRoleId = agentRoleId; }
        public String getOrgOwnerSlug() { return orgOwnerSlug; }
        public void setOrgOwnerSlug(String orgOwnerSlug) { this.orgOwnerSlug = orgOwnerSlug; }
        public String getProjectAdminSlug() { return projectAdminSlug; }
        public void setProjectAdminSlug(String projectAdminSlug) { this.projectAdminSlug = projectAdminSlug; }
        public String getProjectAgentSlug() { return projectAgentSlug; }
        public void setProjectAgentSlug(String projectAgentSlug) { this.projectAgentSlug = projectAgentSlug; }
    }

    public enum NoResellerPolicy { SKIP, ASSIGN }
    public enum ResellerAdminPolicy { SKIP, OWN_PROJECT }
    public enum MergedCustomerPolicy { SKIP }

    public static class Tenant {
        /** D1: customers with no reseller_id. */
        private NoResellerPolicy noResellerPolicy = NoResellerPolicy.SKIP;
        private long noResellerOrgId = 1;
        /** D2: business data owned by reseller admins. */
        private ResellerAdminPolicy resellerAdminPolicy = ResellerAdminPolicy.SKIP;
        /** D4: customers merged into another user by e-mail that got no project. */
        private MergedCustomerPolicy mergedCustomerPolicy = MergedCustomerPolicy.SKIP;
        public NoResellerPolicy getNoResellerPolicy() { return noResellerPolicy; }
        public void setNoResellerPolicy(NoResellerPolicy p) { this.noResellerPolicy = p; }
        public long getNoResellerOrgId() { return noResellerOrgId; }
        public void setNoResellerOrgId(long noResellerOrgId) { this.noResellerOrgId = noResellerOrgId; }
        public ResellerAdminPolicy getResellerAdminPolicy() { return resellerAdminPolicy; }
        public void setResellerAdminPolicy(ResellerAdminPolicy p) { this.resellerAdminPolicy = p; }
        public MergedCustomerPolicy getMergedCustomerPolicy() { return mergedCustomerPolicy; }
        public void setMergedCustomerPolicy(MergedCustomerPolicy p) { this.mergedCustomerPolicy = p; }
    }

    public static class Auth {
        /** D5: mark users that are soft-deleted in the old DB as status=deleted (and archive their project). */
        private boolean fixDeletedUsers = false;
        public boolean isFixDeletedUsers() { return fixDeletedUsers; }
        public void setFixDeletedUsers(boolean fixDeletedUsers) { this.fixDeletedUsers = fixDeletedUsers; }
    }

    public static class Teams {
        private String leadRoleSlug = "team-leader";
        private String leadRoleName = "Team Leader";
        private String memberRoleSlug = "support-agent";
        private String memberRoleName = "Support Agent";
        public String getLeadRoleSlug() { return leadRoleSlug; }
        public void setLeadRoleSlug(String leadRoleSlug) { this.leadRoleSlug = leadRoleSlug; }
        public String getLeadRoleName() { return leadRoleName; }
        public void setLeadRoleName(String leadRoleName) { this.leadRoleName = leadRoleName; }
        public String getMemberRoleSlug() { return memberRoleSlug; }
        public void setMemberRoleSlug(String memberRoleSlug) { this.memberRoleSlug = memberRoleSlug; }
        public String getMemberRoleName() { return memberRoleName; }
        public void setMemberRoleName(String memberRoleName) { this.memberRoleName = memberRoleName; }
    }

    public static class Pricing {
        private String defaultCountryCode = "IN";
        private String defaultCountryName = "India";
        private String currency = "INR";
        public String getDefaultCountryCode() { return defaultCountryCode; }
        public void setDefaultCountryCode(String v) { this.defaultCountryCode = v; }
        public String getDefaultCountryName() { return defaultCountryName; }
        public void setDefaultCountryName(String v) { this.defaultCountryName = v; }
        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }
    }

    public static class Waba {
        private String tokenType = "SYSTEM_USER";
        private boolean includeDeleted = false;
        /** pinacle_credentials.partner_id is NOT NULL and the old DB has none: this value, else the old username. */
        private String pinnaclePartnerId = "";
        /** Old whatsapp_accounts.billing_type codes that mean POSTPAID (comma separated); all others are PREPAID. */
        private String pinnaclePostpaidBillingTypes = "2";
        public String getTokenType() { return tokenType; }
        public void setTokenType(String tokenType) { this.tokenType = tokenType; }
        public boolean isIncludeDeleted() { return includeDeleted; }
        public void setIncludeDeleted(boolean includeDeleted) { this.includeDeleted = includeDeleted; }
        public String getPinnaclePartnerId() { return pinnaclePartnerId; }
        public void setPinnaclePartnerId(String v) { this.pinnaclePartnerId = v; }
        public String getPinnaclePostpaidBillingTypes() { return pinnaclePostpaidBillingTypes; }
        public void setPinnaclePostpaidBillingTypes(String v) { this.pinnaclePostpaidBillingTypes = v; }
    }

    public static class Templates {
        private boolean includeDeleted = false;
        /** whatsapp_templates.category is NOT NULL: used when the old category is unknown (WARN). */
        private String defaultCategory = "MARKETING";
        public boolean isIncludeDeleted() { return includeDeleted; }
        public void setIncludeDeleted(boolean includeDeleted) { this.includeDeleted = includeDeleted; }
        public String getDefaultCategory() { return defaultCategory; }
        public void setDefaultCategory(String defaultCategory) { this.defaultCategory = defaultCategory; }
    }

    public enum UserRefFormat { ID, UUID }

    public static class Contacts {
        /** Dial code used when the old row has none (chat_contacts.country_id, reports, blacklists). */
        private String defaultDialCode = "91";
        /** normalized_phone as "+919876543210" (true) or "919876543210" (false). Must match the contact service. */
        private boolean phoneWithPlus = true;
        /** D6 (legal/product decision): allowed_broadcast=1 and not blocked -> opted_in=TRUE + IMPORT consent row. */
        private boolean optInFromAllowedBroadcast = true;
        /** Q-S9: contact-service VARCHAR(64) user columns hold the user id as text (ID) or the user uuid (UUID). */
        private UserRefFormat userRef = UserRefFormat.ID;
        /** contact_sources.id of the seeded "Migration" source, used for every migrated contact / project_contact. */
        private long sourceId = 18;
        /** contact_statuses.id for live contacts (seed: 1 = Active) and for numbers that are not valid (seed: 4 = Invalid). */
        private long activeStatusId = 1;
        private long invalidStatusId = 4;
        public String getDefaultDialCode() { return defaultDialCode; }
        public void setDefaultDialCode(String v) { this.defaultDialCode = v; }
        public boolean isPhoneWithPlus() { return phoneWithPlus; }
        public void setPhoneWithPlus(boolean phoneWithPlus) { this.phoneWithPlus = phoneWithPlus; }
        public boolean isOptInFromAllowedBroadcast() { return optInFromAllowedBroadcast; }
        public void setOptInFromAllowedBroadcast(boolean v) { this.optInFromAllowedBroadcast = v; }
        public UserRefFormat getUserRef() { return userRef; }
        public void setUserRef(UserRefFormat userRef) { this.userRef = userRef; }
        public long getSourceId() { return sourceId; }
        public void setSourceId(long sourceId) { this.sourceId = sourceId; }
        public long getActiveStatusId() { return activeStatusId; }
        public void setActiveStatusId(long v) { this.activeStatusId = v; }
        public long getInvalidStatusId() { return invalidStatusId; }
        public void setInvalidStatusId(long v) { this.invalidStatusId = v; }
    }

    public static class History {
        /** D7: only chats created on/after this date (yyyy-MM-dd). Empty = all. */
        private String chatsSince = "";
        /** D7: only reports created on/after this date (yyyy-MM-dd). Empty = all. */
        private String reportsSince = "2025-10-01";
        /** D7: only broadcasts created on/after this date. Empty = all. */
        private String broadcastsSince = "";
        public String getChatsSince() { return chatsSince; }
        public void setChatsSince(String chatsSince) { this.chatsSince = chatsSince; }
        public String getReportsSince() { return reportsSince; }
        public void setReportsSince(String reportsSince) { this.reportsSince = reportsSince; }
        public String getBroadcastsSince() { return broadcastsSince; }
        public void setBroadcastsSince(String broadcastsSince) { this.broadcastsSince = broadcastsSince; }
    }

    public static class Messaging {
        private int autoCloseAfterMins = 1440;
        private String defaultTimezone = "Asia/Kolkata";
        /** A conversation with an active old agent assignment becomes OPEN (else RESOLVED). */
        private boolean openIfActiveAssignment = true;
        private int campaignMaxAttempts = 3;
        /** old chats.type -> INBOUND | OUTBOUND (lower-case keys). Unknown -> decided by number matching. */
        private Map<String, String> chatDirection = new LinkedHashMap<>(Map.of(
                "send", "OUTBOUND", "sent", "OUTBOUND", "outgoing", "OUTBOUND",
                "receive", "INBOUND", "received", "INBOUND", "incoming", "INBOUND", "reply", "INBOUND"));
        /** old chats.method -> messages.message_type (lower-case keys). Unknown -> UNSUPPORTED + WARN. */
        private Map<String, String> chatType = new LinkedHashMap<>(Map.ofEntries(
                Map.entry("text", "TEXT"), Map.entry("template", "TEMPLATE"), Map.entry("image", "IMAGE"),
                Map.entry("video", "VIDEO"), Map.entry("audio", "AUDIO"), Map.entry("voice", "AUDIO"),
                Map.entry("document", "DOCUMENT"), Map.entry("file", "DOCUMENT"), Map.entry("sticker", "STICKER"),
                Map.entry("location", "LOCATION"), Map.entry("contacts", "CONTACTS"), Map.entry("contact", "CONTACTS"),
                Map.entry("interactive", "INTERACTIVE"), Map.entry("list", "INTERACTIVE"),
                Map.entry("button", "BUTTON"), Map.entry("reaction", "REACTION"), Map.entry("order", "ORDER"),
                Map.entry("system", "SYSTEM")));
        /** old chats.status -> messages.status. Never QUEUED/PROCESSING after migration. */
        private Map<String, String> chatStatus = new LinkedHashMap<>(Map.ofEntries(
                Map.entry("sent", "SENT"), Map.entry("delivered", "DELIVERED"), Map.entry("read", "READ"),
                Map.entry("seen", "READ"), Map.entry("failed", "FAILED"), Map.entry("error", "FAILED"),
                Map.entry("deleted", "DELETED"), Map.entry("accepted", "SENT"), Map.entry("success", "SENT"),
                Map.entry("pending", "FAILED"), Map.entry("queued", "FAILED"), Map.entry("received", "READ"),
                Map.entry("expired", "EXPIRED"), Map.entry("rejected", "REJECTED")));
        /** old reports.status -> recipient result (SENT | DELIVERED | READ | FAILED | CANCELLED). */
        private Map<String, String> reportStatus = new LinkedHashMap<>(Map.ofEntries(
                Map.entry("sent", "SENT"), Map.entry("delivered", "DELIVERED"), Map.entry("read", "READ"),
                Map.entry("seen", "READ"), Map.entry("failed", "FAILED"), Map.entry("error", "FAILED"),
                Map.entry("accepted", "SENT"), Map.entry("success", "SENT"),
                Map.entry("pending", "CANCELLED"), Map.entry("queued", "CANCELLED")));
        public int getAutoCloseAfterMins() { return autoCloseAfterMins; }
        public void setAutoCloseAfterMins(int v) { this.autoCloseAfterMins = v; }
        public String getDefaultTimezone() { return defaultTimezone; }
        public void setDefaultTimezone(String v) { this.defaultTimezone = v; }
        public boolean isOpenIfActiveAssignment() { return openIfActiveAssignment; }
        public void setOpenIfActiveAssignment(boolean v) { this.openIfActiveAssignment = v; }
        public int getCampaignMaxAttempts() { return campaignMaxAttempts; }
        public void setCampaignMaxAttempts(int v) { this.campaignMaxAttempts = v; }
        public Map<String, String> getChatDirection() { return chatDirection; }
        public void setChatDirection(Map<String, String> v) { this.chatDirection = v; }
        public Map<String, String> getChatType() { return chatType; }
        public void setChatType(Map<String, String> v) { this.chatType = v; }
        public Map<String, String> getChatStatus() { return chatStatus; }
        public void setChatStatus(Map<String, String> v) { this.chatStatus = v; }
        public Map<String, String> getReportStatus() { return reportStatus; }
        public void setReportStatus(Map<String, String> v) { this.reportStatus = v; }
    }

    public static class Problems {
        /** Per (step, code): rows written to mig_errors individually; beyond that only counted (summary row). */
        private int maxRowsPerCode = 10_000_000;
        public int getMaxRowsPerCode() { return maxRowsPerCode; }
        public void setMaxRowsPerCode(int maxRowsPerCode) { this.maxRowsPerCode = maxRowsPerCode; }
    }

    public static class Validate {
        /** Exit with code 1 when a relation check of step 99 fails. */
        private boolean failOnError = false;
        public boolean isFailOnError() { return failOnError; }
        public void setFailOnError(boolean failOnError) { this.failOnError = failOnError; }
    }

    // ------------------------------------------------------------------ getters / setters (top level)

    public String getSteps() { return steps; }
    public void setSteps(String steps) { this.steps = steps; }
    public String getSkipSteps() { return skipSteps; }
    public void setSkipSteps(String skipSteps) { this.skipSteps = skipSteps; }
    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
    public boolean isRefresh() { return refresh; }
    public void setRefresh(boolean refresh) { this.refresh = refresh; }
    public String getTenants() { return tenants; }
    public void setTenants(String tenants) { this.tenants = tenants; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public boolean isResetCheckpoints() { return resetCheckpoints; }
    public void setResetCheckpoints(boolean resetCheckpoints) { this.resetCheckpoints = resetCheckpoints; }
    public Schemas getSchemas() { return schemas; }
    public void setSchemas(Schemas schemas) { this.schemas = schemas; }
    public LegacyMap getLegacyMap() { return legacyMap; }
    public void setLegacyMap(LegacyMap legacyMap) { this.legacyMap = legacyMap; }
    public Baseline getBaseline() { return baseline; }
    public void setBaseline(Baseline baseline) { this.baseline = baseline; }
    public Roles getRoles() { return roles; }
    public void setRoles(Roles roles) { this.roles = roles; }
    public Tenant getTenant() { return tenant; }
    public void setTenant(Tenant tenant) { this.tenant = tenant; }
    public Auth getAuth() { return auth; }
    public void setAuth(Auth auth) { this.auth = auth; }
    public Teams getTeams() { return teams; }
    public void setTeams(Teams teams) { this.teams = teams; }
    public Pricing getPricing() { return pricing; }
    public void setPricing(Pricing pricing) { this.pricing = pricing; }
    public Waba getWaba() { return waba; }
    public void setWaba(Waba waba) { this.waba = waba; }
    public Templates getTemplates() { return templates; }
    public void setTemplates(Templates templates) { this.templates = templates; }
    public Contacts getContacts() { return contacts; }
    public void setContacts(Contacts contacts) { this.contacts = contacts; }
    public History getHistory() { return history; }
    public void setHistory(History history) { this.history = history; }
    public Messaging getMessaging() { return messaging; }
    public void setMessaging(Messaging messaging) { this.messaging = messaging; }
    public Problems getProblems() { return problems; }
    public void setProblems(Problems problems) { this.problems = problems; }
    public Validate getValidate() { return validate; }
    public void setValidate(Validate validate) { this.validate = validate; }
}
