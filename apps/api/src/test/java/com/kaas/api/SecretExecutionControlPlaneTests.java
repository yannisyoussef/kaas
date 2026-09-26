package com.kaas.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.api.controlplane.application.PendingRunScheduler;
import com.kaas.api.controlplane.application.RunClaimService;
import com.kaas.api.controlplane.domain.ExecutionDispatch;
import com.kaas.api.execution.SignedAttestationFixture;
import com.kaas.api.secrets.domain.SecretProviderException;
import com.kaas.api.secrets.domain.SecretTransit;
import com.kaas.api.secrets.domain.TransitContext;
import com.kaas.api.secrets.infrastructure.VaultTransitClient;
import com.kaas.api.testing.VaultTransitFixture;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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

/**
 * The control plane's half of secret execution, against a REAL Vault Transit.
 *
 * <p>Real: PostgreSQL with every migration, the control plane over real HTTP on its real filter chains, and
 * Vault 1.18.5 with a derived {@code kaas-tenant-secrets} key reached through an AppRole whose policy grants
 * encrypt and decrypt on that key and nothing else. Every plaintext here is generated at runtime; none is
 * written in this file.
 *
 * <p>The one seam is {@link CountingTransit}: a delegating wrapper around the production Transit bean — the
 * same {@link VaultTransitClient} the application builds from its own configuration — that counts calls and can
 * run a hook in the middle of a decryption. It performs no cryptography of its own. It exists because "the
 * provider was never asked" and "authority was lost while the provider was working" are claims about timing and
 * absence, and neither can be observed from outside the process.
 *
 * <p><strong>No assertion here compares a plaintext through a message that would print it.</strong> Equality is
 * checked with {@link Arrays#equals} into a boolean, and absence by scanning in-process. A failing assertion
 * that printed the value would put it in the JUnit XML, which is the test-report leak this slice closes.
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Import(SecretExecutionControlPlaneTests.TestWiring.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "kaas.scheduling.auto.enabled=false",
            "kaas.reaping.auto.enabled=false",
            "kaas.outbox.relay.enabled=false",
            "kaas.consumer.enabled=false",
            "kaas.claim.reconcile.enabled=false",
            "kaas.execution.reconcile.enabled=false",
            "kaas.claim.lease-duration=PT60S",
            "kaas.claim.recovery-window=PT30S",
            "kaas.execution.authorization-ttl=PT5M",
            "kaas.execution.capability-ttl=PT5M",
            "kaas.scheduling.queue-timeout=PT5M",
            "kaas.admission.max-active-runs-per-organization=50",
            "kaas.admission.max-queued-runs-per-organization=50",
            // The engine that can consume a secret. The synthetic workload cannot, and is refused one.
            "kaas.engine.type=KARATE",
            "kaas.engine.version=2.1.2"
        })
class SecretExecutionControlPlaneTests {
    private static final String ISSUER = "https://issuer.kaas.test";
    private static final String AUDIENCE = "kaas-api";
    private static final KeyPair SIGNING_KEY = keyPair();
    private static final String WORKER = "kaas.worker.local";
    private static final String OTHER_WORKER = "kaas.worker.other";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16.10-alpine").withDatabaseName("kaas-secrets");

    static final VaultTransitFixture VAULT = new VaultTransitFixture();

    @AfterAll
    static void stopVault() {
        VAULT.close();
    }

    @DynamicPropertySource
    static void configuration(DynamicPropertyRegistry registry) {
        registry.add("kaas.execution.sandbox-attestation",
                () -> SignedAttestationFixture.mandatoryOnly("kaas.sandbox.v1", Instant.now()));
        registry.add("kaas.execution.attestation-trusted-keys",
                () -> SignedAttestationFixture.trustedKeys(SignedAttestationFixture.KEY_ID));
        registry.add("kaas.execution.attestation-runtime-subjects", () -> SignedAttestationFixture.RUNTIME_SUBJECT);
        registry.add("kaas.execution.attestation-runtime-implementations",
                () -> SignedAttestationFixture.RUNTIME_IMPLEMENTATION_DIGEST);
        // The five variables of the Operations contract, exactly as a deployment supplies them.
        registry.add("kaas.secrets.vault.address", VAULT::address);
        registry.add("kaas.secrets.vault.role-id", VAULT::roleId);
        registry.add("kaas.secrets.vault.secret-id", VAULT::secretId);
        registry.add("kaas.secrets.vault.ca-cert", () -> VAULT.caFile().toString());
        registry.add("kaas.secrets.vault.transit-key", () -> VaultTransitFixture.TRANSIT_KEY);
    }

    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PendingRunScheduler scheduler;
    @Autowired private RunClaimService claims;
    @Autowired private CountingTransit transit;

    @Autowired
    @Qualifier("secretTransit")
    private SecretTransit productionTransit;

    // ------------------------------------------------------------------ the write path

    @Test
    @Order(1)
    @Timeout(120)
    void theApplicationWiresTheRealTransitFromTheFiveVariables() {
        // The provider under test is the one the application built from configuration, not one this test made.
        assertThat(productionTransit).isInstanceOf(VaultTransitClient.class);
        assertThat(productionTransit.configured()).isTrue();
    }

    @Test
    @Order(2)
    @Timeout(120)
    void aWrittenVersionIsStoredOnlyAsARealTransitCiphertext(CapturedOutput output) throws Exception {
        Tenant tenant = tenant();
        String secretId = secretReference(tenant, "apiToken");
        byte[] value = generated();

        HttpResponse<String> created = writeVersion(tenant, secretId, value.clone());

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        JsonNode body = objectMapper.readTree(created.body());
        assertThat(body.get("version").asInt()).isEqualTo(1);
        assertThat(body.propertyNames())
                .as("metadata only: no value, no ciphertext, no length, no digest")
                .containsExactlyInAnyOrder(
                        "secretReferenceId", "version", "createdBy", "createdAt", "revoked", "revokedAt");
        assertThat(contains(created.body(), value)).as("the response echoes nothing of the value").isFalse();

        String ciphertext = jdbc.queryForObject(
                "select c.ciphertext from secret_version_ciphertexts c join secret_versions v"
                        + " on v.secret_version_id = c.secret_version_id where v.secret_reference_id = ?",
                String.class, UUID.fromString(secretId));
        assertThat(ciphertext).startsWith("vault:v1:");
        // INDEPENDENT EVIDENCE that Vault encrypted the submitted value: Vault itself, through the operator's
        // root token rather than the platform's AppRole, decrypts the stored row back to exactly those bytes.
        assertThat(Arrays.equals(VAULT.decryptAsOperator(ciphertext, tenant.organizationId(), tenant.projectId()), value))
                .as("the stored ciphertext is Vault's encryption of the submitted value under this tenant's context")
                .isTrue();
        assertThat(VAULT.operatorCanDecrypt(ciphertext, tenant.organizationId(), UUID.randomUUID()))
                .as("and it does not decrypt under a sibling project's context")
                .isFalse();

        assertThat(databaseContains(value)).as("secret_in_database").isFalse();
        assertThat(contains(output.getAll(), value)).as("secret_in_logs").isFalse();
    }

    @Test
    @Order(3)
    @Timeout(120)
    void theWriteSurfaceRefusesEverythingItShould() throws Exception {
        Tenant tenant = tenant();
        String secretId = secretReference(tenant, "strict");

        assertThat(writeVersion(tenant, secretId, new byte[0]).statusCode()).as("empty").isEqualTo(422);
        assertThat(writeVersion(tenant, secretId, new byte[8193]).statusCode()).as("oversized").isEqualTo(422);
        assertThat(writeVersion(tenant, secretId, new byte[] {(byte) 0xc3, 0x28}).statusCode())
                .as("not UTF-8").isEqualTo(422);
        assertThat(send(HttpRequest.newBuilder(versionsUri(tenant, secretId))
                                .header("Authorization", "Bearer " + tenant.bearer())
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString("{\"value\":\"x\"}"))
                                .build())
                        .statusCode())
                .as("a JSON body is not a secret value")
                .isEqualTo(422);
        assertThat(jdbc.queryForObject(
                        "select count(*) from secret_versions where secret_reference_id = ?", Integer.class,
                        UUID.fromString(secretId)))
                .as("no refused write stored anything")
                .isZero();

        // Tenancy: another organization, and a sibling project in the SAME organization, both see nothing.
        Tenant stranger = tenant();
        assertThat(writeVersion(stranger, secretId, generated()).statusCode()).isEqualTo(404);
        Tenant sibling = siblingProject(tenant);
        assertThat(send(HttpRequest.newBuilder(URI.create(base() + "/api/v1/projects/" + sibling.projectId()
                                        + "/secret-references/" + secretId + "/versions"))
                                .header("Authorization", "Bearer " + tenant.bearer())
                                .header("Content-Type", "application/octet-stream")
                                .POST(HttpRequest.BodyPublishers.ofByteArray(generated()))
                                .build())
                        .statusCode())
                .as("a reference is not writable through another project's path")
                .isEqualTo(404);
        // The takeover audit's suspicion, pinned: a sibling project in the same organization cannot read the
        // reference's METADATA by id either.
        assertThat(send(HttpRequest.newBuilder(URI.create(base() + "/api/v1/projects/" + sibling.projectId()
                                        + "/secret-references/" + secretId))
                                .header("Authorization", "Bearer " + tenant.bearer())
                                .GET()
                                .build())
                        .statusCode())
                .isEqualTo(404);

        // And there is no read of a value: not a version, not a history.
        writeVersion(tenant, secretId, generated());
        assertThat(send(HttpRequest.newBuilder(URI.create(versionsUri(tenant, secretId) + "/1"))
                                .header("Authorization", "Bearer " + tenant.bearer())
                                .GET()
                                .build())
                        .statusCode())
                .isIn(404, 405);
        HttpResponse<String> listed = send(HttpRequest.newBuilder(versionsUri(tenant, secretId))
                .header("Authorization", "Bearer " + tenant.bearer())
                .GET()
                .build());
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body()).doesNotContain("vault:").doesNotContain("ciphertext").doesNotContain("value");
    }

    // ------------------------------------------------------------------ pinning, rotation, revocation

    @Test
    @Order(10)
    @Timeout(180)
    void eachRunReceivesTheVersionItPinnedAndNeverTheLatest() throws Exception {
        Tenant tenant = tenant();
        String secretId = secretReference(tenant, "rotating");
        byte[] first = generated();
        byte[] second = generated();
        writeVersion(tenant, secretId, first.clone());
        Tenant bound = bind(tenant, Map.of("API_TOKEN", secretId));
        UUID runA = claimedRun(bound);

        writeVersion(tenant, secretId, second.clone());
        UUID runB = claimedRun(bound);

        // Run A was created before the rotation. It executes with version 1, even though 2 now exists.
        Map<String, byte[]> deliveredToA = redeem(authorize(runA).get("secretCapabilityToken").stringValue());
        Map<String, byte[]> deliveredToB = redeem(authorize(runB).get("secretCapabilityToken").stringValue());
        assertThat(Arrays.equals(deliveredToA.get("API_TOKEN"), first)).as("run A received version 1").isTrue();
        assertThat(Arrays.equals(deliveredToB.get("API_TOKEN"), second)).as("run B received version 2").isTrue();
        assertThat(deliveredToA.keySet()).containsExactly("API_TOKEN");
    }

    @Test
    @Order(11)
    @Timeout(180)
    void aRevokedVersionFailsClosedAndNeverAdvancesToTheNextOne() throws Exception {
        Tenant tenant = tenant();
        String secretId = secretReference(tenant, "revoked");
        writeVersion(tenant, secretId, generated());
        Tenant bound = bind(tenant, Map.of("API_TOKEN", secretId));
        UUID pinnedToOne = claimedRun(bound);
        writeVersion(tenant, secretId, generated());

        HttpResponse<String> revoked = revoke(tenant, secretId, 1);
        assertThat(revoked.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(revoked.body()).get("revoked").asBoolean()).isTrue();

        int callsBefore = transit.decrypts.get();
        HttpResponse<String> refused = postInternal(
                "/internal/v1/runs/" + pinnedToOne + "/attempts/" + attemptId(pinnedToOne) + "/execution-authorizations",
                serviceToken(WORKER), "{\"assignmentEpoch\":1}");
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(objectMapper.readTree(refused.body()).get("code").stringValue()).isEqualTo("SECRET_VERSION_REVOKED");
        assertThat(transit.decrypts.get()).as("revocation is decided without decrypting anything").isEqualTo(callsBefore);

        // CRYPTO-SHREDDED: the ciphertext of version 1 is gone, and its metadata and revocation record remain.
        assertThat(jdbc.queryForObject(
                        "select count(*) from secret_version_ciphertexts c join secret_versions v"
                                + " on v.secret_version_id = c.secret_version_id"
                                + " where v.secret_reference_id = ? and v.version_number = 1",
                        Integer.class, UUID.fromString(secretId)))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from secret_versions where secret_reference_id = ?", Integer.class,
                        UUID.fromString(secretId)))
                .isEqualTo(2);
        // And revoking again is idempotent in effect, with no un-revoke.
        assertThat(revoke(tenant, secretId, 1).statusCode()).isEqualTo(200);
    }

    @Test
    @Order(12)
    @Timeout(180)
    void aVersionRevokedAfterAuthorizationIsRefusedAtRedemption() throws Exception {
        Tenant tenant = tenant();
        String secretId = secretReference(tenant, "lateRevoke");
        writeVersion(tenant, secretId, generated());
        UUID runId = claimedRun(bind(tenant, Map.of("API_TOKEN", secretId)));
        String token = authorize(runId).get("secretCapabilityToken").stringValue();

        revoke(tenant, secretId, 1);

        HttpResponse<byte[]> refused = redeemRaw(token, WORKER);
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(new String(refused.body(), StandardCharsets.UTF_8)).contains("SECRET_VERSION_REVOKED");
    }

    @Test
    @Order(13)
    @Timeout(180)
    void aPartiallyRevokedSetDeliversNothingAtAll() throws Exception {
        Tenant tenant = tenant();
        String kept = secretReference(tenant, "kept");
        String lost = secretReference(tenant, "lost");
        writeVersion(tenant, kept, generated());
        writeVersion(tenant, lost, generated());
        UUID runId = claimedRun(bind(tenant, Map.of("A_KEPT", kept, "B_LOST", lost)));
        String token = authorize(runId).get("secretCapabilityToken").stringValue();

        revoke(tenant, lost, 1);

        HttpResponse<byte[]> refused = redeemRaw(token, WORKER);
        assertThat(refused.statusCode()).as("all or nothing: one revoked binding refuses the whole set").isEqualTo(409);
        assertThat(refused.body().length).isLessThan(128);
    }

    // ------------------------------------------------------------------ the capability

    @Test
    @Order(20)
    @Timeout(180)
    void theCapabilityRedeemsExactlyTheSnapshotSetAtMostTwice() throws Exception {
        Tenant tenant = tenant();
        String alpha = secretReference(tenant, "alpha");
        String beta = secretReference(tenant, "beta");
        byte[] alphaValue = generated();
        byte[] betaValue = ("-----BEGIN PRIVATE KEY-----\r\n" + HexFormat.of().formatHex(generated())
                        + "\r\n-----END PRIVATE KEY-----\r\n").getBytes(StandardCharsets.UTF_8);
        writeVersion(tenant, alpha, alphaValue.clone());
        writeVersion(tenant, beta, betaValue.clone());
        UUID runId = claimedRun(bind(tenant, Map.of("ALPHA", alpha, "BETA", beta)));

        JsonNode delivery = authorize(runId);
        String token = delivery.get("secretCapabilityToken").stringValue();
        assertThat(token).startsWith("kaas_sec_");
        // The command names the exact pinned set -- keys, references, versions -- and never a capability.
        JsonNode bindings = delivery.at("/command/secretBindings");
        assertThat(bindings).hasSize(2);
        assertThat(bindings.get(0).get("bindingKey").stringValue()).isEqualTo("ALPHA");
        assertThat(bindings.get(0).get("version").asInt()).isEqualTo(1);
        assertThat(bindings.get(0).get("provider").stringValue()).isEqualTo("vault-transit");
        assertThat(delivery.get("command").toString()).doesNotContain(token).doesNotContain("kaas_sec_");

        Map<String, byte[]> first = redeem(token);
        assertThat(first.keySet()).containsExactly("ALPHA", "BETA");
        assertThat(Arrays.equals(first.get("ALPHA"), alphaValue)).isTrue();
        assertThat(Arrays.equals(first.get("BETA"), betaValue)).as("CRLF and all, byte for byte").isTrue();
        assertThat(redeemRaw(token, WORKER).statusCode()).as("one retry for a lost response").isEqualTo(200);
        assertThat(redeemRaw(token, WORKER).statusCode()).as("and no third copy").isEqualTo(409);

        // The token is stored only as a hash, and the scope rows name exactly the pinned set.
        assertThat(jdbc.queryForObject(
                        "select count(*) from execution_capability_secret_references s"
                                + " join execution_capabilities c on c.capability_id = s.capability_id"
                                + " join execution_authorizations a on a.authorization_id = c.authorization_id"
                                + " where a.run_id = ?",
                        Integer.class, runId))
                .isEqualTo(2);
        assertThat(databaseContains(token.getBytes(StandardCharsets.US_ASCII))).isFalse();
    }

    @Test
    @Order(21)
    @Timeout(180)
    void aSecretFreeRunGetsNoSecretCapabilityAndNeverTouchesTheProvider() throws Exception {
        Tenant tenant = tenant();
        int before = transit.calls();
        UUID runId = claimedRun(tenant);

        JsonNode delivery = authorize(runId);

        assertThat(delivery.has("secretCapabilityToken")).as("absent, not empty").isFalse();
        assertThat(delivery.at("/command/secretBindings")).isEmpty();
        assertThat(transit.calls()).as("the provider was never asked anything").isEqualTo(before);
        assertThat(jdbc.queryForObject(
                        "select count(*) from execution_capabilities c join execution_authorizations a"
                                + " on a.authorization_id = c.authorization_id"
                                + " where a.run_id = ? and c.capability_type = 'SECRET'",
                        Integer.class, runId))
                .isZero();
    }

    @Test
    @Order(22)
    @Timeout(180)
    void noOtherAuthorityCanRedeemAndNothingIsDecryptedForIt() throws Exception {
        Tenant tenant = tenant();
        String secretId = secretReference(tenant, "guarded");
        writeVersion(tenant, secretId, generated());
        Tenant bound = bind(tenant, Map.of("API_TOKEN", secretId));
        UUID runId = claimedRun(bound);
        JsonNode delivery = authorize(runId);
        String token = delivery.get("secretCapabilityToken").stringValue();
        int before = transit.decrypts.get();

        // Another worker holding the same bearer token: the worker identity is revalidated, not only the token.
        assertThat(redeemRaw(token, OTHER_WORKER).statusCode()).isEqualTo(409);
        // A source token is not a secret token, and a well-shaped token that names nothing is nothing.
        assertThat(redeemRaw(delivery.get("sourceCapabilityToken").stringValue(), WORKER).statusCode()).isEqualTo(409);
        assertThat(redeemRaw("kaas_sec_" + "z".repeat(43), WORKER).statusCode()).isEqualTo(409);
        assertThat(redeemRaw(null, WORKER).statusCode()).isEqualTo(409);
        // A rotated-out token: re-authorizing mints a new capability and revokes the old one.
        String rotated = authorize(runId).get("secretCapabilityToken").stringValue();
        assertThat(rotated).isNotEqualTo(token);
        assertThat(redeemRaw(token, WORKER).statusCode()).as("the superseded token").isEqualTo(409);

        // A cancelled run: fenced, and Vault is never called for it.
        cancel(runId);
        assertThat(redeemRaw(rotated, WORKER).statusCode()).isEqualTo(409);
        assertThat(transit.decrypts.get()).as("no refused redemption decrypted anything").isEqualTo(before);
    }

    @Test
    @Order(23)
    @Timeout(180)
    void authorityLostWhileTheProviderIsDecryptingDeliversNothing() throws Exception {
        Tenant tenant = tenant();
        String secretId = secretReference(tenant, "raced");
        writeVersion(tenant, secretId, generated());
        UUID runId = claimedRun(bind(tenant, Map.of("API_TOKEN", secretId)));
        String token = authorize(runId).get("secretCapabilityToken").stringValue();
        int before = transit.decrypts.get();

        // The run is cancelled while Vault is working. The decryption completes -- plaintext exists in this
        // process -- and the delivery must still not happen.
        transit.duringDecrypt.set(() -> cancel(runId));
        try {
            HttpResponse<byte[]> refused = redeemRaw(token, WORKER);
            assertThat(transit.decrypts.get()).as("the provider really was called").isGreaterThan(before);
            assertThat(refused.statusCode()).isEqualTo(409);
            assertThat(new String(refused.body(), StandardCharsets.UTF_8)).contains("CAPABILITY_FENCED");
        } finally {
            transit.duringDecrypt.set(null);
        }
    }

    // ------------------------------------------------------------------ leakage

    @Test
    @Order(30)
    @Timeout(180)
    void aDeliveredValueIsNowhereInTheDatabaseTheOutboxOrTheLogs(CapturedOutput output) throws Exception {
        Tenant tenant = tenant();
        String secretId = secretReference(tenant, "sentinel");
        byte[] sentinel = generated();
        writeVersion(tenant, secretId, sentinel.clone());
        UUID runId = claimedRun(bind(tenant, Map.of("API_TOKEN", secretId)));
        Map<String, byte[]> delivered = redeem(authorize(runId).get("secretCapabilityToken").stringValue());
        assertThat(Arrays.equals(delivered.get("API_TOKEN"), sentinel)).as("the hazard is real: it was delivered").isTrue();

        // Every text, JSON and byte column of every table, including snapshots, commands, the outbox, the
        // dispatch intents, idempotency records and audit events. Ciphertext is expected in exactly one table,
        // and the sentinel is plaintext, so it must be absent from all of them.
        assertThat(databaseContains(sentinel)).as("secret_in_database").isFalse();
        assertThat(contains(output.getAll(), sentinel)).as("secret_in_logs").isFalse();
    }

    // ------------------------------------------------------------------ provider failure, last

    @Test
    @Order(90)
    @Timeout(180)
    void aSealedProviderRefusesTheSecretRunAndLeavesTheSecretFreeRunAlone() throws Exception {
        Tenant tenant = tenant();
        String secretId = secretReference(tenant, "sealed");
        writeVersion(tenant, secretId, generated());
        UUID secretRun = claimedRun(bind(tenant, Map.of("API_TOKEN", secretId)));
        String token = authorize(secretRun).get("secretCapabilityToken").stringValue();
        UUID secretFreeRun = claimedRun(tenant());

        VAULT.seal();

        HttpResponse<byte[]> refused = redeemRaw(token, WORKER);
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(new String(refused.body(), StandardCharsets.UTF_8)).contains("SECRET_PROVIDER_UNAVAILABLE");
        // Provider-independent: the secret-free run is authorized with the provider sealed.
        JsonNode secretFree = authorize(secretFreeRun);
        assertThat(secretFree.has("secretCapabilityToken")).isFalse();
        assertThat(secretFree.get("sourceCapabilityToken").stringValue()).startsWith("kaas_src_");
        // And a write is refused as unavailable, without detail.
        HttpResponse<String> write = writeVersion(tenant, secretId, generated());
        assertThat(write.statusCode()).isEqualTo(503);
        assertThat(write.body()).contains("SECRET_PROVIDER_UNAVAILABLE").doesNotContain("sealed");
    }

    // ------------------------------------------------------------------ helpers

    /** A value generated here and never written in source. */
    private static byte[] generated() {
        byte[] entropy = new byte[24];
        RANDOM.nextBytes(entropy);
        return ("kaas-sentinel-" + HexFormat.of().formatHex(entropy)).getBytes(StandardCharsets.UTF_8);
    }

    private boolean databaseContains(byte[] value) {
        String needle = new String(value, StandardCharsets.UTF_8);
        List<Map<String, Object>> columns = jdbc.queryForList(
                """
                select table_name, column_name, data_type from information_schema.columns
                 where table_schema = 'public'
                   and data_type in ('text', 'character varying', 'jsonb', 'json', 'bytea', 'character')
                """);
        for (Map<String, Object> column : columns) {
            String table = (String) column.get("table_name");
            String name = (String) column.get("column_name");
            String cast = "bytea".equals(column.get("data_type"))
                    ? "encode(\"" + name + "\", 'escape')"
                    : "\"" + name + "\"::text";
            Integer hits = jdbc.queryForObject(
                    "select count(*) from \"" + table + "\" where strpos(" + cast + ", ?) > 0", Integer.class, needle);
            if (hits != null && hits > 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String haystack, byte[] value) {
        return haystack.contains(new String(value, StandardCharsets.UTF_8));
    }

    private HttpResponse<String> writeVersion(Tenant tenant, String secretId, byte[] value) throws Exception {
        return send(HttpRequest.newBuilder(versionsUri(tenant, secretId))
                .header("Authorization", "Bearer " + tenant.bearer())
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(value))
                .build());
    }

    private HttpResponse<String> revoke(Tenant tenant, String secretId, int version) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(versionsUri(tenant, secretId) + "/" + version + "/revocation"))
                .header("Authorization", "Bearer " + tenant.bearer())
                .POST(HttpRequest.BodyPublishers.noBody())
                .build());
    }

    private URI versionsUri(Tenant tenant, String secretId) {
        return URI.create(base() + "/api/v1/projects/" + tenant.projectId() + "/secret-references/" + secretId
                + "/versions");
    }

    private JsonNode authorize(UUID runId) throws Exception {
        HttpResponse<String> response = postInternal(
                "/internal/v1/runs/" + runId + "/attempts/" + attemptId(runId) + "/execution-authorizations",
                serviceToken(WORKER), "{\"assignmentEpoch\":1}");
        assertThat(response.statusCode()).as("authorization answered %s", response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }

    private Map<String, byte[]> redeem(String token) throws Exception {
        HttpResponse<byte[]> response = redeemRaw(token, WORKER);
        assertThat(response.statusCode()).as("redemption answered %s", response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/octet-stream");
        return decodeBundle(response.body());
    }

    private HttpResponse<byte[]> redeemRaw(String token, String worker) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + "/internal/v1/secret-bundles"))
                .header("Authorization", "Bearer " + serviceToken(worker))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            request.header("X-KaaS-Secret-Capability", token);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    /** An independent parser of the bundle format, written from the contract rather than from the encoder. */
    private static Map<String, byte[]> decodeBundle(byte[] frame) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame));
        byte[] magic = in.readNBytes(8);
        assertThat(new String(magic, StandardCharsets.US_ASCII)).isEqualTo("KAASSEC1");
        int count = in.readInt();
        Map<String, byte[]> values = new LinkedHashMap<>();
        String previous = "";
        for (int index = 0; index < count; index++) {
            int keyLength = in.readUnsignedShort();
            String key = new String(in.readNBytes(keyLength), StandardCharsets.US_ASCII);
            assertThat(key.compareTo(previous)).as("keys strictly ascending").isPositive();
            previous = key;
            int valueLength = in.readInt();
            values.put(key, in.readNBytes(valueLength));
        }
        assertThat(new String(in.readNBytes(8), StandardCharsets.US_ASCII)).isEqualTo("KAASEND1");
        assertThat(in.available()).as("nothing after the trailer").isZero();
        return values;
    }

    private void cancel(UUID runId) {
        jdbc.update("alter table test_runs disable trigger all");
        jdbc.update("alter table execution_attempts disable trigger all");
        try {
            jdbc.update("update execution_attempts set attempt_state = 'FENCED', fenced_at = clock_timestamp()"
                    + " where run_id = ?", runId);
            jdbc.update("update test_runs set lifecycle_state = 'STOPPING', stop_reason = 'USER_REQUESTED',"
                    + " cancellation_status = 'REQUESTED', cancellation_requested_at = clock_timestamp(),"
                    + " run_version = run_version + 1, updated_at = clock_timestamp() where run_id = ?", runId);
        } finally {
            jdbc.update("alter table execution_attempts enable trigger all");
            jdbc.update("alter table test_runs enable trigger all");
        }
    }

    private UUID claimedRun(Tenant tenant) throws Exception {
        HttpResponse<String> created = post("/api/v1/projects/" + tenant.projectId() + "/runs", tenant.bearer(),
                json(Map.of("featureRevisionIds", List.of(tenant.featureRevisionId()),
                        "runProfileRevisionId", tenant.profileRevisionId())));
        assertThat(created.statusCode()).as("run creation answered %s", created.statusCode()).isEqualTo(202);
        UUID runId = UUID.fromString(objectMapper.readTree(created.body()).get("runId").stringValue());
        scheduler.scheduleDue();
        String payload = String.valueOf(
                jdbc.queryForMap("select payload from execution_dispatches where run_id = ?", runId).get("payload"));
        claims.claim(objectMapper.readValue(payload, ExecutionDispatch.class), WORKER);
        return runId;
    }

    private UUID attemptId(UUID runId) {
        return jdbc.queryForObject("select attempt_id from execution_attempts where run_id = ?", UUID.class, runId);
    }

    private record Tenant(
            UUID organizationId, UUID projectId, String bearer, String featureRevisionId, String profileRevisionId) {}

    private Tenant tenant() throws Exception {
        UUID organizationId = UUID.randomUUID();
        return project(organizationId, token("secrets-test", organizationId));
    }

    private Tenant siblingProject(Tenant tenant) throws Exception {
        return project(tenant.organizationId(), tenant.bearer());
    }

    private Tenant project(UUID organizationId, String bearer) throws Exception {
        String projectId = objectMapper.readTree(post("/api/v1/projects", bearer,
                                json(Map.of("name", "Project " + UUID.randomUUID())))
                        .body())
                .get("projectId").stringValue();
        String featureRevision = objectMapper.readTree(post("/api/v1/projects/" + projectId + "/features", bearer,
                                json(Map.of("name", "Secret feature",
                                        "logicalPath", "features/s-" + UUID.randomUUID() + ".feature",
                                        "source", "Feature: a\nScenario: one\n* match 1 == 1\n")))
                        .body())
                .at("/initialRevision/revisionId").stringValue();
        Tenant withoutProfile = new Tenant(organizationId, UUID.fromString(projectId), bearer, featureRevision, null);
        return withProfile(withoutProfile, Map.of());
    }

    /** The same project, with a new environment binding exactly these keys, and a profile over it. */
    private Tenant bind(Tenant tenant, Map<String, String> bindings) throws Exception {
        return withProfile(tenant, bindings);
    }

    private Tenant withProfile(Tenant tenant, Map<String, String> bindings) throws Exception {
        List<Map<String, Object>> secretBindings = new ArrayList<>();
        bindings.forEach((key, reference) -> secretBindings.add(Map.of("key", key, "secretReferenceId", reference)));
        String environmentRevision = objectMapper.readTree(post(
                                "/api/v1/projects/" + tenant.projectId() + "/environments", tenant.bearer(),
                                json(Map.of("name", "Environment " + UUID.randomUUID(),
                                        "variables", List.of(),
                                        "secretBindings", secretBindings)))
                        .body())
                .at("/initialRevision/revisionId").stringValue();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", "Profile " + UUID.randomUUID());
        profile.put("environmentRevisionId", environmentRevision);
        profile.put("selection", Map.of("tags", List.of("@smoke")));
        profile.put("parallelism", 1);
        profile.put("scenarioRetry", Map.of("maxAttempts", 1, "delayMilliseconds", 0));
        profile.put("executionTimeoutSeconds", 60);
        profile.put("artifactPolicy",
                Map.of("types", List.of("RAW_RESULT"), "maxArtifactBytes", 1_000, "maxTotalBytes", 2_000));
        profile.put("configurationOverrides", List.of());
        String profileRevision = objectMapper.readTree(post(
                                "/api/v1/projects/" + tenant.projectId() + "/run-profiles", tenant.bearer(), json(profile))
                        .body())
                .at("/initialRevision/revisionId").stringValue();
        return new Tenant(tenant.organizationId(), tenant.projectId(), tenant.bearer(), tenant.featureRevisionId(),
                profileRevision);
    }

    private String secretReference(Tenant tenant, String name) throws Exception {
        return objectMapper.readTree(post("/api/v1/projects/" + tenant.projectId() + "/secret-references",
                                tenant.bearer(), json(Map.of("name", name + UUID.randomUUID().toString().substring(0, 8))))
                        .body())
                .get("secretReferenceId").stringValue();
    }

    private HttpResponse<String> post(String path, String bearer, String body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base() + path))
                .header("Authorization", "Bearer " + bearer)
                .header("Idempotency-Key", "key-" + UUID.randomUUID())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build());
    }

    private HttpResponse<String> postInternal(String path, String bearer, String body) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base() + path))
                .header("Authorization", "Bearer " + bearer)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build());
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String base() {
        return "http://127.0.0.1:" + port;
    }

    private String json(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    private static String serviceToken(String subject) throws Exception {
        return token(subject, null);
    }

    private static String token(String subject, UUID organizationId) throws Exception {
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER).subject(subject).audience(AUDIENCE)
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

    /**
     * A delegating wrapper over the production Transit bean. It counts and it can interleave; it never
     * encrypts or decrypts anything itself.
     */
    static final class CountingTransit implements SecretTransit {
        final SecretTransit delegate;
        final AtomicInteger encrypts = new AtomicInteger();
        final AtomicInteger decrypts = new AtomicInteger();
        final AtomicReference<Runnable> duringDecrypt = new AtomicReference<>();

        CountingTransit(SecretTransit delegate) {
            this.delegate = delegate;
        }

        int calls() {
            return encrypts.get() + decrypts.get();
        }

        @Override
        public boolean configured() {
            return delegate.configured();
        }

        @Override
        public EncryptedValue encrypt(TransitContext context, byte[] plaintext) throws SecretProviderException {
            encrypts.incrementAndGet();
            return delegate.encrypt(context, plaintext);
        }

        @Override
        public byte[] decrypt(TransitContext context, String ciphertext) throws SecretProviderException {
            decrypts.incrementAndGet();
            byte[] plaintext = delegate.decrypt(context, ciphertext);
            Runnable hook = duringDecrypt.get();
            if (hook != null) {
                hook.run();
            }
            return plaintext;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestWiring {
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

        @Bean
        @Primary
        CountingTransit countingTransit(@Qualifier("secretTransit") SecretTransit production) {
            return new CountingTransit(production);
        }
    }
}
