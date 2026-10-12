# Migration: cases that need a manual decision

Status: 2026-10-07, after migration run 1 on 146.88.24.113 (log up to step 07a) and the new WABA schema (Business Manager
under a project). Cases 1 and 10 are resolved by that schema.
Each case lists the problem, the impact (numbers from run 1 where known), the options, the setting or change that
applies the decision, and how to check. **Default** = what the service does today if nobody decides.

| # | Case | Impact (run 1) | Blocks go-live? | Owner | Default |
|---|---|---|---|---|---|
| 1 | ~~One token per organization~~ | **Resolved** by the new WABA schema | — | — | every WABA keeps its own token |
| 2 | Templates of projects without a WABA | ≥ 3 templates (project 1210), total unknown | Partly | product | skipped |
| 3 | Customer without reseller (D1) | 1 customer | No | product | skipped |
| 4 | Customers merged by e-mail (D4) | 19 new users with 2+ old customers | Check | product | second customer's data skipped |
| 5 | Soft-deleted users (D5) | 161 users | No | product | stay active |
| 6 | Opt-in of migrated contacts (D6) | all contacts | **Yes (legal)** | legal / product | opted in |
| 7 | How much history (D7) | chats / reports volume | Yes (run time) | product | all chats, reports since 2025-10-01 |
| 8 | Team roles | 2 teams | No | org service team | 2 roles created per project |
| 9 | Meta pricing overwrite | 1 country | No | finance / product | server prices kept |
| 10 | ~~Pinnacle accounts~~ | **Resolved**: credentials + billing config migrated | Check `partner_id` | WABA team | `partner_id` = old username, billing code `2` = POSTPAID |
| 11 | WABA shared by two projects | per `WABA_OTHER_PROJECT` rows | Check | product | lowest old row keeps it, the other project gets nothing |
| 12 | Blocked / blacklisted numbers | per contact | No | product | per-project opt-out |
| 13 | Old status values not mapped | unknown until checked | No | dev | fallback + WARN |
| 14 | Parts not migrated at all | permissions, 2FA, admins, subscriptions, … | Check | product | not migrated |

---

## 0. Server WABA tables differ from the WABA SQL (2026-10-07). Decision: go with the server schema

The server dump of 2026-10-07 shows the WABA tables were created by Hibernate from the entities, not from the WABA SQL:

| On the server | Effect | Fix |
|---|---|---|
| `pinacle_credentials`, `pinacle_billing_config`, `pinacle_credit_ledger`, `pinacle_credit_line_attachments` missing | Pinnacle WABAs stored as `PINNACLE` without credentials (WARN `PINNACLE_CREDENTIALS_NOT_MIGRATED`) | create the tables, re-run step 05: credentials are added to the existing WABAs |
| `meta_oauth_tokens.access_token` is **TINYTEXT (255 bytes)** | tokens longer than 255 bytes are not stored (ERROR `TOKEN_TOO_LONG`, never cut); that Business Manager cannot send | `ALTER TABLE meta_oauth_tokens MODIFY access_token LONGTEXT NOT NULL;` |
| no generated `live_*` / `active_*` / `default_project_id` columns, no unique keys, no foreign keys, no CHECK | the database does not reject duplicate WABAs / phone numbers / ACTIVE tokens / defaults | apply the WABA SQL (or the `ALTER`s for the five columns), set Hibernate `ddl-auto` to `validate` |
| `onboarding_tasks`, `waba_event_ledger` text columns TINYTEXT | not used by the migration | WABA team |

The migration runs on the server schema as it is (it does not use the generated columns and checks duplicates itself).
Every fix above is optional and can come later: after it, re-run step 05 and the missing tokens / Pinnacle credentials are
added to the rows already migrated.

```sql
-- how many old tokens do not fit 255 bytes
SELECT COUNT(DISTINCT parmenent_token) tokens_too_long, MAX(LENGTH(parmenent_token)) longest
FROM aigreentick_2nd.whatsapp_accounts WHERE deleted_at IS NULL AND LENGTH(parmenent_token) > 255;
```

## 1. One token per organization: resolved (2026-10-07)

The WABA team changed the schema: Organization -> Project -> Business Manager -> one ACTIVE token + WABAs. Step 05 now
creates one placeholder Business Manager per (customer project, token), so every customer keeps its own token.

| Field | Value |
|---|---|
| `business_managers.meta_business_id` | `legacy-<random uuid>` (the old DB has no Business Manager id; `whatsapp_biz_id` is the WABA id) |
| `business_managers.name` | `Legacy <company or name of the customer>` |
| token | copied as it is, `SYSTEM_USER`, `ACTIVE` |

