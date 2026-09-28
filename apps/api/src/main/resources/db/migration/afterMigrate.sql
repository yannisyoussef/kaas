-- Flyway callback, run after every successful migrate (KAAS-DEPLOY-001).
--
-- The application role reads Flyway's history -- that is how it checks the schema is current before it starts --
-- and must never write it: a role that can edit migration history can make a pending migration look applied.
-- The default privileges in infrastructure/database/roles.sql grant row access to every table the migrator
-- creates, which includes this one, so it is taken back here. A no-op where no kaas_app role exists (local
-- development and the test suites run as one role).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'kaas_app') THEN
        EXECUTE format('REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON %I.flyway_schema_history FROM kaas_app',
                       current_schema());
        EXECUTE format('GRANT SELECT ON %I.flyway_schema_history TO kaas_app', current_schema());
    END IF;
END;
$$;
