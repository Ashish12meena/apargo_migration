# Migration: where the server run can break

Status: 2026-10-07, before the full run on 146.88.24.113. Code tested against the server schema dump of 2026-10-07
(all 17 relation checks pass, second run inserts 0, failure injection: bad rows skipped and logged, run finishes).

Short answer: **the data side is solid; the remaining risks are the environment** (disk, memory, other services,
how it is started). Every stop is resumable: run the same command again and it continues from its checkpoint, without
duplicates.

---

## 1. How the service protects itself

| Situation | What happens |
|---|---|
| One bad old row (too long, bad ENUM, bad JSON, duplicate key, ...) in a row-by-row step | that row is rolled back, logged as `SQL_ERROR` in `mig_errors`, the step goes on |
| One bad row inside a **batch** (contacts, project contacts, consent, attributes, recipients, working tables) | **new:** the batch is retried row by row; only the bad row is logged as `SQL_ERROR`, the rest is inserted |
| Zero dates (`0000-00-00`) in old `chat_contacts` | **new:** replaced by the migration time instead of failing |
| Attribute values longer than TEXT allows | **new:** cut to 16,000 characters |
| A live contact created by the new system during the run (same phone) | the migration maps to it (batch fallback covers the race) |
| Token longer than the column | Business Manager without token, `TOKEN_TOO_LONG` (column already widened) |
| Run stopped (Ctrl+C, crash, connection lost, server reboot) | next run continues from `mig_checkpoint`; nothing half-written (every page / row commits with its checkpoint) |
| Same command run twice | second run inserts 0 rows |

Before this change, one bad row inside a batch stopped the run, and every rerun stopped at the same page.

## 2. What can still stop the run (environment)

| # | Risk | Effect | Prevention |
|---|---|---|---|
| R1 | **Dry run on the full data** | the whole run is one transaction: hours of work, a huge undo log, locks, then everything is rolled back | never use `--migration.dry-run=true` on the server without `--migration.tenants=<a few>` |
| R2 | **Disk full** (data + binlog) | MySQL stops writing; the run fails | `df -h`; free at least 2x the old data size (preflight block 2); check binlog expiry |
| R3 | **Java out of memory** | run fails at the conversations / templates step | start with `-Xmx4g` (at least 2 GB) |
| R4 | **Another service restarts with Hibernate `ddl-auto=create` / `create-drop`** | tables dropped and recreated: migrated data lost (also undoes the `access_token` LONGTEXT fix) | all services on `update`, `validate` or `none` during and after the migration |
| R5 | **Two migration runs at the same time** | they compete for the same rows (duplicate-key errors, wrong counts) | one run only; preflight block 1; `ps aux \| grep migration-service` |
| R6 | **Schema changed by a team during the run** (column renamed / dropped) | SQL errors in every row of that table | freeze schema changes until the run is finished |
| R7 | Terminal closed / SSH dropped | run killed | start with `nohup ... &` (or `screen` / `tmux`) |
| R8 | MySQL restarted / connection lost | run fails | just start it again |
| R9 | `99-validate` finds broken relations | the run ends as FAILED **after** all data is written | not a crash: read the `FAIL` lines and `mig_validation`; send them for analysis |

## 3. Known data losses (logged, by design, need decisions)

| Code | Count (run 3) | Meaning | Decision |
|---|---|---|---|
| `WABA_OTHER_PROJECT` | 2 | WABA used by two customers; second one gets nothing | `manual_decisions.md` case 11 |
| `PINNACLE_CREDENTIALS_NOT_MIGRATED` | 77 | Pinnacle tables missing on the server | case 0 / 10 |
| `NO_WABA` (templates) | project 1210 + ? | project without WABA | case 2 |
| `NO_RESELLER`, `CUSTOMER_NO_PROJECT` | 1 + 1 customer | all their data skipped | cases 3, 4 |
| `CONTACT_NOT_FOUND` (reports) | unknown | recipient number is not a contact | expected |
| `INVALID_PHONE`, `ENUM_UNKNOWN`, `NO_CONVERSATION`, `SQL_ERROR` | unknown | per row | review after the run |

Find them after the run:

```sql
SELECT step, severity, code, COUNT(*) FROM aigreentick_migration.mig_errors
WHERE run_id = (SELECT MAX(id) FROM aigreentick_migration.mig_run) GROUP BY 1,2,3 ORDER BY 1, 4 DESC;
```

## 4. How to start it on the server

1. Build the jar on the PC: `mvn -DskipTests package` → `target/migration-service.jar`; copy it to the server.
2. Stop the PC run (Ctrl+C) so only one runs.
3. Run `scripts/server_preflight.sql` and check each block.
4. Start:

```bash
export DB_HOST=localhost DB_USER=root DB_PASSWORD='...'
nohup java -Xmx4g -jar migration-service.jar > migration-$(date +%F-%H%M).log 2>&1 &
tail -f migration-*.log
```

   The log is also written to `logs/migration.log` next to the jar (rolled at 200 MB, kept 60 files;
   `--logging.file.name=...` to change). Every problem row goes to `aigreentick_migration.mig_errors`
   (no cap; the console shows only the first 3 per code).
5. Done when the log shows the `99-validate` lines and `=== run N finished`. Run the same command once more: it must
   insert 0 rows.