Runs made before the change are cleaned automatically: at start-up the service removes the old map rows of entities
`waba`, `waba_phone`, `meta_token`, so step 05 places every WhatsApp account again.

Later: when a customer reconnects through embedded signup, the real Business Manager id replaces the placeholder.

## 2. Templates of projects without a WABA

**Problem.** `whatsapp_templates.waba_id` is NOT NULL. A template whose project has no WABA in the new DB cannot be
placed (`NO_WABA`). Likely causes: the customer's old `whatsapp_accounts` row is soft-deleted (deleted rows are skipped),
or the customer never had one.

**Impact.** Run 1: project 1210 and possibly more. Size it:

```sql
SELECT reason, COUNT(*) FROM aigreentick_migration.mig_errors WHERE run_id = 1 AND code = 'NO_WABA' GROUP BY reason ORDER BY 2 DESC;
SELECT w.id, w.user_id, w.whatsapp_biz_id, w.status, w.deleted_at FROM aigreentick_2nd.whatsapp_accounts w
WHERE w.user_id IN (SELECT old_user_id FROM aigreentick_migration.mig_tenant_map WHERE project_id = 1210);
```

| Option | What to do |
|---|---|
| A. Skip these templates | nothing (default) |
| B. Bring soft-deleted WhatsApp accounts too | `--migration.waba.include-deleted=true`, then re-run `05-waba,07a-templates` |

Decision: ______________________

## 3. Customer without reseller (D1)

**Problem.** 1 customer has no `reseller_id`. Phase 1 created no organization or project for it, so all its data is
skipped (`NO_RESELLER`).

**Note.** The fallback organization id `1` is **one of the migrated reseller organizations** on the server, not a neutral
platform organization.

| Option | What to do |
|---|---|
| A. Skip (default) | nothing |
| B. Put it into an organization | `--migration.tenant.no-reseller-policy=ASSIGN --migration.tenant.no-reseller-org-id=<id> --migration.reset-checkpoints=true` |

Decision: ______________________ (if B, organization id: ______)

## 4. Customers merged by e-mail (D4)

**Problem.** 19 new users absorbed 2 or more old customers with the same e-mail. When those customers were under
**different** resellers, the second one could not join a second organization (`organization_users.user_id` is unique),
so it has no project and its data is skipped (`CUSTOMER_NO_PROJECT`). Run 1 shows 1 customer without a project.

```sql
SELECT new_id, GROUP_CONCAT(old_id) old_ids FROM aigreentick_migration.migration_id_map
WHERE entity = 'user' GROUP BY new_id HAVING COUNT(*) > 1;
SELECT * FROM aigreentick_migration.mig_errors WHERE run_id = 1 AND code = 'CUSTOMER_NO_PROJECT';
```

| Option | What to do |
|---|---|
| A. Skip (default) | nothing |
| B. Give the second customer a project in the first customer's organization | service change needed |
| C. Split into two users (different login) | manual: change one user's e-mail in the old DB, then re-run Phase 1 |

Decision: ______________________

## 5. Soft-deleted users (D5)

**Problem.** Phase 1 did not filter `deleted_at`. 161 migrated users are deleted in the old DB but active in the new one.

| Option | What to do |
|---|---|
| A. Leave active (default) | nothing |
| B. Mark them deleted and archive their projects | `--migration.auth.fix-deleted-users=true --migration.steps=03h-deleted-users` |

Decision: ______________________

## 6. Opt-in of migrated contacts (D6, legal)

**Problem.** The old DB has only `allowed_broadcast` (default 1). The new system sends broadcasts only to opted-in contacts.

| Option | What to do |
|---|---|
| A. `allowed_broadcast = 1` and not blocked becomes opted in, plus an IMPORT consent row (default) | nothing |
| B. Nobody opted in (broadcasts blocked until customers re-collect consent) | `--migration.contacts.opt-in-from-allowed-broadcast=false` **before** step 08 runs |

Decision (legal sign-off): ______________________

## 7. How much history (D7)

**Problem.** Old volumes: ~964K chats, ~10.3M report rows. Everything takes long and adds a lot of data to the new DB.

| Setting | Default | Meaning |
|---|---|---|
| `migration.history.chats-since` | empty = all | chats → conversations / messages |
| `migration.history.reports-since` | `2025-10-01` | reports → broadcast recipients |
| `migration.history.broadcasts-since` | empty = all | broadcasts → campaigns |

