package com.kaas.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaas.api.execution.SignedAttestationFixture;
import com.kaas.api.execution.application.WorkerAttestations;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
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

/**
 * Attestation refresh, control-plane side (KAAS-DEPLOY-001): a runner delivers freshly signed evidence about its
 * own runtime, and the control plane keeps using it without a restart -- and without letting the runner vouch for
 * itself.
 *
 * <p>No attestation is configured here at all. Every document this control plane holds arrived through the
 * submission endpoint, so each assertion is about submitted evidence and nothing else.
 */
@Testcontainers
@Import(AttestationSubmissionTests.JwtTestConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "kaas.scheduling.auto.enabled=false",
            "kaas.reaping.auto.enabled=false",
            "kaas.outbox.relay.enabled=false",
            "kaas.consumer.enabled=false",
            "kaas.claim.reconcile.enabled=false",
            "kaas.execution.reconcile.enabled=false",
            "kaas.execution.attestation-max-age=PT24H"
        })
class AttestationSubmissionTests {
    private static final String ISSUER = "https://issuer.kaas.test";
    private static final String AUDIENCE = "kaas-api";
    private static final KeyPair SIGNING_KEY = keyPair();
    private static final String RUNNER_A = "kaas.worker.attest-a";
    private static final String RUNNER_B = "kaas.worker.attest-b";
    private static final String PROFILE = "kaas.sandbox.v1";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16.10-alpine").withDatabaseName("kaas-attestation-submission");

    @DynamicPropertySource
    static void trust(DynamicPropertyRegistry registry) {
        // One pinned key, one accepted runtime subject, one accepted runsc implementation. Nothing configured as
        // the attestation itself.
        registry.add("kaas.execution.attestation-trusted-keys",
                () -> SignedAttestationFixture.trustedKeys(SignedAttestationFixture.KEY_ID));
        registry.add("kaas.execution.attestation-runtime-subjects", () -> SignedAttestationFixture.RUNTIME_SUBJECT);
        registry.add("kaas.execution.attestation-runtime-implementations",
                () -> SignedAttestationFixture.RUNTIME_IMPLEMENTATION_DIGEST);
    }

    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WorkerAttestations workerAttestations;

    @AfterEach
    void clear() {
        jdbc.update("alter table sandbox_attestations disable trigger all");
        try {
            jdbc.update("delete from sandbox_attestations");
        } finally {
            jdbc.update("alter table sandbox_attestations enable trigger all");
        }
    }

    @Test
    void freshEvidenceSignedByAPinnedKeyIsAcceptedAndBecomesThisWorkersEvidence() throws Exception {
        String document = fresh("01JREFRESH0000000000000001", Duration.ofMinutes(1));

        HttpResponse<String> response = submit(RUNNER_A, document);

        assertThat(response.statusCode()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("code").stringValue()).isEqualTo("ACCEPTED");
        assertThat(Instant.parse(body.get("usableUntil").stringValue()))
                .isEqualTo(Instant.parse(body.get("assessedAt").stringValue()).plus(Duration.ofHours(24)));
        assertThat(workerAttestations.forWorker(RUNNER_A))
                .hasValueSatisfying(attestation ->
                        assertThat(attestation.payload().attestationId()).isEqualTo("01JREFRESH0000000000000001"));

        // A retry of the submission that landed is the same answer, not an error and not a second row.
        assertThat(submit(RUNNER_A, document).statusCode()).isEqualTo(200);
        assertThat(rows(RUNNER_A)).isEqualTo(1);
    }

    @Test
    void aNewerAssessmentReplacesTheOldOneWithoutARestartAndAnOlderOneCannotReplaceIt() throws Exception {
        assertThat(submit(RUNNER_A, fresh("01JREFRESH0000000000000010", Duration.ofHours(3))).statusCode())
                .isEqualTo(201);
        assertThat(submit(RUNNER_A, fresh("01JREFRESH0000000000000011", Duration.ofMinutes(1))).statusCode())
                .isEqualTo(201);
        assertThat(workerAttestations.forWorker(RUNNER_A).orElseThrow().payload().attestationId())
                .isEqualTo("01JREFRESH0000000000000011");

        // Replaying an older, still-valid document must not roll this worker's evidence back.
        HttpResponse<String> rollback = submit(RUNNER_A, fresh("01JREFRESH0000000000000012", Duration.ofHours(2)));
        assertThat(rollback.statusCode()).isEqualTo(422);
        assertThat(code(rollback)).isEqualTo("NOT_NEWER");
        assertThat(workerAttestations.forWorker(RUNNER_A).orElseThrow().payload().attestationId())
                .isEqualTo("01JREFRESH0000000000000011");
    }

    @Test
    void theSubmitterTransportsAndTheSignatureVouches() throws Exception {
        // A key nobody pinned: a runner cannot introduce its own trust root.
        String unpinned = SignedAttestationFixture.builder(PROFILE, Instant.now().minusSeconds(60))
                .withAttestationId("01JREFRESH0000000000000020")
                .sign(SignedAttestationFixture.SECOND_KEY_ID);
        assertRefused(RUNNER_A, unpinned, "UNKNOWN_KEY");

        // An edit after signing.
        String edited = fresh("01JREFRESH0000000000000021", Duration.ofMinutes(1))
                .replace("\"PASS\"", "\"PASS \"");
        assertThat(submit(RUNNER_A, edited).statusCode()).isEqualTo(422);

        // Not JSON, and too large.
        assertThat(submit(RUNNER_A, "{").statusCode()).isIn(400, 422);
        assertThat(submit(RUNNER_A, "{\"x\":\"" + "a".repeat(300_000) + "\"}").statusCode()).isIn(413, 422);

        assertThat(rows(RUNNER_A)).isZero();
        assertThat(workerAttestations.forWorker(RUNNER_A)).isEmpty();
    }

