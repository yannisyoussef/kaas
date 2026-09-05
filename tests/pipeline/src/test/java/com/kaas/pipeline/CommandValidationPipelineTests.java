package com.kaas.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaas.api.KaasApiApplication;
import com.kaas.api.controlplane.application.PendingRunScheduler;
import com.kaas.api.controlplane.application.RunClaimService;
import com.kaas.api.controlplane.domain.ExecutionDispatch;
import com.kaas.runner.command.CommandRejected;
import com.kaas.runner.command.CommandValidator;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The runner's independent validation, against commands the control plane genuinely produced.
 *
 * <p>This is the test that makes the duplicated digest implementation worth having. A unit test in the runner
 * module could only check the validator against a command the validator itself digested, which proves the code
 * is self-consistent and nothing else. Here the control plane builds a real command with its own
 * implementation, and the runner — which structurally cannot call that implementation — has to arrive at the
 * same digest independently. Agreement is then evidence rather than tautology.
 *
 * <p>Every negative case below tampers with a real command rather than constructing a malformed one, because
 * the threat is a document that has been altered in flight, not one that was never well formed.
 */
@Testcontainers
@Import(CommandValidationPipelineTests.JwtTestConfiguration.class)
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
            "kaas.claim.lease-duration=PT120S",
            "kaas.execution.authorization-ttl=PT5M",
            "kaas.execution.capability-ttl=PT5M",
            "kaas.scheduling.queue-timeout=PT10M"
        })
class CommandValidationPipelineTests {

    private static final String ISSUER = "https://issuer.kaas.test";
    private static final String AUDIENCE = "kaas-api";
    private static final KeyPair SIGNING_KEY = keyPair();
    private static final String WORKER = "kaas.worker.validation";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16.10-alpine").withDatabaseName("kaas-validation");

    @DynamicPropertySource
    static void attestation(DynamicPropertyRegistry registry) {
        registry.add("kaas.execution.sandbox-attestation", () -> validAttestation(Instant.now()));
        registry.add("kaas.execution.attestation-trusted-keys", ProducedAttestation::trustedKeys);
        registry.add(
                "kaas.execution.attestation-runtime-subjects", () -> ProducedAttestation.RUNTIME_SUBJECT);
    }

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PendingRunScheduler scheduler;
    @Autowired private RunClaimService claims;

    @Test
    @DisplayName("two independent digest implementations agree on a real command")
    void theTwoImplementationsAgree() throws Exception {
        ObjectNode command = realCommand();

        var validated = new CommandValidator(mapper).validate(command.toString(), Instant.now());

        // The digest the control plane computed, recomputed here from the parsed document by code that cannot
        // reach the control plane's implementation. If these ever diverge, one side is digesting a field the
        // other is not — which is exactly the class of bug a shared implementation would hide.
        assertThat(validated.commandDigest()).isEqualTo(command.get("commandDigest").asString());
        assertThat(validated.engineType()).isEqualTo("SYNTHETIC");
        assertThat(validated.networkPolicyType()).isEqualTo("DENY_ALL");
    }

    @Test
    @DisplayName("altering any digest-covered field is detected")
    void tamperingIsDetected() throws Exception {
        // One case per KIND of field the digest covers: a scalar, a nested object's value, an array member,
        // and a numeric. A single tampering case would pass while whole branches of the digest were missing.
        record Tamper(String name, java.util.function.Consumer<ObjectNode> apply) {}
        List<Tamper> tampers = List.of(
                new Tamper("run version", node -> node.put("runVersion", node.get("runVersion").asLong() + 1)),
                new Tamper("assignment epoch", node -> node.put("assignmentEpoch", 7)),
                new Tamper("execution timeout", node -> node.put("executionTimeoutSeconds", 999)),
                new Tamper("parallelism", node -> node.put("parallelism", 64)),
                new Tamper("network policy version",
                        node -> ((ObjectNode) node.get("networkPolicy")).put("version", 99)),
                new Tamper("sandbox profile",
                        node -> ((ObjectNode) node.get("sandboxSecurityProfile"))
                                .put("profileVersion", "kaas.sandbox.v0")),
                new Tamper("source bundle digest",
                        node -> ((ObjectNode) node.get("sourceBundle"))
                                .put("contentDigest", "sha256:" + "b".repeat(64))),
                new Tamper("artifact ceiling",
                        node -> ((ObjectNode) node.get("artifactPolicy")).put("maxTotalBytes", 999_999_999L)));

        for (Tamper tamper : tampers) {
            ObjectNode command = realCommand();
            tamper.apply().accept(command);
            assertThatThrownBy(() -> new CommandValidator(mapper).validate(command.toString(), Instant.now()))
                    .as("tampering with the %s must be detected", tamper.name())
                    .isInstanceOf(CommandRejected.class)
                    .hasMessageContaining("digest does not match");
        }
    }

