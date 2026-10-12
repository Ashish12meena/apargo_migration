-- old DB test data covering the hard cases of MIGRATION_PLAN.md section 9
USE aigreentick_2nd;
SET FOREIGN_KEY_CHECKS = 0;

INSERT INTO resellers (id, uuid, slug, name, admin_user_id, status, created_at, updated_at) VALUES
 (1, UUID(), 'alpha-reseller', 'Alpha Reseller', 1, 'active', NOW(), NOW()),
 (2, UUID(), 'beta-reseller', 'Beta Reseller', 2, 'active', NOW(), NOW());

INSERT INTO users (id, role_id, created_by, name, mobile, password, email, company_name, status, reseller_id, account_admin_id, department_id, balance, deleted_at, created_at) VALUES
 (1, 2, NULL, 'Alpha Admin', '9000000001', '$2y$10$x', 'admin@alpha.test', NULL, '1', NULL, NULL, NULL, 100, NULL, '2024-01-01'),
 (2, 2, NULL, 'Beta Admin', '9000000002', '$2y$10$x', 'admin@beta.test', NULL, '1', NULL, NULL, NULL, 50, NULL, '2024-01-01'),
 (3, 1, NULL, 'Super', '9000000003', '$2y$10$x', 'super@old.test', NULL, '1', NULL, NULL, NULL, 0, NULL, '2024-01-01'),
 (10, 3, 1, 'Cust One', '9000000010', '$2y$10$x', 'c1@old.test', 'One Pvt Ltd', '1', 1, NULL, NULL, 10, NULL, '2024-02-01'),
 (11, 3, 1, 'Cust Two', '9000000011', '$2y$10$x', 'c2@old.test', NULL, '1', 1, NULL, NULL, 20, NULL, '2024-02-01'),
 (12, 3, 2, 'Cust Three', '9000000012', '$2y$10$x', 'c3@old.test', 'Three Co', '1', 2, NULL, NULL, 30, NULL, '2024-02-01'),
 (13, 3, NULL, 'Orphan Cust', '9000000013', '$2y$10$x', 'orphan@old.test', NULL, '1', NULL, NULL, NULL, 0, NULL, '2024-02-01'),
 (14, 3, 2, 'Cust One Again', '9000000014', '$2y$10$x', 'C1@old.test', NULL, '1', 2, NULL, NULL, 0, NULL, '2024-02-01'),
 (15, 3, 1, 'Deleted Cust', '9000000015', '$2y$10$x', 'deleted@old.test', NULL, '1', 1, NULL, NULL, 0, '2024-06-01', '2024-02-01'),
 (16, 3, 1, 'Shared Person', '9000000016', '$2y$10$x', 'shared@old.test', NULL, '1', 1, NULL, NULL, 0, NULL, '2024-02-01'),
 (20, 7, 10, 'Agent A1', '9000000020', '$2y$10$x', 'a1@old.test', NULL, '1', 1, 10, 1, 0, NULL, '2024-03-01'),
 (21, 7, 11, 'Agent A2', '9000000021', '$2y$10$x', 'a2@old.test', NULL, '1', 1, 11, 3, 0, NULL, '2024-03-01'),
 (22, 7, 1, 'Agent of admin', '9000000022', '$2y$10$x', 'a3@old.test', NULL, '1', 1, NULL, NULL, 0, NULL, '2024-03-01'),
 (23, 7, 13, 'Agent of orphan', '9000000023', '$2y$10$x', 'a4@old.test', NULL, '1', NULL, 13, NULL, 0, NULL, '2024-03-01');

INSERT INTO departments (id, name, description, created_by, created_at, updated_at, deleted_at) VALUES
 (1, 'Sales', 'Sales team', 10, '2024-03-01', '2024-03-01', NULL),
 (2, 'Sales', 'Duplicate name', 10, '2024-03-02', '2024-03-02', NULL),
 (3, 'Support', NULL, 11, '2024-03-01', '2024-03-01', '2024-05-01'),
 (4, 'Admin dept', NULL, 1, '2024-03-01', '2024-03-01', NULL);

INSERT INTO agent_teams (id, name, description, created_by, is_active, created_at, updated_at, department_id) VALUES
 (1, 'Night Shift', NULL, 10, 1, '2024-03-01', '2024-03-01', 1),
 (2, 'Escalations', NULL, 11, 1, '2024-03-01', '2024-03-01', NULL);
