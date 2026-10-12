# Migration run 4 (server): report

| | |
|---|---|
| Run | `mig_run.id = 4`, on the DB server, all steps, 10:34 → 12:09 (1 h 35 min) |
| Result | **Finished.** 16 of 17 relation checks OK, 1 FAIL (`tenant.template_waba`, 129 rows) |
| Main findings | **290,210 chats (32 %) not migrated: old `chats.type = 'recieve'` (misspelled) was not recognised.** Old reports became recipients only, not messages. Both fixed; re-run 09c–09f + 09l |

## 1. Results per step

| Step | Read | Inserted | Already there | Errors | Time |
|---|---|---|---|---|---|
| 05 WABA | 200 accounts | — | 197 WABAs, 196 tokens | 2 `WABA_OTHER_PROJECT` | 2 s |
| 07a templates | 3,728 | — | 3,631 | 93 `NO_WABA`, 4 `USER_UNKNOWN` | — |
| 08a/08b contacts (PC run 3) | 1,468,924 | 1,427,696 contacts | 38,748 duplicates merged | 2,331 `INVALID_PHONE`, 148 `USER_UNKNOWN` | 17 min |
| 08c contact relations | 1,136,421 | 1,132,872 project contacts, 1,132,857 consent, 47 tags, 21 lists, 52 attributes, 745 values, 20 notes | 3,549 | 0 | 5 min |
| 08d recount | | 1,427,765 counters refreshed | | | 1.5 min |
| 09c chats prepare | 903,153 | 608,008 | | **290,210 `DIRECTION_UNKNOWN`**, 2,699 `CONTACT_MISSING`, 2,020 `NO_WABA_PHONE`, 216 `USER_UNKNOWN` | 1.5 min |
| 09d conversations | 92,213 | 92,213 (+ sessions) | | 0 | 8 min |
| 09e messages | 608,008 | 607,554 | 454 | 103,930 WARN `ENUM_UNKNOWN`, 16 `TRUNCATED` | 31 min |
| 09f assignments | 103,134 | 82,201 | 20,893 | 40 WARN `NO_CONVERSATION` | 8 min |
| 09h campaigns | 165,870 | 164,139 | | 1,691 `TEMPLATE_MISSING`, 30 `USER_UNKNOWN`, 10 `NO_WABA_PHONE`; 76,926 WARN `TEMPLATE_PAYLOAD_EMPTY` | 7 min |
| 09j recipients | 4,326,974 | 4,317,153 | 92 | 7,952 WARN `CONTACT_NOT_FOUND`, 1,777 INFO `CAMPAIGN_NOT_MIGRATED` | 29 min |
| 09k counters | | 141,527 | | | 2 min |

## 2. Issues

### 2.1 290,210 chats lost: `type = 'recieve'` (fixed)

The old system writes incoming messages as `chats.type = 'recieve'` (misspelled). The direction map only knew `receive`,
and the number fallback did not match either, so every one of these chats was skipped.

**Fix (service):** `recieve`, `recieved`, `recive`, `recived` → INBOUND. Conversations created by the migration now also
get their summary (last inbound / outbound / message time, 24 h window, session counts) recomputed from all their chats,
so the re-run moves them forward.

**Re-run (server), together with 2.4:**

```bash
nohup java -Xmx4g -jar migration-service.jar \
  --migration.steps=09c-chats-prepare,09d-conversations,09e-messages,09f-assignments,09l-campaign-messages,99-validate \
  --migration.reset-checkpoints=true > migration-rerun-chats.log 2>&1 &
```

Already migrated messages are skipped; only the missing ones are added. Tested locally: re-run adds the missing chat,
updates its conversation, a second re-run changes nothing.

### 2.4 Old reports were only recipients, not messages (fixed: new step 09l)

In the new system every campaign send is a `messages` row (`campaign_id` + `contact_id` unique, `created_by_type = CAMPAIGN`)
in the contact's conversation, and `broadcast_recipients.message_id` points to it. Run 4 created the 4.32M recipients
but no messages: campaign templates were missing from chat history, delivered / read per recipient was lost, and
contacts reached only by campaigns had no conversation.

New step `09l-campaign-messages` (after 09j):

| Rule | |
|---|---|
| Which recipients | migrated campaigns, state SENT / FAILED, `message_id` NULL (CANCELLED = never sent: no message) |
| Link, not insert | a message with the same (campaign_id, contact_id), or with the same wamid (the send is also in old `chats`) |
| Conversation | (campaign project, campaign phone number, contact); created when missing: RESOLVED, one session (`AGENT_OUTREACH`, `source_campaign_id`) |
| Message | OUTBOUND, TEMPLATE, CAMPAIGN, `campaign_id`, wamid, `body_text` = template name, payload = template name / language / params + legacy report id and status |
| Status | old report (by wamid) `status` / `message_status`, best of the two: SENT / DELIVERED / READ / FAILED; delivered_at / read_at / failed_at = report updated_at; without a report: recipient state |
| After | `message_wamid` rows, `broadcast_recipients.message_id`, conversation last-message times and session counts moved forward |
| Checks | `relation.recipient_message` (sent / failed recipients without a message), `tenant.campaign_messages` |

Expected on the server: about 4.3M messages and up to several hundred thousand new conversations, roughly 20–60 min.

### 2.2 `tenant.template_waba` FAIL: 129 templates

Templates whose `waba_id` does not belong to a WABA of the template's own project. Most likely the templates of the
2 customers that share a WABA with another customer (`WABA_OTHER_PROJECT`, old rows 196 and 429): run 1 (old WABA
schema) gave them the shared WABA, the new schema gives the WABA to the other project. Decision case 11.

```sql
SELECT t.project_id, t.waba_id, w.project_id AS waba_project, COUNT(*) templates
FROM apargo_wa_messaging.whatsapp_templates t
JOIN aigreentick_migration.migration_id_map m ON m.entity = 'template' AND m.new_id = t.id
LEFT JOIN apargo_wa_messaging.waba_accounts w ON w.waba_id = t.waba_id COLLATE utf8mb4_unicode_ci AND w.deleted_at IS NULL
WHERE w.id IS NULL OR w.project_id <> t.project_id OR w.organization_id <> t.organization_id
GROUP BY 1, 2, 3;
```

### 2.3 To review (WARN, data migrated)

| Code | Count | Check |
|---|---|---|
| `ENUM_UNKNOWN` (chats) | 103,930 | old type / method / status values not in the maps: `SELECT reason, COUNT(*) FROM aigreentick_migration.mig_errors WHERE run_id = 4 AND code = 'ENUM_UNKNOWN' GROUP BY reason ORDER BY 2 DESC LIMIT 30;` then add them to `migration.messaging.*` |
| `TEMPLATE_PAYLOAD_EMPTY` (broadcasts) | 76,926 | campaign migrated without the template payload (old `data` / `requests_head` empty) |
| `TEMPLATE_MISSING` (broadcasts) | 1,691 | campaigns of templates that were not migrated (deleted templates or `NO_WABA`) |
| `CONTACT_MISSING` / `CONTACT_NOT_FOUND` | 2,699 / 7,952 | number is not a contact of the organization |
| `NO_WABA_PHONE` | 2,020 chats (project 867), 10 campaigns (project 821) | projects without a migrated number |
| `USER_UNKNOWN` (old user 89, 7) | 148 contacts, 216 chats, 34 others | old user rows missing in `users` |
| `count.business_manager_without_token` | 1 | old account without a token |
