package com.kaas.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import com.kaas.api.KaasApiApplication;
import com.kaas.runner.attestation.RuntimeImplementation;
import com.kaas.runner.daemon.RunnerComposition;
import com.kaas.runner.daemon.RunnerConfiguration;
import com.kaas.runner.sandbox.ExecutionRuntimeType;
import com.kaas.runner.sandbox.KarateEngineImage;
import com.kaas.runner.sandbox.ProbeImage;
import com.kaas.runner.sandbox.SandboxLabels;
import com.kaas.runner.sandbox.SandboxSecurityProfile;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * THE deployed shape, end to end (KAAS-DEPLOY-001): the production runner composition, taking work from the
 * production control plane through the internal claim API, with RabbitMQ on the control-plane side only.
 *
 * <pre>
 *   tenant creates a run
 *   scheduler  -> outbox -> relay -> RabbitMQ -> API consumer (DELIVERED)          all on their own timers
 *   runner     -> waits, claims in its own authenticated name -> ExecutionLoop
 *              -> authorization -> source -> Karate 2.1.2 under the mediating runtime -> result
 *   run COMPLETED / PASSED, assigned to the runner that ran it
 * </pre>
 *
 * <p>The runner is built by {@link RunnerComposition#compose} -- the same method {@code RunnerApplication.main}
 * calls -- with only the Docker client and HTTP client supplied. Its evidence comes from the real gates run on
 * this host when it starts; nothing is configured into the control plane as an attestation. Its service
 * identity comes from a client-credentials token endpoint issuing short-lived tokens signed by the key the
 * control plane trusts, so the credential is refreshed during the suite.
 *
 * <p>No test code sits between the stages: nothing here schedules, relays, consumes or claims. If a stage is
 * missing -- the runner never polls, the consumer claims for somebody else, the composition omits the execution
 * loop -- the run does not complete and this fails.
 *
 * <p>Needs the mediating runtime; like every such suite it is excluded from {@code test} and runs in its CI gate.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import(DeploymentPipelineTests.JwtTestConfiguration.class)
@SpringBootTest(
        classes = KaasApiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            // Everything on its own timer: this suite drives nothing.
            "kaas.scheduling.auto.enabled=true",
            "kaas.scheduling.auto.interval=PT1S",
            "kaas.outbox.relay.enabled=true",
            "kaas.outbox.relay.interval=PT1S",
            "kaas.consumer.enabled=true",
            "kaas.claim.reconcile.enabled=true",
            "kaas.claim.lease-duration=PT180S",
            "kaas.claim.recovery-window=PT60S",
            "kaas.execution.authorization-ttl=PT10M",
            "kaas.execution.capability-ttl=PT10M",
            "kaas.scheduling.queue-timeout=PT15M",
            "kaas.engine.type=KARATE",
            "kaas.engine.version=2.1.2",
            // The production management arrangement: its own port, three endpoints.
            "management.server.port=0",
            "management.server.address=127.0.0.1",
            "management.endpoints.web.exposure.include=health,prometheus,deployment",
            "management.endpoint.health.show-details=never"
        })
