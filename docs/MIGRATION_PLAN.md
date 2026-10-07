# AiGreenTick data migration plan — Spring Boot migration service

| | |
|---|---|
| Version | 2.2 — 2026-10-05 (v2.0 Spring Boot design; v2.1 aligned to the server schema dump, see §1a; v2.2 service implemented, see §14) |
| Source DB | `aigreentick_2nd` (old monolith, MySQL 8) — a **staging copy**, never production |
| Target DB | `apargo_wa_messaging` on the server — already exists with all new tables (every service's tables in this one schema) |
| Targets | Auth, Organization, Plans (MySQL + Mongo), WABA, Storage, Template, Contact, Messaging, Audit (Mongo) |
| Already done (Node, by another developer) | Phase 1 `migrate-to-auth-db.mjs` (users, organizations, projects, memberships) and Phase 2 `migrate-phase2-wallets-charges.mjs` (wallets, ledgers, user charges) |
| Supersedes | the tenant model in `upper_hierarchy_mapping.md` (§1 L2b, §3); step 1 `setup` of `migration_app_README.md`; the Node helper `scripts/migrate-common.mjs` (kept only as reference logic) |

---

## 1. Goal

Move every old row that has a home in the new schemas **exactly once** into the right `organization_id` → `project_id` → `user_id`, using one Spring Boot service (`migration-service`), without overwriting anything that already exists in the new system — including the data Phase 1 and Phase 2 already wrote.

Phase 1 and 2 are **not rewritten**. The Spring Boot service reads their output (`migration_id_map`, `users`, `organizations`, `projects`, `project_members`, wallets) as its starting point and verifies it before doing anything else (step `00-verify-baseline`).

---

## 1a. Server schema findings (dump of 2026-10-05)

Source: `mysqldump --no-data` of the server's `apargo_wa_messaging` (158 tables) and `aigreentick_2nd` (167 tables). Full table-by-table diff: `server_schema_diff.md`. **The server DDL wins over the service docs** wherever they disagree; every step below is written against the server DDL.

**Old DB `aigreentick_2nd`:** identical to the dump the analysis was built on, except `chats.webhook_response` does not exist on the server (the messages step must not read it).

**New DB `apargo_wa_messaging` — what changes the plan:**

| # | Finding | Effect on the plan |
|---|---|---|
| S1 | **The new DB already holds data.** By AUTO_INCREMENT: ~168K `contacts` / `project_contacts` / `contact_list_contacts`, ~488K `messages` and `broadcast_recipients`, ~113K `conversations`, ~1.7M `message_status_events`, 150 `whatsapp_templates`, 257 `media`, 1,225 `projects`, 1,466 `users`, ~749K wallet transactions. (AUTO_INCREMENT also counts rolled-back dry runs, so exact counts are needed — query in §1b.) | **D9 (keep old ids as new ids) is not possible** for contacts, conversations, messages, recipients, templates, media: old ids would collide with existing ids. All steps use new ids + `migration_id_map`. Skip-if-exists must match by natural key, never by id |
| S2 | All service tables live in **one schema** (`apargo_wa_messaging`) | One datasource, one transaction per batch for data + map (section 7.2 preferred layout). Shared names resolved by the server: one `api_idempotency_keys`, separate `idempotency_keys` (messaging) and `idempotency_record` (storage) |
| S3 | ~~Pinnacle tables do not exist~~ **Resolved 2026-10-07: added by the WABA team.** Was: (`pinacle_credentials`, `pinacle_billing_config`, `pinacle_credit_ledger`, `pinacle_credit_line_attachments`); `waba_accounts` has no provider/credential CHECK | Old `waba_accounts` (Pinnacle username, password, api_key, billing) has **no target**. Pinnacle WABAs can only be stored as `onboarding_provider='PINNACLE'` with `bsp_credential_id = NULL`. Ask the WABA team (Q-S3) |
| S4 | `meta_oauth_tokens.access_token` was **TINYTEXT (max 255 bytes)**; `onboarding_tasks` text columns are TINYTEXT too | **Resolved 2026-10-05: `access_token` altered to `TEXT` on the server.** `onboarding_tasks` is not migrated, so its TINYTEXT columns do not block the migration (worth widening for the WABA service itself) |
| S5 | ~~`meta_oauth_tokens` UNIQUE (organization_id)~~ | **Resolved 2026-10-07 by the new WABA schema:** Business Manager under a project, one ACTIVE token per Business Manager. Step 05 creates one placeholder Business Manager per (project, token) |
| S6 | **Teams:** `team_members` references **`project_teams`** and **`project_team_roles`** (Org model, `team_role_id` NOT NULL). Messaging `teams` exists but has no member table. `project_team_roles` is empty | D8 settled: old `agent_teams` → `project_teams`, `agent_team_members` → `team_members`. Team roles (Team Leader, Support Agent, …) must be seeded per project before step 03d |
| S7 | `broadcast_campaign_media` is keyed `(campaign_id, slot_index)` and needs `meta_asset_id` + `meta_media_id` NOT NULL (a Meta upload) | Old campaign media **cannot be migrated** without uploading to Meta. Old campaigns are COMPLETED/CANCELLED, so the step is dropped; the file is still copied to `media` by step 06 |
| S8 | `messages` has **UNIQUE (campaign_id, contact_id)** | At most one message per contact per campaign; a repeated send in one broadcast keeps the first. Non-campaign messages (campaign_id NULL) are unaffected |
| S9 | Contact-service user columns are **VARCHAR(64)** (`created_by`, `updated_by`, `assignee_id`, `inbound_blocked_by`, `added_by`, `assigned_by`, `performed_by`, `changed_by`, `user_id` in favorites/views/shares) | Need the stored format: numeric user id as text, or user **uuid** (Q-S9, query in §1b). The step writes whatever format the contact service writes today |
| S10 | Contact and Messaging tables are Hibernate-generated (`BIT(1)` booleans, `DATETIME(6)`, upper-case ENUM values sorted). `contacts.status_id` is SMALLINT, `contacts.inbound_policy` is VARCHAR(20), `country_code` VARCHAR(2) | Bind booleans as `true/false`, ENUMs exactly as declared (upper case). `created_at`/`updated_at` are NOT NULL without default on several contact tables → always supplied |
| S11 | `projects` has `metadata` and `created_by` columns | Phase 1's `metadata.migrated_from_user_id` did land: step 00 can map customer → project from metadata first, slug second |
| S12 | Plans catalog is in **MySQL** on the server (`plans`, `features`, `plan_features`, `addons`, `addon_features`), plus `organization_subscriptions`, `project_subscriptions`, `organization_addon_subscriptions`, `project_addon_subscriptions`, `org_pricing`, `messaging_margin_ledger` | Step 04e targets these MySQL tables, not Mongo. Plans DDL was never documented → map against the server DDL |
| S13 | `organization_users` has **UNIQUE (user_id)** | Confirms: a user can belong to only one organization (cause of gap F1 / D4) |
| S14 | New tables with an old source: `user_allowed_ips` (← `ip_access_controls`), `chat_bot_flows`, `chat_bot_flow_templates`, `chat_bot_chat_sessions` (← old chatbot tables), `project_members.allowed_ips` | These move from "out of scope" to candidate steps 03i (IP allow-list) and 12 (chatbot) once their column mapping is reviewed |
| S15 | Missing on server: `contact_statistics`, `project_team_members` (replaced by `team_members`) | Contact recount step skips `contact_statistics` |

### 1b. Queries to run on the server before coding the steps

```sql
-- exact row count of every table in the new DB (raises the GROUP_CONCAT limit first)
SET SESSION group_concat_max_len = 1000000;
SELECT GROUP_CONCAT(CONCAT('SELECT ''', table_name, ''' AS t, COUNT(*) AS n FROM `', table_name, '`')
                    SEPARATOR ' UNION ALL ') INTO @q
FROM information_schema.tables
WHERE table_schema = 'apargo_wa_messaging' AND table_type = 'BASE TABLE';
PREPARE s FROM @q; EXECUTE s; DEALLOCATE PREPARE s;

-- did Phase 1/2 run on this server?
SELECT entity, COUNT(*), COUNT(DISTINCT new_id) FROM apargo_wa_messaging.migration_id_map GROUP BY entity;

-- Q-S9: what do contact-service user columns hold (id or uuid)?
SELECT created_by, updated_by FROM apargo_wa_messaging.contacts WHERE created_by IS NOT NULL LIMIT 5;
SELECT assignee_id FROM apargo_wa_messaging.project_contacts WHERE assignee_id IS NOT NULL LIMIT 5;

-- is existing contact / message data real customers or test load?
SELECT organization_id, COUNT(*) FROM apargo_wa_messaging.contacts GROUP BY organization_id;
SELECT organization_id, COUNT(*) FROM apargo_wa_messaging.messages  GROUP BY organization_id;
```

---

## 2. Ground rules (same behaviour as Phase 1/2, now built into the service)

| Rule | How the service does it |
|---|---|
| **Idempotent** | Every old id → new id is stored in `migration_id_map (entity, old_id, new_id)` — the same table Phase 1/2 use. Re-running any step inserts 0 rows. |
| **Skip if it already exists** | Before inserting: (1) look up `migration_id_map`; (2) look up the target table's natural unique key. If found → record the mapping, count it as `matchedExisting`, skip. Existing new-system data is never overwritten. |
| **Dry run** | `--migration.dry-run=true`: each step runs in one transaction that is rolled back at the end. Constraint/ENUM/length errors still surface. For big steps combine with `--migration.tenants=…` (pilot). |
| **Refresh** | `--migration.refresh=true`: updates a fixed, per-step list of mutable columns on existing rows. Never for balances, ledgers or anything users edit after go-live. |
| **Problems, not crashes** | An unplaceable row is written to `mig_errors` (step, source table, old id, severity, reason) and the run continues. A real bug (SQL error not tied to one row) stops the step. |
| **No `INSERT IGNORE`** | IGNORE also hides truncation and bad dates. Skip-if-exists is done with explicit lookups / `NOT EXISTS` anti-joins under strict SQL mode. |
| **Resumable** | Large steps commit per batch and save a high-water mark (`mig_checkpoint`). A crash or a cutover delta re-run continues after the last committed old id. |
| **No events** | Rows are written directly to tables (no service APIs, no outbox), so bulk data does not flood Kafka or trigger sends. Downstream re-sync happens after the load (section 11). |

---

## 2a. Relation rule: ids change, relations do not

New ids are expected — the target tables already hold data and every row gets the next id of its table. What must survive is **every relation**: a contact still belongs to the same customer's project, a message still points to the same contact and conversation, a template button to the same template. That is guaranteed by the **hierarchical order**: a parent is always migrated before its children, and a child never copies an old id — it looks up the parent's new id in `migration_id_map`.

Three rules every step follows:

1. **Parents first.** A step runs only after the steps of all its parents (section 4 order). Step 00 refuses to start a step whose parent entity has no rows in `migration_id_map`.
2. **Every reference column is translated.** No old id is ever written into a new table. Each foreign-key-like column is resolved through the parent's map entity (table below). If the lookup finds nothing, the child row is **not inserted** — it goes to `mig_errors` with the missing parent named (`PARENT_MISSING contact 12345`) — so no orphan or wrong link can be created.
3. **Relations are verified after each step** with orphan checks (queries below). A step is accepted only when they return 0 for migrated rows.

### Parent → child resolution map

| New child column | Old source column | Resolved through |
|---|---|---|
| `*.organization_id`, `*.project_id` | `user_id` / `created_by` of the old row | `TenantResolver` (entities `user`, `org`, `project`) |
| `created_by`, `updated_by`, `assignee_id`, `author_id`, `assigned_to_id`, `added_by`, `invited_by` | old user id | entity `user` (contact-service columns are VARCHAR(64): written in the format the service uses, Q-S9) |
| `project_members.department_id` | `users.department_id` | entity `department` (same project only) |
| `team_members.team_id`, `project_teams.parent_team_id` | `agent_team_members.team_id` | entity `team` |
| `waba_accounts.business_manager_account_id` | (project, token) of the old `whatsapp_accounts` rows | entity `business_manager` |
| `waba_phone_numbers.waba_account_id` | old row's `whatsapp_biz_id` | entity `waba` |
| `waba_accounts.bsp_credential_id` | old `waba_accounts.id` (Pinnacle) | entity `pinnacle_credential` |
| `whatsapp_templates.waba_id` | old template owner's WABA | entity `waba` → `waba_accounts.waba_id` |
| `whatsapp_template_components.template_id` | `template_components.template_id` | entity `template` |
| `whatsapp_template_buttons.component_id` | `template_component_buttons.component_id` | entity `template_component` |
| `whatsapp_template_variables.template_id` | via `template_texts.component_id` → component → template | entities `template_component`, `template` |
| carousel cards / card components / carousel buttons | old card / component ids | entities `template_component`, `carousel_card` |
| `media.organisation_id`, `media.project_id` | owner of the old file URL | `TenantResolver` + `mig_media_map` |
| `project_contacts.contact_id`, `contact_tag_assignments.contact_id`, `contact_list_contacts.contact_id`, `contact_attribute_values.contact_id`, `contact_notes.contact_id` | `chat_contacts.id` (and `contact_id` of tag/group/attribute/note rows) | entity `contact` (duplicates resolve to the master contact) |
| `contact_tag_assignments.tag_id` | `tag_contacts.tag_id` | entity `tag` |
| `contact_list_contacts.list_id` | `group_members.group_id` | entity `list` |
| `contact_attribute_values.attribute_definition_id` | `contact_attributes.attribute` (name) | entity `attribute` |
| `conversations.contact_id`, `.waba_phone_number_id`, `.waba_account_id` | `chats.contact_id`, `chats.send_from`/`send_to` | entities `contact`, `waba_phone`, `waba` |
| `messages.conversation_id`, `.contact_id`, `.waba_phone_number_id`, `.campaign_id`, `.created_by_id` | `chats.*` | `mig_chat` (built from entities `contact`, `waba_phone`), entity `campaign`, entity `user` |
| `message_wamid.message_id`, `.conversation_id` | `chats.message_id` | entity `message` |
| `conversation_assignments.conversation_id`, `.assigned_to_id` | `agent_assignments.contact_id`, `.agent_id` | entity `contact` → conversation lookup; entity `user` |
| `canned_responses.project_id`, `.created_by` | `canned_messages.user_id` | `TenantResolver`, entity `user` |
| `broadcast_campaigns.template_id`, `.waba_account_id`, `.waba_phone_number_id` | `broadcasts.template_id`, `broadcasts.whatsapp` | entities `template`, `waba`, `waba_phone` |
| `broadcast_recipients.campaign_id`, `.contact_id`, `.message_id` | `reports.broadcast_id`, `reports.mobile`, `reports.message_id` | entity `campaign`; contact by (organization, normalized phone); entity `message` / `message_wamid` |
| `messaging_wallet_transactions.wallet_id` | old wallet owner | wallet found by (owner_type, owner_id) — done in Phase 2 |

### Relation checks (run after each step; each must return 0 for migrated rows)

```sql
-- contacts must belong to a project of their own organization
SELECT COUNT(*) FROM project_contacts pc
JOIN contacts c ON c.id = pc.contact_id
JOIN projects p ON p.id = pc.project_id
WHERE c.organization_id <> p.organization_id;

-- every migrated child points to an existing parent (pattern; one query per relation)
SELECT COUNT(*) FROM whatsapp_template_components x
LEFT JOIN whatsapp_templates p ON p.id = x.template_id WHERE p.id IS NULL;

SELECT COUNT(*) FROM messages m
LEFT JOIN conversations cv ON cv.id = m.conversation_id
WHERE cv.id IS NULL OR cv.contact_id <> m.contact_id OR cv.project_id <> m.project_id;

SELECT COUNT(*) FROM broadcast_recipients r
JOIN broadcast_campaigns bc ON bc.id = r.campaign_id
JOIN contacts c ON c.id = r.contact_id
WHERE c.organization_id <> bc.organization_id;

-- an old relation survives: old chat → old contact must equal new message → new contact
SELECT COUNT(*) FROM aigreentick_2nd.chats ch
JOIN migration_id_map mm ON mm.entity = 'message' AND mm.old_id = ch.id
JOIN migration_id_map mc ON mc.entity = 'contact' AND mc.old_id = ch.contact_id
JOIN messages m ON m.id = mm.new_id
WHERE m.contact_id <> mc.new_id;
```

`99-validate` runs the full set (one check per row of the resolution map) and stores the results in `mig_validation`.

---

## 3. Target hierarchy (decided by Phase 1 — do not change)

```
organizations (one per old reseller)                       ← old resellers
 ├── organization_users: reseller admin = owner            ← resellers.admin_user_id
 ├── organization_users: customers + agents (project-admin role id, seed convention)
 └── projects (one per old customer, role 3)               ← users where role_id = 3
      ├── project_members: the customer (admin)
      └── project_members: agents of that customer (agent)  ← users where role_id = 7
```

**A customer is a project, not an organization.** Every old business row keyed by `user_id` (contacts, templates, WABAs, chats, broadcasts, …) belongs to a **project**.

### 3.1 Tenant resolution rule

| Old row's `user_id` is a… | `organization_id` | `project_id` | actor `user_id` |
|---|---|---|---|
| Customer (`role_id = 3`) | org of `users.reseller_id` | the customer's project | the customer |
| Agent (`role_id = 7`) | creator's org | creator's project (creator = `created_by`, else `account_admin_id`) | the agent |
| Reseller admin | reseller's org | **none** (decision D2) | the admin |
| Customer with **no `reseller_id`** | decision **D1** (skip, or org `1`) | D1 | the customer |
| Role 1/2, not a reseller admin | not migrated (D3) | — | — |

Implemented once, in the `TenantResolver` bean (section 7.4). Every step asks it; no step re-derives tenants on its own.

### 3.2 Where the links live

| Link | Stored as | Note |
|---|---|---|
| old user → new user | `migration_id_map` entity `user` | many old ids → one new user (42 email merges) |
| old reseller → org | `migration_id_map` entity `org` | |
| old customer → project | `migration_id_map` entity `project` | **Phase 1 did not write it.** `00-verify-baseline` backfills it from the project slug `…-project-{oldUserId}`, cross-checked against the owner user and org |
| old user → (org, project, new user) | `mig_tenant_map` (view-like table written by the resolver) | lets SQL-based bulk steps join tenants without Java lookups |

---

## 4. Phase status

| Phase | Where | Scope | Status |
|---|---|---|---|
| 1 | Node script | users, organizations, organization_users, projects, project_members | **Done** |
| 2 | Node script | org_messaging_charges (user), messaging_wallets, messaging_wallet_transactions | **Done** |
| 0 | Spring step `00-verify-baseline` | reproduce Phase 1/2 counts, backfill project map, build `mig_tenant_map` | To do (first step of the service) |
| 3 | Spring steps `03*` | Auth / Org remainder | To do |
| 4 | Spring steps `04*` | Plans remainder | To do |
| 5 | Spring step `05-waba` | WABA | To do |
| 6 | Spring step `06-media` | Storage (files + rows) | To do |
| 7 | Spring steps `07*` | Templates | To do |
| 8 | Spring steps `08*` | Contacts | To do |
| 9 | Spring steps `09*` | Messaging | To do |
| 10 | Spring step `10-audit` | Audit (Mongo) | Optional |
| 11 | Spring step `99-validate` | Reconciliation | To do |

Steps always execute in this order, even when only some are selected.

---

## 5. Phase 1 results — user migration (verified)

### 5.1 Numbers reported by the other developer

```text
Step                                               Count
-------------------------------------------------- -----
Users in old DB                                    539
Not selected by script                             -10
(role 1/2 who aren't reseller admins)

Old users processed (migration_id_map)             529
Old users merged into existing new user by email   -42
Distinct new users created                         487
Other users already in new DB (seed)                +6
-------------------------------------------------- -----
Users count in new DB                              493
```

**Conclusion:** the 46-user gap (539 → 493) is fully explained: **10 not selected + 42 merged − 6 seed = 46**. No user is missing.

### 5.2 Queries that reproduce these numbers

`00-verify-baseline` runs these and stops the service if they differ from the expected values in `application.yml` (`migration.baseline.*`), so nothing is built on a changed baseline.

```sql
-- OLD DB
SELECT COUNT(*) FROM users;                                                   -- 539
SELECT COUNT(*) FROM users
 WHERE role_id IN (3, 7)
    OR id IN (SELECT admin_user_id FROM resellers WHERE deleted_at IS NULL);  -- 529
SELECT id, role_id, email, deleted_at FROM users
 WHERE role_id NOT IN (3, 7)
   AND id NOT IN (SELECT admin_user_id FROM resellers WHERE deleted_at IS NULL); -- the 10

-- NEW DB
SELECT COUNT(*)               FROM migration_id_map WHERE entity = 'user';    -- 529
SELECT COUNT(DISTINCT new_id) FROM migration_id_map WHERE entity = 'user';    -- 487
SELECT COUNT(*) FROM users;                                                   -- 493
SELECT COUNT(*) FROM users u
 WHERE NOT EXISTS (SELECT 1 FROM migration_id_map m
                    WHERE m.entity = 'user' AND m.new_id = u.id);             -- 6 (seed)
```

### 5.3 Follow-ups the numbers do not show

They do not change the user count, but they decide whether that user's **data** can be placed. `00-verify-baseline` writes each case to `mig_errors` with severity `INFO` so they can be counted.

| # | Check | Why it matters |
|---|---|---|
| F1 | Which of the 42 merges joined **two customers** (role 3)? | The second customer hit the unique `organization_users.user_id` and got **no project** → its data is unplaceable (D4) |
| F2 | How many of the 529 were **soft-deleted** in the old DB? | Phase 1 did not filter `deleted_at`; deleted customers now have active users and projects (D5) |
| F3 | How many customers have **no `reseller_id`**? | No org and no project were created for them (D1) |
| F4 | How many agents were **not placed** in a project? | Creator was not a role-3 customer, e.g. a reseller admin |
| F5 | Do reseller admins own business data themselves? | They have no project (D2) |

```sql
-- F1 (NEW DB, then old roles): new users that absorbed 2+ old users
SELECT new_id, GROUP_CONCAT(old_id ORDER BY old_id) old_ids, COUNT(*) n
FROM migration_id_map WHERE entity = 'user' GROUP BY new_id HAVING n > 1;

-- F2 (OLD DB)
SELECT COUNT(*) FROM users WHERE deleted_at IS NOT NULL
  AND (role_id IN (3, 7) OR id IN (SELECT admin_user_id FROM resellers WHERE deleted_at IS NULL));

-- F3 (OLD DB)
SELECT COUNT(*) FROM users WHERE role_id = 3 AND reseller_id IS NULL;

-- F4 (OLD DB): agents whose creator is not a customer
SELECT a.id, a.email, COALESCE(a.created_by, a.account_admin_id) creator, c.role_id creator_role
FROM users a LEFT JOIN users c ON c.id = COALESCE(a.created_by, a.account_admin_id)
WHERE a.role_id = 7 AND (c.id IS NULL OR c.role_id <> 3);

-- F5 (OLD DB): business data owned directly by reseller admins
SELECT r.id, r.admin_user_id,
  (SELECT COUNT(*) FROM whatsapp_accounts w WHERE w.user_id = r.admin_user_id AND w.deleted_at IS NULL) wabas,
  (SELECT COUNT(*) FROM templates t        WHERE t.user_id = r.admin_user_id AND t.deleted_at IS NULL) templates,
  (SELECT COUNT(*) FROM chat_contacts c    WHERE c.user_id = r.admin_user_id AND c.deleted_at IS NULL) contacts,
  (SELECT COUNT(*) FROM broadcasts b       WHERE b.user_id = r.admin_user_id) broadcasts
FROM resellers r WHERE r.deleted_at IS NULL;
```

---

## 6. Decisions

All decisions are **configuration**, so a later answer means a config change and a re-run, not a code change.

| # | Decision | Options | Config key / default |
|---|---|---|---|
| **D1** | Customers with **no `reseller_id`** | `SKIP`: user exists, no org/project, all their data logged `NO_RESELLER`. `ASSIGN`: membership + project inside organization `1` | **Decided later.** `migration.tenant.no-reseller-policy=SKIP\|ASSIGN`, `migration.tenant.no-reseller-org-id=1`. Default `SKIP`. Switching to `ASSIGN` later is safe: step `03a` creates the projects, and every later step picks up the rows it skipped on its next run (they were never mapped) |
| **D2** | Reseller admins have no project | `SKIP` their own data, or `OWN_PROJECT` per reseller org | `migration.tenant.reseller-admin-policy=SKIP`. Answer with F5 — if all counts are 0, nothing to do |
| D3 | The 10 role 1/2 users | migrate as `system_users`, or leave out | `migration.auth.migrate-system-users=false` |
| D4 | Merged customers with no project (F1) | second project in the same org, or skip | `migration.tenant.merged-customer-policy=SKIP` |
| D5 | Soft-deleted users migrated as active (F2) | set `users.status='deleted'` + project `archived`, or leave | `migration.auth.fix-deleted-users=true` (only users with no new-system activity) |
| D6 | Opt-in of migrated contacts | `allowed_broadcast=1` and not blocked → `opted_in=TRUE` + `IMPORT` consent row, or `FALSE` | `migration.contacts.opt-in-from-allowed-broadcast` — **legal/product decision**; if false, broadcasts are blocked after cutover |
| D7 | History scope | all chats + ~16M report rows, or last N months | `migration.history.reports-since=2025-10-01`, `migration.history.chats-since=` (empty = all) |
| D8 | Teams source of truth | **Settled by the server schema (S6):** `team_members` references `project_teams` + `project_team_roles` | old teams → `project_teams` + `team_members`; Messaging `teams` not filled |
| D9 | Keep old ids as new ids for big tables | **Not possible on the server (S1):** target tables already hold data | new ids + `migration_id_map` everywhere; `migration.ids.keep-old-ids=false` |
| D10 | Subscription for migrated orgs/projects | one `LEGACY` plan with old expiry | `migration.plans.legacy-plan-slug=legacy` |

---

## 7. Service design

### 7.1 Stack

| Part | Choice | Why |
|---|---|---|
| Runtime | Spring Boot 3.3+, Java 21, `spring.main.web-application-type=none`, `ApplicationRunner` | one-off batch job, exits with code 0/1 |
| SQL access | `NamedParameterJdbcTemplate` + `TransactionTemplate` | full control of bulk SQL, batches and transactions. **No JPA**: the schemas belong to the services (Hibernate/Flyway) and must not be touched |
| Mongo | `spring-boot-starter-data-mongodb`, `MongoTemplate` | Plans catalog (`plans`, `addons`, `features`) and Audit |
| Files | AWS SDK v2 `S3Client` | Storage step (S3 / MinIO) |
| Meta API | Spring `RestClient` | optional: resolve template → WABA, re-sync phone details |
| Crypto | the WABA / Auth services' own encryptor classes (shared jar), or a copy with the same algorithm and key | tokens and API keys must be readable by the services |
| Tests | JUnit 5 + Testcontainers MySQL + Mongo | run every step twice on seeded data; second run must insert 0 |

### 7.2 Database connections

**Preferred: one MySQL server, one `DataSource`, schema-qualified table names.** The staging copy of the old DB and every new schema sit on the same server (as `migration_app_README.md` already requires). Then one transaction covers both the data rows and their `migration_id_map` rows (no half-written state), and bulk steps can use cross-schema `INSERT … SELECT`.

```yaml
spring:
  main.web-application-type: none
  datasource:
    url: jdbc:mysql://db-host:3306/?rewriteBatchedStatements=true&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&sessionVariables=sql_mode='STRICT_ALL_TABLES,NO_ZERO_DATE,NO_ZERO_IN_DATE,ERROR_FOR_DIVISION_BY_ZERO'
    username: ${DB_USER}
    password: ${DB_PASSWORD}
    hikari.maximum-pool-size: 4
  data.mongodb.uri: ${MONGO_URI}

migration:
  steps: ""                       # empty = all, else comma list of step ids
  dry-run: false
  refresh: false
  tenants: ""                     # pilot: comma list of OLD customer user ids
  batch-size: 5000
  schemas:                        # server layout as of 2026-10-05: one old DB, one new DB holding every service's tables
    old: aigreentick_2nd          # old monolith DB
    core: apargo_wa_messaging     # users, organizations, projects, migration_id_map, plans tables (Phase 1/2 target)
    waba: apargo_wa_messaging
    storage: apargo_wa_messaging
    template: apargo_wa_messaging
    contact: apargo_wa_messaging
    messaging: apargo_wa_messaging
    mig: aigreentick_migration    # the service's own tracking schema (section 7.2a), same server
  baseline:                       # step 00 stops if Phase 1/2 output differs
    old-users: 539
    mapped-users: 529
    distinct-new-users: 487
    new-users-total: 493
  tenant:
    no-reseller-policy: SKIP      # D1: SKIP | ASSIGN
    no-reseller-org-id: 1
    reseller-admin-policy: SKIP   # D2
    merged-customer-policy: SKIP  # D4
```

**Fallback if the schemas are on different servers:** one `DataSource` per target (`@Qualifier("contactJdbc")` etc.) plus the `core` one for `migration_id_map`. Each batch then commits the target first and the map second; a crash between the two is repaired on the next run because the natural-key lookup finds the row and writes the missing map entry. Cross-server `INSERT … SELECT` is not possible, so bulk steps read in Java and write with `batchUpdate`.

Old `core` schema name: Auth, Org and WABA docs all name `apargo_wa_messaging`. Confirm the real names before the first run; they are config only.

### 7.2a The service's own tracking database

The migration service owns one schema, **`aigreentick_migration`**, on the **same MySQL server** as `aigreentick_2nd` and `apargo_wa_messaging`.

- **Same server is required:** one InnoDB transaction can span schemas on one server, so a batch of new rows and its `migration_id_map` entries commit together or not at all. On another server they would be two commits, and a crash between them would break the relation rule (section 2a).
- **Why not inside `apargo_wa_messaging`:** keeps the services' schema free of migration tables, lets the tracking data be backed up and inspected on its own, and is removed with one `DROP SCHEMA` after sign-off.
- **Versioned with Flyway, scoped to this schema only:** `spring.flyway.schemas=aigreentick_migration`, `spring.flyway.default-schema=aigreentick_migration`. Flyway must never point at `apargo_wa_messaging` (its tables belong to other services' Hibernate/Flyway).
- **Grants:** migration user — ALL on `aigreentick_migration`; SELECT on `aigreentick_2nd`; SELECT, INSERT, UPDATE on `apargo_wa_messaging` (no DROP / ALTER).
- **Existing `migration_id_map`:** Phase 1/2 wrote it inside `apargo_wa_messaging`. Step 00 copies it once (`INSERT … SELECT … ON DUPLICATE KEY UPDATE`) into `aigreentick_migration.migration_id_map`; from then on the service reads and writes only the copy. The original stays untouched as Phase 1/2 evidence.

`V1__tracking_tables.sql` (in `src/main/resources/db/migration`):

```sql
CREATE TABLE migration_id_map (
  entity      VARCHAR(50)      NOT NULL,
  old_id      BIGINT UNSIGNED  NOT NULL,
  new_id      BIGINT UNSIGNED  NOT NULL,
  step        VARCHAR(60)      NULL,
  created_at  DATETIME(6)      NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (entity, old_id),
  KEY idx_reverse (entity, new_id)                       -- new -> old, for validation and support
) ENGINE=InnoDB;

CREATE TABLE mig_tenant_map (
  old_user_id     BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  kind            VARCHAR(20)     NOT NULL,              -- CUSTOMER | AGENT | RESELLER_ADMIN | NO_RESELLER | OTHER
  organization_id BIGINT UNSIGNED NULL,
  project_id      BIGINT UNSIGNED NULL,
  new_user_id     BIGINT UNSIGNED NULL,
  reason          VARCHAR(255)    NULL,
  resolved_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_project (project_id)
) ENGINE=InnoDB;

CREATE TABLE mig_run (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  started_at   DATETIME(6)  NOT NULL,
  finished_at  DATETIME(6)  NULL,
  dry_run      TINYINT(1)   NOT NULL,
  refresh      TINYINT(1)   NOT NULL,
  steps        VARCHAR(500) NULL,
  tenants      VARCHAR(500) NULL,
  status       VARCHAR(20)  NOT NULL,                    -- RUNNING | SUCCESS | FAILED
  error        TEXT         NULL
) ENGINE=InnoDB;

CREATE TABLE mig_step_stats (
  run_id            BIGINT       NOT NULL,
  step              VARCHAR(60)  NOT NULL,
  entity            VARCHAR(50)  NOT NULL,
  read_rows         BIGINT       NOT NULL DEFAULT 0,
  inserted          BIGINT       NOT NULL DEFAULT 0,
  matched_existing  BIGINT       NOT NULL DEFAULT 0,
  skipped_mapped    BIGINT       NOT NULL DEFAULT 0,
  refreshed         BIGINT       NOT NULL DEFAULT 0,
  errors            BIGINT       NOT NULL DEFAULT 0,
  started_at        DATETIME(6)  NOT NULL,
  finished_at       DATETIME(6)  NULL,
  PRIMARY KEY (run_id, step, entity)
) ENGINE=InnoDB;

CREATE TABLE mig_checkpoint (
  step         VARCHAR(60)     NOT NULL,
  phase        VARCHAR(60)     NOT NULL DEFAULT 'main',
  last_old_id  BIGINT UNSIGNED NOT NULL,
  updated_at   DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (step, phase)
) ENGINE=InnoDB;

CREATE TABLE mig_errors (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id        BIGINT          NULL,
  step          VARCHAR(60)     NOT NULL,
  source_table  VARCHAR(100)    NOT NULL,
  old_id        BIGINT UNSIGNED NULL,
  severity      VARCHAR(10)     NOT NULL,              -- ERROR | WARN | INFO
  code          VARCHAR(50)     NOT NULL,              -- PARENT_MISSING, NO_RESELLER, DUPLICATE, ENUM_UNKNOWN, ...
  reason        VARCHAR(1000)   NOT NULL,
  created_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_step (step, severity, code),
  KEY idx_old (source_table, old_id)
) ENGINE=InnoDB;

CREATE TABLE mig_validation (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id      BIGINT        NOT NULL,
  check_name  VARCHAR(100)  NOT NULL,
  expected    BIGINT        NULL,
  actual      BIGINT        NULL,
  passed      TINYINT(1)    NOT NULL,
  detail      VARCHAR(1000) NULL,
  checked_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;
```

Working tables for the big steps (`mig_media_map`, `mig_contact`, `mig_chat`) are added by later Flyway versions together with the step that uses them. `ProblemLog` writes `mig_errors` in a `REQUIRES_NEW` transaction so problems survive a dry-run rollback.

### 7.3 Project layout

```
migration-service/
  pom.xml
  src/main/java/com/aigreentick/migration/
    MigrationApplication.java
    config/        MigrationProperties, JdbcConfig, MongoConfig, S3Config
    core/          MigrationStep, StepContext, StepRunner, StepStats
                   IdMap, TenantResolver, Tenant, ProblemLog, Checkpoints
                   RowMigrator, BatchReader, Sql (schema-qualified names)
    util/          PhoneNormalizer (libphonenumber), JsonUtil, EnumMapper, Money (paise)
    steps/
      baseline/    VerifyBaselineStep                       00
      auth/        OrphanCustomersStep, ResellerOwnProjectStep, DepartmentsStep,
                   TeamsStep, PermissionOverridesStep, TwoFactorStep, SystemUsersStep   03a–03h
      plans/       MetaPricingStep, ResellerPricingStep, UserPricingStep,
                   VirtualWalletStep, SubscriptionsStep                                  04a–04e
      waba/        WabaStep                                  05
      storage/     MediaStep                                 06
      template/    TemplatesStep, SystemTemplatesStep        07a–07b
      contact/     ContactsPrepareStep, ContactsStep, ContactRelationsStep, ContactRecountStep  08a–08d
      messaging/   MessagingSettingsStep, MsgTeamsStep, ChatsPrepareStep, ConversationsStep,
                   MessagesStep, AssignmentsStep, CannedResponsesStep, CampaignsStep,
                   CampaignMediaStep, RecipientsStep, CampaignCountersStep              09a–09k
      audit/       AuditStep                                 10
      validate/    ValidateStep                              99
  src/main/resources/application.yml
  src/main/resources/sql/   mig_tables.sql, one .sql file per bulk step
  src/test/java/...         per-step Testcontainers tests + seed data
```

If the earlier migration app (`migration_app_README.md`) already exists in the repo, reuse it: its steps 2–21 map to Phases 5–11 here. Replace its `setup` step with `VerifyBaselineStep` + `TenantResolver`, because `setup` assumed **old user ids were kept** and **each owner had its own organization** — Phase 1 did neither. Any SQL in those steps that writes `created_by`, `assigned_id`, `author_id` etc. from an old user id must join `mig_tenant_map` / `migration_id_map` for the new user id.

### 7.4 Core components

**Step contract**

```java
public interface MigrationStep {
    String id();                 // "03c-departments"
    int order();                 // 303 — the runner sorts by this
    void run(StepContext ctx);
}

public record StepContext(
        MigrationProperties props,
        NamedParameterJdbcTemplate jdbc,   // single datasource, schema-qualified SQL
        TransactionTemplate tx,
        IdMap idMap,
        TenantResolver tenants,
        ProblemLog problems,
        Checkpoints checkpoints,
        StepStats stats) {

    public boolean dryRun()  { return props.isDryRun(); }
    public boolean refresh() { return props.isRefresh(); }
    public String t(String schemaKey, String table) {          // "contact", "contacts" -> `aigreentick_contact`.`contacts`
        return "`" + props.getSchemas().get(schemaKey) + "`.`" + table + "`";
    }
}
```

**Runner** — sorts steps, filters by `--migration.steps`, runs each one, prints `StepStats`, and exits 1 on the first failed step.

```java
@Component
@RequiredArgsConstructor
public class StepRunner implements ApplicationRunner {
    private final List<MigrationStep> steps;
    private final StepContextFactory contexts;

    @Override
    public void run(ApplicationArguments args) {
        Set<String> selected = contexts.props().selectedSteps();      // empty = all
        for (MigrationStep step : steps.stream().sorted(comparingInt(MigrationStep::order)).toList()) {
            if (!selected.isEmpty() && !selected.contains(step.id())) continue;
            StepContext ctx = contexts.create(step.id());
            log.info("=== {} {}", step.id(), ctx.dryRun() ? "(DRY RUN)" : "");
            try {
                if (ctx.dryRun()) {
                    ctx.tx().executeWithoutResult(s -> { step.run(ctx); s.setRollbackOnly(); });
                } else {
                    step.run(ctx);          // steps open their own per-batch transactions
                }
                ctx.stats().print(log);
            } catch (RuntimeException e) {
                log.error("Step {} failed", step.id(), e);
                SpringApplication.exit(contexts.appContext(), () -> 1);
                System.exit(1);
            }
        }
    }
}
```

In dry-run mode the whole step is one rolled-back transaction, so batch commits inside the step become part of it (`TransactionTemplate` with `PROPAGATION_REQUIRED` joins the outer one). For the big steps use dry run together with `--migration.tenants=…`.

**`IdMap`** — the same `migration_id_map` table Phase 1/2 use, cached per entity; stale entries (target row deleted, DB re-seeded) are dropped on load, as Phase 2 does.

```java
@Component
@RequiredArgsConstructor
public class IdMap {
    private static final Map<String, String> CHECK = Map.of(
            "user", "users", "org", "organizations", "project", "projects");
    private final NamedParameterJdbcTemplate jdbc;
    private final MigrationProperties props;
    private final Map<String, Map<Long, Long>> cache = new ConcurrentHashMap<>();

    public Long get(String entity, Long oldId) {
        return oldId == null ? null : load(entity).get(oldId);
    }

    public void put(String entity, long oldId, long newId) {
        jdbc.update("""
            INSERT INTO %s.migration_id_map (entity, old_id, new_id) VALUES (:e, :o, :n)
            ON DUPLICATE KEY UPDATE new_id = VALUES(new_id)""".formatted(core()),
            Map.of("e", entity, "o", oldId, "n", newId));
        load(entity).put(oldId, newId);
    }

    public void putAll(String entity, Map<Long, Long> pairs) {   // one batch per call
        SqlParameterSource[] p = pairs.entrySet().stream()
            .map(x -> new MapSqlParameterSource().addValue("e", entity)
                    .addValue("o", x.getKey()).addValue("n", x.getValue()))
            .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
            INSERT INTO %s.migration_id_map (entity, old_id, new_id) VALUES (:e, :o, :n)
            ON DUPLICATE KEY UPDATE new_id = VALUES(new_id)""".formatted(core()), p);
        load(entity).putAll(pairs);
    }

    private Map<Long, Long> load(String entity) {
        return cache.computeIfAbsent(entity, e -> {
            String check = CHECK.get(e);
            String sql = check == null
                ? "SELECT old_id, new_id FROM %s.migration_id_map WHERE entity = :e".formatted(core())
                : "SELECT m.old_id, m.new_id FROM %1$s.migration_id_map m JOIN %1$s.%2$s x ON x.id = m.new_id WHERE m.entity = :e"
                    .formatted(core(), check);
            Map<Long, Long> m = new ConcurrentHashMap<>();
            jdbc.query(sql, Map.of("e", e), rs -> { m.put(rs.getLong(1), rs.getLong(2)); });
            return m;
        });
    }

    private String core() { return "`" + props.getSchemas().get("core") + "`"; }
}
```

**`TenantResolver`** — loads old `users` and `resellers` once, applies section 3.1 and the D1/D2/D4 policies, and returns a `Tenant`.

```java
public record Tenant(boolean ok, Kind kind, Long orgId, Long projectId, Long userId, String reason) {
    public enum Kind { CUSTOMER, AGENT, RESELLER_ADMIN, NO_RESELLER, OTHER, UNKNOWN }
    public boolean hasProject() { return ok && projectId != null; }
}

@Component
@RequiredArgsConstructor
public class TenantResolver {
    private final NamedParameterJdbcTemplate jdbc;
    private final IdMap idMap;
    private final MigrationProperties props;
    private Map<Long, OldUser> users;            // id -> role, reseller_id, created_by, account_admin_id
    private Map<Long, Long> resellerByAdmin;     // admin user id -> reseller id
    private final Map<Long, Tenant> cache = new ConcurrentHashMap<>();

    @PostConstruct
    void load() { /* SELECT id, role_id, reseller_id, created_by, account_admin_id, email FROM old.users;
                     SELECT id, admin_user_id FROM old.resellers WHERE deleted_at IS NULL */ }

    public Tenant resolve(Long oldUserId) {
        if (oldUserId == null) return fail(Kind.UNKNOWN, "user_id is NULL");
        return cache.computeIfAbsent(oldUserId, id -> doResolve(id, 0));
    }

    private Tenant doResolve(long id, int depth) {
        OldUser u = users.get(id);
        Long newUser = idMap.get("user", id);
        if (u == null) return fail(Kind.UNKNOWN, "old user " + id + " does not exist");

        if (resellerByAdmin.containsKey(id)) {
            Long org = idMap.get("org", resellerByAdmin.get(id));
            Long project = idMap.get("reseller_project", resellerByAdmin.get(id));   // only with D2 = OWN_PROJECT
            return org == null ? fail(Kind.RESELLER_ADMIN, "reseller not migrated")
                 : new Tenant(true, Kind.RESELLER_ADMIN, org, project, newUser,
                              project == null ? "reseller admin has no project (D2)" : null);
        }
        if (u.roleId() == props.getCustomerRoleId()) {
            if (u.resellerId() == null) {
                if (props.getTenant().getNoResellerPolicy() == NoResellerPolicy.SKIP)
                    return fail(Kind.NO_RESELLER, "NO_RESELLER (D1 = SKIP)");
                Long project = idMap.get("project", id);           // created by step 03a
                return project == null ? fail(Kind.NO_RESELLER, "NO_RESELLER: run step 03a")
                     : new Tenant(true, Kind.CUSTOMER, props.getTenant().getNoResellerOrgId(), project, newUser, null);
            }
            Long org = idMap.get("org", u.resellerId());
            Long project = idMap.get("project", id);
            if (org == null)     return fail(Kind.CUSTOMER, "reseller " + u.resellerId() + " not migrated");
            if (project == null) return fail(Kind.CUSTOMER, "customer has no project (email merge, D4)");
            return new Tenant(true, Kind.CUSTOMER, org, project, newUser, null);
        }
        if (u.roleId() == props.getAgentRoleId()) {
            Long creator = u.createdBy() != null ? u.createdBy() : u.accountAdminId();
            if (creator == null || depth > 2) return fail(Kind.AGENT, "agent has no creator");
            Tenant c = doResolve(creator, depth + 1);
            return c.ok() && c.kind() == Kind.CUSTOMER
                 ? new Tenant(true, Kind.AGENT, c.orgId(), c.projectId(), newUser, null)
                 : fail(Kind.AGENT, "agent creator " + creator + ": " + c.reason());
        }
        return fail(Kind.OTHER, "old role " + u.roleId() + " is not migrated (D3)");
    }

    private static Tenant fail(Kind k, String why) { return new Tenant(false, k, null, null, null, why); }
}
```

`VerifyBaselineStep` also writes every resolved tenant to `mig_tenant_map (old_user_id, kind, organization_id, project_id, new_user_id, reason)` so SQL-only bulk steps can `JOIN` it instead of calling Java.

**`ProblemLog`** — `mig_errors (id, step, source_table, old_id, severity ERROR|WARN|INFO, reason, created_at)`. Written in its own `REQUIRES_NEW` transaction so problems survive a dry-run rollback. Full rescans clear that step's `ERROR` rows first.

**`Checkpoints`** — `mig_checkpoint (step, phase, last_old_id, updated_at)`, updated in the same transaction as the batch it describes. `--migration.reset-checkpoints=true` re-scans from the start after a data or config fix. Pilot runs (`--migration.tenants`) never read or write checkpoints.

### 7.5 Two ways a step moves data

**Row mode** (`RowMigrator`) — tables up to ~50K rows (departments, teams, tags, templates, canned responses …). Per row: map lookup → natural-key lookup → insert → map. Java equivalent of the Node `migrateRows`:

```java
public <R> void migrate(StepContext ctx, String entity, List<R> rows,
                        Function<R, Long> oldId,
                        Function<R, Optional<Map<String, Object>>> build,   // empty = unplaceable, problem already logged
                        Function<Map<String, Object>, Optional<Long>> findExisting,
                        Function<Map<String, Object>, Long> insert,
                        List<String> refreshable) {
    StepStats.Entity s = ctx.stats().entity(entity);
    for (R row : rows) {
        Long mapped = ctx.idMap().get(entity, oldId.apply(row));
        if (mapped != null && !ctx.refresh()) { s.skippedMapped++; continue; }

        Optional<Map<String, Object>> rec = build.apply(row);
        if (rec.isEmpty()) { s.unplaced++; continue; }

        Optional<Long> existing = mapped != null ? Optional.of(mapped) : findExisting.apply(rec.get());
        if (existing.isPresent()) {
            if (mapped == null) { ctx.idMap().put(entity, oldId.apply(row), existing.get()); s.matchedExisting++; }
            else s.skippedMapped++;
            if (ctx.refresh() && !refreshable.isEmpty()) { /* UPDATE only refreshable columns */ s.refreshed++; }
            continue;
        }
        long newId = insert.apply(rec.get());
        ctx.idMap().put(entity, oldId.apply(row), newId);
        s.inserted++;
    }
}
```

**Bulk mode** (`BatchReader` + SQL files) — contacts (~5.9M), chats (~964K), reports (~10.3M), ledgers. Per batch, in one transaction:

1. Read old rows `WHERE id > :checkpoint ORDER BY id LIMIT :batchSize` (plus the history-scope filter, D7).
2. Resolve tenants from the in-memory resolver (or `JOIN mig_tenant_map` in SQL). Unplaceable → `mig_errors`.
3. Insert with an anti-join so existing rows are skipped:
   `INSERT INTO target (...) SELECT ... FROM old.x JOIN mig.mig_tenant_map ... WHERE x.id BETWEEN :from AND :to AND NOT EXISTS (SELECT 1 FROM target t WHERE <natural key>)`.
4. Read back the new ids by natural key and `IdMap.putAll` them. (Keeping old ids as new ids, D9, is not possible on the server because the target tables already hold data — S1.)
5. Save the checkpoint (`last_old_id`) and commit.

After the bulk load of a table with kept ids, set its `AUTO_INCREMENT` above the max migrated id.

### 7.6 Running it

```bash
mvn -q package                                    # → target/migration-service.jar

export DB_USER=migration DB_PASSWORD=... MONGO_URI=...
export WABA_ENCRYPTION_KEY=... PINNACLE_PARTNER_ID=...
export S3_BUCKET=... S3_ENDPOINT=... S3_ACCESS_KEY=... S3_SECRET_KEY=...

# 1. baseline check only
java -jar target/migration-service.jar --migration.steps=00-verify-baseline

# 2. dry run of one step
java -jar target/migration-service.jar --migration.steps=03c-departments --migration.dry-run=true

# 3. pilot: 1-2 customers (old user ids), all steps, then check them in the new UI
java -jar target/migration-service.jar --migration.tenants=12,45

# 4. full run (resumable: re-run the same command after a crash)
java -jar target/migration-service.jar

# 5. later decision, e.g. D1 → ASSIGN: re-run from 03a; later steps pick up skipped rows
java -jar target/migration-service.jar --migration.tenant.no-reseller-policy=ASSIGN --migration.reset-checkpoints=true
```

Exit code 1 = a step failed; the log shows the SQL and the error. After every run:
`SELECT step, source_table, severity, reason, COUNT(*) FROM mig_errors GROUP BY 1,2,3,4;`

---

## 8. Step catalogue

Each step lists the natural key used for **skip if exists**, the `migration_id_map` entity, and the main problems it logs.

### 00 — Baseline (`VerifyBaselineStep`)

Checks section 5.2 counts against `migration.baseline.*`; backfills entity `project` from project slugs (cross-checked owner + org); fills `mig_tenant_map`; logs F1–F5 as `INFO`. Stops the run if the baseline differs.

### 03 — Auth / Organization remainder

| Step | Old → New | Natural key | Map entity | Rules / problems |
|---|---|---|---|---|
| 03a `orphan-customers` (D1) | role-3 users with `reseller_id IS NULL` → `organization_users` + `projects` + `project_members` in org `no-reseller-org-id` | (organization_id, user_id); (organization_id, slug) | `project` | Only when D1 = `ASSIGN`. Slug `{emailLocal}-project-{oldId}` (same as Phase 1); `metadata.migration_reason = NO_RESELLER` |
| 03b `reseller-own-project` (D2) | → `projects` | (organization_id, slug `{slug}-own-{resellerId}`) | `reseller_project` | Only when D2 = `OWN_PROJECT` |
| 03c `departments` | `departments` → `project_departments`; `users.department_id` → `project_members.department_id` | (project_id, name) | `department` | Deleted → `archived`; member's department set only when NULL; cross-project link refused (port of `migrate-03-departments.mjs`) |
| 03d `teams` (D8) | `agent_teams`, `agent_team_members` → `project_teams` + `team_members` (server table, FK to `project_teams` and `project_team_roles`) | (project_id, name) and (project_id, slug) / (team_id, user_id) | `team` | Seed `project_team_roles` per project first (empty on server); slug from name; admin → Team Leader, agent → Support Agent |
| 03e `permission-overrides` | `user_permissions` → `project_member_permissions` | (project_member_id, permission_id) | — | Needs old `master_permissions.name` → new `permissions.key_name` table (config file); unmatched → WARN |
| 03f `two-factor` | `user_2fa_settings`, `backup_codes` → `user_2fa`, `user_2fa_backup_codes` | user_id | — | Only if secrets can be re-encrypted; else skipped, users re-enrol |
| 03g `system-users` (D3) | the 10 role 1/2 users → `users` + `system_users` | email | `user` | Only when D3 = true |
| 03h `deleted-users` (D5) | — | — | — | Status update only, only for users with no activity in the new system |

### 04 — Plans remainder

| Step | Old → New | Natural key | Rules |
|---|---|---|---|
| 04a `meta-pricing` | `platform_meta_pricing` → `meta_messaging_charges` | country_code | `market→marketing`, `auth→authentication` |
| 04b `reseller-pricing` | `reseller_messages_pricing` → `org_default_messaging_charges` | (organization_id, country_code) | `*_set`, fallback `*_min`; `*_min` and margins → WARN (no column) |
| 04c `user-pricing` | `user_messages_pricing` → `org_messaging_charges` (`target_type='user'`) | (target_type, user_id, country_code) | **Phase 2 already wrote rows from `users.*_msg_charge` with the same key → skipped.** Use `--migration.refresh=true` only if `user_messages_pricing` must override |
| 04d `virtual-wallet` | `reseller_wallet_transactions` with `amount_type='virtual'` → org wallet `virtual_balance` + `category='virtual'` rows | checkpoint per reseller | Phase 2 skipped these rows on purpose; never touch `balance` |
| 04e `subscriptions` (D10) | `resellers.subscription_expires_at`, `users.demo_end`, `licences` → `organization_subscriptions` / `project_subscriptions` (server tables, S12) | (owner, plan) | Plans catalog is in MySQL on the server (`plans`, `features`, …); map against the server DDL |

### 05 — WABA (`WabaStep`), schema of 2026-10-07

| Old → New | Natural key | Map entity | Rules / problems |
|---|---|---|---|
| (customer project) → `project_refs` | project_id | — | created on first WABA of the project; a different organization → row ERROR |
| `whatsapp_accounts` grouped by (project, token) → `business_managers` | — (placeholder) | `business_manager` (lowest old row id) | old DB has no Business Manager id: `meta_business_id = "legacy-<uuid>"`, name `Legacy <company>`, provider PINNACLE if a Pinnacle row is in it. Never shared between projects |
| `whatsapp_accounts.parmenent_token` → `meta_oauth_tokens` | one ACTIVE per Business Manager | `meta_token` | copied as it is, `SYSTEM_USER`, `ACTIVE` |
| `whatsapp_accounts` grouped by `whatsapp_biz_id` (= WABA id) → `waba_accounts` | live `waba_id` | `waba` | one project per WABA: lowest old row wins, others `WABA_OTHER_PROJECT`; exists in another project → ERROR; first WABA of a project `is_project_default` |
| each `whatsapp_accounts` row → `waba_phone_numbers` | live `phone_number_id` | `waba_phone` | status `1/2/other` → ACTIVE/BLOCKED/DISABLED; `is_official_business_account = false` |
| old `waba_accounts` (api_key) → `pinacle_credentials` + `pinacle_billing_config` | — | `pinnacle_credential` | credentials as they are; `partner_id` from config, else old username; billing type codes from config (`2` = POSTPAID) |

Not migrated: `flow_private_key`, `flow_public_key`, `ads_access_token`, `whatsapp_onboarding_sessions`, `*_deleted_backup`.

### 06 — Storage (`MediaStep`)

| Old → New | Natural key | Rules |
|---|---|---|
| (derived) → `org_storage`, `project_storage` | org_id / (org_id, project_id) | quota from plan; `used_bytes` = sum of migrated files; org row before project row (FK) |
| `media_uploads`, `broadcast_media`, `campaign_media`, template `image_url`s, `users.profile_photo` → S3 object + `media` | `mig_media_map (project_id, sha256(url))` | one file per distinct URL per project; key `org-{o}/proj-{p}/{type}/{uuid}.{ext}`; size, MIME and SHA-256 from the download; unreachable → ERROR. Parallel downloads with a bounded executor; scope per D7 |

### 07 — Templates

| Old → New | Natural key | Map entity | Rules / problems |
|---|---|---|---|
| `templates` → `whatsapp_templates` | live (waba_id, name, language), else `meta_template_id` | `template` | `waba_id` from the project's WABA (several → match `wa_id` via Meta API, else oldest); status/category → ENUM (unknown → `UNKNOWN`, raw in `meta_status_raw`); invalid JSON → `{}` + WARN; older live duplicate inserted soft-deleted |
| `template_components` → `whatsapp_template_components` | (template_id, component_type, component_order) | `template_component` | HEADER 0, BODY 1, FOOTER 2, BUTTONS 3 |
| `template_component_buttons` → `whatsapp_template_buttons` | (component_id, button_index) | `template_button` | index by old id order; FLOW → WARN (no ENUM value) |
| `template_texts` → `whatsapp_template_variables` (+ examples) | (template_id, component_type, variable_index, button_index, card_index) | — | `default_value` → `label_value` |
| carousel cards + buttons → card / card_component / carousel_button | (component_id, card_index) / (card_component_id, button_index) | `carousel_card` | a BUTTONS card_component parents the buttons |
| `template_library` → `system_templates` (07b) | (name, language) | `system_template` | payload → `{template, variables}` |

### 08 — Contacts (detail: `contact_mapping.md`)

| Step | Old → New | Natural key | Map entity | Rules |
|---|---|---|---|---|
| 08a `contacts-prepare` | `chat_contacts` → `mig_contact` (E.164, master per org) | — | — | libphonenumber; duplicates per org → lowest id is master |
| 08b `contacts` (bulk) | → `contacts` (+ `contact_merge_history`) | (organization_id, normalized_phone) live | `contact` | org from `mig_tenant_map`; duplicates mapped to master |
| 08c `contact-relations` | → `project_contacts`, `contact_consent_history`, tags, lists, attributes, notes, blacklist | (project_id, contact_id), (org, project, name), … | `tag`, `list`, `attribute`, `contact_note` | **customer's project**; opt-in per D6 |
| 08d `contact-recount` | → counters, `contact_statistics` | — | — | recompute |

### 09 — Messaging (schema created by Hibernate: boot the service once, then set `ddl-auto=validate`)

| Step | Old → New | Natural key | Map entity | Rules |
|---|---|---|---|---|
| 09a `messaging-settings` | `live_chat_settings` → `project_messaging_settings` | project_id | — | working hours → one quiet range where possible |
| ~~09b `msg-teams`~~ | — | — | — | Dropped: teams are migrated in 03d (S6) |
| 09c `chats-prepare` | `chats` → `mig_chat` (direction, number, master contact) | — | — | number from `send_from`/`send_to` |
| 09d `conversations` | → `conversations` + 1 `conversation_sessions` each | (project_id, waba_phone_number_id, contact_id) | — | unmatched number → ERROR |
| 09e `messages` (bulk) | `chats` → `messages`, `message_wamid` | wamid; for campaign messages also (campaign_id, contact_id) UNIQUE (S8) | `message` | never QUEUED/PROCESSING; text > 4096 cut, full in `payload`; duplicate wamid → WARN; `chats.webhook_response` does not exist on the server |
| 09f `assignments` | `agent_assignments` → `conversation_assignments` | (conversation_id, assigned_to_id, assigned_at) | — | self/admin → MANUAL, bot → AUTO, reassigned → TRANSFER |
| 09g `canned-responses` | `canned_messages` → `canned_responses` | (project_id, shortcut) live | `canned` | `message_sets` flattened to text |
| 09h `campaigns` | `broadcasts` → `broadcast_campaigns` | — | `campaign` | template snapshot; never SCHEDULED/READY/RUNNING; future → CANCELLED |
| ~~09i `campaign-media`~~ | — | — | — | Dropped: server table needs a Meta upload (`meta_asset_id`, `meta_media_id` NOT NULL, S7); old campaigns are finished. Files still copied to `media` in step 06 |
| 09j `recipients` (bulk) | `reports` → `broadcast_recipients` | (campaign_id, contact_id) | — | must match a contact (or create, D7); never PENDING/QUEUED |
| 09k `campaign-counters` | `reports` → `broadcast_campaign_counters` (shard 0) | (campaign_id, shard) | — | recomputed |

### 10 — Audit (optional, `MongoTemplate`)

`activity_logs`, `twofa_audit_logs`, `permission_logs`, `reseller_pricing_logs` → `access_logs` / `audit_logs`; UUIDv7 `eventId`; `orgId` from the resolver; `archiveAt` set deliberately so history does not archive on the first archiver run.

### 99 — Validate (`ValidateStep`)

Per step and per organization: old count − problems = new count. Plus wallet balances vs `users.balance`, templates per WABA vs Meta, recipients per campaign vs `broadcasts.total`. Results to `mig_validation`. Manual end-to-end check of 3–5 pilot projects in the new UI.

### `migration_id_map` entity registry

| Entity | old_id | new_id | Written by |
|---|---|---|---|
| `user` | old users.id | users.id | Phase 1 |
| `org` | old resellers.id | organizations.id | Phase 1 |
| `project` | old customer users.id | projects.id | step 00 backfill / 03a |
| `wtx_user_hw`, `wtx_org_hw` | new user id / old reseller id | last old txn id | Phase 2 |
| `reseller_project` | old resellers.id | projects.id | 03b |
| `department`, `team` | old ids | new ids | 03c, 03d |
| `waba`, `waba_phone`, `meta_token`, `pinacle_cred` | old whatsapp_accounts.id / waba_accounts.id | new ids | 05 |
| `template`, `template_component`, `template_button`, `carousel_card`, `system_template` | old ids | new ids | 07 |
| `contact`, `tag`, `list`, `attribute`, `contact_note` | old ids | new ids (merged contacts → master) | 08 |
| `message`, `campaign`, `canned`, `msg_team` | old ids | new ids | 09 |

Media is keyed by URL hash → `mig_media_map`. All tracking tables, including the service's copy of `migration_id_map`, live in `aigreentick_migration` (section 7.2a); the Phase 1/2 original in `apargo_wa_messaging` is only read once by step 00.

---

## 9. Testing before any real run

1. **Testcontainers**: load every real DDL (Auth, Org, Plans MySQL, WABA, Storage via Flyway, Template, Contact, Messaging via one Hibernate boot), the Phase 1/2 output, and a seed covering the hard cases: duplicate emails, customer without reseller, agent of a reseller admin, duplicate phones, shared WABA, several tokens per reseller, duplicate live templates, FLOW buttons, carousel, future broadcast, missing media file.
2. Each step test runs the step **twice** and asserts the second run inserts 0 rows.
3. A dry-run test asserts the target row counts are unchanged afterwards.
4. A policy test runs with D1 = `SKIP`, then `ASSIGN`, and asserts the skipped customer's data appears after the second run.

---

## 10. Run procedure per environment

1. Refresh the staging copy of the old DB.
2. `00-verify-baseline` must pass.
3. Dry run each step; fix every ERROR or accept it in writing.
4. Pilot (`--migration.tenants`) → check in the new UI.
5. Full run.
6. Second full run must insert 0.
7. `99-validate`; fill in section 12.

**Cutover:** stop writes on the old app → refresh staging → full run again (only old ids after each checkpoint are processed) → validate → switch traffic. Rows **edited** in the old app after the bulk run are not re-applied unless the affected steps re-run with `--migration.refresh=true` and `--migration.reset-checkpoints=true`; keep the freeze short.

---

## 11. Risks and guards

| Risk | Guard |
|---|---|
| Strict-mode ENUM / length errors (template status, button text 150, body 4096, phone 20) | `EnumMapper` with `UNKNOWN` fallback; cut + WARN |
| TEXT → JSON columns (`templates.payload`, `chats.payload`, `broadcasts.requests`) | Jackson parse in Java / `JSON_VALID()` in SQL; invalid → `{}` + raw in metadata + WARN |
| Unique keys old data does not respect | natural-key lookup or `NOT EXISTS` before insert |
| Timezones | JDBC `connectionTimeZone=UTC`; old `TIMESTAMP` is UTC; confirm old `DATETIME` (Sequelize) is UTC with discovery query 0.3 |
| Encrypted columns | service's own encryptor, never plain SQL |
| Destructive DDL files (`DROP SCHEMA` at the top of contact / template / WABA) | never re-run after loading |
| Messaging Hibernate `ddl-auto=update` | switch to `validate` before loading |
| Rows written without outbox events | after load: rebuild search index and segment caches, seed Redis unread counters, re-sync WABA details from Meta, re-create pending scheduled broadcasts |
| Memory on 10M-row steps | keyset pagination, `batch-size` 5000, never `SELECT *` of a whole table into a list |

---

## 12. Results log (fill in after each run)

| Phase / step | Old rows | Inserted | Matched existing | Problems | Second run inserted | Date | By |
|---|---|---|---|---|---|---|---|
| 1 users | 539 (529 selected) | 487 new users | 42 merged by email | 10 not selected | 0 | | other developer (Node) |
| 1 orgs / projects | | | | | | | other developer (Node) |
| 2 wallets / charges | | | | | | | other developer (Node) |
| 00 baseline | | | | | | | |
| 03 | | | | | | | |
| 04 | | | | | | | |
| 05 | | | | | | | |
| 06 | | | | | | | |
| 07 | | | | | | | |
| 08 | | | | | | | |
| 09 | | | | | | | |

---

## 13. Out of scope (no target service yet)

Kept read-only in the old DB until their service exists. Keep `migration_id_map` (users, projects, contacts, templates) permanently so these can be migrated later.

Chatbot / flows (`chat_bot_*`, `chatbots`, `flows`, `whatsapp_flows`, `wapp_forms`), support tickets (`support_*`, `ticket_*`), commerce (`shops`, `themes`, `whatsapp_shops`, `whatsapp_orders`, catalogues, `discounts`, `delivery_persons`), integrations (`shopify_*`, `woo_*`, `razorpay_*`, `calendly_user_settings`, `busy_*`, `user_credentials`), ads (`meta_ad_*`, `meta_capi_events`), links (`qrcodes`, `shortlink`), automation (`welcome_messages`, `off_hours_messages`, `opt_rules`, `optout_keywords`, `tag_keywords`, `agent_settings`, `agent_unresponsive_logs`), access (`api_keys`, `ip_access_controls`), reseller days system (`reseller_days_transactions`, `user_days_transactions`), `service_message_usage`.

Never migrated: `cache*`, `jobs`, `failed_jobs`, `migrations`, `sessions`, `personal_access_tokens`, `oauth_states`, `user_2fa_temp`, all `*_backup*` tables, `contacts_messages*`, `chat_contacts_29_09_25_2137`.


---

## 14. Implementation status — `migration-service` 1.0 (2026-10-05)

The Spring Boot service is built (`migration-service/`, Java 21, Maven, Spring Boot 3.5, JDBC only). Its README has the run procedure.

| Plan item | Status |
|---|---|
| Bootstrap (every run): import Phase 1/2 map, backfill `project` map (metadata, then slug, cross-checked), rewrite `mig_tenant_map` | done |
| 00 baseline + F1–F5 | done (`baseline.enforce=true`; `new-users-total` is report-only) |
| 03a, 03b, 03c, 03d, 03h | done (03d seeds `team-leader` / `support-agent` project roles per project) |
| 03e, 03f, 03g | **not implemented** (need permission name mapping / 2FA re-encryption / D3 = true) |
| 04a, 04b, 04c | done (04c matches Phase 2 rows; refresh overrides) |
| 04d virtual wallet, 04e subscriptions | **not implemented** |
| 05 WABA | done for the WABA schema of 2026-10-07 (placeholder Business Manager per project + token, every WABA keeps its token, Pinnacle credentials + billing config migrated); **access token copied as it is (no encryption, decided 2026-10-05)** |
| 06 media | **dropped (decided 2026-10-05)**: files are not re-uploaded; the old file server stays up, so template / carousel `media_url` and `messages.media_url` keep the old URLs. No `media` rows |
| 07a, 07b | done |
| 08a–08d | done (contacts per organization + E.164 with `+`; duplicates map to the master; soft-deleted contacts not migrated; seeded source id 18 and status ids 1 Active / 4 Invalid, decided 2026-10-05) |
| 09a, 09c–09h, 09j, 09k | done (09b, 09i dropped as planned) |
| 10 audit | **not implemented** |
| 99 validate | done: 11 tenant checks + 6 relation checks + reconciliation counts |

Changes against the design in §7:

- Config keys `migration.schemas` are `old`, `target`, `mig` (all service tables are in one target schema on the server).
- JDBC URL default database = the tracking schema (`createDatabaseIfNotExist=true`); strict `sql_mode` is set by Hikari `connection-init-sql`. Default server `146.88.24.113`, user `root`.
- Dry run = bootstrap + all selected steps in **one** transaction, rolled back at the end (not one per step).
- Every old row runs in a savepoint (`Tx.row`): an SQL error rolls back that row only and is logged as `SQL_ERROR`.
- `mig_errors` is capped per (step, code) at `migration.problems.max-rows-per-code` rows plus one `SUMMARY_*` row.
- Recipients: the first report row of a number wins; existing recipients are found by lookup (no `ON DUPLICATE KEY`).

Test result on a local MySQL 8.0.46 with the server DDL, after the real Phase 1/2 scripts:

- dry run left the target unchanged
- the full run passed all 17 checks
- the second run inserted 0 rows
- a pilot run followed by a full run gave the same result as one full run
- switching D1/D2/D5 after a full run added only the newly placeable data
