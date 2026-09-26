package com.kaas.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import com.kaas.api.KaasApiApplication;
import com.kaas.api.controlplane.application.PendingRunScheduler;
import com.kaas.api.controlplane.application.RunClaimService;
import com.kaas.api.controlplane.domain.ExecutionDispatch;
import com.kaas.api.execution.domain.EgressDestination;
import com.kaas.api.execution.domain.EgressScheme;
import com.kaas.api.execution.domain.NetworkPolicyRevision;
import com.kaas.api.execution.domain.NetworkPolicyType;
import com.kaas.api.testing.VaultTransitFixture;
import com.kaas.runner.client.ControlPlaneClient;
import com.kaas.runner.command.CommandValidator;
import com.kaas.runner.execution.ExecutionLoop;
import com.kaas.runner.sandbox.DockerEgressExecutions;
import com.kaas.runner.sandbox.DockerSandboxLauncher;
import com.kaas.runner.sandbox.ExecutionRuntimeType;
import com.kaas.runner.sandbox.KarateEngineImage;
import com.kaas.runner.sandbox.ProtocolScanner;
import com.kaas.runner.sandbox.SandboxOutcome;
import com.kaas.runner.sandbox.SandboxSecurityProfile;
import com.kaas.runner.sandbox.SyntheticProbe;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Secret-bearing Karate, end to end, with nothing simulated.
 *
 * <p>A tenant writes a runtime-generated secret through the public API; the control plane encrypts it with a
 * REAL Vault Transit (1.18.5, derived key, AppRole with encrypt/decrypt on one key and nothing else) and stores
 * only ciphertext; an environment binds it; the run pins its version; the run is scheduled and claimed; the
 * runner is authorized and issued a secret capability; it redeems the exact pinned set from the control plane,
 * which decrypts through Vault under the tenant's context; the runner frames it after the source; the sandbox
 * runs under the MEDIATING RUNTIME, freezes the source, drops every capability, and hands over to the Karate
 * adapter, which reads the frame, closes standard input, and starts Karate 2.1.2; Karate calls a controlled
 * target through the real egress proxy with the secret as a bearer token; the target accepts ONLY that exact
 * value; the feature passes and the run completes. The tenant also prints the raw value on both streams, and the
 * trusted collector must see it raw and keep it redacted.
 *
 * <p>The one piece of instrumentation is {@link ExecutionLoop.ExecutionObserver}, which the loop calls with the
 * sandbox's outcome: redaction counts and the kept transcripts. It is the platform's own observation hook,
 * not a substitute for anything.
 *
 * <p>Nothing here prints a secret. Containment is computed in-process into booleans, and the gate reads the
 * booleans from the evidence file this suite writes.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import(SecretExecutionPipelineTests.JwtTestConfiguration.class)
@SpringBootTest(
        classes = KaasApiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "kaas.scheduling.auto.enabled=false",
            "kaas.reaping.auto.enabled=false",
            "kaas.outbox.relay.enabled=false",
            "kaas.consumer.enabled=false",
            "kaas.claim.reconcile.enabled=false",
            "kaas.execution.reconcile.enabled=false",
            "kaas.claim.lease-duration=PT180S",
            "kaas.claim.recovery-window=PT60S",
            "kaas.execution.authorization-ttl=PT10M",
            "kaas.execution.capability-ttl=PT10M",
            "kaas.scheduling.queue-timeout=PT10M",
            // The engine that runs tenant code, under the profile of the mediating runtime.
            "kaas.engine.type=KARATE",
            "kaas.engine.version=2.1.2"
        })
class SecretExecutionPipelineTests {

    private static final String ISSUER = "https://issuer.kaas.test";
    private static final String AUDIENCE = "kaas-api";
    private static final KeyPair SIGNING_KEY = keyPair();
    private static final String WORKER = "kaas.worker.secret-pipeline";
    private static final String GENERATION = "secret-pipeline";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The secret the target accepts. Generated per run of this class, never written in source. */
    private static final String ACCEPTED = generated();

    static final VaultTransitFixture VAULT = new VaultTransitFixture();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16.10-alpine").withDatabaseName("kaas-secret-pipeline");

