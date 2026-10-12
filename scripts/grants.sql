-- Run as an admin on the MySQL server that holds all three schemas.
CREATE USER IF NOT EXISTS 'migration'@'%' IDENTIFIED BY 'change-me';
GRANT ALL ON aigreentick_migration.* TO 'migration'@'%';          -- tracking schema (Flyway creates it)
GRANT SELECT ON aigreentick_2nd.* TO 'migration'@'%';              -- old monolith: read only
GRANT SELECT, INSERT, UPDATE ON apargo_wa_messaging.* TO 'migration'@'%';   -- new services: no DROP / ALTER / DELETE
FLUSH PRIVILEGES;