Campaign counters always use all reports of a migrated campaign.

Decision: chats since ________ reports since ________ broadcasts since ________

## 8. Team roles

**Problem.** `team_members.team_role_id` is NOT NULL, and the master seed creates no `project_team_roles`. The service creates
two roles in each project that has teams: `team-leader` ("Team Leader", old role admin) and `support-agent`
("Support Agent", old role agent, default).

Decision: keep these / use slugs ______________________ (`migration.teams.*`)

## 9. Meta pricing overwrite

**Problem.** The server already has prices in `meta_messaging_charges` (run 1: the old `IN` row matched an existing one).
The service never overwrites existing countries.

| Option | What to do |
|---|---|
| A. Keep server prices (default) | nothing |
| B. Old platform prices win | `--migration.refresh=true --migration.steps=04a-meta-pricing` |

Decision: ______________________

## 10. Pinnacle accounts: two values to confirm (tables missing on the server, see case 0)

When the Pinnacle tables exist: Old `waba_accounts` rows with an api_key become `pinacle_credentials` (api key, password,
username copied as they are) + `pinacle_billing_config`, and the WABA gets `onboarding_provider = PINNACLE` with
`bsp_credential_id`. Each one is logged as INFO `PINNACLE_MIGRATED`.

| Value | Not in the old DB | Default | Setting |
|---|---|---|---|
| `partner_id` (NOT NULL) | yes | old username, else `unknown` | `migration.waba.pinnacle-partner-id` |
| billing type | old codes are numbers | `2` = POSTPAID, everything else PREPAID | `migration.waba.pinnacle-postpaid-billing-types` |

Decision: partner id ______ POSTPAID codes ______

## 11. WABA shared by two projects

**Problem.** A WABA belongs to one project (and one Business Manager). When two customers used the same
`whatsapp_biz_id`, the lowest old row keeps it; the other customer's rows get `WABA_OTHER_PROJECT` (no WABA, its templates
hit `NO_WABA`, its chats are not placed). A WABA that already exists in another project on the server gets the same error.

```sql
SELECT * FROM aigreentick_migration.mig_errors WHERE code = 'WABA_OTHER_PROJECT' ORDER BY run_id DESC;
```

Decision: which project owns each shared WABA: ______________________

## 12. Blocked and blacklisted numbers

**Problem.** In the old DB, blocking and the blacklist were per customer. In the new DB the contact status is per
organization, which is shared by all customers of a reseller.

| Option | What to do |
|---|---|
| A. Opt-out only in that customer's project, contact stays Active (default) | nothing |
| B. Contact status BLOCKED / DND for the whole organization | service change needed |

Decision: ______________________

## 13. Old status values that are not mapped yet

**Problem.** The real value sets of `chats.type`, `chats.method`, `chats.status`, `reports.status` and template statuses
were never checked. Unknown values fall back (`UNSUPPORTED`, `SENT`/`READ`, `FAILED`) and are logged as `ENUM_UNKNOWN`.

Check with `scripts/server_seed_checks.sql` (last block). Missing values are added to `migration.messaging.*` in
`application.yml`.

Decision: dev, after the query output is reviewed.

## 14. Parts that are not migrated at all

| Old data | Why not | Decision needed |
|---|---|---|
| Permission overrides (`user_permissions`) | needs an old → new permission name mapping | map / drop |
| 2FA settings | secrets must be re-encrypted for the auth service | users re-enrol / migrate |
| 10 admin users (roles 1/2, D3) | not selected by Phase 1; new `system_users` with `system-admin` / `system-manager` / `system-support` exist | migrate as system users / recreate by hand |
| Virtual wallet rows (`amount_type='virtual'`) | skipped by Phase 2 | migrate / drop |
| Subscriptions / expiry (`resellers.subscription_expires_at`, `users.demo_end`) | plans tables never mapped (D10) | legacy plan / set by hand |
| Campaign media (`broadcast_media`) | new table needs a Meta upload | drop (old campaigns are finished) |
| Audit logs, chatbot flows, IP allow-list, support tickets, shops, integrations | no target or not mapped | later / drop |

Decision per row: ______________________

---

### How a decision is applied

Most decisions are a setting. Add it to the command (or to `application.yml`) and re-run the affected steps. Already-migrated
rows are never duplicated. Changes to tenants or to contacts need `--migration.reset-checkpoints=true` so the big steps
re-scan. Cases marked "service change needed" (4B, 12B) need a code change first.