    @Test
    void expiredEvidenceIsRefusedRatherThanStored() throws Exception {
        assertRefused(RUNNER_A, fresh("01JREFRESH0000000000000030", Duration.ofHours(25)), "STALE");
    }

    @Test
    void aChangedRuntimeImplementationIsRefusedAndTheWorkerKeepsNoUsableEvidenceFromIt() throws Exception {
        // runsc was replaced on the host and the refresh measured the new binary -- which the operator has not
        // accepted. The document is honest and correctly signed, and it authorizes nothing.
        String changedRuntime = SignedAttestationFixture.builder(PROFILE, Instant.now().minusSeconds(60))
                .withAttestationId("01JREFRESH0000000000000040")
                .withRuntimeImplementationDigest("sha256:" + "f".repeat(64))
                .sign(SignedAttestationFixture.KEY_ID);
        assertRefused(RUNNER_A, changedRuntime, "RUNTIME_IMPLEMENTATION_MISMATCH");

        String foreignSubject = SignedAttestationFixture.builder(PROFILE, Instant.now().minusSeconds(60))
                .withAttestationId("01JREFRESH0000000000000041")
                .withRuntimeSubject("kaas.runtime.somebody-else")
                .sign(SignedAttestationFixture.KEY_ID);
        assertRefused(RUNNER_A, foreignSubject, "WRONG_SUBJECT");

        String failedControl = SignedAttestationFixture.builder(PROFILE, Instant.now().minusSeconds(60))
                .withAttestationId("01JREFRESH0000000000000042")
                .withMandatoryControl(firstMandatoryControl(), "FAIL")
                .sign(SignedAttestationFixture.KEY_ID);
        assertRefused(RUNNER_A, failedControl, "CONTROL_FAILED");
    }

    @Test
    void evidenceDescribesOneHostAndNeverVouchesForAnother() throws Exception {
        assertThat(submit(RUNNER_A, fresh("01JREFRESH0000000000000050", Duration.ofMinutes(1))).statusCode())
                .isEqualTo(201);

        // B submitted nothing and nothing is configured: B has no evidence, whatever A holds.
        assertThat(workerAttestations.forWorker(RUNNER_B)).isEmpty();
    }

    @Test
    void onlyAWorkerMaySubmitAndOnlyForItself() throws Exception {
        String document = fresh("01JREFRESH0000000000000060", Duration.ofMinutes(1));
        assertThat(submitAs(token("kaas.scheduler", null), document).statusCode()).isEqualTo(403);
        assertThat(submitAs(token("kaas.egress-proxy", null), document).statusCode()).isEqualTo(403);
        assertThat(submitAs(token("tenant-user", UUID.randomUUID()), document).statusCode()).isEqualTo(401);
        assertThat(submitAs(null, document).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("select count(*) from sandbox_attestations", Integer.class)).isZero();
    }

    @Test
    void submittedEvidenceIsAppendOnly() throws Exception {
        assertThat(submit(RUNNER_A, fresh("01JREFRESH0000000000000070", Duration.ofMinutes(1))).statusCode())
                .isEqualTo(201);
        for (String sql : List.of(
                "update sandbox_attestations set assessed_at = now()",
                "update sandbox_attestations set document = '{}'",
                "delete from sandbox_attestations")) {
            assertThatThrownBy(() -> jdbc.update(sql))
                    .as(sql)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("submitted attestations are immutable evidence");
        }
    }

    private static String firstMandatoryControl() {
        return com.kaas.api.execution.domain.RequiredSecurityControls.mandatoryFor(PROFILE).iterator().next();
    }

    private static String fresh(String attestationId, Duration age) {
        return SignedAttestationFixture.builder(PROFILE, Instant.now().minus(age))
                .withAttestationId(attestationId)
                .sign(SignedAttestationFixture.KEY_ID);
    }

    private void assertRefused(String worker, String document, String expectedCode) throws Exception {
        HttpResponse<String> response = submit(worker, document);
        assertThat(response.statusCode()).as(expectedCode).isEqualTo(422);
        assertThat(code(response)).isEqualTo(expectedCode);
        assertThat(rows(worker)).isZero();
    }

    private String code(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body()).get("code").stringValue();
    }

    private int rows(String worker) {
        return jdbc.queryForObject(
                "select count(*) from sandbox_attestations where worker_id = ?", Integer.class, worker);
    }

    private HttpResponse<String> submit(String worker, String document) throws Exception {
        return submitAs(token(worker, null), document);
    }

    private HttpResponse<String> submitAs(String bearer, String document) throws Exception {
        var request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/internal/v1/sandbox-attestations"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(document, StandardCharsets.UTF_8));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) SIGNING_KEY.getPublic()).build();
            var audience = new JwtClaimValidator<List<String>>(
                    "aud", values -> values != null && values.contains(AUDIENCE));
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(ISSUER), audience));
            return decoder;
        }
    }
}