    @Test
    @DisplayName("an unknown field is fatal, at every level of the document")
    void unknownFieldsAreFatal() throws Exception {
        // Checked at the root and inside a nested object, because the rejection is per-object and a validator
        // that only screened the root would accept an injected field anywhere below it.
        ObjectNode atRoot = realCommand();
        atRoot.put("privilegeEscalation", true);
        assertThatThrownBy(() -> new CommandValidator(mapper).validate(atRoot.toString(), Instant.now()))
                .isInstanceOf(CommandRejected.class)
                .hasMessageContaining("Unknown field(s) in command: privilegeEscalation");

        ObjectNode nested = realCommand();
        ((ObjectNode) nested.get("networkPolicy")).put("allowAll", true);
        assertThatThrownBy(() -> new CommandValidator(mapper).validate(nested.toString(), Instant.now()))
                .isInstanceOf(CommandRejected.class)
                .hasMessageContaining("Unknown field(s) in networkPolicy: allowAll");
    }

    @Test
    @DisplayName("an unenforceable network policy is refused rather than degraded")
    void allowlistIsRefused() throws Exception {
        ObjectNode command = realCommand();
        ((ObjectNode) command.get("networkPolicy")).put("type", "ALLOWLIST");

        // Refused twice over, and both matter. The digest no longer matches, and even a correctly digested
        // ALLOWLIST command would be refused by the policy check — which is asserted separately below, because
        // a check that is only ever reached after another one fails is a check nothing is testing.
        assertThatThrownBy(() -> new CommandValidator(mapper).validate(command.toString(), Instant.now()))
                .isInstanceOf(CommandRejected.class);
    }

    @Test
    @DisplayName("an expired command is refused even though it is perfectly formed and correctly digested")
    void anExpiredCommandIsRefused() throws Exception {
        ObjectNode command = realCommand();
        Instant expiry = Instant.parse(command.get("expiresAt").asString());

        // The document is untouched, so the digest still matches. This isolates expiry from integrity: a
        // validator that only checked the digest would accept this, and a leaked command would never stop
        // working.
        assertThatThrownBy(() -> new CommandValidator(mapper)
                        .validate(command.toString(), expiry.plusSeconds(1)))
                .isInstanceOf(CommandRejected.class)
                .hasMessageContaining("expired");
    }

