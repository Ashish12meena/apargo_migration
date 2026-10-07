-- Run on the server (146.88.24.113) and send back the output.
-- Answers every seed / reference-data question of docs/seed_data_and_assumptions.md (S-1 .. S-10).
USE apargo_wa_messaging;

-- S-1 roles the auth steps look up by (slug, scope, organization_id NULL, project_id NULL)
SELECT id, slug, scope, name, organization_id, project_id, is_default, status FROM roles ORDER BY scope, slug;
-- which role do Phase 1 memberships actually use?
SELECT 'organization_users' t, role_id, COUNT(*) n FROM organization_users GROUP BY role_id
UNION ALL SELECT 'project_members', role_id, COUNT(*) FROM project_members GROUP BY role_id;

-- S-2 contact statuses (step 08b needs ACTIVE; INVALID for bad numbers)
SELECT id, code, name, is_system FROM contact_statuses ORDER BY id;

-- S-3 contact sources: what keys does the contact service create, per organization or global?
SELECT source_key, display_name, is_system, COUNT(*) orgs, MIN(organization_id), MAX(organization_id)
FROM contact_sources GROUP BY source_key, display_name, is_system ORDER BY orgs DESC;
SELECT s.source_key, COUNT(c.id) contacts FROM contacts c LEFT JOIN contact_sources s ON s.id = c.source_id GROUP BY s.source_key;

-- S-4 project team roles: does the org service seed them per project? which slugs?
SELECT slug, name, team_id IS NULL project_level, is_default, is_system, COUNT(*) projects
FROM project_team_roles GROUP BY slug, name, team_id IS NULL, is_default, is_system;

-- S-5 fallback organization for customers without reseller (D1 = ASSIGN uses id 1 by default)
SELECT id, slug, name, organization_type, owner_user_id, status FROM organizations ORDER BY id LIMIT 20;

-- S-6 platform pricing already present? (step 04a never overwrites existing countries)
SELECT COUNT(*) countries, SUM(country_code = 'IN') has_in, MIN(created_at), MAX(updated_at) FROM meta_messaging_charges;
SELECT country_code, currency, marketing_charge, utility_charge, authentication_charge, service_charge
FROM meta_messaging_charges WHERE country_code IN ('IN', 'US', 'AE', 'GB');

-- S-7 WABA tokens: token_type and format used by the WABA service today
SELECT token_type, COUNT(*), MAX(CHAR_LENGTH(access_token)) max_len, SUM(access_token LIKE 'EAA%') plain_eaa FROM meta_oauth_tokens GROUP BY token_type;
SELECT onboarding_provider, status, COUNT(*) FROM waba_accounts GROUP BY 1, 2;

-- S-8 contact-service user columns: user id as text, or uuid? (Q-S9)
SELECT created_by, updated_by FROM contacts WHERE created_by IS NOT NULL LIMIT 5;
SELECT assignee_id FROM project_contacts WHERE assignee_id IS NOT NULL LIMIT 5;
SELECT created_by FROM contact_tags WHERE created_by IS NOT NULL LIMIT 5;

-- S-9 phone format of existing contacts (+91… or 91…)
SELECT LEFT(normalized_phone, 1) first_char, COUNT(*) FROM contacts GROUP BY 1;
SELECT inbound_policy, COUNT(*) FROM contacts GROUP BY 1;

-- S-10 values the messaging service writes (to copy its conventions)
SELECT direction, created_by_type, COUNT(*) FROM messages GROUP BY 1, 2;
SELECT audience_type, status, COUNT(*) FROM broadcast_campaigns GROUP BY 1, 2;
SELECT opened_reason, status, COUNT(*) FROM conversation_sessions GROUP BY 1, 2;
SELECT storage_provider, status, COUNT(*) FROM media GROUP BY 1, 2;

-- ---------------------------------------------------------------- old DB value sets (enum maps in application.yml)
USE aigreentick_2nd;
SELECT type, method, COUNT(*) FROM chats GROUP BY 1, 2 ORDER BY 3 DESC;           -- chat-direction / chat-type
SELECT status, COUNT(*) FROM chats GROUP BY 1 ORDER BY 2 DESC;                     -- chat-status
SELECT status, COUNT(*) FROM reports GROUP BY 1 ORDER BY 2 DESC;                   -- report-status
SELECT status, category, COUNT(*) FROM templates GROUP BY 1, 2 ORDER BY 3 DESC;    -- template status / category
SELECT type, COUNT(*) FROM template_component_buttons GROUP BY 1;                  -- button types
SELECT country_id, LEFT(mobile, 3) p, LENGTH(mobile) len, COUNT(*) FROM chat_contacts GROUP BY 1, 2, 3 ORDER BY 4 DESC LIMIT 20;  -- phone format
SELECT DISTINCT country_id FROM blacklists LIMIT 20;                               -- dial code or countries.id?
SELECT status, COUNT(*) FROM `groups` GROUP BY 1;                                  -- '0','1','2' meaning
SELECT @@global.time_zone, @@session.time_zone, NOW(), UTC_TIMESTAMP();            -- old DATETIME columns written in UTC or IST?
SELECT id, created_at, updated_at FROM departments ORDER BY id DESC LIMIT 3;
