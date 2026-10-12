-- Working tables of the big steps (contacts, chats). Derived data: safe to rebuild.

-- 08a: one row per old chat_contacts row, with its tenant and E.164 phone
CREATE TABLE mig_contact (
  old_id           BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  old_user_id      BIGINT UNSIGNED NULL,
  organization_id  BIGINT UNSIGNED NULL,
  project_id       BIGINT UNSIGNED NULL,
  new_user_id      BIGINT UNSIGNED NULL,
  dial_code        VARCHAR(8)      NULL,
  national         VARCHAR(20)     NULL,
  normalized       VARCHAR(30)     NULL,
  country_code     VARCHAR(2)      NULL,
  phone_valid      TINYINT(1)      NOT NULL DEFAULT 0,
  opt_in           TINYINT(1)      NOT NULL DEFAULT 0,
  opt_out          TINYINT(1)      NOT NULL DEFAULT 0,
  is_deleted       TINYINT(1)      NOT NULL DEFAULT 0,
  placeable        TINYINT(1)      NOT NULL DEFAULT 0,
  reason           VARCHAR(255)    NULL,
  prepared_at      DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_org_phone (organization_id, normalized),
  KEY idx_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 09c: one row per placeable old chat
CREATE TABLE mig_chat (
  old_id                BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  organization_id       BIGINT UNSIGNED NOT NULL,
  project_id            BIGINT UNSIGNED NOT NULL,
  contact_id            BIGINT UNSIGNED NOT NULL,
  waba_account_id       BIGINT UNSIGNED NOT NULL,
  waba_phone_number_id  BIGINT UNSIGNED NOT NULL,
  direction             VARCHAR(8)      NOT NULL,
  normalized_phone      VARCHAR(30)     NOT NULL,
  created_at            DATETIME(6)     NULL,
  conversation_id       BIGINT UNSIGNED NULL,
  KEY idx_conv_key (project_id, waba_phone_number_id, contact_id),
  KEY idx_conversation (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 09d: conversations the migration created (only those get sessions / summaries rewritten)
CREATE TABLE mig_conversation (
  conversation_id  BIGINT UNSIGNED NOT NULL PRIMARY KEY,
  project_id       BIGINT UNSIGNED NOT NULL,
  session_id       BIGINT UNSIGNED NULL,
  inserted         TINYINT(1)      NOT NULL,
  created_at       DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
