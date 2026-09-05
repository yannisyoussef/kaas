package com.kaas.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.api.KaasApiApplication;
import com.kaas.api.controlplane.application.PendingRunScheduler;
import com.kaas.api.controlplane.application.RunClaimService;
import com.kaas.api.controlplane.domain.ExecutionDispatch;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * A run that outlives one lease period.
 *
 * <p>This class exists because of a defect the rest of the suite could not see. Heartbeating was refused for
 * every execution phase, so the lease could never be renewed once a worker started work — and with the shipped
 * thirty-second lease against a thirty-minute execution budget, every run longer than half a minute was refused
 * mid-flight and then recorded as having timed out during execution. Both halves of that were false.
 *
 * <p>The other pipeline tests could not observe it for two compounding reasons: they override the lease to two
 * minutes, and the synthetic workload finishes in a fraction of a second. The override was the symptom. Here
 * the lease is deliberately SHORT and the run deliberately spans more than one period, which is the only shape
 * in which renewal is load-bearing.
 */
@Testcontainers
@Import(LeaseRenewalPipelineTests.JwtTestConfiguration.class)
@SpringBootTest(
        classes = KaasApiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "kaas.scheduling.auto.enabled=false",
            "kaas.reaping.auto.enabled=false",
            "kaas.outbox.relay.enabled=false",
            "kaas.consumer.enabled=false",
            // The lease reconciler runs here, unlike elsewhere: fencing a lapsed lease during an execution
            // phase is half of what this class is about, and disabling it would hide that half.
            "kaas.claim.reconcile.enabled=false",
            "kaas.execution.reconcile.enabled=false",
            // Short on purpose. Long enough that a heartbeat can land inside it, short enough that a test can
            // outlive it without a slow suite.
            "kaas.claim.lease-duration=PT3S",
            "kaas.claim.recovery-window=PT1S",
            "kaas.execution.authorization-ttl=PT5M",
            "kaas.execution.capability-ttl=PT5M",
            "kaas.scheduling.queue-timeout=PT10M"
        })
class LeaseRenewalPipelineTests {

    private static final String ISSUER = "https://issuer.kaas.test";
    private static final String AUDIENCE = "kaas-api";
    private static final KeyPair SIGNING_KEY = keyPair();
    private static final String WORKER = "kaas.worker.lease";
    private static final Duration LEASE = Duration.ofSeconds(3);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16.10-alpine").withDatabaseName("kaas-lease");

    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PendingRunScheduler scheduler;
    @Autowired private RunClaimService claims;
    @Autowired private com.kaas.api.controlplane.application.WorkerLeaseService leases;

    @Test
    @DisplayName("a heartbeat renews the lease during an execution phase, and the run survives past it")
    void heartbeatingKeepsAnExecutingRunAlive() throws Exception {
        UUID runId = claimedRun();
        UUID attemptId = attemptId(runId);
        assertThat(advance(runId, attemptId, "PROVISIONING").statusCode()).isEqualTo(200);

        // The heartbeat has to work in PROVISIONING specifically. That is the exact state in which it was
        // refused, and refusing it there is what made every budget past the lease unreachable.
        Instant deadline = Instant.now().plus(LEASE).plusSeconds(2);
        while (Instant.now().isBefore(deadline)) {
            assertThat(heartbeat(runId, attemptId).statusCode())
                    .as("a heartbeat during PROVISIONING must be accepted")
                    .isEqualTo(200);
            Thread.sleep(500);
        }

        // More than one lease period has now elapsed since the claim. Without renewal the next advance is
        // refused; with it, the run continues.
        assertThat(advance(runId, attemptId, "RUNNING").statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                        "select lifecycle_state from test_runs where run_id = ?", String.class, runId))
                .isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("without heartbeating, the same run is refused once its lease lapses")
    void theLeaseGenuinelyExpiresWithoutRenewal() throws Exception {
        // The anti-vacuity twin. If the lease never actually expired, the test above would pass with
        // heartbeating deleted and would be proving nothing at all.
        UUID runId = claimedRun();
        UUID attemptId = attemptId(runId);
        assertThat(advance(runId, attemptId, "PROVISIONING").statusCode()).isEqualTo(200);

        Thread.sleep(LEASE.plusSeconds(1).toMillis());

        HttpResponse<String> refused = advance(runId, attemptId, "RUNNING");
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(mapper.readTree(refused.body()).get("code").asString()).isEqualTo("LEASE_EXPIRED");
    }

