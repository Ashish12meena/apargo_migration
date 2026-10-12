-- Tracking schema of the migration service (aigreentick_migration). Flyway manages ONLY this schema.
-- The services' schema (apargo_wa_messaging) is never touched by Flyway.

CREATE TABLE migration_id_map (
  entity      VARCHAR(50)      NOT NULL,
  old_id      BIGINT UNSIGNED  NOT NULL,
  new_id      BIGINT UNSIGNED  NOT NULL,
  step        VARCHAR(60)      NULL,
  created_at  DATETIME(6)      NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (entity, old_id),
  KEY idx_reverse (entity, new_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mig_tenant_map (
  old_user_id     BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  kind            VARCHAR(20)     NOT NULL,
  organization_id BIGINT UNSIGNED NULL,
  project_id      BIGINT UNSIGNED NULL,
  new_user_id     BIGINT UNSIGNED NULL,
  reason          VARCHAR(255)    NULL,
  resolved_at     DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mig_run (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  started_at   DATETIME(6)  NOT NULL,
  finished_at  DATETIME(6)  NULL,
  dry_run      TINYINT(1)   NOT NULL,
  refresh      TINYINT(1)   NOT NULL,
  steps        VARCHAR(500) NULL,
  tenants      VARCHAR(500) NULL,
  status       VARCHAR(20)  NOT NULL,
  error        TEXT         NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mig_checkpoint (
  step         VARCHAR(60)     NOT NULL,
  phase        VARCHAR(60)     NOT NULL DEFAULT 'main',
  last_old_id  BIGINT UNSIGNED NOT NULL,
  updated_at   DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (step, phase)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mig_errors (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id        BIGINT          NULL,
  step          VARCHAR(60)     NOT NULL,
  source_table  VARCHAR(100)    NOT NULL,
  old_id        BIGINT UNSIGNED NULL,
  severity      VARCHAR(10)     NOT NULL,
  code          VARCHAR(50)     NOT NULL,
  reason        VARCHAR(1000)   NOT NULL,
  created_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_run (run_id),
  KEY idx_step (step, severity, code),
  KEY idx_old (source_table, old_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mig_validation (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  run_id      BIGINT        NOT NULL,
  check_name  VARCHAR(100)  NOT NULL,
  expected    BIGINT        NULL,
  actual      BIGINT        NULL,
  passed      TINYINT(1)    NOT NULL,
  detail      VARCHAR(1000) NULL,
  checked_at  DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