INSERT INTO agent_team_members (id, team_id, agent_id, role, joined_at, created_at, updated_at) VALUES
 (1, 1, 20, 'admin', '2024-03-01', '2024-03-01', '2024-03-01'),
 (2, 1, 21, 'agent', '2024-03-01', '2024-03-01', '2024-03-01'),
 (3, 2, 21, 'agent', '2024-03-01', '2024-03-01', '2024-03-01');

INSERT INTO whatsapp_accounts (id, user_id, created_by, whatsapp_no, whatsapp_no_id, whatsapp_biz_id, parmenent_token, status, created_at, updated_at, deleted_at) VALUES
 (1, 10, 10, '919800000001', 'PNID1', 'BIZ1', 'EAA-token-one', '1', '2024-03-01', '2024-03-01', NULL),
 (2, 11, 11, '919800000002', 'PNID2', 'BIZ2', 'EAA-token-two', '1', '2024-03-01', '2024-04-01', NULL),
 (3, 12, 12, '919800000003', 'PNID3', 'BIZ3', 'EAA-token-three', '0', '2024-03-01', '2024-03-01', NULL),
 (4, 13, 13, '919800000004', 'PNID4', 'BIZ4', 'EAA-token-four', '1', '2024-03-01', '2024-03-01', NULL),
 (5, 10, 10, '919800000005', 'PNID5', 'BIZ1', 'EAA-token-one', '1', '2024-03-02', '2024-03-02', NULL),
 (6, 12, 12, '919800000006', 'PNID6', 'BIZ1', 'EAA-token-x', '1', '2024-03-02', '2024-03-02', NULL),
 (7, 10, 10, '919800000007', 'PNID7', 'BIZ7', NULL, '1', '2024-03-02', '2024-03-02', '2024-04-01');
INSERT INTO waba_accounts (id, user_id, whatsapp_account_id, waba_id, api_key, template_namespace, username, password, created_at) VALUES
 (1, 12, 3, 'BIZ3', 'pinnacle-key', 'ns_three', 'pinuser', 'pinpass', NOW());

INSERT INTO platform_meta_pricing (id, country_code, country_name, currency, market_msg_charge, utility_msg_charge, auth_msg_charge, service_msg_charge) VALUES
 (1, 'IN', 'India', 'INR', 0.7800, 0.1150, 0.1150, 0), (2, '1', NULL, 'USD', 0.0250, 0.0040, 0.0135, 0), (3, 'XX', NULL, 'INR', 1, 1, 1, 0);
INSERT INTO reseller_messages_pricing (id, reseller_id, market_msg_charge_min, market_msg_charge_set, utility_msg_charge_min, utility_msg_charge_set,
  auth_msg_charge_min, auth_msg_charge_set, service_msg_charge_min, service_msg_charge_set, margin_percentage) VALUES
 (1, 1, 0.80, 0.90, 0.12, NULL, 0.12, 0.13, 0, NULL, 10), (2, 2, 0.85, NULL, 0.13, NULL, 0.13, NULL, 0, NULL, 5);
INSERT INTO user_messages_pricing (id, user_id, reseller_id, market_msg_charge, utility_msg_charge, auth_msg_charge, created_at, updated_at) VALUES
 (1, 10, 1, 0.95, 0.15, 0.15, NOW(), NOW()), (2, 12, 2, 0.99, 0.16, 0.16, NOW(), NOW()), (3, 13, 1, 1, 1, 1, NOW(), NOW());

INSERT INTO templates (id, user_id, name, language, status, category, wa_id, payload, response, created_at, updated_at, deleted_at) VALUES
 (9,  10, 'welcome', 'en', 'APPROVED', 'MARKETING', 'META9',  '{"name":"welcome"}', NULL, '2024-01-01', '2024-01-01', NULL),
 (10, 10, 'welcome', 'en', 'APPROVED', 'MARKETING', 'META10', '{"name":"welcome","v":2}', '{"id":"META10"}', '2024-02-01', '2024-02-01', NULL),
 (12, 11, 'promo', 'en_US', 'IN_APPEAL', 'marketing', 'META12', '{}', NULL, '2024-02-01', '2024-02-01', NULL),
 (13, 13, 'orphan_tpl', 'en', 'APPROVED', 'UTILITY', 'META13', NULL, NULL, '2024-02-01', '2024-02-01', NULL),
 (14, 12, 'otp', 'en', 'REJECTED', 'AUTHENTICATION', 'META14', 'not json {', NULL, '2024-02-01', '2024-02-01', NULL),
 (15, 20, 'agent_tpl', 'hi', 'PENDING', 'UTILITY', 'META15', NULL, NULL, '2024-02-01', '2024-02-01', NULL),
 (16, 10, 'old_deleted', 'en', 'APPROVED', 'MARKETING', 'META16', NULL, NULL, '2024-02-01', '2024-02-01', '2024-03-01');