    @Test
    @DisplayName("a heartbeat is still refused once the run is genuinely over")
    void aCompletedRunStillRefusesHeartbeats() throws Exception {
        // Widening the heartbeat to the execution phases must not widen it to terminal ones. A late heartbeat
        // on a finished run must never bring an assignment back — that is the whole point of fencing.
        UUID runId = claimedRun();
        UUID attemptId = attemptId(runId);
        assertThat(advance(runId, attemptId, "PROVISIONING").statusCode()).isEqualTo(200);

        // Fenced through the real path, not by writing STOPPING directly. A hand-written pair of statements is
        // refused by the scheduling-bundle constraint — a stopping run must have its assignment fenced in the
        // same transaction — and reproducing that here would be reimplementing the thing under test badly.
        //
        // This also exercises the OTHER half of the widening: the lease reconciler must now be able to fence a
        // lapsed lease during an execution phase. Before, it could not see this run at all.
        Thread.sleep(LEASE.plusSeconds(1).toMillis());
        assertThat(leases.fenceExpired(runId))
                .as("the reconciler must be able to fence a lapsed lease during PROVISIONING")
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "select lifecycle_state from test_runs where run_id = ?", String.class, runId))
                .isEqualTo("STOPPING");

        // And a late heartbeat cannot bring the assignment back.
        assertThat(heartbeat(runId, attemptId).statusCode()).isEqualTo(409);
    }

    private HttpResponse<String> advance(UUID runId, UUID attemptId, String phase) throws Exception {
        return postInternal(
                "/internal/v1/runs/" + runId + "/attempts/" + attemptId + "/phases",
                json(Map.of("assignmentEpoch", 1, "phase", phase, "sandboxReference", "sandbox-lease")));
    }

    private HttpResponse<String> heartbeat(UUID runId, UUID attemptId) throws Exception {
        return postInternal(
                "/internal/v1/runs/" + runId + "/attempts/" + attemptId + "/heartbeat",
                json(Map.of("assignmentEpoch", 1)));
    }

    private HttpResponse<String> postInternal(String path, String body) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token(WORKER, null))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private UUID attemptId(UUID runId) {
        return jdbc.queryForObject(
                "select current_attempt_id from test_runs where run_id = ?", UUID.class, runId);
    }

    private UUID claimedRun() throws Exception {
        UUID organizationId = UUID.randomUUID();
        String bearer = token("lease-test", organizationId);
        String projectId = mapper.readTree(
                        post("/api/v1/projects", bearer, json(Map.of("name", "L " + UUID.randomUUID()))).body())
                .get("projectId")
                .stringValue();
        String featureRevision = mapper.readTree(post(
                                "/api/v1/projects/" + projectId + "/features",
                                bearer,
                                json(Map.of(
                                        "name", "Lease feature",
                                        "logicalPath", "features/l-" + UUID.randomUUID() + ".feature",
                                        "source", "Feature: a\nScenario: one\n* match 1 == 1\n")))
                        .body())
                .at("/initialRevision/revisionId")
                .stringValue();
        String environmentRevision = mapper.readTree(post(
                                "/api/v1/projects/" + projectId + "/environments",
                                bearer,
                                json(Map.of(
                                        "name", "Lease environment",
                                        "variables",
                                                List.of(Map.of(
                                                        "key", "baseUrl", "type", "STRING",
                                                        "value", "https://environment.example")),
                                        "secretBindings", List.of())))
                        .body())
                .at("/initialRevision/revisionId")
                .stringValue();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", "Lease profile");
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
        // A heartbeat acquires the assignment, exactly as a first authorization would. Until some authenticated
        // worker binds it, the attempt is held by nobody and no phase may be driven.
        assertThat(heartbeat(runId, attemptId(runId)).statusCode())
                .as("the first heartbeat must acquire the assignment")
                .isEqualTo(200);
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
