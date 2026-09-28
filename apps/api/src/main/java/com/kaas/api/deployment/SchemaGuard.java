package com.kaas.api.deployment;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.ValidateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Refuses to start the application against a schema it was not built for, without ever changing that schema.
 *
 * <p>Active exactly when startup migration is off -- the production profile. There, schema mutation belongs to
 * the migrator (a separate command, a separate credential), and the application's own role cannot run DDL at all.
 * What the application can still do is READ Flyway's history and notice that it is ahead of the database: a
 * pending migration means the migrator did not run, or ran an older build. Starting anyway would serve requests
 * against a schema missing whatever the pending migration adds, so it fails here, loudly, before readiness.
 *
 * <p>Validation catches the other direction too: an applied migration whose checksum differs from this build's
 * file means the two disagree about history, which is not something to discover mid-request.
 */
@Component
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "false")
public class SchemaGuard {
    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaGuard.class);

    public SchemaGuard(DataSource dataSource) {
        Flyway flyway = flywayFor(dataSource);
        int pending = flyway.info().pending().length;
        if (pending > 0) {
            LOGGER.atError()
                    .addKeyValue("event", "SCHEMA_NOT_CURRENT")
                    .addKeyValue("pendingMigrations", pending)
                    .log("The database schema is behind this build; run the migrator before the application");
            throw new IllegalStateException("SCHEMA_NOT_CURRENT: " + pending + " migration(s) pending");
        }
        ValidateResult validation = flyway.validateWithResult();
        if (!validation.validationSuccessful) {
            LOGGER.atError()
                    .addKeyValue("event", "SCHEMA_HISTORY_MISMATCH")
                    .log("The database's migration history does not match this build's migrations");
            throw new IllegalStateException("SCHEMA_HISTORY_MISMATCH");
        }
        LOGGER.atInfo().addKeyValue("event", "SCHEMA_CURRENT").log("The database schema matches this build");
    }

    /**
     * Flyway configured exactly as the migrator configures it, so "pending" means the same thing to both.
     * Read-only use: {@code info} and {@code validate} create nothing, and {@code clean} is disabled regardless.
     */
    static Flyway flywayFor(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(DatabaseMigrator.LOCATION)
                .cleanDisabled(true)
                .load();
    }
}
