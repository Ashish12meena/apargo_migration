-- =====================================================================
-- WABA (WhatsApp Business Account) Service - MySQL Schema
-- Database: apargo_wa_messaging
-- Full documentation: see waba_sql.md
-- =====================================================================
--
-- The JPA entities are the source of truth; this file matches them
-- column for column. On top of the entity columns it adds what JPA
-- cannot express:
--
--   * composite foreign keys that keep the ownership tree consistent
--       Organization -> Project -> Business Manager -> (credentials, WABA -> phone number)
--   * "live row" uniqueness: STORED generated columns that are NULL for
--     soft-deleted / inactive rows, with a UNIQUE index on them (MySQL
--     has no partial unique indexes). Entities do not map these columns;
--     MySQL computes them, and Hibernate never writes them.
--
-- Requires MySQL 8.0.16+ (CHECK constraints are enforced from 8.0.16).
-- =====================================================================

CREATE DATABASE IF NOT EXISTS apargo_wa_messaging
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE apargo_wa_messaging;

-- =====================================================================
-- Drop tables (reverse dependency order — children before parents, so
-- no live FK reference blocks a drop; no FOREIGN_KEY_CHECKS toggle).
-- Includes the retired project_waba_assignments table.
-- =====================================================================
DROP TABLE IF EXISTS pinacle_credit_line_attachments;   -- -> waba_accounts
DROP TABLE IF EXISTS pinacle_billing_config;            -- -> pinacle_credentials
DROP TABLE IF EXISTS pinacle_credit_ledger;             -- -> pinacle_credentials
DROP TABLE IF EXISTS waba_phone_numbers;                -- -> waba_accounts
DROP TABLE IF EXISTS waba_daily_message_usage;          -- -> waba_accounts
DROP TABLE IF EXISTS project_waba_assignments;          -- retired; -> waba_accounts
DROP TABLE IF EXISTS waba_accounts;                     -- -> business_managers, pinacle_credentials
DROP TABLE IF EXISTS meta_oauth_tokens;                 -- -> business_managers
DROP TABLE IF EXISTS business_managers;                 -- -> project_refs
DROP TABLE IF EXISTS project_refs;
DROP TABLE IF EXISTS pinacle_credentials;
DROP TABLE IF EXISTS onboarding_tasks;                  -- standalone
DROP TABLE IF EXISTS waba_event_ledger;                 -- standalone
DROP TABLE IF EXISTS api_idempotency_keys;              -- standalone


-- =====================================================================
-- Create tables (forward dependency order — parents before children)
-- =====================================================================