INSERT INTO template_components (id, template_id, type, format, text, image_url) VALUES
 (100, 10, 'HEADER', 'IMAGE', NULL, 'https://cdn.old.test/img/welcome.jpg'),
 (101, 10, 'BODY', NULL, 'Hello {{1}}, welcome!', NULL),
 (102, 10, 'FOOTER', NULL, 'Reply STOP', NULL),
 (103, 10, 'BUTTONS', NULL, NULL, NULL),
 (104, 12, 'BODY', NULL, 'Big sale', NULL),
 (105, 12, 'CAROUSEL', NULL, NULL, NULL),
 (106, 9, 'BODY', NULL, 'Hello old', NULL),
 (107, 14, 'BODY', NULL, '{{1}} is your code', NULL),
 (108, 14, 'weird', NULL, 'x', NULL);
INSERT INTO template_component_buttons (id, template_id, component_id, type, number, text, url) VALUES
 (200, 10, 103, 'QUICK_REPLY', NULL, 'Yes', NULL), (201, 10, 103, 'URL', NULL, 'Visit', 'https://old.test/{{1}}'),
 (202, 10, 103, 'FLOW', NULL, 'Open flow', NULL), (203, 10, 103, 'phone_number', '+919800000001', 'Call us', NULL);
INSERT INTO template_texts (id, component_id, text, default_value, text_index) VALUES
 (300, 101, 'name', 'John', 1), (301, 107, 'code', '123456', 1), (302, 101, 'name dup', 'X', 1);
INSERT INTO template_carousel_cards (id, template_id, component_id, header, body, image_url, media_type, card_index) VALUES
 (400, 12, 105, NULL, 'Card one', 'https://cdn.old.test/c1.jpg', 'image', 0), (401, 12, 105, NULL, 'Card two', 'https://cdn.old.test/c2.mp4', 'video', 1);
INSERT INTO template_carousel_card_buttons (id, card_id, type, text, url) VALUES
 (500, 400, 'QUICK_REPLY', 'More', NULL), (501, 400, 'URL', 'Buy', 'https://old.test/buy'), (502, 401, 'QUICK_REPLY', 'More', NULL);
INSERT INTO template_library (id, user_id, name, language, status, category, sub_category, template_type, wa_id, payload, created_at, updated_at, variable_data) VALUES
 (1, NULL, 'lib_welcome', 'en', 'APPROVED', 'MARKETING', 'ECOMMERCE', 'standard', 'LIB1', '[{"type":"BODY","text":"Hi {{1}}"}]', NOW(), NOW(), '["name"]'),
 (2, 10, 'my_copy', 'en', 'APPROVED', 'UTILITY', 'SERVICE', 'standard', 'LIB2', '[]', NOW(), NOW(), NULL);

INSERT INTO chat_contacts (id, user_id, name, mobile, country_id, email, is_blocked, allowed_broadcast, created_at, updated_at, deleted_at) VALUES
 (1, 10, 'Alice', '9876543210', '91', 'alice@x.test', 0, 1, '2024-04-01', '2024-04-01', NULL),
 (2, 11, 'Alice (C2)', '+91 98765 43210', '91', NULL, 0, 1, '2024-04-01', '2024-04-01', NULL),
 (3, 10, 'Bob', '919876543211', '91', 'not-an-email', 1, 1, '2024-04-01', '2024-04-01', NULL),
 (4, 10, 'Carol', '09876543212', '91', NULL, 0, 0, '2024-04-01', '2024-04-01', NULL),
 (5, 10, 'Broken', '12', '91', NULL, 0, 1, '2024-04-01', '2024-04-01', NULL),
 (6, 12, 'Alice (R2)', '9876543210', '91', NULL, 0, 1, '2024-04-01', '2024-04-01', NULL),
 (7, 13, 'Orphan contact', '9876543299', '91', NULL, 0, 1, '2024-04-01', '2024-04-01', NULL),
 (8, 10, 'Deleted', '9876543298', '91', NULL, 0, 1, '2024-04-01', '2024-04-01', '2024-05-01'),
 (9, 20, 'Agent contact', '9876500000', '91', NULL, 0, 1, '2024-04-01', '2024-04-01', NULL),
 (10, 10, 'Alice dup', '98765-43210', '91', NULL, 0, 1, '2024-04-02', '2024-04-02', NULL),
 (11, 10, 'US person', '+1 415 555 2671', '1', NULL, 0, 1, '2024-04-02', '2024-04-02', NULL),
 (12, NULL, 'No owner', '9876543297', '91', NULL, 0, 1, '2024-04-02', '2024-04-02', NULL);
