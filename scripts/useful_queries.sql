-- ---------------------------------------------------------------- runs
SELECT * FROM aigreentick_migration.mig_run ORDER BY id DESC LIMIT 10;

-- what each step did in the last run
SELECT step, entity, read_rows, inserted, matched_existing, skipped_mapped, refreshed, errors
FROM aigreentick_migration.mig_step_stats
WHERE run_id = (SELECT MAX(id) FROM aigreentick_migration.mig_run) ORDER BY step, entity;

-- ---------------------------------------------------------------- problems
SELECT step, severity, code, COUNT(*) n
FROM aigreentick_migration.mig_errors
WHERE run_id = (SELECT MAX(id) FROM aigreentick_migration.mig_run)
GROUP BY 1, 2, 3 ORDER BY FIELD(severity, 'ERROR', 'WARN', 'INFO'), n DESC;

-- details of one code
SELECT source_table, old_id, reason FROM aigreentick_migration.mig_errors
WHERE run_id = (SELECT MAX(id) FROM aigreentick_migration.mig_run) AND code = 'NO_RESELLER' LIMIT 100;

-- ---------------------------------------------------------------- validation
SELECT check_name, expected, actual, passed, detail FROM aigreentick_migration.mig_validation
WHERE run_id = (SELECT MAX(id) FROM aigreentick_migration.mig_run) ORDER BY passed, check_name;

-- ---------------------------------------------------------------- id map
SELECT entity, COUNT(*) old_rows, COUNT(DISTINCT new_id) new_rows FROM aigreentick_migration.migration_id_map GROUP BY entity;

-- where did old customer 12 go?
SELECT * FROM aigreentick_migration.mig_tenant_map WHERE old_user_id = 12;

-- old contact -> new contact -> projects
SELECT m.old_id, m.new_id, c.organization_id, c.normalized_phone, pc.project_id
FROM aigreentick_migration.migration_id_map m
JOIN apargo_wa_messaging.contacts c ON c.id = m.new_id
LEFT JOIN apargo_wa_messaging.project_contacts pc ON pc.contact_id = c.id
WHERE m.entity = 'contact' AND m.old_id = 12345;

-- tenants that cannot be placed and why
SELECT kind, reason, COUNT(*) FROM aigreentick_migration.mig_tenant_map WHERE project_id IS NULL GROUP BY 1, 2;

-- ---------------------------------------------------------------- checkpoints (resume points)
SELECT * FROM aigreentick_migration.mig_checkpoint;
