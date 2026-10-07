# migration-service

One-off Spring Boot batch job (Java 21, Maven) that moves the old monolith DB **`aigreentick_2nd`** into the new
schema **`apargo_wa_messaging`** (every new service's tables live there), following `docs/MIGRATION_PLAN.md`.

It continues where the other developer's Node scripts stopped:

- Phase 1 (`migrate-to-auth-db.mjs`): users, organizations, organization_users, projects, project_members
- Phase 2 (`migrate-phase2-wallets-charges.mjs`): user charges, wallets, wallet ledgers

Those phases are **not redone**. The service reads their output (`migration_id_map`, projects, memberships) and builds
everything else on the same hierarchy:

```
organization (one per old reseller)
 └── project (one per old customer, role 3)        <- every business row of the customer and of its agents
```

**IDs change, relations do not.** Every row gets a new id. Parents are migrated before children, and every reference
column is translated through `migration_id_map`. If a parent cannot be found, the child is not inserted, and a row
goes to `mig_errors`. Step `99-validate` then checks every relation.

---

## 1. Requirements

| | |
|---|---|
| Java | 21 |
| Maven | 3.9+ |
| MySQL | 8.0. `aigreentick_2nd` (staging copy) and `apargo_wa_messaging` must be **on the same server**: data rows and their `migration_id_map` rows commit in one transaction |
| Tracking schema | `aigreentick_migration`, created and versioned by Flyway (`src/main/resources/db/migration`). Flyway never touches the other two schemas |

Default connection: server **146.88.24.113:3306**, user **root** (password from `DB_PASSWORD`). If you later use a
dedicated user instead of root, these grants are enough (`scripts/grants.sql`):

```sql
GRANT ALL ON aigreentick_migration.* TO 'migration'@'%';
GRANT SELECT ON aigreentick_2nd.* TO 'migration'@'%';
GRANT SELECT, INSERT, UPDATE ON apargo_wa_messaging.* TO 'migration'@'%';   -- no DROP / ALTER / DELETE
```

## 2. Build

```bash
mvn -q package            # -> target/migration-service.jar  (unit tests need no database)
```

## 3. Configure

Everything is in `src/main/resources/application.yml`. Any value can be overridden with `--migration.x=y`
on the command line or with an environment variable.

| Env var | Meaning |
|---|---|
| `DB_HOST`, `DB_PORT`, `DB_USER`, `DB_PASSWORD` | MySQL server holding all three schemas. Defaults: `146.88.24.113`, `3306`, `root`; the password has no default |
| `OLD_SCHEMA` / `TARGET_SCHEMA` / `MIG_SCHEMA` | default `aigreentick_2nd` / `apargo_wa_messaging` / `aigreentick_migration` |

### Decisions (MIGRATION_PLAN.md section 6): each one is a setting

| # | Property | Default | Note |
|---|---|---|---|
| D1 | `migration.tenant.no-reseller-policy` (`SKIP`/`ASSIGN`) + `no-reseller-org-id` | `SKIP`, `1` | Customers without `reseller_id`. Switching to `ASSIGN` later is safe: re-run with `--migration.reset-checkpoints=true` and their data is picked up |
| D2 | `migration.tenant.reseller-admin-policy` (`SKIP`/`OWN_PROJECT`) | `SKIP` | Data owned directly by reseller admins (F5 in the step 00 report) |
| D4 | `migration.tenant.merged-customer-policy` | `SKIP` | Second customer merged by e-mail into another user's account, which leaves it without a project |
| D5 | `migration.auth.fix-deleted-users` | `false` | Soft-deleted old users -> `users.status='deleted'`, project archived |
| D6 | `migration.contacts.opt-in-from-allowed-broadcast` | `true` | **Legal/product sign-off needed.** `false` blocks broadcasts to migrated contacts |
| D7 | `migration.history.chats-since` / `reports-since` / `broadcasts-since` | all / `2025-10-01` / all | How much history to migrate |
| D8 | (settled) | | old teams -> `project_teams` + `team_members` |
| D9 | (settled) | | new ids everywhere (the target already holds data) |

## 4. Run

Always on a **staging copy** first.

```bash
JAR=target/migration-service.jar
export DB_PASSWORD=...          # host 146.88.24.113 and user root are the defaults

# 1. baseline: Phase 1/2 numbers + follow-ups F1-F5 (stops when the numbers differ from migration.baseline.*)
java -jar $JAR --migration.steps=00-baseline

# 2. dry run of everything (one transaction, rolled back; mig_errors / mig_run rows are kept)
java -jar $JAR --migration.dry-run=true

# 3. pilot: 1-2 customers (OLD user ids), then check them in the new UI
java -jar $JAR --migration.tenants=12,45

# 4. full run (resumable: after a crash run the same command again)
java -jar $JAR

# 5. second full run must insert 0 rows (every "inserted" column in the log = 0)
java -jar $JAR

# one step / a few steps
java -jar $JAR --migration.steps=07a-templates,07b-system-templates
```

Exit code `0` = success, `1` = a step failed (the log shows the SQL and the error), or with
`--migration.validate.fail-on-error=true` a relation check failed.

Other switches: `--migration.refresh=true` updates each step's refreshable columns on rows that already exist.
`--migration.reset-checkpoints=true` re-scans the big steps from the first old id.
`--migration.skip-steps=09j-recipients` leaves steps out.
`--migration.baseline.enforce=false` reports a baseline difference instead of stopping.

## 5. Steps

Steps always run in this order, even when only some are selected.

| Step | Old -> new | Skip-if-exists (natural key) | Map entity |
|---|---|---|---|
| bootstrap (every run) | Phase 1/2 `migration_id_map` -> tracking copy; customer -> project map (metadata, then slug, cross-checked); `mig_tenant_map` | | `project` |
| `00-baseline` | checks section 5.2 numbers; F1-F5 as INFO | | |
| `03a-orphan-customers` | D1=ASSIGN: customers without reseller -> project in org `no-reseller-org-id` (+ their agents) | (org, slug) | `project` |
| `03b-reseller-own-project` | D2=OWN_PROJECT: one project per reseller org (+ agents of the admin) | (org, slug) | `reseller_project` |
| `03c-departments` | departments -> project_departments; users.department_id -> project_members | (project, name) | `department` |
| `03d-teams` | agent_teams / members -> project_teams / team_members (+ seeds 2 project_team_roles per project) | (project, name) / (team, user) | `team` |
| `03h-deleted-users` | D5 | | |
| `04a-meta-pricing` | platform_meta_pricing -> meta_messaging_charges | country_code | `meta_charge` |
| `04b-reseller-pricing` | reseller_messages_pricing -> org_default_messaging_charges | (org, country) | `reseller_charge` |
| `04c-user-pricing` | user_messages_pricing -> org_messaging_charges (user) — Phase 2 rows are matched | (user, user_id, country) | `user_charge` |
| `05-waba` | whatsapp_accounts -> project_refs, business_managers, meta_oauth_tokens, waba_accounts, waba_phone_numbers; old waba_accounts -> pinacle_credentials + pinacle_billing_config | live waba_id / live phone_number_id / ACTIVE token per Business Manager | `business_manager`, `meta_token`, `waba`, `waba_phone`, `pinnacle_credential` |
| `07a-templates` | templates (+ components, buttons, variables, examples, carousel) -> whatsapp_templates… | meta id in org / live (waba, name, language) | `template`, `template_component`, `template_button`, `carousel_card` |
| `07b-system-templates` | template_library (global) -> system_templates | (name, language) | `system_template` |
| `08a-contacts-prepare` | chat_contacts -> mig_contact (tenant, E.164, consent flags) | | |
| `08b-contacts` | mig_contact -> contacts (one per organization + phone; duplicates -> master) | live (org, dedupe_phone) | `contact` |
| `08c-contact-relations` | project_contacts + consent, tags, tag links, lists, members, attributes, notes | (project, contact), (org, project, name), … | `tag`, `list`, `attribute`, `contact_note` |
| `08d-contact-recount` | counters | | |
| `09a-messaging-settings` | live_chat_settings -> project_messaging_settings | project_id | |
| `09c-chats-prepare` | chats -> mig_chat (direction, our number, contact) | | |
| `09d-conversations` | conversations + 1 session each | (project, phone, contact) | `mig_conversation` |
| `09e-messages` | chats -> messages + message_wamid | wamid | `message` |
| `09f-assignments` | agent_assignments -> conversation_assignments | (conversation, agent, assigned_at) | `assignment` |
| `09g-canned-responses` | canned_messages -> canned_responses | live (project, shortcut) + same text | `canned` |
| `09h-campaigns` | broadcasts -> broadcast_campaigns | | `campaign` |
| `09j-recipients` | reports -> broadcast_recipients | (campaign, contact) | |
| `09k-campaign-counters` | reports -> broadcast_campaign_counters + campaign counts | (campaign, shard 0) | |
| `99-validate` | 17 relation / tenant checks + reconciliation -> mig_validation | | |

Not implemented in 1.0 (no target, or blocked on an answer; see the plan): 03e permission overrides, 03f 2FA,
03g system users, 04d virtual wallet rows, 04e subscriptions, 10 audit (Mongo), campaign media (S7: needs a Meta upload),
chatbot / IP allow-list (S14). Step 06 (copying files into storage) was removed on purpose: old media URLs are kept.

## 6. Tracking schema (`aigreentick_migration`)

| Table | Content |
|---|---|
| `migration_id_map` | old id -> new id per entity (copy of Phase 1/2's table + everything this service writes) |
| `mig_tenant_map` | old user -> kind, organization, project, new user (rewritten every run) |
| `mig_run`, `mig_step_stats` | one row per run; read / inserted / matched / mapped / refreshed / errors per step and entity |
| `mig_errors` | every row that was skipped (ERROR), changed (WARN) or worth knowing (INFO), with code and reason |
| `mig_validation` | baseline numbers and the relation checks of step 99 |
| `mig_checkpoint` | resume points of the big steps |
| `mig_contact`, `mig_chat`, `mig_conversation` | working tables of steps 08 and 09 |

Useful queries: `scripts/useful_queries.sql`. After sign-off the whole schema can be dropped. Keep `migration_id_map`
if services that come later (chatbot, support, …) still need to be migrated.

## 7. Behaviour worth knowing

- **Skip if it already exists.** Before inserting, every step checks the map, then the target's natural key. Rows created
  in the new system are matched and **never overwritten** (only `--migration.refresh=true` updates a short list of columns).
- **No `INSERT IGNORE`.** The session runs in strict mode, so truncation, bad dates and bad ENUM values fail and are
  reported. Long values are cut on purpose and reported as `TRUNCATED` WARN rows.
- **One failing row does not stop a step.** Each old row runs in a savepoint. An SQL error rolls back only that row and is
  written to `mig_errors` (`SQL_ERROR`). An error that is not tied to a row stops the run (exit 1).
- **Seeded rows used by id:** every migrated contact / project_contact gets `source_id = 18` (seeded "Migration" source)
  and `status_id = 1` (Active), or `4` (Invalid) when the number is not a valid phone number
  (`migration.contacts.source-id`, `active-status-id`, `invalid-status-id`). Step 08b stops if one of them is missing.
- **Contacts are per organization.** One contact per (organization, E.164 phone). The same number of two customers of one
  reseller is one contact with two `project_contacts`. Duplicates in the old DB are mapped to the master contact, so their
  tags, chats and recipients follow it. Soft-deleted old contacts are not migrated.
- **Tenant guard.** A tag/list link is created only when the contact belongs to the same project as the tag/list.
- **WABA schema of 2026-10-07** (Organization -> Project -> Business Manager -> token + WABAs -> phone numbers):
  - The old DB has no Business Manager id (`whatsapp_biz_id` is the **WABA id**). Step 05 creates one placeholder Business
    Manager per (customer project, token) with `meta_business_id = "legacy-<uuid>"` and name `Legacy <company>`.
    Every WABA keeps its own customer's token. A Business Manager is never shared between projects.
  - One ACTIVE token per Business Manager, `token_type = SYSTEM_USER`.
  - A WABA belongs to one project. When two customers used the same WABA, the lowest old row keeps it and the other
    gets `WABA_OTHER_PROJECT`. Each project's first WABA becomes `is_project_default`.
  - Pinnacle: old `waba_accounts` rows with an api_key -> `pinacle_credentials` (credentials as they are,
    `partner_id` = `migration.waba.pinnacle-partner-id`, else the old username) + `pinacle_billing_config`
    (`pinnacle-postpaid-billing-types`, default `2` = POSTPAID). The WABA gets `onboarding_provider = PINNACLE`.
  - Lookups use the plain columns (`waba_id`, `phone_number_id`, token `status`, `is_project_default`) + `deleted_at`,
    not the generated `live_*` columns (the server's tables do not have them). Step 05 first checks that every column it
    writes exists and stops with the list of missing columns if not (nothing written).
  - The server's WABA tables were created by Hibernate (dump of 2026-10-07): no `pinacle_*` tables, `access_token` TINYTEXT,
    BIT(1) flags, no unique keys. Step 05 handles this: without the Pinnacle tables, Pinnacle WABAs are stored as `PINNACLE`
    without credentials (WARN), and a later run fills them once the tables exist. A token longer than `access_token`
    can hold (255 bytes on the server) is never cut: its Business Manager gets no token (ERROR `TOKEN_TOO_LONG`), the run
    goes on, and a later run stores it once the column is widened.
  - Map rows written by runs made on the retired WABA schema are removed automatically at start-up (entities `waba`,
    `waba_phone`, `meta_token`), so step 05 places everything again.
- **Messages:** never QUEUED / PROCESSING; old raw data kept in `payload.legacy`; texts over 4096 kept in full there.
- **Access tokens are copied as they are** into `meta_oauth_tokens.access_token` (no encryption).
- **Media is not re-uploaded.** The old file server keeps serving, so the old URLs are used as they are: template
  header and carousel images go to `media_url`. Chat media links found in the old payload (`image.link`, `url`, …) go to
  `messages.media_url` with `media_status = READY`. Old URLs and the Meta media ids stay in the payload / `provider_media_id`.
  No `media` / storage rows are created.

## 8. Confirm before the production run

1. Pinnacle `partner_id` (the old DB has none; default = old username) and the meaning of old `billing_type` codes.
2. **D6** opt-in (legal) and **D7** history dates.
3. Open seed questions in `docs/seed_data_and_assumptions.md` (team roles, organization 1, Meta pricing overwrite).
4. Enum value maps for old chat / report statuses: `migration.messaging.*`. Check them against the real value sets
   (discovery queries E1 in `migration_discovery_queries.sql`) and add any missing values in `application.yml`.

## 9. How it was tested

On a local MySQL 8.0.46 (the server's version), loaded with the **server's own DDL dumps** of both schemas, plus a seed
covering the hard cases. The seed included: duplicate e-mails across resellers, a customer without a reseller, agents of a
reseller admin and of an orphan customer, departments with the same name, a shared WABA, a WABA used by two
projects, several tokens per reseller, a Pinnacle account, duplicate live templates, FLOW buttons, a carousel, invalid JSON, duplicate
phones in 6 formats, a blacklist entry, a contact that already exists in the new DB, a duplicate wamid, unknown statuses,
a future broadcast, recipients without a contact, and chat media with an old URL.

The **real Phase 1 and Phase 2 Node scripts** were run first, so the service started from the same state as the server.
Results:

- dry run: target unchanged, map unchanged
- full run: all 17 relation checks pass
- second run: 0 rows inserted
- pilot run followed by a full run gives exactly the same result as one full run
- D1/D2/D5 switched after a full run: only the newly placeable data is added
- refresh run: 0 rows inserted
- unit tests: `mvn test`

The build machine here could not reach Maven Central. The code was therefore compiled and run against the same Spring
JDBC/transaction API (`NamedParameterJdbcTemplate`, `TransactionTemplate`) with small stand-ins for the four Spring Boot
classes it uses (`@ConfigurationProperties`, `ApplicationRunner`, `SpringApplication`, `@SpringBootApplication`).
The first `mvn package` on your machine is the first build with Spring Boot 3.5 itself. If a dependency version does not
resolve in your repository, change the version properties at the top of `pom.xml`.

## Where problems are logged

| Where | What |
|---|---|
| `logs/migration.log` (next to the jar) | full log of every run, also on the console |
| `aigreentick_migration.mig_errors` | every skipped (ERROR) or changed (WARN) row: run, step, old table, old id, code, reason |
| `mig_step_stats` | read / inserted / matched / skipped / errors per run, step and entity |
| `mig_validation` | results of `99-validate` |
| `mig_run` | one row per run: start, end, status, error |
| `mig_checkpoint` | how far each big step got |
| `migration_id_map` | old id → new id per entity |

Queries: `scripts/useful_queries.sql`. Risks of the server run: `docs/server_run_risks.md`, checks before it:
`scripts/server_preflight.sql`.
