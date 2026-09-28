-- KaaS database roles: one that migrates, one that runs the application.
--
-- Run ONCE per database by its owner (or a superuser), before the first migration, with psql variables for the
-- two passwords -- they are never written into this file or into the repository:
--
--   psql "<admin connection>" -v ON_ERROR_STOP=1 \
--        -v migrator_password="$KAAS_MIGRATOR_DATABASE_PASSWORD" \
--        -v app_password="$KAAS_DATABASE_PASSWORD" \
--        -f infrastructure/database/roles.sql
--
-- kaas_migrator OWNS the schema and everything the migrations create in it: it is the only role with DDL
-- authority. kaas_app can read and write rows and nothing else -- it cannot create, alter or drop an object,
-- cannot truncate, and cannot write Flyway's history, so the application process cannot migrate even if its
-- configuration told it to. In production it does not try: it checks the schema is current and refuses to start
-- otherwise.

CREATE ROLE kaas_migrator LOGIN PASSWORD :'migrator_password';
CREATE ROLE kaas_app LOGIN PASSWORD :'app_password';

-- The schema belongs to the migrator. Nobody else may create objects in it.
ALTER SCHEMA public OWNER TO kaas_migrator;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO kaas_app;

-- Row access to every table and sequence the migrator creates from now on. Deliberately not TRUNCATE,
-- REFERENCES or TRIGGER: none is a row operation.
ALTER DEFAULT PRIVILEGES FOR ROLE kaas_migrator IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO kaas_app;
ALTER DEFAULT PRIVILEGES FOR ROLE kaas_migrator IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO kaas_app;

-- Flyway's own history table also receives those defaults when the first migration creates it. The migration
-- callback db/migration/afterMigrate.sql takes write access to it back from kaas_app after every migration run,
-- leaving SELECT so the application can check that the schema is current.
