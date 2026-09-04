package com.kaas.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.api.controlplane.application.PendingRunScheduler;
import com.kaas.api.controlplane.application.RunClaimService;
import com.kaas.api.controlplane.domain.ExecutionDispatch;
import com.kaas.api.execution.application.ExecutionAuthorizationService;
import com.kaas.api.execution.domain.ExecutionDenial;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * What the authorization decision does when the sandbox evidence is absent or cannot be relied on.
 *
 * <p>This class exists because mutation testing said it had to. The main suite always runs with a valid
 * attestation configured, so deleting the attestation checks from the service left every one of its tests
 * green — the refusals were covered only by unit tests of the document, and the service's own handling of
 * missing evidence was covered by nothing at all. That is the precise shape of an evidence-axis blind spot:
 * a control that has only ever been exercised in the state where it does nothing.
 *
 * <p>Two contexts rather than two methods, because the attestation is read once at startup. Making it settable
 * per test would mean making a deployment's security posture settable at runtime, which is the one thing it
 * must not be.
 */
@Testcontainers
class ExecutionAuthorizationGateRefusalTests {
    private static final String ISSUER = "https://issuer.kaas.test";
    private static final String AUDIENCE = "kaas-api";
    private static final KeyPair SIGNING_KEY = keyPair();
    private static final String WORKER = "kaas.worker.local";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16.10-alpine").withDatabaseName("kaas-gate");