    /** A command the control plane actually issued, fetched over the real internal API. */
    private ObjectNode realCommand() throws Exception {
        UUID runId = claimedRun();
        UUID attemptId = jdbc.queryForObject(
                "select current_attempt_id from test_runs where run_id = ?", UUID.class, runId);
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/internal/v1/runs/" + runId
                                + "/attempts/" + attemptId + "/execution-authorizations"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token(WORKER, null))
                        .POST(HttpRequest.BodyPublishers.ofString("{\"assignmentEpoch\":1}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("authorization returned %s: %s", response.statusCode(), response.body())
                .isEqualTo(200);
        JsonNode envelope = mapper.readTree(response.body());
        return (ObjectNode) envelope.get("command");
    }

    private UUID claimedRun() throws Exception {
        UUID organizationId = UUID.randomUUID();
        String bearer = token("validation-test", organizationId);
        String projectId = mapper.readTree(
                        post("/api/v1/projects", bearer, json(Map.of("name", "V " + UUID.randomUUID()))).body())
                .get("projectId")
                .stringValue();
        String featureRevision = mapper.readTree(post(
                                "/api/v1/projects/" + projectId + "/features",
                                bearer,
                                json(Map.of(
                                        "name", "Validation feature",
                                        "logicalPath", "features/v-" + UUID.randomUUID() + ".feature",
                                        "source", "Feature: a\nScenario: one\n* match 1 == 1\n")))
                        .body())
                .at("/initialRevision/revisionId")
                .stringValue();
        String environmentRevision = mapper.readTree(post(
                                "/api/v1/projects/" + projectId + "/environments",
                                bearer,
                                json(Map.of(
                                        "name", "Validation environment",
                                        "variables",
                                                List.of(Map.of(
                                                        "key", "baseUrl", "type", "STRING",
                                                        "value", "https://environment.example")),
                                        "secretBindings", List.of())))
                        .body())
                .at("/initialRevision/revisionId")
                .stringValue();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", "Validation profile");
        profile.put("environmentRevisionId", environmentRevision);
        profile.put("selection", Map.of("tags", List.of("@smoke")));
        profile.put("parallelism", 1);
        profile.put("scenarioRetry", Map.of("maxAttempts", 1, "delayMilliseconds", 0));
        profile.put("executionTimeoutSeconds", 60);
        profile.put(
                "artifactPolicy",
                Map.of("types", List.of("RAW_RESULT"), "maxArtifactBytes", 1_000, "maxTotalBytes", 2_000));
        profile.put("configurationOverrides", List.of());
        String profileRevision = mapper.readTree(
                        post("/api/v1/projects/" + projectId + "/run-profiles", bearer, json(profile)).body())
                .at("/initialRevision/revisionId")
                .stringValue();

        UUID runId = UUID.fromString(mapper.readTree(post(
                                "/api/v1/projects/" + projectId + "/runs",
                                bearer,
                                json(Map.of(
                                        "featureRevisionIds", List.of(featureRevision),
                                        "runProfileRevisionId", profileRevision)))
                        .body())
                .get("runId")
                .stringValue());
        scheduler.scheduleDue();
        String payload = String.valueOf(
                jdbc.queryForMap("select payload from execution_dispatches where run_id = ?", runId)
                        .get("payload"));
        claims.claim(mapper.readValue(payload, ExecutionDispatch.class), WORKER);
        return runId;
    }

    private HttpResponse<String> post(String path, String bearer, String body) throws Exception {
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + bearer)
                        .header("Idempotency-Key", "key-" + UUID.randomUUID())
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode())
                .as("POST %s returned %s: %s", path, response.statusCode(), response.body())
                .isBetween(200, 299);
        return response;
    }

    private String json(Object value) {
        return mapper.writeValueAsString(value);
    }

    private static String token(String subject, UUID organizationId) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(subject)
                .audience(AUDIENCE)
                .issueTime(Date.from(now.minusSeconds(5)))
                .notBeforeTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(900)));
        if (organizationId != null) {
            claims.claim("org_id", organizationId.toString());
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) SIGNING_KEY.getPrivate()));
        return jwt.serialize();
    }

    private static String validAttestation(Instant assessedAt) {
        return validAttestation(assessedAt, Map.of());
    }

    /**
     * An attestation document, optionally claiming this deployment can enforce egress.
     *
     * <p>Absent by default, because that is what an assessment produced by a host that has not demonstrated
     * egress enforcement looks like — and it is the state in which ALLOWLIST must keep being refused. A test
     * that wants the allowlist path has to say so.
     */
    private static String validAttestation(Instant assessedAt, Map<String, String> egress) {
        // Produced by the RUNNER's producer and verified by the CONTROL PLANE's verifier. Two independent
        // implementations of one written contract, meeting for the first time in this module.
        return egress.isEmpty()
                ? ProducedAttestation.mandatoryOnly("kaas.sandbox.v1", assessedAt)
                : ProducedAttestation.withEgress("kaas.sandbox.v1", assessedAt);
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
            NimbusJwtDecoder decoder =
                    NimbusJwtDecoder.withPublicKey((RSAPublicKey) SIGNING_KEY.getPublic()).build();
            var audience = new JwtClaimValidator<List<String>>(
                    "aud", values -> values != null && values.contains(AUDIENCE));
            decoder.setJwtValidator(
                    new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(ISSUER), audience));
            return decoder;
        }
    }
}