INSERT INTO blacklists (id, user_id, mobile, country_id, is_blocked, created_at, updated_at) VALUES (1, 10, '919876543210', 91, '1', NOW(), NOW());

INSERT INTO tags (id, user_id, name, tag_color, status, deleted_at) VALUES
 (1, 10, 'VIP', '#ff0000', 'active', NULL), (2, 11, 'VIP', 'a-very-long-color-name', 'active', NULL), (3, 10, 'Gone', '#000', 'active', '2024-05-01');
INSERT INTO tag_contacts (id, tag_id, contact_id, created_at) VALUES (1, 1, 1, NOW()), (2, 1, 10, NOW()), (3, 1, 2, NOW()), (4, 2, 2, NOW()), (5, 3, 1, NOW());
INSERT INTO `groups` (id, user_id, name, type, status) VALUES (1, 10, 'Leads', 'manual', '1'), (2, 12, 'R2 list', NULL, '0');
INSERT INTO group_members (id, group_id, contact_id) VALUES (1, 1, 1), (2, 1, 3), (3, 2, 6), (4, 1, 999);
INSERT INTO attribute_masters (id, user_id, attribute, created_at, updated_at) VALUES (1, 10, 'City', NOW(), NOW());
INSERT INTO contact_attributes (id, contact_id, attribute, attribute_value, created_at, updated_at) VALUES
 (1, 1, 'City', 'Pune', NOW(), NOW()), (2, 1, 'City', 'Mumbai', NOW(), NOW()), (3, 3, 'Company Name', 'Acme', NOW(), NOW());
INSERT INTO chat_contact_notes (id, user_id, chat_contact_id, note, created_at, updated_at) VALUES (1, 20, 1, 'Called, interested', NOW(), NOW());
INSERT INTO live_chat_settings (id, user_id, auto_resolve_enabled, timezone, working_hours, created_at, updated_at) VALUES
 (1, 10, 1, 'Asia/Kolkata', '{"monday":{"enabled":true,"start":"09:00","end":"18:00"},"tuesday":{"enabled":true,"start":"09:00","end":"18:00"},"sunday":{"enabled":false}}', NOW(), NOW()),
 (2, 11, 0, NULL, '[{"day":"monday","isOpen":true,"from":"09:00","to":"18:00"},{"day":"tuesday","isOpen":true,"from":"10:00","to":"17:00"}]', NOW(), NOW());

INSERT INTO chats (id, user_id, contact_id, send_from, send_to, send_from_id, send_to_id, text, type, method, status, message_id, created_at, updated_at, agent_id, image_id, reply_message_id, payload) VALUES
 (1, 10, 1, '919800000001', '919876543210', 'PNID1', NULL, 'Hi Alice', 'send', 'text', 'read', 'wamid.1', '2025-01-01 10:00:00', '2025-01-01 10:05:00', NULL, NULL, NULL, '{"x":1}'),
 (2, 10, 1, '919876543210', '919800000001', NULL, 'PNID1', 'Hello back', 'receive', 'text', 'received', 'wamid.2', '2025-01-01 10:01:00', '2025-01-01 10:01:00', NULL, NULL, 'wamid.1', NULL),
 (3, 10, 1, '919876543210', '919800000001', NULL, 'PNID1', 'Hello back', 'receive', 'text', 'received', 'wamid.2', '2025-01-01 10:01:00', '2025-01-01 10:01:00', NULL, NULL, NULL, NULL),
 (4, 10, NULL, '919800000001', '919876543212', 'PNID1', NULL, 'Hi Carol', 'send', 'text', 'delivered', 'wamid.4', '2025-01-02 10:00:00', '2025-01-02 10:00:00', NULL, NULL, NULL, 'bad json'),
 (5, 10, 1, '919800000005', '919876543210', 'PNID5', NULL, NULL, 'send', 'image', 'sent', 'wamid.5', '2025-01-03 10:00:00', '2025-01-03 10:00:00', 20, 'MEDIA123', NULL, '{"type":"image","image":{"link":"https://cdn.old.test/img/welcome.jpg"}}'),
 (6, 12, 6, '919800000003', '919876543210', 'PNID3', NULL, REPEAT('x', 5000), 'send', 'template', 'delivered', 'wamid.6', '2025-01-04 10:00:00', '2025-01-04 10:00:00', NULL, NULL, NULL, NULL),
 (7, 10, 1, '911111111111', '912222222222', NULL, NULL, 'weird', 'weird', 'text', 'sent', 'wamid.7', '2025-01-05 10:00:00', '2025-01-05 10:00:00', NULL, NULL, NULL, NULL),
 (8, 20, 9, '919800000001', '919876500000', 'PNID1', NULL, 'From agent', 'send', 'sticker_pack', 'pending', 'wamid.8', '2025-01-06 10:00:00', '2025-01-06 10:00:00', 20, NULL, NULL, NULL),
 (9, 13, 7, '919800000004', '919876543299', 'PNID4', NULL, 'orphan', 'send', 'text', 'sent', 'wamid.9', '2025-01-06 10:00:00', '2025-01-06 10:00:00', NULL, NULL, NULL, NULL);
