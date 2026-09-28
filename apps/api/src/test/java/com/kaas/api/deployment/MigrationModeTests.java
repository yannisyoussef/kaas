package com.kaas.api.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaas.api.KaasApiApplication;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The migrate-only entrypoint, the role split, and the production rule that the application never migrates.
 *
 * <p>Against a real PostgreSQL, with the roles created by the same {@code infrastructure/database/roles.sql}
 * Operations runs. The container's own superuser plays the database owner that runs that script once; everything
 * after it happens as {@code kaas_migrator} or {@code kaas_app}, exactly as it would in a deployment.
 */
@Testcontainers
class MigrationModeTests {
    private static final String MIGRATOR_PASSWORD = "migrator-test-only";
    private static final String APP_PASSWORD = "app-test-only";

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16.10-alpine").withDatabaseName("kaas-migration");

    @BeforeAll
    static void createRolesTheWayOperationsDoes() throws Exception {
        String script = Files.readString(repositoryRoot().resolve("infrastructure/database/roles.sql"))
                // psql substitutes these; JDBC does not. The substitution is the only difference.
                .replace(":'migrator_password'", "'" + MIGRATOR_PASSWORD + "'")
                .replace(":'app_password'", "'" + APP_PASSWORD + "'");
        try (Connection admin = connect(POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = admin.createStatement()) {
            statement.execute(script);
        }
    }

    @Test
    void theMigratorAppliesEveryMigrationAndThenReportsTheSchemaCurrent() {
        Result first = migrate(migratorEnvironment());
        assertThat(first.exitCode()).isZero();
        assertThat(first.output()).startsWith("migration=CURRENT").contains("version=");

        // Idempotent: a second run is the ordinary "nothing pending" case Operations hits on every deploy.
        Result second = migrate(migratorEnvironment());
        assertThat(second.exitCode()).isZero();
        assertThat(second.output()).contains("migration=CURRENT applied=0");
    }

    @Test
    void theMigratorIsFlywayAndNothingElse() {
        // Structural, because the property is about what the migrator CAN start: a web server, a scheduler, the
        // consumer or the relay would each be the whole application behind a flag. Nothing reachable from it may
        // name Spring's application bootstrap or the API's own entry point.
        com.tngtech.archunit.core.domain.JavaClasses classes = new com.tngtech.archunit.core.importer.ClassFileImporter()
                .importPackages("com.kaas.api.deployment");
        com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses()
                .that().haveSimpleName("DatabaseMigrator")
                .should().dependOnClassesThat().haveFullyQualifiedName("org.springframework.boot.SpringApplication")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("com.kaas.api.KaasApiApplication")
                .orShould().dependOnClassesThat().resideInAPackage("org.springframework.context..")
                .check(classes);
    }

    @Test
    void theMigratorFailsNonZeroAndSaysNothingItShouldNot() {
        Map<String, String> wrong = migratorEnvironment();
        wrong.put("KAAS_MIGRATOR_DATABASE_PASSWORD", "definitely-not-the-password");
        Result refused = migrate(wrong);
        assertThat(refused.exitCode()).isEqualTo(1);
        assertThat(refused.output()).startsWith("migration=FAILED")
                .doesNotContain("definitely-not-the-password")
                .doesNotContain(POSTGRES.getJdbcUrl())
                .doesNotContain("kaas_migrator");

        Map<String, String> absent = new HashMap<>(migratorEnvironment());
        absent.remove("KAAS_MIGRATOR_DATABASE_USERNAME");
        Result unconfigured = migrate(absent);
        assertThat(unconfigured.exitCode()).isEqualTo(2);
        assertThat(unconfigured.output()).startsWith("migration=CONFIGURATION_INVALID");
    }

    @Test
    void theMigratorReadsItsPasswordFromAFileSoItNeedNotSitInTheEnvironment() throws Exception {
        Path file = Files.createTempFile("kaas-migrator", ".password");
        Files.writeString(file, MIGRATOR_PASSWORD + "\n");
        Map<String, String> environment = migratorEnvironment();
        environment.remove("KAAS_MIGRATOR_DATABASE_PASSWORD");
        environment.put("KAAS_MIGRATOR_DATABASE_PASSWORD_FILE", file.toString());

        assertThat(migrate(environment).exitCode()).isZero();
    }

    @Test
    void theApplicationRoleCanUseRowsButCannotChangeTheSchemaOrItsHistory() throws Exception {
        assertThat(migrate(migratorEnvironment()).exitCode()).isZero();
        try (Connection app = connect("kaas_app", APP_PASSWORD); Statement statement = app.createStatement()) {
            // Row access works: this is the role the application runs as.
            statement.executeQuery("select count(*) from test_runs").close();
            statement.executeUpdate("insert into worker_presence (worker_id, first_seen_at, last_seen_at)"
                    + " values ('kaas.worker.role-test', now(), now()) on conflict do nothing");

            // DDL does not.
            assertPermissionDenied(statement, "create table kaas_app_was_here (id int)");
            assertPermissionDenied(statement, "alter table test_runs add column kaas_app_was_here int");
            assertPermissionDenied(statement, "drop table worker_presence");
            assertPermissionDenied(statement, "truncate worker_presence");
            // Nor may it make a pending migration look applied.
            statement.executeQuery("select count(*) from flyway_schema_history").close();
            assertPermissionDenied(statement, "delete from flyway_schema_history");
            assertPermissionDenied(statement,
                    "update flyway_schema_history set checksum = 0 where installed_rank = 1");
        }
    }

    @Test
    void inProductionTheApplicationDoesNotMigrateAndRefusesASchemaThatIsBehind() throws Exception {
        // A database nobody migrated. The production application, even with credentials that could migrate, must
        // not: it refuses to start and leaves the database exactly as empty as it found it.
        try (Connection admin = connect(POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = admin.createStatement()) {
            statement.execute("create database kaas_unmigrated owner kaas_migrator");
        }
        String unmigrated = POSTGRES.getJdbcUrl().replace("/kaas-migration", "/kaas_unmigrated");
        assertThatThrownBy(() -> production(unmigrated, "kaas_migrator", MIGRATOR_PASSWORD).close())
                .hasStackTraceContaining("SCHEMA_NOT_CURRENT");
        try (Connection probe = DriverManager.getConnection(unmigrated, "kaas_migrator", MIGRATOR_PASSWORD);
                var tables = probe.createStatement().executeQuery(
                        "select count(*) from information_schema.tables where table_schema = 'public'")) {
            tables.next();
            assertThat(tables.getInt(1)).as("the production application created nothing").isZero();
        }

        // Migrated by the migrator, the application starts -- as the role that cannot migrate -- and changes
        // nothing about the schema's history.
        assertThat(migrate(migratorEnvironment()).exitCode()).isZero();
        int historyBefore = historyRows();
        try (ConfigurableApplicationContext context = production(POSTGRES.getJdbcUrl(), "kaas_app", APP_PASSWORD)) {
            assertThat(context.isRunning()).isTrue();
            assertThat(context.getEnvironment().getProperty("spring.flyway.enabled")).isEqualTo("false");
            assertThat(context.getBean(SchemaGuard.class)).isNotNull();
        }
        assertThat(historyRows()).isEqualTo(historyBefore);
    }

    @Test
    void theProductionManagementPortServesHealthDeploymentAndMetricsAndNothingElse() throws Exception {
        assertThat(migrate(migratorEnvironment()).exitCode()).isZero();
        try (ConfigurableApplicationContext context = production(POSTGRES.getJdbcUrl(), "kaas_app", APP_PASSWORD)) {
            int management = Integer.parseInt(context.getEnvironment().getProperty("local.management.port"));
            int server = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
            assertThat(management).isNotEqualTo(server);
            var http = java.net.http.HttpClient.newHttpClient();
            java.util.function.Function<String, java.net.http.HttpResponse<String>> get = url -> {
                try {
                    return http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).GET().build(),
                            java.net.http.HttpResponse.BodyHandlers.ofString());
                } catch (Exception failed) {
                    throw new IllegalStateException(failed);
                }
            };
            String base = "http://127.0.0.1:" + management;
            var readiness = get.apply(base + "/actuator/health/readiness");
            assertThat(readiness.statusCode()).isIn(200, 503);
            var deployment = get.apply(base + "/actuator/deployment");
            assertThat(deployment.statusCode()).as(deployment.body()).isEqualTo(200);
            assertThat(deployment.body()).contains("\"status\":\"NOT_READY\"").contains("\"schema\":\"CURRENT\"")
                    .contains("\"runnersWithCurrentEvidence\":0");
            var metrics = get.apply(base + "/actuator/prometheus");
            assertThat(metrics.statusCode()).isEqualTo(200);
            assertThat(metrics.body()).contains("jvm_");
            // Health shows no details, and nothing but these three is exposed.
            assertThat(get.apply(base + "/actuator/health").body()).doesNotContain("details");
            assertThat(get.apply(base + "/actuator/env").statusCode()).isIn(401, 403, 404);
            assertThat(get.apply(base + "/actuator/beans").statusCode()).isIn(401, 403, 404);
            // And the public port does not serve them at all.
            assertThat(get.apply("http://127.0.0.1:" + server + "/actuator/deployment").statusCode())
                    .isIn(401, 403, 404);
        }
    }