    @Nested
    @Import(JwtTestConfiguration.class)
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = BASE)
    @TestPropertySource(properties = "kaas.execution.sandbox-attestation=")
    class WhenNoAssessmentIsConfigured extends Fixture {
        @Test
        @Timeout(120)
        void anOtherwisePerfectlyValidAssignmentIsStillRefused() throws Exception {
            UUID runId = claimedRun();

            // Everything else is in order: the run is claimed, the lease is live, the worker and epoch match,
            // the snapshot is sealed. The only missing thing is evidence that this deployment's sandbox
            // confines anything, and that alone is disqualifying.
            assertThat(authorizations.authorize(runId, attemptId(runId), 1, WORKER).denial())
                    .contains(ExecutionDenial.SECURITY_GATE_UNAVAILABLE);
            assertThat(jdbc.queryForObject("select count(*) from execution_authorizations", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForObject("select count(*) from execution_commands", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from execution_capabilities", Integer.class))
                    .isZero();
        }
    }

    @Nested
    @Import(JwtTestConfiguration.class)
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = BASE)
    class WhenTheAssessmentReportsAFailingControl extends Fixture {

        /**
         * Generated fresh rather than pasted.
         *
         * <p>A hardcoded assessedAt would make this class start refusing on FRESHNESS once it aged past the
         * maximum — while its name and its assertion both say the refusal is about a failing control. The two
         * are indistinguishable at the denial enum, so the test would keep passing and stop testing.
         */
        @org.springframework.test.context.DynamicPropertySource
        static void freshFailingAttestation(
                org.springframework.test.context.DynamicPropertyRegistry registry) {
            registry.add(
                    "kaas.execution.sandbox-attestation",
                    () -> ExecutionSecurityGateDependencyTests.failingAttestation(java.time.Instant.now()));
        }

        @Test
        @Timeout(120)
        void aReadableAssessmentThatSaysTheSandboxIsBrokenIsNotEvidenceThatItWorks() throws Exception {
            UUID runId = claimedRun();

            // The document parses. It is well-formed, correctly digested, and for the right profile. It also
            // says READ_ONLY_ROOT did not pass, and a gate that accepted it would be certifying a sandbox its
            // own evidence describes as broken.
            assertThat(authorizations.authorize(runId, attemptId(runId), 1, WORKER).denial())
                    .contains(ExecutionDenial.SECURITY_GATE_FAILED);
            assertThat(jdbc.queryForObject("select count(*) from execution_authorizations", Integer.class))
                    .isZero();
        }
    }

    /** Shared setup. The attestation is the only thing that differs between the contexts above. */
    abstract class Fixture {
        @Value("${local.server.port}")
        protected int port;

        @Autowired protected ObjectMapper objectMapper;
        @Autowired protected JdbcTemplate jdbc;
        @Autowired protected PendingRunScheduler scheduler;
        @Autowired protected RunClaimService claims;
        @Autowired protected ExecutionAuthorizationService authorizations;

        private final HttpClient client = HttpClient.newHttpClient();

        protected UUID claimedRun() throws Exception {
            UUID organizationId = UUID.randomUUID();
            String bearer = token("gate-test", organizationId);
            String projectId = objectMapper
                    .readTree(post("/api/v1/projects", bearer, json(Map.of("name", "P " + UUID.randomUUID())))
                            .body())
                    .get("projectId")
                    .stringValue();
            String featureRevision = objectMapper
                    .readTree(post(
                                    "/api/v1/projects/" + projectId + "/features",
                                    bearer,
                                    json(Map.of(
                                            "name", "Gate feature",
                                            "logicalPath", "features/g-" + UUID.randomUUID() + ".feature",
                                            "source", "Feature: a\nScenario: one\n* match 1 == 1\n")))
                            .body())
                    .at("/initialRevision/revisionId")
                    .stringValue();
            String environmentRevision = objectMapper
                    .readTree(post(
                                    "/api/v1/projects/" + projectId + "/environments",
                                    bearer,
                                    json(Map.of(
                                            "name", "Gate environment",
                                            "variables",
                                                    List.of(Map.of(
                                                            "key", "baseUrl", "type", "STRING",
                                                            "value", "https://environment.example")),
                                            "secretBindings", List.of())))
                            .body())
                    .at("/initialRevision/revisionId")
                    .stringValue();
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("name", "Gate profile");
            profile.put("environmentRevisionId", environmentRevision);
            profile.put("selection", Map.of("tags", List.of("@smoke")));
            profile.put("parallelism", 1);
            profile.put("scenarioRetry", Map.of("maxAttempts", 1, "delayMilliseconds", 0));
            profile.put("executionTimeoutSeconds", 60);
            profile.put(
                    "artifactPolicy",
                    Map.of("types", List.of("RAW_RESULT"), "maxArtifactBytes", 1_000, "maxTotalBytes", 2_000));
            profile.put("configurationOverrides", List.of());
            String profileRevision = objectMapper
                    .readTree(post("/api/v1/projects/" + projectId + "/run-profiles", bearer, json(profile))
                            .body())
                    .at("/initialRevision/revisionId")
                    .stringValue();

            UUID runId = UUID.fromString(objectMapper
                    .readTree(post(
                                    "/api/v1/projects/" + projectId + "/runs",
                                    bearer,
                                    json(Map.of(
                                            "featureRevisionIds", List.of(featureRevision),
                                            "runProfileRevisionId", profileRevision)))
                            .body())
                    .get("runId")
                    .stringValue());
            scheduler.scheduleDue();
            claims.claim(dispatchFor(runId), WORKER);
            return runId;
        }

        protected UUID attemptId(UUID runId) {
            return jdbc.queryForObject(
                    "select attempt_id from execution_attempts where run_id = ?", UUID.class, runId);
        }

        private ExecutionDispatch dispatchFor(UUID runId) throws Exception {
            String payload = String.valueOf(jdbc.queryForMap(
                            "select payload from execution_dispatches where run_id = ?", runId)
                    .get("payload"));
            return objectMapper.readValue(payload, ExecutionDispatch.class);
        }

        private HttpResponse<String> post(String path, String bearer, String body) throws Exception {
            return client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                            .header("Accept", "application/json")
                            .header("Authorization", "Bearer " + bearer)
                            .header("Idempotency-Key", "key-" + UUID.randomUUID())
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        private String json(Object value) {
            return objectMapper.writeValueAsString(value);
        }
    }

    static final String BASE = "kaas.scheduling.auto.enabled=false";

    private static String token(String subject, UUID organizationId) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(subject)
                .audience(AUDIENCE)
                .issueTime(Date.from(now.minusSeconds(5)))
                .notBeforeTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(900)))
                .claim("org_id", organizationId.toString());
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
