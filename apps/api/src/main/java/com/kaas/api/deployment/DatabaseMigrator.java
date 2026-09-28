package com.kaas.api.deployment;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * The migrate-only entrypoint: applies pending Flyway migrations and exits.
 *
 * <p>It is a plain {@code main}, not a Spring application. It starts no web server, no scheduler, no consumer,
 * no relay and no security chain -- it has no application to start. It needs the migration scripts on its
 * classpath and a database credential, and it is shipped inside the API image so the two can never be built
 * from different sources.
 *
 * <p>Its credential is the MIGRATOR's ({@code KAAS_MIGRATOR_DATABASE_*}), which is the only role holding DDL
 * authority. The application runs as a different role that cannot migrate (see
 * {@code infrastructure/database/roles.sql}), and in production it does not try to: it only checks that the
 * schema is current ({@link SchemaGuard}).
 *
 * <h2>Contract</h2>
 *
 * <pre>
 *   exit 0  schema current (migrations applied now, or none pending); prints migration=CURRENT
 *   exit 1  a migration failed or the database refused; prints migration=FAILED
 *   exit 2  configuration missing or unreadable; prints migration=CONFIGURATION_INVALID
 * </pre>
 *
 * Output names versions and counts. It never prints a URL, a user name or a password.
 */
public final class DatabaseMigrator {
    static final String LOCATION = "classpath:db/migration";

    private DatabaseMigrator() {}

    public static void main(String[] arguments) {
        silenceLibraryLogging();
        System.exit(run(System.getenv(), System.out));
    }

    /**
     * Flyway logs at INFO to standard output, and its first line names the database by JDBC URL -- which can carry
     * credentials in its query string, and which this command's contract says it never prints. The contract lines
     * below are the output; the library's own narration is switched off. Found by running the shipped image.
     */
    static void silenceLibraryLogging() {
        if (org.slf4j.LoggerFactory.getILoggerFactory() instanceof ch.qos.logback.classic.LoggerContext context) {
            context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(ch.qos.logback.classic.Level.OFF);
        }
    }

    static int run(Map<String, String> environment, PrintStream out) {
        String url = first(environment, "KAAS_MIGRATOR_DATABASE_URL", "KAAS_DATABASE_URL");
        String user = environment.get("KAAS_MIGRATOR_DATABASE_USERNAME");
        String password;
        try {
            password = secret(environment, "KAAS_MIGRATOR_DATABASE_PASSWORD");
        } catch (IOException unreadable) {
            out.println("migration=CONFIGURATION_INVALID reason=PASSWORD_FILE_UNREADABLE");
            return 2;
        }
        if (blank(url) || blank(user) || password == null) {
            out.println("migration=CONFIGURATION_INVALID reason=MIGRATOR_CREDENTIAL_ABSENT");
            return 2;
        }
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(url, user, password)
                    .locations(LOCATION)
                    .cleanDisabled(true)
                    .load();
            MigrateResult result = flyway.migrate();
            String version = flyway.info().current() == null ? "none" : flyway.info().current().getVersion().getVersion();
            if (!result.success) {
                out.println("migration=FAILED version=" + version);
                return 1;
            }
            out.println("migration=CURRENT applied=" + result.migrationsExecuted + " version=" + version);
            return 0;
        } catch (RuntimeException failed) {
            // The type and, when the database gave one, its SQLSTATE -- 42501 is "the role may not do this". Never the
            // message: a Flyway exception can quote the JDBC URL or a failing statement, and this output ends up in a
            // deployment log.
            out.println("migration=FAILED error=" + failed.getClass().getSimpleName() + sqlState(failed));
            return 1;
        }
    }

    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().matches("[0-9A-Z]{5}")) {
                return " sqlState=" + sql.getSQLState();
            }
        }
        return "";
    }

    /** {@code NAME}, or the contents of the file named by {@code NAME_FILE}, trailing newline removed. */
    static String secret(Map<String, String> environment, String name) throws IOException {
        String file = environment.get(name + "_FILE");
        if (!blank(file)) {
            return Files.readString(Path.of(file), StandardCharsets.UTF_8).stripTrailing();
        }
        String value = environment.get(name);
        return blank(value) ? null : value;
    }

    private static String first(Map<String, String> environment, String preferred, String fallback) {
        String value = environment.get(preferred);
        return blank(value) ? environment.get(fallback) : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