    /** The real application under the production profile, with only what a test cannot provide overridden. */
    private static ConfigurableApplicationContext production(String url, String user, String password) {
        // Command-line arguments rather than builder properties: those are DEFAULTS, and application.properties
        // outranks them -- which silently pointed the first version of this test at the wrong credentials.
        return new SpringApplicationBuilder(KaasApiApplication.class)
                .profiles("production")
                .web(WebApplicationType.SERVLET)
                .run(
                        "--spring.datasource.url=" + url,
                        "--spring.datasource.username=" + user,
                        "--spring.datasource.password=" + password,
                        "--server.port=0",
                        "--management.server.port=0",
                        "--management.server.address=127.0.0.1",
                        // No broker in this test. Production turns the consumer on; that it is on is asserted by
                        // reading the profile, in its own test, not by standing up RabbitMQ here.
                        "--kaas.consumer.enabled=false",
                        "--kaas.outbox.relay.enabled=false",
                        "--kaas.scheduling.auto.enabled=false",
                        "--kaas.reaping.auto.enabled=false",
                        "--kaas.claim.reconcile.enabled=false",
                        "--kaas.execution.reconcile.enabled=false");
    }

    @Test
    void theProductionProfileTurnsMigrationOffAndCannotBeTalkedOutOfIt() throws Exception {
        String profile = Files.readString(
                repositoryRoot().resolve("apps/api/src/main/resources/application-production.properties"));
        // A literal, not a placeholder: no environment variable may turn startup migration back on in production.
        assertThat(profile).containsPattern("(?m)^spring\\.flyway\\.enabled=false$");
        assertThat(profile).containsPattern("(?m)^kaas\\.consumer\\.enabled=\\$\\{KAAS_CONSUMER_ENABLED:true}$");
    }