INSERT INTO agent_assignments (id, contact_id, agent_id, assignment_type, assigned_by, assigned_at, status, created_at, updated_at) VALUES
 (1, 1, 20, 'self', NULL, '2025-01-01 10:02:00', 'active', NOW(), NOW()),
 (2, 9, 20, 'reassigned', 10, '2025-01-06 10:01:00', 'transferred', NOW(), NOW()),
 (3, 3, 20, 'bot', NULL, '2025-01-06 10:01:00', 'active', NOW(), NOW());
INSERT INTO canned_messages (id, project_id, user_id, title, keyword, created_at, updated_at, message_sets) VALUES
 (1, NULL, 10, 'Hello', 'hi', NOW(), NOW(), '[{"type":"text","text":"Hi there"},{"type":"image","url":"https://x/y.png"}]'),
 (2, NULL, 10, 'Hello again', 'hi', NOW(), NOW(), '[{"type":"text","text":"Hey!"}]'),
 (3, NULL, 11, 'No keyword reply', NULL, NOW(), NOW(), '"plain"');
INSERT INTO broadcasts (id, user_id, template_id, whatsapp, country_id, campname, is_media, data, total, schedule_at, status, created_at, updated_at) VALUES
 (1, 10, 10, 1, 91, 'Jan promo', '0', '{"template":{"name":"welcome"}}', 4, NULL, '1', '2025-11-01', '2025-11-01'),
 (2, 12, 14, NULL, 91, 'Future OTP', '0', NULL, 1, '2030-01-01', '1', '2025-11-01', '2025-11-01'),
 (3, 10, 16, 1, 91, 'Deleted template', '0', NULL, 1, NULL, '1', '2025-11-01', '2025-11-01');
INSERT INTO reports (id, user_id, broadcast_id, mobile, message_id, status, platform, created_at, updated_at) VALUES
 (1, 10, 1, '919876543210', 'wamid.r1', 'delivered', 'web', '2025-11-01', '2025-11-01'),
 (2, 10, 1, '919876543212', 'wamid.4', 'read', 'web', '2025-11-01', '2025-11-01'),
 (3, 10, 1, '919876543999', 'wamid.r3', 'sent', 'web', '2025-11-01', '2025-11-01'),
 (4, 10, 1, '9876543210', 'wamid.r4', 'failed', 'web', '2025-11-01', '2025-11-01'),
 (5, 12, 2, '919876543210', NULL, 'pending', 'web', '2025-11-01', '2025-11-01'),
 (6, 10, 1, '919876543211', NULL, 'weird', 'web', '2024-01-01', '2024-01-01'),
 (7, 10, NULL, '919876543211', NULL, 'sent', 'api', '2025-11-01', '2025-11-01');
INSERT INTO media_uploads (id, user_id, original_name, filename, file_url, mime_type, size_bytes, uploaded_at) VALUES
 (1, 10, 'logo.png', 'logo.png', 'https://cdn.old.test/uploads/logo.png', 'image/png', 4, NOW());
SET FOREIGN_KEY_CHECKS = 1;
