-- =====================================================================================================
-- Run on the DB server BEFORE starting the full migration there. Read-only.
-- Each block says what a bad result looks like and what to do. See docs/server_run_risks.md.
-- =====================================================================================================

-- 1. Is another migration still running? (last run must be FINISHED / FAILED, not RUNNING)
--    If RUNNING and no java process is alive, the old run was killed: safe to start a new one.
SELECT id, status, started_at, finished_at, dry_run FROM aigreentick_migration.mig_run ORDER BY id DESC LIMIT 3;

-- 2. Disk: the migration adds roughly the size of the old chat_contacts + chats + reports data, plus binlog.
--    Compare with free disk (df -h on the server). Keep at least 2x the old data size free.
SELECT table_schema, ROUND(SUM(data_length + index_length) / 1024 / 1024 / 1024, 2) AS size_gb
FROM information_schema.tables WHERE table_schema IN ('aigreentick_2nd', 'apargo_wa_messaging', 'aigreentick_migration')
GROUP BY table_schema;
SHOW VARIABLES WHERE Variable_name IN ('log_bin', 'binlog_expire_logs_seconds', 'max_binlog_size');

-- 3. Packet size for batch inserts (64 MB or more is fine)
SHOW VARIABLES LIKE 'max_allowed_packet';

-- 4. Tokens fit (must return 0 rows after the LONGTEXT change)
SELECT COUNT(*) AS tokens_too_long FROM aigreentick_2nd.whatsapp_accounts w
WHERE LENGTH(w.parmenent_token) > (SELECT character_octet_length FROM information_schema.columns
  WHERE table_schema = 'apargo_wa_messaging' AND table_name = 'meta_oauth_tokens' AND column_name = 'access_token');

-- 5. Zero / broken dates in the big old tables (handled, but good to know)
SELECT 'chat_contacts' t, COUNT(*) zero_dates FROM aigreentick_2nd.chat_contacts WHERE created_at < '1970-01-02'
UNION ALL SELECT 'chats', COUNT(*) FROM aigreentick_2nd.chats WHERE created_at < '1970-01-02'
UNION ALL SELECT 'broadcasts', COUNT(*) FROM aigreentick_2nd.broadcasts WHERE created_at < '1970-01-02';

-- 6. What is still to do (old rows vs already mapped)
SELECT 'contacts' what, (SELECT COUNT(*) FROM aigreentick_2nd.chat_contacts WHERE deleted_at IS NULL) old_rows,
       (SELECT COUNT(*) FROM aigreentick_migration.migration_id_map WHERE entity = 'contact') mapped
UNION ALL SELECT 'messages', (SELECT COUNT(*) FROM aigreentick_2nd.chats WHERE deleted_at IS NULL),
       (SELECT COUNT(*) FROM aigreentick_migration.migration_id_map WHERE entity = 'message')
UNION ALL SELECT 'campaigns', (SELECT COUNT(*) FROM aigreentick_2nd.broadcasts WHERE deleted_at IS NULL),
       (SELECT COUNT(*) FROM aigreentick_migration.migration_id_map WHERE entity = 'campaign')
UNION ALL SELECT 'assignments', (SELECT COUNT(*) FROM aigreentick_2nd.agent_assignments WHERE deleted_at IS NULL),
       (SELECT COUNT(*) FROM aigreentick_migration.migration_id_map WHERE entity = 'assignment');

-- 7. Checkpoints (where each big step will continue)
SELECT * FROM aigreentick_migration.mig_checkpoint ORDER BY updated_at DESC;

-- ------------------------------------------------------------------ while it runs
-- current step / errors so far
-- SELECT * FROM aigreentick_migration.mig_run ORDER BY id DESC LIMIT 1;
-- SELECT step, severity, code, COUNT(*) FROM aigreentick_migration.mig_errors
--   WHERE run_id = (SELECT MAX(id) FROM aigreentick_migration.mig_run) GROUP BY 1,2,3 ORDER BY 1, 4 DESC;
-- rows that failed and were skipped (new safety net): look at the message
-- SELECT * FROM aigreentick_migration.mig_errors WHERE code = 'SQL_ERROR' ORDER BY id DESC LIMIT 50;