    private static int historyRows() throws SQLException {
        try (Connection admin = connect(POSTGRES.getUsername(), POSTGRES.getPassword());
                var rows = admin.createStatement().executeQuery("select count(*) from flyway_schema_history")) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static void assertPermissionDenied(Statement statement, String sql) {
        assertThatThrownBy(() -> statement.execute(sql))
                .as(sql)
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> assertThat(((SQLException) failure).getSQLState())
                        // 42501 insufficient_privilege; 42P01 would mean the test targeted nothing.
                        .isIn("42501", "42809"));
    }

    private static Map<String, String> migratorEnvironment() {
        Map<String, String> environment = new HashMap<>();
        environment.put("KAAS_MIGRATOR_DATABASE_URL", POSTGRES.getJdbcUrl());
        environment.put("KAAS_MIGRATOR_DATABASE_USERNAME", "kaas_migrator");
        environment.put("KAAS_MIGRATOR_DATABASE_PASSWORD", MIGRATOR_PASSWORD);
        return environment;
    }

    private static Result migrate(Map<String, String> environment) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int exit = DatabaseMigrator.run(environment, new PrintStream(bytes, true, StandardCharsets.UTF_8));
        return new Result(exit, bytes.toString(StandardCharsets.UTF_8).strip());
    }

    private record Result(int exitCode, String output) {}

    private static Connection connect(String user, String password) throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), user, password);
    }

    private static Path repositoryRoot() {
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (here != null && !Files.exists(here.resolve("settings.gradle.kts"))) {
            here = here.getParent();
        }
        if (here == null) {
            throw new IllegalStateException("The repository root holds settings.gradle.kts.");
        }
        return here;
    }
}