-- =====================================================================
-- 1. project_refs — entity: ProjectRef
-- =====================================================================
-- Projects live in another service. This table pins each project id to
-- the ONE organization that owns it (an organization never shares a
-- project), so business_managers can reference (project_id,
-- organization_id) and a Business Manager can never sit under a project
-- of another organization. Rows are created on first use (onboarding).
CREATE TABLE project_refs (
    project_id              BIGINT NOT NULL,                                  -- external project id (not generated here)
    organization_id         BIGINT NOT NULL,                                  -- the one owning organization
    created_at              DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at              DATETIME(6) NULL,

    CONSTRAINT pk_project_refs PRIMARY KEY (project_id),
    CONSTRAINT uq_project_refs_project_org UNIQUE (project_id, organization_id),  -- target of the composite FK below

    INDEX idx_project_refs_org (organization_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;


-- =====================================================================
-- 2. business_managers — entity: BusinessManager
-- =====================================================================
-- A Meta Business Manager (business portfolio). Owned by exactly ONE
-- project; a project owns many. Owns its credentials and its WABAs.
-- Business verification and the portfolio messaging limit are
-- portfolio-level in Meta, so they live here, not on waba_accounts.
CREATE TABLE business_managers (
    id                              BIGINT AUTO_INCREMENT PRIMARY KEY,
    organization_id                 BIGINT NOT NULL,                          -- = project_refs.organization_id
    project_id                      BIGINT NOT NULL,                          -- owning project
    meta_business_id                VARCHAR(100) NOT NULL,                    -- Meta-issued Business Manager id
    name                            VARCHAR(255) NULL,
    onboarding_provider             ENUM('META_DIRECT','PINNACLE') NOT NULL DEFAULT 'META_DIRECT',
    status                          ENUM('ACTIVE','SUSPENDED','DISCONNECTED') NOT NULL DEFAULT 'ACTIVE',
    business_verification_status    ENUM('NOT_VERIFIED','PENDING','PENDING_NEED_MORE_INFO','VERIFIED','REJECTED') NULL,
    max_daily_conversations         VARCHAR(50) NULL,                         -- as reported by business_capability_update
    created_at                      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at                      DATETIME(6) NULL,

    -- A Meta Business Manager is connected once on the whole platform
    -- (among live rows). Soft-deleting frees the id for reconnection.
    live_meta_business_id           VARCHAR(100)
        GENERATED ALWAYS AS (IF(deleted_at IS NULL, meta_business_id, NULL)) STORED,

    CONSTRAINT uq_business_managers_live_meta_business_id UNIQUE (live_meta_business_id),

    -- Targets of the composite FKs from meta_oauth_tokens and waba_accounts.
    CONSTRAINT uq_business_managers_id_org UNIQUE (id, organization_id),
    CONSTRAINT uq_business_managers_id_project_org UNIQUE (id, project_id, organization_id),

    -- The project must belong to the same organization.
    CONSTRAINT fk_business_managers_project
        FOREIGN KEY (project_id, organization_id) REFERENCES project_refs (project_id, organization_id),

    INDEX idx_business_managers_org_project (organization_id, project_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;


-- =====================================================================
-- 3. meta_oauth_tokens — entity: MetaOAuthToken
-- =====================================================================
-- Meta system-user / access tokens. Each belongs to ONE Business Manager
-- (the system user lives in that portfolio) — never to a WABA or phone
-- number. A Business Manager keeps its credential history; at most one
-- row per Business Manager is ACTIVE.
CREATE TABLE meta_oauth_tokens (
    id                              BIGINT AUTO_INCREMENT PRIMARY KEY,
    business_manager_account_id     BIGINT NOT NULL,                          -- owning business_managers.id
    organization_id                 BIGINT NOT NULL,                          -- = business_managers.organization_id
    access_token                    LONGTEXT NOT NULL,                        -- encrypted at rest
    token_type                      ENUM('USER_TOKEN','SYSTEM_USER') NOT NULL DEFAULT 'USER_TOKEN',
    status                          ENUM('ACTIVE','ROTATED','REVOKED') NOT NULL DEFAULT 'ACTIVE',
    expires_at                      DATETIME(6) NULL,                         -- NULL for long-lived system-user tokens
    meta_user_id                    VARCHAR(100) NULL,                        -- Meta user that granted the token
    system_user_id                  VARCHAR(100) NULL,                        -- Meta system user that holds the token
    granted_scopes                  VARCHAR(500) NULL,                        -- comma-separated OAuth scopes
    granted_at                      DATETIME(6) NULL,
    revoked_at                      DATETIME(6) NULL,                         -- when it stopped being ACTIVE
    created_at                      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                      DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at                      DATETIME(6) NULL,

    -- One ACTIVE, live credential per Business Manager.
    active_business_manager_account_id BIGINT
        GENERATED ALWAYS AS (IF(status = 'ACTIVE' AND deleted_at IS NULL, business_manager_account_id, NULL)) STORED,

    CONSTRAINT uq_meta_oauth_tokens_active_bm UNIQUE (active_business_manager_account_id),

    -- Owner and organization always agree with business_managers.
    -- (No CASCADE: business_manager_account_id is a base column of the
    -- stored generated column above, which MySQL forbids with CASCADE.)
    CONSTRAINT fk_meta_oauth_tokens_business_manager
        FOREIGN KEY (business_manager_account_id, organization_id)
        REFERENCES business_managers (id, organization_id),

    INDEX idx_meta_oauth_tokens_org (organization_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;


-- =====================================================================
-- 4. pinacle_credentials (no JPA entity yet)
-- =====================================================================
CREATE TABLE pinacle_credentials (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    organization_id         BIGINT NOT NULL,                                  -- external org id
    partner_id               VARCHAR(100) NOT NULL,                            -- Pinacle-assigned partner/reseller id
    api_key_encrypted        VARCHAR(500) NOT NULL,                            -- Pinacle API key (encrypted at rest)
    password_encrypted       VARCHAR(500) NULL,                                -- Pinacle dashboard password, if used (encrypted at rest)
    username                 VARCHAR(150) NULL,                                -- Pinacle dashboard username
    webhook_verify_token     VARCHAR(255) NULL,                                -- verifies inbound Pinacle webhooks
    meta_credit_line_id      VARCHAR(100) NULL,                                -- Meta Credit-Line-ID Pinacle holds as a Solution Partner
    status                    ENUM('ACTIVE','REVOKED','EXPIRED') NOT NULL DEFAULT 'ACTIVE',
    granted_at                DATETIME(6) NULL,
    created_at                DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at                 DATETIME(6) NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;


-- =====================================================================
-- 5. waba_accounts — entity: WabaAccount
-- =====================================================================
-- A WABA belongs to exactly ONE Business Manager (the portfolio that owns
-- it in Meta). project_id / organization_id are copies of the Business
-- Manager's, pinned by the composite FK. No token column: the credential
-- is the Business Manager's ACTIVE meta_oauth_tokens row.
CREATE TABLE waba_accounts (
    id                                BIGINT AUTO_INCREMENT PRIMARY KEY,
    business_manager_account_id       BIGINT NOT NULL,                        -- owning business_managers.id
    project_id                        BIGINT NOT NULL,                        -- = business_managers.project_id
    organization_id                   BIGINT NOT NULL,                        -- = business_managers.organization_id
    onboarding_provider               ENUM('META_DIRECT','PINNACLE') NOT NULL DEFAULT 'META_DIRECT',
    bsp_credential_id                 BIGINT NULL,                            -- set only for BSP-managed WABAs (e.g. PINNACLE)
    waba_id                           VARCHAR(100) NOT NULL,                  -- Meta-issued WABA ID
    status                            ENUM('ACTIVE','SUSPENDED','DISCONNECTED') NOT NULL,
    account_review_status             ENUM('UNVERIFIED','PENDING','APPROVED','REJECTED','DISABLED','PERMANENTLY_DISABLED') NULL,
    message_template_namespace        VARCHAR(255) NULL,                      -- required prefix for template messages
    timezone_id                       VARCHAR(255) NULL,                      -- immutable once a credit line is attached
    currency                          VARCHAR(255) NULL,                      -- immutable once a credit line is attached
    is_project_default                BOOLEAN NOT NULL DEFAULT FALSE,         -- the project's default WABA
    custom_daily_limit                INT NULL,                               -- optional platform-side daily cap
    created_at                        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at                        DATETIME(6) NULL,

    -- Meta's WABA id is unique among live rows.
    live_waba_id                      VARCHAR(100)
        GENERATED ALWAYS AS (IF(deleted_at IS NULL, waba_id, NULL)) STORED,
    -- At most one live default WABA per project.
    default_project_id                BIGINT
        GENERATED ALWAYS AS (IF(is_project_default AND deleted_at IS NULL, project_id, NULL)) STORED,

    CONSTRAINT uq_waba_accounts_live_waba_id UNIQUE (live_waba_id),
    CONSTRAINT uq_waba_accounts_project_default UNIQUE (default_project_id),

    -- Business Manager, project and organization always agree.
    CONSTRAINT fk_waba_accounts_business_manager
        FOREIGN KEY (business_manager_account_id, project_id, organization_id)
        REFERENCES business_managers (id, project_id, organization_id),

    CONSTRAINT fk_waba_accounts_bsp_credential
        FOREIGN KEY (bsp_credential_id) REFERENCES pinacle_credentials (id),

    CONSTRAINT chk_waba_accounts_provider_credential                          -- BSP credential iff BSP-managed
        CHECK (
            (onboarding_provider = 'META_DIRECT' AND bsp_credential_id IS NULL)
            OR
            (onboarding_provider = 'PINNACLE' AND bsp_credential_id IS NOT NULL)
        ),

    INDEX idx_waba_accounts_org_project (organization_id, project_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;


-- =====================================================================
-- 6. pinacle_credit_line_attachments (no JPA entity yet)
-- =====================================================================
CREATE TABLE pinacle_credit_line_attachments (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    waba_account_id         BIGINT NOT NULL,                         -- the client WABA this credit line was attached to
    credit_line_id           VARCHAR(100) NOT NULL,                            -- Meta Credit-Line-ID used for this attach call
    allocation_config_id     VARCHAR(100) NULL,                                -- returned by whatsapp_credit_sharing_and_attach
    waba_currency             VARCHAR(10) NOT NULL,                             -- must match waba_accounts.currency
    status                    ENUM('PENDING','ATTACHED','FAILED','REVOKED') NOT NULL DEFAULT 'PENDING',
    shared_at                 DATETIME(6) NULL,
    revoked_at                DATETIME(6) NULL,
    error_message             LONGTEXT NULL,
    created_at                DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT uq_pinacle_credit_line_waba UNIQUE (waba_account_id),          -- one attachment record per WABA

    CONSTRAINT fk_pinacle_credit_line_waba_account
        FOREIGN KEY (waba_account_id) REFERENCES waba_accounts (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;


-- =====================================================================
-- 7. pinacle_billing_config (no JPA entity yet)
-- =====================================================================
CREATE TABLE pinacle_billing_config (
    id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
    pinacle_credential_id       BIGINT NOT NULL,

    billing_type                 ENUM('PREPAID','POSTPAID') NOT NULL DEFAULT 'PREPAID',
    billing_enabled               BOOLEAN NOT NULL DEFAULT FALSE,

    minimum_balance_limit        DECIMAL(12,2) NOT NULL DEFAULT 0.00,          -- floor balance before messaging blocks / alert fires
    credit_limit_assigned        BOOLEAN NOT NULL DEFAULT FALSE,
    credit_limit                 DECIMAL(12,2) NULL,                           -- set only when credit_limit_assigned = TRUE
    current_balance              DECIMAL(12,2) NOT NULL DEFAULT 0.00,          -- history lives in pinacle_credit_ledger

    billing_contact_name         VARCHAR(150) NULL,
    billing_email                 VARCHAR(150) NULL,
    billing_mobile                VARCHAR(20) NULL,
    gst_no                       VARCHAR(50) NULL,                            -- GSTIN for invoicing (India)

    created_at                   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at                    DATETIME(6) NULL,

    CONSTRAINT uq_pinacle_billing_config_credential UNIQUE (pinacle_credential_id),

    CONSTRAINT fk_pinacle_billing_config_credential
        FOREIGN KEY (pinacle_credential_id) REFERENCES pinacle_credentials (id),

    CONSTRAINT chk_pinacle_billing_credit_limit
        CHECK (
            (credit_limit_assigned = FALSE AND credit_limit IS NULL)
            OR
            (credit_limit_assigned = TRUE AND credit_limit IS NOT NULL)
        )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;


-- =====================================================================
-- 8. pinacle_credit_ledger (no JPA entity yet)
-- =====================================================================
CREATE TABLE pinacle_credit_ledger (
    id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
    pinacle_credential_id       BIGINT NOT NULL,
    entry_type                   ENUM('RECHARGE','MESSAGE_DEBIT','ADJUSTMENT','REFUND') NOT NULL,
    amount                       DECIMAL(12,2) NOT NULL,
    balance_after                DECIMAL(12,2) NOT NULL,                       -- running balance snapshot after this entry
    reference                    VARCHAR(255) NULL,                            -- payment gateway txn id, message id, etc.
    remarks                      VARCHAR(500) NULL,
    created_at                   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT fk_pinacle_credit_ledger_credential
        FOREIGN KEY (pinacle_credential_id) REFERENCES pinacle_credentials (id),

    INDEX idx_pinacle_credit_ledger_credential_date (pinacle_credential_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;


-- =====================================================================
-- 9. waba_phone_numbers — entity: WabaPhoneNumber
-- =====================================================================
-- A phone number belongs to exactly ONE WABA. It never holds a token.
CREATE TABLE waba_phone_numbers (
    id                              BIGINT AUTO_INCREMENT PRIMARY KEY,
    waba_account_id                 BIGINT NOT NULL,
    phone_number_id                 VARCHAR(100) NOT NULL,                    -- Meta-issued phone number ID
    display_phone_number             VARCHAR(255) NULL,
    status                           ENUM('ACTIVE','PENDING','REGISTRATION_FAILED','DISABLED','BLOCKED') NOT NULL DEFAULT 'ACTIVE',
    verified_name                    VARCHAR(255) NULL,
    quality_rating                   ENUM('GREEN','YELLOW','RED','UNKNOWN') NULL,
    messaging_limit_tier             ENUM('LIMIT_250','LIMIT_2K','LIMIT_10K','LIMIT_100K','LIMIT_UNLIMITED') NULL,
    messaging_throughput_tier        ENUM('STANDARD','HIGH') NULL,
    name_status                      ENUM('APPROVED','AVAILABLE_WITHOUT_REVIEW','REJECTED','PENDING','PENDING_DELETION','DELETED','DISABLED') NULL,
    health_status                    ENUM('GREEN','YELLOW','RED','PAUSED','BLOCKED','UNKNOWN') NULL,
    is_official_business_account     BOOLEAN NOT NULL DEFAULT FALSE,           -- Meta's green-tick OBA badge
    code_verification_status         ENUM('NOT_VERIFIED','PENDING','VERIFIED','EXPIRED','FAILED') NULL,
    created_at                       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at                        DATETIME(6) NULL,

    -- Meta's Phone Number ID is unique among live rows.
    live_phone_number_id             VARCHAR(100)
        GENERATED ALWAYS AS (IF(deleted_at IS NULL, phone_number_id, NULL)) STORED,

    CONSTRAINT uq_waba_phone_numbers_live_phone_number_id UNIQUE (live_phone_number_id),

    CONSTRAINT fk_waba_phone_numbers_waba_account
        FOREIGN KEY (waba_account_id) REFERENCES waba_accounts (id),

    INDEX idx_waba_phone_numbers_phone_number_id (phone_number_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;


-- =====================================================================
-- 10. waba_daily_message_usage — entity: WabaDailyMessageUsage
-- =====================================================================
CREATE TABLE waba_daily_message_usage (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    waba_account_id     BIGINT NOT NULL,
    usage_date           DATE NOT NULL,
    messages_sent         INT NOT NULL DEFAULT 0,                               -- volume only, not cost
    created_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at            DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at             DATETIME(6) NULL,

    CONSTRAINT uq_waba_daily_usage UNIQUE (waba_account_id, usage_date),

    CONSTRAINT fk_waba_daily_usage_waba_account
        FOREIGN KEY (waba_account_id) REFERENCES waba_accounts (id),

    INDEX idx_waba_usage_date (usage_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;


-- =====================================================================
-- 11. onboarding_tasks — entity: OnboardingTask
-- =====================================================================
-- result_* columns are loose references (no FK) on purpose: a task can
-- exist, and fail, before the rows it would create exist.
CREATE TABLE onboarding_tasks (
    id                                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    organization_id                     BIGINT NOT NULL,
    project_id                          BIGINT NOT NULL,                       -- project that will own the Business Manager
    oauth_code                          VARCHAR(500) NOT NULL,                 -- code returned by the embedded signup popup
    status                              ENUM('FAILED','PROCESSING','COMPLETED','CANCELLED','PENDING') NOT NULL DEFAULT 'PENDING',
    current_step                        ENUM(
                                            'TOKEN_EXCHANGE',
                                            'TOKEN_EXTENSION',
                                            'SCOPE_VERIFICATION',
                                            'BUSINESS_MANAGER_RESOLUTION',
                                            'WABA_RESOLUTION',
                                            'PHONE_NUMBER_RESOLUTION',
                                            'CREDENTIAL_PERSISTENCE',
                                            'WEBHOOK_SUBSCRIPTION',
                                            'PHONE_SYNC',
                                            'PHONE_REGISTRATION',
                                            'SMB_SYNC',
                                            'PHASE2_PROVISIONING'
                                         ) NULL,
    retry_count                         INT NOT NULL DEFAULT 0,
    idempotency_key                     VARCHAR(200) NOT NULL,                 -- prevents duplicate onboarding runs
    encrypted_access_token              LONGTEXT NULL,
    token_expires_in                    BIGINT NULL,
    resolved_waba_id                    VARCHAR(255) NULL,
    resolved_business_manager_id        VARCHAR(255) NULL,                     -- Meta id
    resolved_phone_number_id            VARCHAR(255) NULL,
    result_business_manager_account_id  BIGINT NULL,                           -- loose reference, no FK
    result_waba_account_id              BIGINT NULL,                           -- loose reference, no FK
    completed_steps                     LONGTEXT NULL,                         -- JSON log of completed steps
    result_summary                      LONGTEXT NULL,
    error_message                       LONGTEXT NULL,
    started_at                          DATETIME(6) NULL,
    finished_at                         DATETIME(6) NULL,
    created_at                          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at                          DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at                          DATETIME(6) NULL,

    -- Idempotency keys are scoped to the organization.
    CONSTRAINT uq_onboarding_org_idempotency UNIQUE (organization_id, idempotency_key),

    INDEX idx_onboarding_tasks_org_status (organization_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;


-- =====================================================================
-- 12. waba_event_ledger — entity: WabaEventLedger
-- =====================================================================
-- Append-only record of every ACCOUNT-lane event seen. event_id is the
-- primary key, so inserting is the dedup check; MAX(received_at) per
-- (waba_id, field) over handled rows is the staleness high-water mark.
CREATE TABLE waba_event_ledger (
    event_id                VARCHAR(64) NOT NULL,                             -- producer-assigned event id
    field                   VARCHAR(64) NOT NULL,                             -- Meta's field discriminator
    waba_id                 VARCHAR(64) NOT NULL,                             -- Meta WABA id (not waba_accounts.id)
    phone_number_id         VARCHAR(64) NULL,
    received_at             DATETIME(6) NOT NULL,
    handled                 BOOLEAN NOT NULL DEFAULT TRUE,                    -- FALSE = recorded but deliberately not applied
    raw_payload             LONGTEXT NULL,                                    -- only for events that could not be applied
    created_at              DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at              DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at              DATETIME(6) NULL,

    CONSTRAINT pk_waba_event_ledger PRIMARY KEY (event_id),

    INDEX idx_waba_event_ledger_waba_field (waba_id, field, handled, received_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;


-- =====================================================================
-- 13. api_idempotency_keys — entity: infrastructure.idempotency.IdempotencyRecord
-- =====================================================================
-- X-Idempotency-Key handling for create endpoints (company API Standard
-- §1). Technical, TTL-bound table: rows are HARD-deleted by the hourly
-- purge once older than idempotency.ttl (default 24 h), so it has no
-- updated_at/deleted_at. project_id = 0 for organization-level keys.
CREATE TABLE api_idempotency_keys (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    organization_id      BIGINT NOT NULL,
    project_id           BIGINT NOT NULL,
    idempotency_key      VARCHAR(128) NOT NULL,
    request_fingerprint  VARCHAR(64) NOT NULL,
    state                VARCHAR(16) NOT NULL,
    response_status      INT NULL,
    response_body        LONGTEXT NULL,
    created_at           DATETIME(6) NOT NULL,
    completed_at         DATETIME(6) NULL,

    CONSTRAINT uk_idempotency_scope_key UNIQUE (organization_id, project_id, idempotency_key),
    INDEX idx_idempotency_created_at (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