    private static DockerClient docker;
    private static String engineImage;
    private static EgressPipelineTopology topology;

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        // The profile of the runtime this suite runs under: the mediating runtime's, except under the local-only
        // override, where the runner would otherwise (correctly) refuse a command authorized for a runtime it
        // does not instantiate. The gate reads the daemon-reported runtime from this suite's evidence.
        String profile = SandboxSecurityProfile.versionFor(mediatedRuntime());
        registry.add("kaas.execution.security-profile-version", () -> profile);
        registry.add("kaas.execution.sandbox-attestation",
                () -> ProducedAttestation.withEgress(profile, Instant.now()));
        registry.add("kaas.execution.attestation-trusted-keys", ProducedAttestation::trustedKeys);
        registry.add("kaas.execution.attestation-runtime-subjects", () -> ProducedAttestation.RUNTIME_SUBJECT);
        registry.add("kaas.execution.attestation-runtime-implementations",
                () -> ProducedAttestation.RUNTIME_IMPLEMENTATION_DIGEST);
        // The five variables of the Operations contract. The control plane authenticates with the AppRole the
        // fixture provisioned; the fixture's root token never reaches it.
        registry.add("kaas.secrets.vault.address", VAULT::address);
        registry.add("kaas.secrets.vault.role-id", VAULT::roleId);
        registry.add("kaas.secrets.vault.secret-id", VAULT::secretId);
        registry.add("kaas.secrets.vault.ca-cert", () -> VAULT.caFile().toString());
        registry.add("kaas.secrets.vault.transit-key", () -> VaultTransitFixture.TRANSIT_KEY);
    }

    @org.junit.jupiter.api.BeforeAll
    static void freshEvidence() throws IOException {
        String directory = System.getProperty("kaas.evidence.dir");
        if (directory != null) {
            Files.deleteIfExists(Path.of(directory, "secret-pipeline-evidence.txt"));
        }
        PipelineEvidence.append("secret-pipeline-evidence.txt",
                "sentinel_nonce_applied=" + (System.getProperty("kaas.test.sentinel-nonce") != null) + "\n");
    }

    @AfterAll
    static void tearDown() {
        if (topology != null) {
            topology.close();
        }
        VAULT.close();
    }

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private com.kaas.api.secrets.domain.SecretTransit transit;
    @Autowired private PendingRunScheduler scheduler;
    @Autowired private RunClaimService claims;

    // ------------------------------------------------------------------ the whole chain

    @Test
    @Order(1)
    @Timeout(900)
    @DisplayName("a runtime-generated secret travels from the tenant, through Vault, into Karate under gVisor, "
            + "authenticates to a controlled target, and is redacted from everything the platform keeps")
    void theWholeChain() throws Exception {
        Instant started = Instant.now();
        Tenant tenant = tenantWithFeature(authenticatingFeature("auth"));
        String secretId = secretReference(tenant, "apiToken");
        writeVersion(tenant, secretId, ACCEPTED.getBytes(StandardCharsets.UTF_8));
        Tenant bound = bind(tenant, Map.of("API_TOKEN", secretId));
        allowlistFor(bound, EgressPipelineTopology.ALLOWED_HOST, EgressPipelineTopology.TARGET_PORT, "HTTP");
        UUID runId = claimedRunFor(bound);

        // VAULT ENCRYPTED THE SUBMITTED VALUE: the stored row is Vault's ciphertext of exactly these bytes under
        // this tenant's context, as Vault itself (the operator, not the platform) confirms.
        String ciphertext = jdbc.queryForObject(
                "select c.ciphertext from secret_version_ciphertexts c join secret_versions v"
                        + " on v.secret_version_id = c.secret_version_id where v.secret_reference_id = ?",
                String.class, UUID.fromString(secretId));
        boolean encryptedByVault = Arrays.equals(
                VAULT.decryptAsOperator(ciphertext, bound.organizationId(), bound.projectId()),
                ACCEPTED.getBytes(StandardCharsets.UTF_8));
        assertThat(encryptedByVault).as("secret_encryption").isTrue();
        // THE RUN PINNED VERSION 1, as metadata.
        Integer pinned = jdbc.queryForObject(
                "select secret_version_number from run_snapshot_configuration_entries"
                        + " where run_id = ? and config_key = 'API_TOKEN'", Integer.class, runId);
        assertThat(pinned).as("secret_version_pinned").isEqualTo(1);

        List<SandboxOutcome> observed = new CopyOnWriteArrayList<>();
        int authenticatedBefore = topology().authenticatedRequests();
        ExecutionLoop.ExecutionReport report;
        String proxyLog;
        int proxiesSeen;
        try (EgressPipelineTopology.ProxyLogWatcher proxies = topology().watchProxies(GENERATION)) {
            report = loop(observed).execute(runId, attemptId(runId), 1);
            proxyLog = proxies.captured();
            proxiesSeen = proxies.proxiesSeen();
        }

        assertThat(report.status()).as("report %s at %s: %s", report.status(), report.phase(), report.detail())
                .isEqualTo("COMPLETED");
        assertThat(report.detail()).isEqualTo("PASSED");
        Map<String, Object> run = jdbc.queryForMap(
                "select lifecycle_state, test_outcome, infrastructure_outcome from test_runs where run_id = ?", runId);
        assertThat(run.get("lifecycle_state")).isEqualTo("COMPLETED");
        assertThat(run.get("test_outcome")).isEqualTo("PASSED");
        assertThat(run.get("infrastructure_outcome")).isEqualTo("SUCCEEDED");

        // THE ENGINE REALLY HAD THE SECRET: the controlled target, which accepts only the exact generated value,
        // recorded an authenticated request. Its own log, not anything the tenant printed.
        int authenticated = topology().authenticatedRequests() - authenticatedBefore;
        assertThat(authenticated).as("secret_authenticated_request").isEqualTo(1);

        // The capability was real, scoped to exactly one binding, and redeemed exactly once.
        Map<String, Object> capability = jdbc.queryForMap(
                "select c.redemption_count, (select count(*) from execution_capability_secret_references s"
                        + " where s.capability_id = c.capability_id) as scope"
                        + " from execution_capabilities c join execution_authorizations a"
                        + " on a.authorization_id = c.authorization_id"
                        + " where a.run_id = ? and c.capability_type = 'SECRET' and c.redemption_count > 0",
                runId);
        assertThat(((Number) capability.get("redemption_count")).intValue()).as("secret_redemption").isEqualTo(1);
        assertThat(((Number) capability.get("scope")).intValue()).as("secret_capability").isEqualTo(1);

        // THE HAZARD HAPPENED AND WAS CONTAINED.
        assertThat(observed).as("the loop reported its sandbox").hasSize(1);
        SandboxOutcome outcome = observed.getFirst();
        assertThat(outcome.redaction().stdoutMatches()).as("raw_stdout_secret_observed").isPositive();
        assertThat(outcome.redaction().stderrMatches()).as("raw_stderr_secret_observed").isPositive();
        boolean kept = outcome.redaction().stdout().contains(ACCEPTED)
                || outcome.redaction().stderr().contains(ACCEPTED)
                || outcome.observations().toString().contains(ACCEPTED);
        assertThat(kept).as("persisted_raw_secret").isFalse();
        assertThat(outcome.protocol().single(ProtocolScanner.ENGINE_KEY)).isEqualTo("karate 2.1.2");

        // AND IT IS NOWHERE ELSE.
        boolean inDatabase = databaseContains(ACCEPTED);
        boolean inQueue = queueContains(ACCEPTED);
        boolean inHostFiles = hostFilesContain(ACCEPTED, started);
        // The proxy's own log, read while it existed. The control: the watcher saw the proxy and read its
        // startup line, so an empty capture cannot pass as "the proxy logged nothing sensitive".
        assertThat(proxiesSeen).as("the watcher observed the execution's proxy").isPositive();
        assertThat(proxyLog).as("the watcher really read the proxy's log").contains("kaas-egress-proxy listening");
        boolean inProxyLogs = proxyLog.contains(ACCEPTED);
        assertThat(inDatabase).as("secret_in_database").isFalse();
        assertThat(inQueue).as("secret_in_queue").isFalse();
        assertThat(inHostFiles).as("secret_in_host_files").isFalse();
        assertThat(inProxyLogs).as("secret_in_proxy_logs").isFalse();
        assertThat(topology().leftovers(GENERATION)).as("containers and networks").isEmpty();

        // Every value below is computed from what was observed, not restated from the assertions above: an
        // evidence file that could only ever say VALID would prove that the test passed and nothing else.
        int redemptions = ((Number) capability.get("redemption_count")).intValue();
        int scope = ((Number) capability.get("scope")).intValue();
        PipelineEvidence.append("secret-pipeline-evidence.txt",
                // The provider the application actually wired, read from the bean rather than named here.
                "secret_provider=" + (transit instanceof com.kaas.api.secrets.infrastructure.VaultTransitClient ? "vault-transit" : "OTHER") + "\n"
                        // Vault accepted this deployment's AppRole for both the encryption (Vault decrypts the
                        // stored ciphertext for the operator) and the decryption (the engine held the value).
                        + "secret_provider_auth=" + (encryptedByVault && authenticated == 1 ? "VALID" : "INVALID") + "\n"
                        + "secret_encryption=" + (encryptedByVault ? "VALID" : "INVALID") + "\n"
                        + "secret_version_pinned=" + (pinned == 1) + "\n"
                        + "secret_capability=" + (scope == 1 ? "VALID" : "INVALID") + "\n"
                        + "secret_redemption=" + (redemptions == 1 ? "VALID" : "INVALID") + "\n"
                        + "secret_authenticated_request=" + (authenticated == 1) + "\n"
                        + "raw_stdout_secret_observed=" + (outcome.redaction().stdoutMatches() > 0) + "\n"
                        + "raw_stderr_secret_observed=" + (outcome.redaction().stderrMatches() > 0) + "\n"
                        + "persisted_raw_secret=" + kept + "\n"
                        + "redaction=" + (kept ? "INVALID" : "VALID") + "\n"
                        + "secret_in_database=" + inDatabase + "\n"
                        + "secret_in_queue=" + inQueue + "\n"
                        + "secret_in_host_files=" + inHostFiles + "\n"
                        + "secret_in_proxy_logs=" + inProxyLogs + "\n"
                        + "engine_identity=" + outcome.protocol().single(ProtocolScanner.ENGINE_KEY) + "\n"
                        + "engine_verdict=" + report.detail() + "\n"
                        + "runtime=" + outcome.assignedRuntime() + "\n");
    }

    @Test
    @Order(2)
    @Timeout(900)
    @DisplayName("the target refuses any other value: the same run with a different secret is a FAILED test")
    void theTargetAcceptsOnlyTheExactSecret() throws Exception {
        // The control that makes "the target authenticated the secret" mean something. Without it, a target
        // that accepted any bearer token -- or a feature that never sent one -- would pass the test above.
        Tenant tenant = tenantWithFeature(authenticatingFeature("auth"));
        String secretId = secretReference(tenant, "wrongToken");
        writeVersion(tenant, secretId, generated().getBytes(StandardCharsets.UTF_8));
        Tenant bound = bind(tenant, Map.of("API_TOKEN", secretId));
        allowlistFor(bound, EgressPipelineTopology.ALLOWED_HOST, EgressPipelineTopology.TARGET_PORT, "HTTP");
        UUID runId = claimedRunFor(bound);
        int rejectedBefore = topology().rejectedRequests();

        ExecutionLoop.ExecutionReport report = loop(new ArrayList<>()).execute(runId, attemptId(runId), 1);

        assertThat(report.status()).as("%s", report.detail()).isEqualTo("COMPLETED");
        assertThat(report.detail()).as("a wrong secret is the tenant's failing test").isEqualTo("FAILED");
        assertThat(topology().rejectedRequests() - rejectedBefore).as("the target saw and refused it").isEqualTo(1);
        assertThat(jdbc.queryForObject("select infrastructure_outcome from test_runs where run_id = ?",
                String.class, runId)).isEqualTo("SUCCEEDED");
    }

    @Test
    @Order(3)
    @Timeout(900)
    @DisplayName("an HTTP failure whose body echoes the secret is a FAILED test with nothing raw kept")
    void aFailureBodyCarryingTheSecretIsRedacted() throws Exception {
        String secret = generated();
        Tenant tenant = tenantWithFeature(authenticatingFeature("echo-auth"));
        String secretId = secretReference(tenant, "echoed");
        writeVersion(tenant, secretId, secret.getBytes(StandardCharsets.UTF_8));
        Tenant bound = bind(tenant, Map.of("API_TOKEN", secretId));
        allowlistFor(bound, EgressPipelineTopology.ALLOWED_HOST, EgressPipelineTopology.TARGET_PORT, "HTTP");
        UUID runId = claimedRunFor(bound);
        List<SandboxOutcome> observed = new CopyOnWriteArrayList<>();

        ExecutionLoop.ExecutionReport report = loop(observed).execute(runId, attemptId(runId), 1);

        assertThat(report.status()).as("%s", report.detail()).isEqualTo("COMPLETED");
        assertThat(report.detail()).isEqualTo("FAILED");
        SandboxOutcome outcome = observed.getFirst();
        assertThat(outcome.redaction().stdout().contains(secret) || outcome.redaction().stderr().contains(secret))
                .isFalse();
        assertThat(databaseContains(secret)).isFalse();
    }

    @Test
    @Order(4)
    @Timeout(900)
    @DisplayName("a revoked version ends the run before any sandbox exists, and never advances to another version")
    void aRevokedVersionStartsNoEngine() throws Exception {
        Tenant tenant = tenantWithFeature(authenticatingFeature("auth"));
        String secretId = secretReference(tenant, "revoked");
        writeVersion(tenant, secretId, ACCEPTED.getBytes(StandardCharsets.UTF_8));
        Tenant bound = bind(tenant, Map.of("API_TOKEN", secretId));
        allowlistFor(bound, EgressPipelineTopology.ALLOWED_HOST, EgressPipelineTopology.TARGET_PORT, "HTTP");
        UUID runId = claimedRunFor(bound);
        writeVersion(tenant, secretId, ACCEPTED.getBytes(StandardCharsets.UTF_8));
        revoke(tenant, secretId, 1);
        List<SandboxOutcome> observed = new CopyOnWriteArrayList<>();
        int authenticatedBefore = topology().authenticatedRequests();

        ExecutionLoop.ExecutionReport report = loop(observed).execute(runId, attemptId(runId), 1);

        assertThat(report.status()).isIn("REFUSED", "INFRASTRUCTURE_FAILED");
        assertThat(report.detail()).contains("SECRET_VERSION_REVOKED");
        assertThat(observed).as("no sandbox was launched").isEmpty();
        assertThat(topology().authenticatedRequests()).as("and version 2 was never used").isEqualTo(authenticatedBefore);
        PipelineEvidence.append("secret-pipeline-evidence.txt", "revoked_version_refused="
                + (String.valueOf(report.detail()).contains("SECRET_VERSION_REVOKED") && observed.isEmpty()) + "\n");
    }

    @Test
    @Order(90)
    @Timeout(900)
    @DisplayName("with Vault sealed, a secret-bearing run starts no engine and a secret-free run still passes")
    void aSealedProviderStopsOnlyTheRunsThatNeedIt() throws Exception {
        Tenant secretTenant = tenantWithFeature(authenticatingFeature("auth"));
        String secretId = secretReference(secretTenant, "sealed");
        writeVersion(secretTenant, secretId, ACCEPTED.getBytes(StandardCharsets.UTF_8));
        Tenant bound = bind(secretTenant, Map.of("API_TOKEN", secretId));
        allowlistFor(bound, EgressPipelineTopology.ALLOWED_HOST, EgressPipelineTopology.TARGET_PORT, "HTTP");
        UUID secretRun = claimedRunFor(bound);
        Tenant secretFree = tenantWithFeature(
                "Feature: no secrets at all\n  Scenario: holds\n    * match 1 + 1 == 2\n");
        UUID secretFreeRun = claimedRunFor(secretFree);

        VAULT.seal();

        List<SandboxOutcome> observed = new CopyOnWriteArrayList<>();
        ExecutionLoop.ExecutionReport refused = loop(observed).execute(secretRun, attemptId(secretRun), 1);
        assertThat(refused.status()).isIn("REFUSED", "INFRASTRUCTURE_FAILED");
        assertThat(refused.detail()).contains("SECRET_PROVIDER_UNAVAILABLE");
        assertThat(observed).as("no engine started for the secret-bearing run").isEmpty();

        ExecutionLoop.ExecutionReport passed = loop(observed).execute(secretFreeRun, attemptId(secretFreeRun), 1);
        assertThat(passed.status()).as("%s", passed.detail()).isEqualTo("COMPLETED");
        assertThat(passed.detail()).isEqualTo("PASSED");
        PipelineEvidence.append("secret-pipeline-evidence.txt",
                "provider_outage_refused_secret_run="
                        + (String.valueOf(refused.detail()).contains("SECRET_PROVIDER_UNAVAILABLE")) + "\n"
                        + "secret_free_run_with_provider_down=" + passed.detail() + "\n");
    }

    // ------------------------------------------------------------------ the tenant's feature

    /** A feature that calls the target with its secret, then prints the raw secret on both streams. */
    private static String authenticatingFeature(String path) {
        return """
                Feature: a tenant calling its API with its own credential
                  Scenario: authenticate with exactly the delivered secret
                    * url 'http://%s'
                    * path '%s'
                    * header Authorization = 'Bearer ' + kaas.secrets.API_TOKEN
                    * method get
                    * def System = Java.type('java.lang.System')
                    * eval System.out.println('tenant-print:' + kaas.secrets.API_TOKEN)
                    * eval System.err.println('tenant-print:' + kaas.secrets.API_TOKEN)
                    * status 200
                    * match response == 'KAAS_SECRET_AUTHENTICATED'
                """.formatted(EgressPipelineTopology.ALLOWED_HOST, path);
    }

    // ------------------------------------------------------------------ the runner

    private ExecutionLoop loop(List<SandboxOutcome> observed) throws Exception {
        ControlPlaneClient client = new ControlPlaneClient(
                HttpClient.newHttpClient(),
                URI.create("http://localhost:" + port),
                "Bearer " + token(WORKER, null),
                java.time.Duration.ofSeconds(30),
                duration -> Thread.sleep(duration.toMillis()));
        ExecutionRuntimeType runtime = mediatedRuntime();
        SandboxSecurityProfile profile = SandboxSecurityProfile.version1(engineImage(), runtime);
        return new ExecutionLoop(
                client,
                new CommandValidator(mapper, java.util.Set.of("DENY_ALL", "ALLOWLIST"), java.util.Optional.empty(),
                        CommandValidator.KARATE_ENGINE, CommandValidator.KARATE_VERSION),
                new DockerSandboxLauncher(docker(), profile, GENERATION),
                mapper,
                Clock.systemUTC(),
                SyntheticProbe.KARATE_ENGINE,
                new DockerEgressExecutions(docker(), topology().deployment(port, "Bearer " + token("kaas.egress-proxy", null), runtime), GENERATION),
                true,
                CommandValidator.KARATE_ENGINE,
                (runId, outcome) -> observed.add(outcome));
    }

    private static ExecutionRuntimeType mediatedRuntime() {
        String override = System.getProperty("kaas.test.mediated-runtime");
        return override == null || override.isBlank() ? ExecutionRuntimeType.GVISOR : ExecutionRuntimeType.valueOf(override);
    }

    private static synchronized DockerClient docker() {
        if (docker == null) {
            docker = DockerClientFactory.instance().client();
        }
        return docker;
    }

    private static synchronized String engineImage() {
        if (engineImage == null) {
            String context = System.getProperty("kaas.karate.engine.context");
            if (context == null) {
                throw new IllegalStateException("kaas.karate.engine.context is set by the build.");
            }
            engineImage = KarateEngineImage.build(docker(), Path.of(context));
        }
        return engineImage;
    }

    private static synchronized EgressPipelineTopology topology() throws IOException {
        if (topology == null) {
            topology = new EgressPipelineTopology(docker(), engineImage(), Map.of(
                    "KAAS_EXPECTED_AUTH_SHA256", sha256(ACCEPTED)));
        }
        return topology;
    }

    // ------------------------------------------------------------------ leakage scans

    private boolean databaseContains(String needle) {
        for (Map<String, Object> column : jdbc.queryForList(
                """
                select table_name, column_name, data_type from information_schema.columns
                 where table_schema = 'public'
                   and data_type in ('text', 'character varying', 'jsonb', 'json', 'bytea', 'character')
                """)) {
            String table = (String) column.get("table_name");
            String name = (String) column.get("column_name");
            String cast = "bytea".equals(column.get("data_type"))
                    ? "encode(\"" + name + "\", 'escape')" : "\"" + name + "\"::text";
            Integer hits = jdbc.queryForObject(
                    "select count(*) from \"" + table + "\" where strpos(" + cast + ", ?) > 0", Integer.class, needle);
            if (hits != null && hits > 0) {
                return true;
            }
        }
        return false;
    }

    /** What the broker would carry: every dispatch intent and outbox payload, published or not. */
    private boolean queueContains(String needle) {
        Integer outbox = jdbc.queryForObject(
                "select count(*) from outbox_messages where strpos(payload::text, ?) > 0", Integer.class, needle);
        Integer dispatches = jdbc.queryForObject(
                "select count(*) from execution_dispatches where strpos(payload::text, ?) > 0", Integer.class, needle);
        return (outbox != null && outbox > 0) || (dispatches != null && dispatches > 0);
    }

    /**
     * KaaS-owned locations on this host that could have received a file: the JVM's temporary directory and
     * the build directories of the modules under test, restricted to files written during this test. Not the
     * whole host: that would be a search that proves nothing about KaaS and costs everything.
     *
     * <p>Fails closed. A directory that cannot be read is skipped and the walk goes on -- it does not abandon
     * the root, which is how the first version of this scan missed a file written straight into the temporary
     * directory: one unreadable sibling ended the whole walk and "found nothing" read as "nothing there". The
     * control proves each root was really walked: a marker file is planted in every root before the scan, and
     * a root whose marker the scan did not find fails the test rather than passing it.
     */
    private static boolean hostFilesContain(String needle, Instant since) throws IOException {
        List<Path> roots = List.of(
                Path.of(System.getProperty("java.io.tmpdir")),
                Path.of("build"),
                Path.of("..", "..", "services", "runner", "build"),
                Path.of("..", "..", "apps", "api", "build"));
        byte[] target = needle.getBytes(StandardCharsets.UTF_8);
        byte[] marker = ("kaas-scan-control-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        boolean found = false;
        for (Path root : roots) {
            assertThat(root).as("scan root %s exists", root).isDirectory();
            Path control = Files.createTempFile(root, "kaas-scan-control", ".txt");
            try {
                Files.write(control, marker);
                boolean[] hits = scan(root, since, target, marker);
                assertThat(hits[1]).as("the scan really walked %s: it found its control file", root).isTrue();
                found |= hits[0];
            } finally {
                Files.deleteIfExists(control);
            }
        }
        return found;
    }

    /** {target found, marker found} under {@code root}, skipping what cannot be read and nothing else. */
    private static boolean[] scan(Path root, Instant since, byte[] target, byte[] marker) throws IOException {
        boolean[] hits = new boolean[2];
        Files.walkFileTree(root, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), 8,
                new java.nio.file.SimpleFileVisitor<>() {
                    @Override
                    public java.nio.file.FileVisitResult visitFile(
                            Path file, java.nio.file.attribute.BasicFileAttributes attributes) {
                        if (!attributes.isRegularFile() || attributes.size() > 32 << 20
                                || attributes.lastModifiedTime().toInstant().isBefore(since)) {
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }
                        try {
                            byte[] content = Files.readAllBytes(file);
                            hits[0] |= indexOf(content, target) >= 0;
                            hits[1] |= indexOf(content, marker) >= 0;
                        } catch (IOException | SecurityException unreadable) {
                            // Another process's file, or one removed while walking. The walk goes on.
                        }
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }

                    @Override
                    public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException unreadable) {
                        // An unreadable or vanished entry: skip it, never the rest of the root.
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
        return hits;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int start = 0; start + needle.length <= haystack.length; start++) {
            for (int offset = 0; offset < needle.length; offset++) {
                if (haystack[start + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return start;
        }
        return -1;
    }

    // ------------------------------------------------------------------ the tenant, through the public API

    private record Tenant(UUID organizationId, UUID projectId, String bearer, String featureRevisionId,
            String profileRevisionId) {}

    private Tenant tenantWithFeature(String source) throws Exception {
        UUID organizationId = UUID.randomUUID();
        String bearer = token("secret-pipeline", organizationId);
        String projectId = mapper.readTree(post("/api/v1/projects", bearer,
                        json(Map.of("name", "Secret pipeline " + UUID.randomUUID()))).body())
                .get("projectId").stringValue();
        String featureRevision = mapper.readTree(post("/api/v1/projects/" + projectId + "/features", bearer,
                        json(Map.of("name", "Secret feature",
                                "logicalPath", "features/secret-" + UUID.randomUUID() + ".feature",
                                "source", source))).body())
                .at("/initialRevision/revisionId").stringValue();
        return bind(new Tenant(organizationId, UUID.fromString(projectId), bearer, featureRevision, null), Map.of());
    }

    private Tenant bind(Tenant tenant, Map<String, String> bindings) throws Exception {
        List<Map<String, Object>> secretBindings = new ArrayList<>();
        bindings.forEach((key, reference) -> secretBindings.add(Map.of("key", key, "secretReferenceId", reference)));
        String environmentRevision = mapper.readTree(post("/api/v1/projects/" + tenant.projectId() + "/environments",
                        tenant.bearer(), json(Map.of("name", "Environment " + UUID.randomUUID(), "variables", List.of(),
                                "secretBindings", secretBindings))).body())
                .at("/initialRevision/revisionId").stringValue();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", "Profile " + UUID.randomUUID());
        profile.put("environmentRevisionId", environmentRevision);
        profile.put("selection", Map.of("tags", List.of()));
        profile.put("parallelism", 1);
        profile.put("scenarioRetry", Map.of("maxAttempts", 1, "delayMilliseconds", 0));
        profile.put("executionTimeoutSeconds", 120);
        profile.put("artifactPolicy",
                Map.of("types", List.of("RAW_RESULT"), "maxArtifactBytes", 1_000, "maxTotalBytes", 2_000));
        profile.put("configurationOverrides", List.of());
        String profileRevision = mapper.readTree(post("/api/v1/projects/" + tenant.projectId() + "/run-profiles",
                        tenant.bearer(), json(profile)).body())
                .at("/initialRevision/revisionId").stringValue();
        return new Tenant(tenant.organizationId(), tenant.projectId(), tenant.bearer(), tenant.featureRevisionId(),
                profileRevision);
    }

    private String secretReference(Tenant tenant, String name) throws Exception {
        return mapper.readTree(post("/api/v1/projects/" + tenant.projectId() + "/secret-references", tenant.bearer(),
                        json(Map.of("name", name + UUID.randomUUID().toString().substring(0, 8)))).body())
                .get("secretReferenceId").stringValue();
    }

    private void writeVersion(Tenant tenant, String secretId, byte[] value) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                                + "/api/v1/projects/" + tenant.projectId() + "/secret-references/" + secretId + "/versions"))
                        .header("Authorization", "Bearer " + tenant.bearer())
                        .header("Content-Type", "application/octet-stream")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(value))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("version write answered %s", response.statusCode()).isEqualTo(201);
    }

    private void revoke(Tenant tenant, String secretId, int version) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                                + "/api/v1/projects/" + tenant.projectId() + "/secret-references/" + secretId
                                + "/versions/" + version + "/revocation"))
                        .header("Authorization", "Bearer " + tenant.bearer())
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private UUID allowlistFor(Tenant tenant, String host, int port, String scheme) {
        UUID policyId = UUID.randomUUID();
        jdbc.update(
                "insert into network_policy_revisions (policy_revision_id, policy_type, policy_version,"
                        + " canonical_digest, created_by, created_at, organization_id, project_id)"
                        + " values (?, 'ALLOWLIST', 1, ?, 'kaas.platform', now(), ?, ?)",
                policyId,
                NetworkPolicyRevision.digestOf(NetworkPolicyType.ALLOWLIST, 1,
                        List.of(new EgressDestination(host, port, EgressScheme.valueOf(scheme)))),
                tenant.organizationId(), tenant.projectId());
        jdbc.update("insert into network_policy_destinations (policy_revision_id, host, port, scheme)"
                + " values (?, ?, ?, ?)", policyId, host, port, scheme);
        jdbc.update("update projects set network_policy_revision_id = ? where project_id = ?",
                policyId, tenant.projectId());
        return policyId;
    }

    private UUID claimedRunFor(Tenant tenant) throws Exception {
        UUID runId = UUID.fromString(mapper.readTree(post("/api/v1/projects/" + tenant.projectId() + "/runs",
                        tenant.bearer(), json(Map.of("featureRevisionIds", List.of(tenant.featureRevisionId()),
                                "runProfileRevisionId", tenant.profileRevisionId()))).body())
                .get("runId").stringValue());
        scheduler.scheduleDue();
        String payload = String.valueOf(
                jdbc.queryForMap("select payload from execution_dispatches where run_id = ?", runId).get("payload"));
        claims.claim(mapper.readValue(payload, ExecutionDispatch.class), WORKER);
        return runId;
    }

    private UUID attemptId(UUID runId) {
        return jdbc.queryForObject("select current_attempt_id from test_runs where run_id = ?", UUID.class, runId);
    }

    private HttpResponse<String> post(String path, String bearer, String body) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + bearer)
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("POST %s answered %s", path, response.statusCode()).isBetween(200, 299);
        return response;
    }

    private String json(Object value) {
        return mapper.writeValueAsString(value);
    }

    /** A value generated here and never written in source, carrying the gate's nonce when there is one. */
    private static String generated() {
        byte[] entropy = new byte[18];
        RANDOM.nextBytes(entropy);
        return "kaas-sentinel-" + System.getProperty("kaas.test.sentinel-nonce", "local") + "-"
                + HexFormat.of().formatHex(entropy);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String token(String subject, UUID organizationId) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject(subject).audience(AUDIENCE)
                .issueTime(Date.from(now.minusSeconds(5)))
                .notBeforeTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(1800)));
        if (organizationId != null) {
            claims.claim("org_id", organizationId.toString());
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) SIGNING_KEY.getPrivate()));
        return jwt.serialize();
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
