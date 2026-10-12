# Migration: problem summary

Status: 2026-10-07, after migration run 1 on 146.88.24.113 and the new WABA schema.
Detail of every case and the setting that applies each decision: `manual_decisions.md`.

---

## The main problem (one token per organization): resolved

### What went wrong

The old system kept one token per customer (`whatsapp_accounts.parmenent_token`). The first new schema allowed only one
token per organization, and Phase 1 put all customers of a reseller into one organization. Run 1 left **118 of 196 WABAs
without a token**.

### How the new WABA schema fixes it

```
Organization (reseller)
   ├── Project A ── Business Manager A ── token A ── WABA A ── phone numbers
   ├── Project B ── Business Manager B ── token B ── WABA B ── phone numbers
   └── Project C ── Business Manager C ── token C ── WABA C ── phone numbers
```

A Business Manager now belongs to a **project**, and the token belongs to the Business Manager. So every customer keeps its
own token.

### What the migration does

| Old data | New data |
|---|---|
| customer (project) + its token | one placeholder Business Manager, `meta_business_id = legacy-<uuid>` (the old DB has no Business Manager id) |
| `parmenent_token` | one ACTIVE token of that Business Manager, copied as it is |
| `whatsapp_biz_id` (= WABA id) | one `waba_accounts` row in the customer's project |
| `whatsapp_no_id` | one `waba_phone_numbers` row |
| old Pinnacle `waba_accounts` | `pinacle_credentials` + `pinacle_billing_config` |

A Business Manager is never shared between projects. Map rows written by run 1 for the old WABA tables are removed
automatically when the service starts.

### What is left

- **Same WABA used by two customers:** a WABA can belong to one project only. The first customer keeps it, the other is
  logged as `WABA_OTHER_PROJECT` (decision 11).
- **Pinnacle values not in the old DB:** `partner_id` (default: old username) and which old billing code means POSTPAID
  (default: `2`) (decision 10).
- Placeholder Business Manager ids are replaced when a customer reconnects through embedded signup.

---

## Other problems

| # | Problem | Size | Decision needed |
|---|---|---|---|
| 0 | **Server WABA tables were created by Hibernate**, not by the WABA SQL: no Pinnacle tables, `access_token` only 255 bytes, no unique keys | 4 tables / 1 column | WABA team: apply the SQL (see `manual_decisions.md` case 0) |
| 1 | **Seed org takeover:** org id 1 (`aigreentick` from the master seed) may have been taken over by a reseller in Phase 1, mixing demo seed data with real data | check with the queries below | clean up or accept |
| 2 | The run is too slow from a PC (every query goes over the internet) | messages step would take days | run the jar on the DB server |
| 3 | Templates of projects without a WABA are skipped (`NO_WABA`) | project 1210 + others | bring soft-deleted WhatsApp accounts or skip |
| 4 | Customer without reseller | 1 | skip, or assign an organization |
| 5 | Same e-mail under two resellers: the second customer has no project | 1 (of 19 merges) | skip or handle by hand |
| 6 | Soft-deleted old users are active in the new system | 161 | leave or mark deleted |
| 7 | Opt-in of contacts (legal) | all contacts | decide before the contacts step |
| 8 | How much history (chats / reports) | ~964K chats, ~10M reports | pick dates |
| 9 | Pinnacle `partner_id` and billing codes are not in the old DB | 77 WABAs | confirm defaults |
| 10 | Team roles, Meta price overwrite, blocked numbers, unmapped status values, parts not migrated | small | see `manual_decisions.md` |

### Queries for problem 1 (seed org takeover)

```sql
-- was org 1 the seed's root org, now owned by a reseller?
SELECT id, slug, name, owner_user_id, parent_organization_id,
       JSON_EXTRACT(metadata, '$.migrated_from_reseller_id') AS reseller_id
FROM apargo_wa_messaging.organizations WHERE id = 1 OR slug = 'aigreentick';

-- seed child orgs under it
SELECT COUNT(*) FROM apargo_wa_messaging.organizations WHERE parent_organization_id = 1;

-- projects in org 1: migrated customers vs seed demo projects
SELECT JSON_EXTRACT(metadata, '$.migrated_from_user_id') IS NOT NULL AS migrated, COUNT(*)
FROM apargo_wa_messaging.projects WHERE organization_id = 1 GROUP BY 1;
```

If `reseller_id` is set and there are seed child orgs or demo projects in organization 1, the seed and the real data
got mixed.

---

## What to do next, in order

1. Run the org 1 takeover queries.
2. Move the run to the DB server and run the new jar (step 05 rebuilds the WABA data on the new schema).
3. Check `WABA_OTHER_PROJECT` rows and the Pinnacle defaults.
4. Fill in the decisions in `manual_decisions.md`; most are a setting plus a re-run.
