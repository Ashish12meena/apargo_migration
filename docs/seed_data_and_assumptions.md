# Seed data and assumptions of migration-service

Status: 2026-10-05 (answers of 2026-10-05 19:54 included). Open items can be checked with `scripts/server_seed_checks.sql` (run on 146.88.24.113, send the output).
"Default" = what the service does today if nobody answers.

## A. Reference / seed rows the service needs in `apargo_wa_messaging`

| # | Table | What the service does | Default if not answered | Question |
|---|---|---|---|---|
| S-1 | `roles` | Looks up `owner`/organization, `admin`/project, `agent`/project with `organization_id` and `project_id` NULL | — | **Resolved** by the master seed (`20260101000002-master-seed-…cjs`): exactly these slugs exist (plus `admin`/organization, `member`/project, `system-admin|manager|support`/system) |
| S-2 | `contact_statuses` | Every migrated contact gets id **1 (Active)**; numbers libphonenumber rejects get id **4 (Invalid)**. Step 08b stops if an id is missing | — | **Resolved** (config `migration.contacts.active-status-id=1`, `invalid-status-id=4`). Still open: blocked / blacklisted numbers stay a per-project opt-out, not `BLOCKED`/`DND` |
| S-3 | `contact_sources` | Every migrated contact and project_contact gets `source_id = 18` (the seeded "Migration" source, uuid `89db02b3-b0fa-11f1-a67a-a687fa8690d2`). No sources are created | — | **Resolved** (config `migration.contacts.source-id=18`) |
| S-4 | `project_team_roles` | **Creates** 2 project-level roles (team_id NULL) in each project that has teams: `team-leader` (old role admin) and `support-agent` (old role agent, `is_default=1`) | creates them | Does the org service seed team roles per project? Which slugs/names? (config `migration.teams.*`) |
| S-5 | `organizations` id 1 | D1 = ASSIGN puts customers without reseller in organization `1` | not used while D1 = SKIP | Open. The master seed creates the root org `aigreentick` first, so on the server it is most likely id 1 — confirm before switching D1 |
| S-6 | `meta_messaging_charges` | Inserts old platform prices only for countries that do not exist yet; never overwrites | existing server prices win | Server already has ~141 countries. Keep them, or should the old `platform_meta_pricing` win (`--migration.refresh=true` on step 04a)? |
| S-7 | `meta_oauth_tokens` | Writes the old token as it is, `token_type='SYSTEM_USER'`, one ACTIVE per Business Manager | — | **Resolved**: token type confirmed; one-token-per-organization replaced by the WABA schema of 2026-10-07. Was: one token per organization (S5) still open |
| S-8 | contact-service user columns (`created_by`, `assignee_id`, `added_by` …, VARCHAR 64) | Writes the new user id as text | `ID` | **Skipped by decision**: user id as text stays |
| S-9 | `contacts.normalized_phone` | `+919876543210` (with `+`) | — | **Resolved**: the contact service stores it with `+` |
| S-10 | ENUM conventions of new services | Inbound messages `created_by_type='SYSTEM'`; campaigns `audience_type='CSV_UPLOAD'`, `COMPLETED`/`CANCELLED`; sessions `AGENT_OUTREACH`/`INBOUND`; `contacts.inbound_policy='ALLOWED'` | as described | Any service-specific convention that differs? |
| S-11 | `business_managers.meta_business_id` | NOT NULL and the old DB has no Business Manager id: `legacy-<random uuid>` per placeholder Business Manager | — | **Decided 2026-10-07** (random when NULL is not allowed). Replace with the real id when the customer reconnects |
| S-12 | `pinacle_credentials.partner_id` | NOT NULL, not in the old DB: `migration.waba.pinnacle-partner-id`, else the old username, else `unknown` | old username | Confirm the Pinnacle partner id |
| S-13 | `pinacle_billing_config.billing_type` | old `billing_type` codes in `migration.waba.pinnacle-postpaid-billing-types` → POSTPAID, others PREPAID | `2` = POSTPAID | Confirm the old codes |

Also seen in the master seed: `system_users` + roles `system-admin`, `system-manager`, `system-support`. The 10 old role 1/2
users (D3) could therefore become `system_users` later (step 03g, not built).

Not needed any more: `media`, `org_storage`, `project_storage` (media keeps old URLs), token encryption key.
Not seeded or written by the service: `tag_categories`, `attribute_categories` (category_id NULL), plans / subscriptions (04e not built).

## B. Old-data meanings that were assumed

| # | Assumption | Where | Check |
|---|---|---|---|
| A-1 | `chat_contacts.country_id` is a dial code (`'91'`); missing → `91` | 08a | phone-format query |
| A-2 | `blacklists.country_id` is a dial code too (not a `countries.id`) | 08a | distinct values query |
| A-3 | A row in `blacklists` (not deleted) = opted out in that customer's project, whatever `is_blocked` says | 08a | — |
| A-4 | `groups.status = '1'` is active, `'0'`/`'2'` inactive | 08c | value query |
| A-5 | `chats.type`: `send` = outbound, `receive` = inbound; others decided by matching our numbers | 09c | value query; extend `migration.messaging.chat-direction` |
| A-6 | `chats.method` and `chats.status`, `reports.status` value sets mapped in `application.yml`; unknown → WARN + fallback | 09e, 09j, 09k | value queries; extend the maps |
| A-7 | `pending` / `queued` old messages → `FAILED`, old recipients → `CANCELLED` (never re-sent) | 09e, 09j | — |
| A-8 | Old DATETIME / TIMESTAMP values are UTC | all | time-zone query |
| A-9 | `broadcasts.whatsapp` = old `whatsapp_accounts.id` | 09h | — |
| A-10 | `live_chat_settings.working_hours` JSON shape: per-day `{enabled, start, end}` or list `{day, isOpen, from, to}` | 09a | sample a few rows |
| A-11 | `agent_assignments.status='active'` = current owner; `self`/`admin` → MANUAL, `bot` → AUTO, `reassigned` → TRANSFER | 09f | — |
