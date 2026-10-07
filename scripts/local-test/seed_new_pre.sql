-- new DB "master seed" (before Phase 1): roles, contact statuses, one seed org + 2 seed users
USE apargo_wa_messaging;
INSERT INTO roles (uuid, name, slug, description, scope, organization_id, project_id, is_default, is_system, status, created_at, updated_at) VALUES
 (UUID(), 'Super Admin', 'super-admin', NULL, 'system', NULL, NULL, 0, 1, 'active', NOW(), NOW()),
 (UUID(), 'Owner', 'owner', NULL, 'organization', NULL, NULL, 0, 1, 'active', NOW(), NOW()),
 (UUID(), 'Org Admin', 'admin', NULL, 'organization', NULL, NULL, 0, 1, 'active', NOW(), NOW()),
 (UUID(), 'Project Admin', 'admin', NULL, 'project', NULL, NULL, 1, 1, 'active', NOW(), NOW()),
 (UUID(), 'Agent', 'agent', NULL, 'project', NULL, NULL, 0, 1, 'active', NOW(), NOW());

INSERT INTO contact_statuses (id, code, name, description, sort_order, is_system) VALUES
 (1,'ACTIVE','Active','Normal contact',1,b'1'), (2,'BLOCKED','Blocked','Cannot receive campaigns',2,b'1'),
 (3,'ARCHIVED','Archived','Hidden',3,b'1'), (4,'INVALID','Invalid Number','Phone number invalid',4,b'1'), (5,'DND','Do Not Disturb','Opted out',5,b'1');

INSERT INTO users (uuid, name, email, password_hash, status, created_at, updated_at) VALUES
 (UUID(), 'Seed Admin', 'seed.admin@apargo.test', 'x', 'active', NOW(), NOW()),
 (UUID(), 'Existing Person', 'shared@old.test', 'x', 'active', NOW(), NOW());
INSERT INTO organizations (uuid, slug, name, organization_type, owner_user_id, status, created_at, updated_at)
 VALUES (UUID(), 'seed-org', 'Seed Org', 'organization', 1, 'active', NOW(), NOW());
INSERT INTO contact_sources (id, created_at, updated_at, is_active, display_name, organization_id, source_key, is_system, uuid)
 VALUES (18, NOW(6), NOW(6), b'1', 'Migration', 1, 'MIGRATION', b'1', '89db02b3-b0fa-11f1-a67a-a687fa8690d2');
