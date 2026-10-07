# Local end-to-end test (what was run before delivery)

1. MySQL 8.0 with the server's DDL: load `server_old_aigreentick_2nd.sql` and `server_new_apargo_wa_messaging.sql`
   (project docs `migration/schema/`), both without data, then the WABA schema of 2026-10-07
   (`waba_v2_2026-10-07.sql` in this folder: business_managers, project_refs, pinacle_*; drops project_waba_assignments).
2. `mysql < seed_new_pre.sql`  — master seed: roles, contact_statuses, one seed org, two seed users.
3. `mysql < seed_old.sql`      — old data with every hard case (see comments in the file).
4. Run the other developer's Phase 1 and Phase 2 Node scripts against these two schemas.
5. Optional (S1, data already in the new DB): insert a contact `+919876543211` into the organization of reseller 1.
6. `java -jar target/migration-service.jar --migration.baseline.enforce=false --migration.dry-run=true`
   then without `--migration.dry-run`, then once more (second run must insert 0).

Expected: all 17 checks of `99-validate` pass. The ERROR rows in `mig_errors` are the cases the seed contains on purpose:
NO_RESELLER, WABA_OTHER_PROJECT (BIZ1 used by customers 10 and 12), INVALID_PHONE, DIRECTION_UNKNOWN, TEMPLATE_MISSING, NOT_PROJECT_MEMBER,
COUNTRY_UNKNOWN, USER_UNKNOWN and RESELLER_ADMIN_NO_PROJECT.

Expected in step 05: 3 Business Managers (`legacy-<uuid>`), 3 ACTIVE tokens, 3 WABAs (each the default of its project),
4 phone numbers, 1 Pinnacle credential + billing config (WABA BIZ3, `onboarding_provider = PINNACLE`).