class DeploymentPipelineTests {
    private static final String ISSUER = "https://issuer.kaas.test";
    private static final String AUDIENCE = "kaas-api";
    private static final KeyPair SIGNING_KEY = keyPair();
    private static final String WORKER = "kaas.worker.deployment-pipeline";
    private static final String CLIENT_ID = "kaas-runner-deployment-pipeline";
    private static final String RUNTIME_SUBJECT = "kaas.runtime.deployment-pipeline";
    /** Short, so the suite outlives several tokens and the refresh is exercised rather than assumed. */
    private static final Duration TOKEN_LIFETIME = Duration.ofSeconds(60);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16.10-alpine").withDatabaseName("kaas-deployment-pipeline");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    private static HttpServer issuer;
    private static final AtomicInteger TOKENS_ISSUED = new AtomicInteger();
    private static String probeImage;
    private static String engineImage;
    private static String measuredRuntime;
    private static RunnerComposition.Runner runner;
    private static Path keyFile;
    private static Path secretFile;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        String profile = SandboxSecurityProfile.versionFor(runtime());
        registry.add("kaas.execution.security-profile-version", () -> profile);
        // Trust the published test key and accept THIS host's runtime, measured now. No attestation is
        // configured: the runner must produce, sign and submit its own.
        registry.add("kaas.execution.attestation-trusted-keys", ProducedAttestation::trustedKeys);
        registry.add("kaas.execution.attestation-runtime-subjects", () -> RUNTIME_SUBJECT);
        registry.add("kaas.execution.attestation-runtime-implementations", DeploymentPipelineTests::measuredRuntime);
    }

    @BeforeAll
    static void prepare() throws Exception {
        issuer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        issuer.createContext("/oauth/token", exchange -> {
            String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean ours = form.contains("client_id=" + CLIENT_ID) && form.contains("client_secret=deployment-secret");
            byte[] body;
            int status;
            if (ours) {
                TOKENS_ISSUED.incrementAndGet();
                body = ("{\"access_token\":\"" + token(WORKER, null, TOKEN_LIFETIME) + "\",\"token_type\":\"Bearer\""
                        + ",\"expires_in\":" + TOKEN_LIFETIME.toSeconds() + "}").getBytes(StandardCharsets.UTF_8);
                status = 200;
            } else {
                body = "{\"error\":\"invalid_client\"}".getBytes(StandardCharsets.UTF_8);
                status = 401;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        issuer.start();
        keyFile = Files.createTempFile("kaas-deployment-attestation", ".key");
        Files.writeString(keyFile, ProducedAttestation.privateKeyPkcs8());
        secretFile = Files.createTempFile("kaas-deployment-client", ".secret");
        Files.writeString(secretFile, "deployment-secret\n");
        PipelineEvidence.append("deployment-pipeline-evidence.txt", "suite=DeploymentPipelineTests\n");
    }

    @AfterAll
    static void tearDown() {
        if (runner != null) {
            runner.close();
        }
        if (issuer != null) {
            issuer.stop(0);
        }
    }

    @Value("${local.server.port}")
    private int port;

    @Autowired private Environment environment;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @Order(10)
    @Timeout(120)
    void withoutARunnerTheDeploymentCheckRefuses() {
        Check result = deployCheck("--timeout", "PT3S");
        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.output()).startsWith("deploy_check=NOT_READY").contains("runnersWithCurrentEvidence:0")
                .contains("broker:UP").contains("schema:CURRENT");
        PipelineEvidence.append("deployment-pipeline-evidence.txt", "deploy_check_without_runner=NOT_READY\n");
    }

    @Test
    @Order(20)
    @Timeout(1200)
    void theProductionCompositionBecomesReadyOnlyWithAcceptedEvidenceFromThisHost() throws Exception {
        runner = RunnerComposition.compose(RunnerConfiguration.fromEnvironment(runnerEnvironment()),
                docker(), HttpClient.newHttpClient(), Clock.systemUTC());
        runner.start();
        int health = runner.healthPort();
        assertThat(get("http://127.0.0.1:" + health + "/health/liveness").statusCode()).isEqualTo(200);

        awaitTrue(Duration.ofMinutes(15), () -> runner.daemon().readiness().ready());
        HttpResponse<String> readiness = get("http://127.0.0.1:" + health + "/health/readiness");
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(readiness.body()).contains("\"status\":\"UP\"");

        // The evidence the control plane holds is this runner's own, measured here, and it names this host's
        // runtime binary -- nobody configured it.
        Map<String, Object> evidence = jdbc.queryForMap(
                "select * from sandbox_attestations where worker_id = ? order by assessed_at desc limit 1", WORKER);
        assertThat(evidence.get("runtime_implementation_digest")).isEqualTo(measuredRuntime);
        assertThat(evidence.get("runtime_subject")).isEqualTo(RUNTIME_SUBJECT);

        Check ready = deployCheck("--runner", "http://127.0.0.1:" + health, "--timeout", "PT60S");
        assertThat(ready.exit()).as(ready.output()).isZero();
        assertThat(ready.output()).startsWith("deploy_check=READY");

        String metrics = get("http://127.0.0.1:" + health + "/metrics").body();
        assertThat(metrics).contains("kaas_runner_ready 1").contains("kaas_runner_claim_api_available 1")
                .contains("kaas_runner_runtime_digest_match 1");
        PipelineEvidence.append("deployment-pipeline-evidence.txt", "runner_composition=RunnerComposition.compose\n"
                + "runner_ready=true\n"
                + "attestation_submitted_by_runner=true\n"
                + "attestation_runtime_implementation=" + measuredRuntime + "\n"
                + "deploy_check_with_runner=READY\n");
    }

    @Test
    @Order(30)
    @Timeout(1200)
    void aRunTravelsFromTheTenantThroughRabbitToTheRunnerAndKarateWithNobodyDrivingIt() throws Exception {
        UUID organizationId = UUID.randomUUID();
        String bearer = token("deployment-tenant", organizationId, Duration.ofHours(1));
        String projectId = json(post("/api/v1/projects", bearer, Map.of("name", "Deployment " + UUID.randomUUID())))
                .get("projectId").stringValue();
        String featureRevision = json(post("/api/v1/projects/" + projectId + "/features", bearer, Map.of(
                        "name", "Deployment feature",
                        "logicalPath", "features/deploy-" + UUID.randomUUID() + ".feature",
                        "source", "Feature: deployed\n  Scenario: runs\n    * match 1 + 1 == 2\n")))
                .at("/initialRevision/revisionId").stringValue();
        String environmentRevision = json(post("/api/v1/projects/" + projectId + "/environments", bearer, Map.of(
                        "name", "Deployment environment", "variables", List.of(), "secretBindings", List.of())))
                .at("/initialRevision/revisionId").stringValue();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", "Deployment profile");
        profile.put("environmentRevisionId", environmentRevision);
        profile.put("selection", Map.of("tags", List.of()));
        profile.put("parallelism", 1);
        profile.put("scenarioRetry", Map.of("maxAttempts", 1, "delayMilliseconds", 0));
        profile.put("executionTimeoutSeconds", 120);
        profile.put("artifactPolicy",
                Map.of("types", List.of("RAW_RESULT"), "maxArtifactBytes", 1_000, "maxTotalBytes", 2_000));
        profile.put("configurationOverrides", List.of());
        String profileRevision = json(post("/api/v1/projects/" + projectId + "/run-profiles", bearer, profile))
                .at("/initialRevision/revisionId").stringValue();
        UUID runId = UUID.fromString(json(post("/api/v1/projects/" + projectId + "/runs", bearer, Map.of(
                        "featureRevisionIds", List.of(featureRevision), "runProfileRevisionId", profileRevision)))
                .get("runId").stringValue());

        awaitTrue(Duration.ofMinutes(15), () -> "COMPLETED".equals(jdbc.queryForObject(
                "select lifecycle_state from test_runs where run_id = ?", String.class, runId)));

        Map<String, Object> run = jdbc.queryForMap("select * from test_runs where run_id = ?", runId);
        assertThat(run.get("test_outcome")).as("%s", run).isEqualTo("PASSED");
        Map<String, Object> attempt = jdbc.queryForMap("select * from execution_attempts where run_id = ?", runId);
        assertThat(attempt.get("assigned_worker_id")).isEqualTo(WORKER);
        assertThat(jdbc.queryForObject("select disposition from dispatch_inbox where run_id = ?", String.class, runId))
                .isEqualTo("DELIVERED");
        assertThat(runner.daemon().metrics().count("kaas_runner_claim_success_total")).isGreaterThanOrEqualTo(1);
        assertThat(runner.daemon().metrics().count("kaas_runner_execution_completed_total{status=\"COMPLETED\"}"))
                .isGreaterThanOrEqualTo(1);
        PipelineEvidence.append("deployment-pipeline-evidence.txt", "claim_path=RABBITMQ_TO_API_CONSUMER_TO_RUNNER_CLAIM\n"
                + "inbox_disposition=DELIVERED\n"
                + "assigned_worker=" + attempt.get("assigned_worker_id") + "\n"
                + "run_outcome=" + run.get("lifecycle_state") + "/" + run.get("test_outcome") + "\n"
                + "sandbox_runtime=" + runtime().daemonRuntimeName() + "\n");
    }

    @Test
    @Order(40)
    void theServiceCredentialWasRefreshedAndNeverOutlivedItsExpiry() {
        // The suite has run for minutes against sixty-second tokens.
        assertThat(TOKENS_ISSUED.get()).isGreaterThanOrEqualTo(2);
        PipelineEvidence.append("deployment-pipeline-evidence.txt",
                "service_tokens_issued=" + TOKENS_ISSUED.get() + "\nservice_token_lifetime_seconds="
                        + TOKEN_LIFETIME.toSeconds() + "\n");
    }

    @Test
    @Order(90)
    @Timeout(600)
    void shutdownStopsIntakeAndLeavesNothingBehind() throws Exception {
        long began = System.nanoTime();
        runner.close();
        Duration took = Duration.ofNanos(System.nanoTime() - began);
        runner = null;

        assertThat(took).isLessThan(Duration.ofMinutes(3));
        var leftovers = docker().listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of(SandboxLabels.MANAGED, "true")).exec().stream()
                .filter(container -> String.valueOf(container.getLabels().get(SandboxLabels.GENERATION))
                        .startsWith("runner-"))
                .toList();
        assertThat(leftovers).isEmpty();
        PipelineEvidence.append("deployment-pipeline-evidence.txt", "shutdown_seconds=" + took.toSeconds() + "\n"
                + "runner_containers_after_shutdown=" + leftovers.size() + "\n");
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, String> runnerEnvironment() {
        Map<String, String> environment = new HashMap<>();
        environment.put("KAAS_RUNNER_WORKER_ID", WORKER);
        environment.put("KAAS_RUNNER_API_URL", "http://127.0.0.1:" + port);
        environment.put("KAAS_RUNNER_TOKEN_ENDPOINT", "http://127.0.0.1:" + issuer.getAddress().getPort()
                + "/oauth/token");
        environment.put("KAAS_RUNNER_CLIENT_ID", CLIENT_ID);
        environment.put("KAAS_RUNNER_CLIENT_SECRET_FILE", secretFile.toString());
        environment.put("KAAS_RUNNER_ATTESTATION_KEY_ID", ProducedAttestation.KEY_ID);
        environment.put("KAAS_RUNNER_ATTESTATION_KEY_FILE", keyFile.toString());
        environment.put("KAAS_RUNNER_RUNTIME_SUBJECT", RUNTIME_SUBJECT);
        environment.put("KAAS_RUNNER_PROBE_IMAGE", probeImage());
        environment.put("KAAS_RUNNER_ENGINE_IMAGE", engineImage());
        environment.put("KAAS_RUNNER_SANDBOX_RUNTIME", runtime() == ExecutionRuntimeType.GVISOR ? "gvisor" : "docker");
        environment.put("KAAS_RUNNER_HEALTH_PORT", "0");
        environment.put("KAAS_RUNNER_CLAIM_WAIT", "PT5S");
        environment.put("KAAS_RUNNER_SHUTDOWN_TIMEOUT", "PT60S");
        return environment;
    }

    private Check deployCheck(String... extra) {
        String management = "http://127.0.0.1:" + environment.getProperty("local.management.port");
        String[] arguments = new String[extra.length + 2];
        arguments[0] = "--api";
        arguments[1] = management;
        System.arraycopy(extra, 0, arguments, 2, extra.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = com.kaas.api.deployment.DeploymentCheck.run(arguments, http, new PrintStream(out, true, StandardCharsets.UTF_8));
        return new Check(exit, out.toString(StandardCharsets.UTF_8).strip());
    }

    private record Check(int exit, String output) {}

    private HttpResponse<String> get(String url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String bearer, Object body) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + bearer)
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("POST %s: %s", path, response.body()).isBetween(200, 299);
        return response;
    }

    private JsonNode json(HttpResponse<String> response) {
        return mapper.readTree(response.body());
    }

    private static void awaitTrue(Duration within, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + within.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("not reached within " + within);
            }
            Thread.sleep(500);
        }
    }

    private static ExecutionRuntimeType runtime() {
        String override = System.getProperty("kaas.test.mediated-runtime");
        return override == null || override.isBlank() ? ExecutionRuntimeType.GVISOR
                : ExecutionRuntimeType.valueOf(override);
    }

    private static synchronized DockerClient docker() {
        return DockerClientFactory.instance().client();
    }

    private static synchronized String measuredRuntime() {
        if (measuredRuntime == null) {
            measuredRuntime = RuntimeImplementation.measure(docker(), runtime().daemonRuntimeName()).digest();
        }
        return measuredRuntime;
    }

    private static synchronized String probeImage() {
        if (probeImage == null) {
            probeImage = ProbeImage.build(docker(),
                    Path.of("..", "..", "services", "runner", "src", "main", "docker", "probe"));
        }
        return probeImage;
    }

    private static synchronized String engineImage() {
        if (engineImage == null) {
            engineImage = KarateEngineImage.build(docker(), Path.of(System.getProperty("kaas.karate.engine.context")));
        }
        return engineImage;
    }

    private static String token(String subject, UUID organizationId, Duration lifetime) {
        try {
            Instant now = Instant.now();
            var claims = new JWTClaimsSet.Builder()
                    .issuer(ISSUER).subject(subject).audience(AUDIENCE)
                    .issueTime(Date.from(now.minusSeconds(5)))
                    .notBeforeTime(Date.from(now.minusSeconds(5)))
                    .expirationTime(Date.from(now.plus(lifetime)));
            if (organizationId != null) {
                claims.claim("org_id", organizationId.toString());
            }
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
            jwt.sign(new RSASSASigner((RSAPrivateKey) SIGNING_KEY.getPrivate()));
            return jwt.serialize();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JwtTestConfiguration {
        @Bean
        @Primary
        NimbusJwtDecoder jwtDecoder() {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) SIGNING_KEY.getPublic()).build();
            var audience = new JwtClaimValidator<List<String>>(
                    "aud", values -> values != null && values.contains(AUDIENCE));
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(ISSUER), audience));
            return decoder;
        }
    }
}
