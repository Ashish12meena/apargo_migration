# Migration run 1: report

| | |
|---|---|
| Run | `mig_run.id = 1`, real run (no `[DRY RUN]`), all steps |
| Started | 06:31:51 (log time) from a developer PC (`D:\WhatsappProject\...`) against `146.88.24.113` |
| Log covered | start → step 07a (templates), still running when the log was taken |
| Overall | **Working.** The Phase 1 baseline matches exactly. Three issues need action (sections 3–5) |

---

## 1. Baseline (step 00): OK

| Check | Expected (other developer's report) | Actual |
|---|---|---|
| Old users | 539 | 539 |
| Selected by Phase 1 | 529 | 529 |
| Mapped users | 529 | 529 |
| Distinct new users | 487 | 487 |
| Users in new DB | 493 | 493 |
| Users not from the migration (seed) | 6 | 6 |
| Organizations mapped | — | 4 |
| Customer projects mapped (backfilled from slug / metadata) | — | 408 (0 mismatched) |

Follow-ups reported as INFO (no action needed now, they are known decisions):

| # | Finding | Count | Decision |
|---|---|---|---|
| F1 | New users that absorbed 2+ old customers (e-mail merge) | 19 | D4 |
| F2 | Migrated users that are soft-deleted in the old DB | 161 | D5 (off) |
| F3 | Customers without `reseller_id` | 1 | D1 = SKIP |
| F4 | Agents not placed in a project | 0 | — |
| F5 | Reseller admins owning business data | 0 | D2 not needed |
| — | Customers without a project | 1 | (the F3 customer) |

Existing contacts in the new DB use `normalized_phone` **with `+`**, which matches the setting.

## 2. Step results so far

| Step | Entity | Read | Inserted | Matched existing | Errors | Time |
|---|---|---|---|---|---|---|
| bootstrap | legacy `migration_id_map` rows imported | 732 | | | | |
| 03a / 03b / 03h | D1, D2, D5 off | — | — | — | — | |
| 03c departments | department | 145 | 144 | 1 (same name in one project) | 0 | 15 s |
| | member department | 3 | 3 | 0 | 0 | |
| 03d teams | team / team member | 2 / 3 | 2 / 3 | 0 | 0 | 1 s |
| 04a meta pricing | meta_charge | 1 | 0 | 1 (country already on server) | 0 | |
| 04b reseller pricing | reseller_charge | 4 | 4 | 0 | 0 | |
| 04c user pricing | user_charge | 81 | 1 | 80 (Phase 2 rows) | 0 | 7 s |
| 05 WABA | whatsapp_accounts read | 200 | | | | 61 s |
| | meta_token | 2 | 1 | 1 | 0 | |
| | waba | 197 | 196 | 1 | 0 | |
| | waba_phone | 200 | 198 | 2 | 0 | |
| | project_waba_assignment | 200 | 199 | 1 | 0 | |
| 07a templates | (running) | | | | NO_WABA errors | |

Problems logged in step 05: `TOKEN_CONFLICT` 118 (ERROR), `PINNACLE_CREDENTIALS_NOT_MIGRATED` 77 (WARN, expected).

---

## 3. Issue: speed (action: run on the server)

The service runs on a PC and every query goes over the internet to the DB server. Per-row steps are therefore very slow:
145 departments took 15 s, 200 WhatsApp accounts took 61 s (≈ 0.1–0.3 s per row).

The messages step writes about 5 queries per chat for ~964K chats, so at this speed it would take **several days**.

**Action:** stop the current run (safe at any point, nothing is half-written). Copy the jar to the DB server and run it there:

```bash
export DB_HOST=localhost DB_PASSWORD='...'
nohup java -jar migration-service.jar > migration-$(date +%F-%H%M).log 2>&1 &
```

The new run skips everything already migrated and continues with what is left.

## 4. Issue: 118 WhatsApp accounts without a token (action: decision)

> **Update 2026-10-07: resolved.** The WABA team changed the schema (Business Manager under a project, one token per
> Business Manager). The service was updated: every customer keeps its own token. The options below are history.

`meta_oauth_tokens` allows **one token per organization** (UNIQUE `organization_id`). All customers of a reseller are in that
reseller's single organization, and each had its own token. The newest token was kept. **118 of 196 WABAs** got
`meta_oauth_token_id = NULL` and cannot send in the new system until this is decided.

| Option | What it means | Change needed |
|---|---|---|
| **A (recommended)** | The WABA team allows one token per WABA (drop the UNIQUE on `organization_id`, or key tokens by WABA) | Schema change by the WABA team. Step 05 is then updated to store every customer's own token |
| B | Link the organization's single token to all its WABAs (`--migration.waba.link-token-on-conflict=true`) | Works **only** if that one token can access every customer's WABA (all onboarded under the reseller's own Meta Business account). Otherwise sending fails |

Both can be applied after this run. Today step 05 does not fill tokens on WABAs it already created, so a small change is
needed once the option is chosen. The old tokens stay available in `aigreentick_2nd.whatsapp_accounts`.

Note: organization **id 1 is one of the migrated reseller organizations**. This matters for D1 (`no-reseller-org-id=1`),
which would put customers without a reseller into that reseller's organization.

## 5. Issue: templates with `NO_WABA` (action: check)

Templates of project 1210 (and possibly others) were skipped because the project has **no WhatsApp account** in the new DB.
`whatsapp_templates.waba_id` is NOT NULL, so a template must belong to an account. The likely reasons are that the
customer's old `whatsapp_accounts` row is soft-deleted (deleted rows are skipped by default), or that the customer never
had one.

Queries to size and explain it:

```sql
-- templates skipped, per project
SELECT reason, COUNT(*) FROM aigreentick_migration.mig_errors
WHERE run_id = 1 AND code = 'NO_WABA' GROUP BY reason ORDER BY 2 DESC;

-- the old WhatsApp accounts of project 1210's owner
SELECT w.id, w.user_id, w.whatsapp_biz_id, w.status, w.deleted_at
FROM aigreentick_2nd.whatsapp_accounts w
WHERE w.user_id IN (SELECT old_user_id FROM aigreentick_migration.mig_tenant_map WHERE project_id = 1210);
```

If the accounts are only soft-deleted, `--migration.waba.include-deleted=true` brings them over. Then re-run steps
`05-waba,07a-templates`: skipped templates are retried, and nothing is duplicated.

## 6. Harmless messages in the log

| Message | Meaning |
|---|---|
| `Integer display width is deprecated (1681)` × 9 | MySQL notice while Flyway creates the tracking tables on first start |
| `F2_SOFT_DELETED : 1` | one summary row standing for all 161 soft-deleted users |
| `PINNACLE_CREDENTIALS_NOT_MIGRATED` × 77 | expected at that time; Pinnacle tables were added on 2026-10-07 and are now migrated |

## 7. Next steps

1. Stop the run on the PC and restart it **on the DB server** (section 3).
2. Decide the token option with the WABA team (section 4).
3. Run the two `NO_WABA` queries and decide on `include-deleted` (section 5).
4. Send the complete log of the server run, including the end: `=== run N finished` and the `99-validate` lines.
5. After it finishes, run the same command once more. The second run must insert 0 rows.

Useful queries while it runs: `scripts/useful_queries.sql` (`mig_run`, `mig_errors` by code, `mig_checkpoint` progress).
