package com.kaas.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@Import(ConfigurationHttpIntegrationTests.JwtTestConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // These suites assert CREATED-state invariants, so the production timers must not act on them.
        properties = {
            "kaas.scheduling.auto.enabled=false",
            "kaas.outbox.relay.enabled=false",
            // No broker in this suite and nothing claimed: both background components would only add writes
            // that assertions about state would then have to tolerate.
            "kaas.consumer.enabled=false",
            "kaas.claim.reconcile.enabled=false"
        })
class ConfigurationHttpIntegrationTests {
    private static final String ISSUER = "https://issuer.kaas.test";
    private static final String AUDIENCE = "kaas-api";
    private static final KeyPair SIGNING_KEY = keyPair();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16.10-alpine").withDatabaseName("kaas-configuration");

    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void secretReferencesAndEnvironmentsAreSafeImmutableCanonicalAndTenantScoped() throws Exception {
        UUID organizationA = UUID.randomUUID();
        UUID organizationB = UUID.randomUUID();
        String tokenA = token(organizationA, "member-a");
        String tokenB = token(organizationB, "member-b");
        String projectA = createProject(tokenA);
        String projectA2 = createProject(tokenA);
        String projectB = createProject(tokenB);

        String secretKey = key();
        String secretBody = json(Map.of("name", "paymentClientSecret"));
        var secretCreated = post(secretPath(projectA), tokenA, secretKey, secretBody);
        assertThat(secretCreated.statusCode()).isEqualTo(201);
        JsonNode secret = json(secretCreated);
        String secretId = secret.get("secretReferenceId").stringValue();
        assertThat(secret.size()).isEqualTo(5);
        assertThat(secret.has("secretReferenceId")).isTrue();
        assertThat(secret.has("projectId")).isTrue();
        assertThat(secret.has("name")).isTrue();
        assertThat(secret.has("createdBy")).isTrue();
        assertThat(secret.has("createdAt")).isTrue();
        assertThat(secret.toString())
                .doesNotContain("value", "provider", "path", "token", "cipher", "capability", "uri");
        assertThat(secretCreated.headers().firstValue("Cache-Control")).contains("no-store");
        assertProblem(post(secretPath(projectA), tokenA, key(), secretBody), 409, "SECRET_REFERENCE_NAME_CONFLICT");
        assertThat(get(secretPath(projectA) + "/" + secretId, tokenA).statusCode()).isEqualTo(200);
        assertThat(json(get(secretPath(projectA), tokenA)).get("items").toString()).contains(secretId);

        var secretReplay = post(secretPath(projectA), tokenA, secretKey, secretBody);
        assertThat(secretReplay.body()).isEqualTo(secretCreated.body());
        assertThat(secretReplay.headers().firstValue("Location"))
                .isEqualTo(secretCreated.headers().firstValue("Location"));
        assertThat(secretReplay.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertProblem(
                post(secretPath(projectA), tokenA, secretKey, json(Map.of("name", "differentSecret"))),
                409,
                "IDEMPOTENCY_CONFLICT");
        for (String forbidden :
                List.of("value", "secret", "provider", "vaultPath", "token", "credentials", "organizationId")) {
            assertProblem(
                    post(
                            secretPath(projectA),
                            tokenA,
                            key(),
                            "{\"name\":\"safeName\",\"" + forbidden + "\":\"sensitive-sentinel\"}"),
                    400,
                    "VALIDATION_FAILED");
        }
        assertThat(post(
                                secretPath(projectA),
                                tokenA,
                                key(),
                                json(Map.of("name", "s" + "a".repeat(127))))
                        .statusCode())
                .isEqualTo(201);
        assertProblem(
                post(
                        secretPath(projectA),
                        tokenA,
                        key(),
                        json(Map.of("name", "s" + "a".repeat(128)))),
                422,
                "VALIDATION_FAILED");

        List<Map<String, Object>> variables = List.of(
                variable("timeoutMs", "INTEGER", 10_000),
                variable("baseUrl", "STRING", "https://qa.example.test"),
                variable("featureFlag", "BOOLEAN", true));
        List<Map<String, Object>> reversed = List.of(variables.get(2), variables.get(1), variables.get(0));
        List<Map<String, Object>> bindings = List.of(binding("clientSecret", secretId));
        String environmentKey = key();
        String environmentBody = environmentBody("QA", variables, bindings);
        var environmentCreated = post(environmentPath(projectA), tokenA, environmentKey, environmentBody);
        assertThat(environmentCreated.statusCode()).isEqualTo(201);
        assertThat(environmentCreated.headers().firstValue("Cache-Control")).contains("no-store");
        JsonNode created = json(environmentCreated);
        String environmentId = created.at("/environment/environmentId").stringValue();
        String revisionOneId = created.at("/initialRevision/revisionId").stringValue();
        String digest = created.at("/initialRevision/contentDigest").stringValue();
        assertThat(created.at("/initialRevision/revisionNumber").asInt()).isEqualTo(1);
        assertThat(created.at("/initialRevision/variables/0/key").stringValue()).isEqualTo("baseUrl");
        assertThat(created.at("/initialRevision/variables/1/value").booleanValue()).isTrue();
        assertThat(created.at("/initialRevision/variables/2/value").longValue()).isEqualTo(10_000);
        assertThat(created.at("/initialRevision/secretBindings/0/secretReferenceId").stringValue())
                .isEqualTo(secretId);
        assertProblem(
                post(environmentPath(projectA), tokenA, key(), environmentBody),
                409,
                "ENVIRONMENT_NAME_CONFLICT");
        assertProblem(
                post(
                        environmentPath(projectA),
                        tokenA,
                        key(),
                        """
                        {"name":"Unsafe binding","variables":[],"secretBindings":[{
                          "key":"unsafe","secretReferenceId":"%s","value":"sensitive-sentinel"
                        }]}
                        """.formatted(secretId)),
                400,
                "VALIDATION_FAILED");

        var sameContentDifferentOrder = post(
                environmentPath(projectA),
                tokenA,
                key(),
                environmentBody("QA reordered", reversed, bindings));
        JsonNode reorderedEnvironment = json(sameContentDifferentOrder);
        assertThat(reorderedEnvironment.at("/initialRevision/contentDigest").stringValue())
                .isEqualTo(digest);
        assertProblem(
                get(
                        environmentRevisionPath(projectA, environmentId) + "/"
                                + reorderedEnvironment.at("/initialRevision/revisionId").stringValue(),
                        tokenA),
                404,
                "NOT_FOUND");
        String reorderedProperties = """
                {
                  "secretBindings": %s,
                  "variables": %s,
                  "name": "QA property order"
                }
                """
                .formatted(json(bindings), json(variables));
        assertThat(json(post(environmentPath(projectA), tokenA, key(), reorderedProperties))
                        .at("/initialRevision/contentDigest")
                        .stringValue())
                .isEqualTo(digest);
        assertThat(post(
                                environmentPath(projectA),
                                tokenA,
                                key(),
                                environmentBody("E".repeat(128), List.of(), List.of()))
                        .statusCode())
                .isEqualTo(201);
        assertProblem(
                post(
                        environmentPath(projectA),
                        tokenA,
                        key(),
                        environmentBody("E".repeat(129), List.of(), List.of())),
                422,
                "VALIDATION_FAILED");

        String revisionTwoBody = environmentRevisionBody(
                List.of(
                        variable("baseUrl", "STRING", "https://qa-v2.example.test"),
                        variable("timeoutMs", "INTEGER", 20_000)),
                bindings);
        var revisionTwo = post(environmentRevisionPath(projectA, environmentId), tokenA, key(), revisionTwoBody);
        assertThat(revisionTwo.statusCode()).isEqualTo(201);
        assertThat(json(revisionTwo).get("revisionNumber").asInt()).isEqualTo(2);

        var createReplayAfterRevisionTwo = post(
                environmentPath(projectA), tokenA, environmentKey, environmentBody);
        assertThat(createReplayAfterRevisionTwo.body()).isEqualTo(environmentCreated.body());
        assertThat(get(environmentPath(projectA) + "/" + environmentId, tokenA).statusCode()).isEqualTo(200);
        assertThat(json(get(environmentPath(projectA), tokenA)).get("items").toString())
                .contains(environmentId);
        assertThat(json(get(
                                environmentPath(projectA) + "/" + environmentId + "/revisions/" + revisionOneId,
                                tokenA))
                        .get("contentDigest")
                        .stringValue())
                .isEqualTo(digest);
        JsonNode history = json(get(environmentRevisionPath(projectA, environmentId), tokenA));
        assertThat(history.get("totalElements").asInt()).isEqualTo(2);
        assertThat(history.at("/items/0").has("variables")).isFalse();

        assertProblem(get(environmentPath(projectA) + "/" + environmentId, tokenB), 404, "NOT_FOUND");
        assertProblem(get(environmentRevisionPath(projectA, environmentId), tokenB), 404, "NOT_FOUND");
        assertProblem(
                get(environmentRevisionPath(projectA, environmentId) + "/" + revisionOneId, tokenB),
                404,
                "NOT_FOUND");
        assertProblem(get(secretPath(projectA) + "/" + secretId, tokenB), 404, "NOT_FOUND");
        assertProblem(get(secretPath(projectA), tokenB), 404, "NOT_FOUND");
        assertProblem(get(environmentPath(projectA), tokenB), 404, "NOT_FOUND");
        assertProblem(
                post(
                        environmentRevisionPath(projectA, environmentId),
                        tokenB,
                        key(),
                        environmentRevisionBody(List.of(), List.of())),
                404,
                "NOT_FOUND");
        assertProblem(
                post(
                        environmentPath(projectA2),
                        tokenA,
                        key(),
                        environmentBody("Foreign secret", List.of(), bindings)),
                404,
                "NOT_FOUND");
        assertProblem(
                get(environmentPath(projectA2) + "/" + environmentId, tokenA), 404, "NOT_FOUND");
        assertProblem(
                post(
                        environmentRevisionPath(projectA2, environmentId),
                        tokenA,
                        key(),
                        environmentRevisionBody(List.of(), List.of())),
                404,
                "NOT_FOUND");

        var foreignSecret = post(
                secretPath(projectB), tokenB, secretKey, json(Map.of("name", "paymentClientSecret")));
        assertThat(foreignSecret.statusCode()).isEqualTo(201);
        assertProblem(
                post(
                        environmentPath(projectA),
                        tokenA,
                        key(),
                        environmentBody(
                                "Foreign tenant secret",
                                List.of(),
                                List.of(binding(
                                        "foreignSecret",
                                        json(foreignSecret).get("secretReferenceId").stringValue())))),
                404,
                "NOT_FOUND");
    }

    @Test
    @Timeout(60)
    void environmentRevisionConcurrencyIsContiguousLosslessAndDatabaseImmutable() throws Exception {
        String bearer = token(UUID.randomUUID(), "member");
        String projectId = createProject(bearer);
        JsonNode created = json(post(
                environmentPath(projectId),
                bearer,
                key(),
                environmentBody("Concurrent environment", List.of(), List.of())));
        String environmentId = created.at("/environment/environmentId").stringValue();
        String revisionOneId = created.at("/initialRevision/revisionId").stringValue();

        int writers = 10;
        CyclicBarrier barrier = new CyclicBarrier(writers);
        List<HttpResponse<String>> responses;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = IntStream.range(0, writers)
                    .mapToObj(index -> executor.submit(() -> {
                        barrier.await();
                        return post(
                                environmentRevisionPath(projectId, environmentId),
                                bearer,
                                key(),
                                environmentRevisionBody(
                                        List.of(variable("writer", "INTEGER", index)), List.of()));
                    }))
                    .toList();
            responses = futures.stream().map(future -> {
                        try {
                            return future.get(30, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .toList();
        }

        assertThat(responses).allMatch(response -> response.statusCode() == 201, responses.toString());
        assertThat(responses.stream().map(response -> uncheckedJson(response).get("revisionNumber").asInt()))
                .containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(2, 11).boxed().toList());
        for (int index = 0; index < writers; index++) {
            JsonNode response = json(responses.get(index));
            JsonNode stored = json(get(
                    environmentRevisionPath(projectId, environmentId) + "/"
                            + response.get("revisionId").stringValue(),
                    bearer));
            assertThat(stored.at("/variables/0/value").asInt()).isEqualTo(index);
            assertThat(stored.get("contentDigest").stringValue())
                    .isEqualTo(response.get("contentDigest").stringValue());
        }
        assertThat(json(get(environmentRevisionPath(projectId, environmentId), bearer))
                        .get("totalElements")
                        .asInt())
                .isEqualTo(11);
        assertThat(json(get(environmentRevisionPath(projectId, environmentId) + "/" + revisionOneId, bearer))
                        .get("revisionNumber")
                        .asInt())
                .isEqualTo(1);

        UUID revisionId = UUID.fromString(revisionOneId);
        UUID organizationId = jdbc.queryForObject(
                "select organization_id from projects where project_id = ?",
                UUID.class,
                UUID.fromString(projectId));
        assertThatThrownBy(() -> jdbc.update(
                        "update environment_revisions set content_sha256 = ? where revision_id = ?",
                        "0".repeat(64),
                        revisionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "delete from environment_revisions where revision_id = ?", revisionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        """
                        insert into environment_revision_entries
                            (organization_id, project_id, environment_id, environment_revision_id,
                             config_key, value_kind, string_value)
                        values (?, ?, ?, ?, 'late', 'STRING', 'forbidden')
                        """,
                        organizationId,
                        UUID.fromString(projectId),
                        UUID.fromString(environmentId),
                        revisionId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void runProfilesPinEnvironmentRevisionsAndEnforceCanonicalPrecedenceAndTenancy() throws Exception {
        UUID organizationA = UUID.randomUUID();
        UUID organizationB = UUID.randomUUID();
        String tokenA = token(organizationA, "member-a");
        String tokenB = token(organizationB, "member-b");
        String projectA = createProject(tokenA);
        String projectA2 = createProject(tokenA);
        String projectB = createProject(tokenB);
        String secretId = json(post(
                        secretPath(projectA), tokenA, key(), json(Map.of("name", "profileSecret"))))
                .get("secretReferenceId")
                .stringValue();
        JsonNode environment = json(post(
                environmentPath(projectA),
                tokenA,
                key(),
                environmentBody(
                        "Profile environment",
                        List.of(variable("baseUrl", "STRING", "https://one.example")),
                        List.of(binding("secretKey", secretId)))));
        String environmentId = environment.at("/environment/environmentId").stringValue();
        String environmentRevisionOneId = environment.at("/initialRevision/revisionId").stringValue();
        String environmentRevisionTwoId = json(post(
                        environmentRevisionPath(projectA, environmentId),
                        tokenA,
                        key(),
                        environmentRevisionBody(
                                List.of(variable("baseUrl", "STRING", "https://two.example")),
                                List.of(binding("secretKey", secretId)))))
                .get("revisionId")
                .stringValue();

        String profileKey = key();
        String profileBody = profileBody(
                "Regression QA",
                environmentRevisionOneId,
                List.of("@smoke", "@regression"),
                4,
                1,
                0,
                300,
                List.of("RAW_RESULT", "EXECUTION_LOG"),
                1_000,
                2_000,
                List.of(variable("baseUrl", "STRING", "https://override.example")));
        var profileCreated = post(profilePath(projectA), tokenA, profileKey, profileBody);
        assertThat(profileCreated.statusCode()).isEqualTo(201);
        assertThat(profileCreated.headers().firstValue("Cache-Control")).contains("no-store");
        JsonNode profile = json(profileCreated);
        String profileId = profile.at("/runProfile/runProfileId").stringValue();
        String profileRevisionOneId = profile.at("/initialRevision/revisionId").stringValue();
        String profileDigest = profile.at("/initialRevision/contentDigest").stringValue();
        assertThat(profile.at("/initialRevision/environmentRevisionId").stringValue())
                .isEqualTo(environmentRevisionOneId);
        assertThat(profile.at("/initialRevision/selection/tags/0").stringValue()).isEqualTo("@regression");
        assertProblem(
                post(profilePath(projectA), tokenA, key(), profileBody),
                409,
                "RUN_PROFILE_NAME_CONFLICT");
        String unsafeProfile = profileBody(
                        "Unsafe profile",
                        environmentRevisionOneId,
                        List.of(),
                        1,
                        1,
                        0,
                        60,
                        List.of(),
                        0,
                        0,
                        List.of())
                .replace(
                        "\"configurationOverrides\":[]",
                        "\"configurationOverrides\":[{\"key\":\"unsafe\",\"type\":\"STRING\",\"value\":\"ok\",\"secret\":\"sensitive-sentinel\"}]");
        assertProblem(post(profilePath(projectA), tokenA, key(), unsafeProfile), 400, "VALIDATION_FAILED");

        var reordered = post(
                profilePath(projectA),
                tokenA,
                key(),
                profileBody(
                        "Regression reordered",
                        environmentRevisionOneId,
                        List.of("@regression", "@smoke"),
                        4,
                        1,
                        0,
                        300,
                        List.of("EXECUTION_LOG", "RAW_RESULT"),
                        1_000,
                        2_000,
                        List.of(variable("baseUrl", "STRING", "https://override.example"))));
        assertThat(json(reordered).at("/initialRevision/contentDigest").stringValue())
                .isEqualTo(profileDigest);
        assertThat(post(
                                profilePath(projectA),
                                tokenA,
                                key(),
                                profileBody(
                                        "P".repeat(128),
                                        environmentRevisionOneId,
                                        List.of(),
                                        1,
                                        1,
                                        0,
                                        60,
                                        List.of(),
                                        0,
                                        0,
                                        List.of()))
                        .statusCode())
                .isEqualTo(201);
        assertProblem(
                post(
                        profilePath(projectA),
                        tokenA,
                        key(),
                        profileBody(
                                "P".repeat(129),
                                environmentRevisionOneId,
                                List.of(),
                                1,
                                1,
                                0,
                                60,
                                List.of(),
                                0,
                                0,
                                List.of())),
                422,
                "VALIDATION_FAILED");

        var profileRevisionTwo = post(
                profileRevisionPath(projectA, profileId),
                tokenA,
                key(),
                profileRevisionBody(
                        environmentRevisionTwoId,
                        List.of("@regression"),
                        2,
                        2,
                        100,
                        600,
                        List.of("RAW_RESULT"),
                        2_000,
                        3_000,
                        List.of(variable("baseUrl", "STRING", "https://profile-two.example"))));
        assertThat(json(profileRevisionTwo).get("revisionNumber").asInt()).isEqualTo(2);
        assertThat(json(get(profileRevisionPath(projectA, profileId) + "/" + profileRevisionOneId, tokenA))
                        .get("environmentRevisionId")
                        .stringValue())
                .isEqualTo(environmentRevisionOneId);
        assertThat(post(profilePath(projectA), tokenA, profileKey, profileBody).body())
                .isEqualTo(profileCreated.body());
        assertThat(get(profilePath(projectA) + "/" + profileId, tokenA).statusCode()).isEqualTo(200);
        assertThat(json(get(profilePath(projectA), tokenA)).get("items").toString()).contains(profileId);
        assertThat(json(get(profileRevisionPath(projectA, profileId), tokenA)).get("totalElements").asInt())
                .isEqualTo(2);

        assertProblem(
                post(
                        profileRevisionPath(projectA, profileId),
                        tokenA,
                        key(),
                        profileRevisionBody(
                                environmentRevisionOneId,
                                List.of(),
                                1,
                                1,
                                0,
                                60,
                                List.of(),
                                0,
                                0,
                                List.of(variable("secretKey", "STRING", "forbidden")))),
                422,
                "VALIDATION_FAILED");
        assertProblem(
                post(
                        profileRevisionPath(projectA, profileId),
                        tokenA,
                        key(),
                        profileRevisionBody(
                                environmentRevisionOneId,
                                List.of(),
                                33,
                                1,
                                0,
                                60,
                                List.of(),
                                0,
                                0,
                                List.of())),
                422,
                "VALIDATION_FAILED");
        assertProblem(
                post(
                        profileRevisionPath(projectA, profileId),
                        tokenA,
                        key(),
                        profileRevisionBody(
                                environmentRevisionOneId,
                                List.of(),
                                1,
                                1,
                                0,
                                60,
                                List.of(),
                                2,
                                1,
                                List.of())),
                422,
                "VALIDATION_FAILED");

        assertProblem(get(profilePath(projectA) + "/" + profileId, tokenB), 404, "NOT_FOUND");
        assertProblem(get(profilePath(projectA), tokenB), 404, "NOT_FOUND");
        assertProblem(get(profileRevisionPath(projectA, profileId), tokenB), 404, "NOT_FOUND");
        assertProblem(
                get(profileRevisionPath(projectA, profileId) + "/" + profileRevisionOneId, tokenB),
                404,
                "NOT_FOUND");
        assertProblem(
                post(
                        profileRevisionPath(projectA, profileId),
                        tokenB,
                        key(),
                        profileRevisionBody(
                                environmentRevisionOneId,
                                List.of(),
                                1,
                                1,
                                0,
                                60,
                                List.of(),
                                0,
                                0,
                                List.of())),
                404,
                "NOT_FOUND");
        assertProblem(get(profilePath(projectA2) + "/" + profileId, tokenA), 404, "NOT_FOUND");

        String environmentA2Revision = json(post(
                        environmentPath(projectA2),
                        tokenA,
                        key(),
                        environmentBody("A2 environment", List.of(), List.of())))
                .at("/initialRevision/revisionId")
                .stringValue();
        String environmentBRevision = json(post(
                        environmentPath(projectB),
                        tokenB,
                        key(),
                        environmentBody("B environment", List.of(), List.of())))
                .at("/initialRevision/revisionId")
                .stringValue();
        for (String foreignRevision : List.of(environmentA2Revision, environmentBRevision)) {
            assertProblem(
                    post(
                            profilePath(projectA),
                            tokenA,
                            key(),
                            profileBody(
                                    "Foreign " + UUID.randomUUID(),
                                    foreignRevision,
                                    List.of(),
                                    1,
                                    1,
                                    0,
                                    60,
                                    List.of(),
                                    0,
                                    0,
                                    List.of())),
                    404,
                    "NOT_FOUND");
        }
    }

    @Test
    @Timeout(60)
    void runProfileRevisionConcurrencyIsContiguousLosslessAndDatabaseImmutable() throws Exception {
        String bearer = token(UUID.randomUUID(), "member");
        String projectId = createProject(bearer);
        String environmentRevisionId = json(post(
                        environmentPath(projectId),
                        bearer,
                        key(),
                        environmentBody("Concurrent profile environment", List.of(), List.of())))
                .at("/initialRevision/revisionId")
                .stringValue();
        JsonNode created = json(post(
                profilePath(projectId),
                bearer,
                key(),
                profileBody(
                        "Concurrent profile",
                        environmentRevisionId,
                        List.of(),
                        1,
                        1,
                        0,
                        60,
                        List.of(),
                        0,
                        0,
                        List.of())));
        String profileId = created.at("/runProfile/runProfileId").stringValue();
        String revisionOneId = created.at("/initialRevision/revisionId").stringValue();

        int writers = 10;
        CyclicBarrier barrier = new CyclicBarrier(writers);
        List<HttpResponse<String>> responses;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = IntStream.range(0, writers)
                    .mapToObj(index -> executor.submit(() -> {
                        barrier.await();
                        return post(
                                profileRevisionPath(projectId, profileId),
                                bearer,
                                key(),
                                profileRevisionBody(
                                        environmentRevisionId,
                                        List.of("@writer" + index),
                                        index + 1,
                                        1,
                                        index,
                                        60 + index,
                                        List.of("RAW_RESULT"),
                                        100 + index,
                                        200 + index,
                                        List.of(variable("writer", "INTEGER", index))));
                    }))
                    .toList();
            responses = futures.stream().map(future -> {
                        try {
                            return future.get(30, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .toList();
        }

        assertThat(responses).allMatch(response -> response.statusCode() == 201, responses.toString());
        assertThat(responses.stream().map(response -> uncheckedJson(response).get("revisionNumber").asInt()))
                .containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(2, 11).boxed().toList());
        for (int index = 0; index < writers; index++) {
            JsonNode response = json(responses.get(index));
            JsonNode stored = json(get(
                    profileRevisionPath(projectId, profileId) + "/"
                            + response.get("revisionId").stringValue(),
                    bearer));
            assertThat(stored.get("parallelism").asInt()).isEqualTo(index + 1);
            assertThat(stored.at("/configurationOverrides/0/value").asInt()).isEqualTo(index);
            assertThat(stored.get("contentDigest").stringValue())
                    .isEqualTo(response.get("contentDigest").stringValue());
        }
        assertThat(json(get(profileRevisionPath(projectId, profileId), bearer)).get("totalElements").asInt())
                .isEqualTo(11);

        UUID revisionId = UUID.fromString(revisionOneId);
        UUID organizationId = jdbc.queryForObject(
                "select organization_id from projects where project_id = ?",
                UUID.class,
                UUID.fromString(projectId));
        assertThatThrownBy(() -> jdbc.update(
                        "update run_profile_revisions set parallelism = 2 where revision_id = ?", revisionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "delete from run_profile_revisions where revision_id = ?", revisionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(
                        """
                        insert into run_profile_revision_tags
                            (organization_id, project_id, run_profile_id, run_profile_revision_id, tag)
                        values (?, ?, ?, ?, '@late')
                        """,
                        organizationId,
                        UUID.fromString(projectId),
                        UUID.fromString(profileId),
                        revisionId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Timeout(60)
    void everyNewPostUsesConcurrentTransactionalIdempotencyAndPrincipalScope() throws Exception {
        UUID organizationId = UUID.randomUUID();
        String tokenOne = token(organizationId, "member-one");
        String tokenTwo = token(organizationId, "member-two");
        String projectId = createProject(tokenOne);

        assertConcurrentSamePost(
                secretPath(projectId), tokenOne, json(Map.of("name", "concurrentSecret")));
        assertChangedBodyConflict(
                secretPath(projectId),
                tokenOne,
                json(Map.of("name", "conflictSecretOne")),
                json(Map.of("name", "conflictSecretTwo")));
        String secretId = json(post(
                        secretPath(projectId), tokenOne, key(), json(Map.of("name", "baseSecret"))))
                .get("secretReferenceId")
                .stringValue();
        String environmentBody = environmentBody(
                "Concurrent environment create",
                List.of(variable("plain", "STRING", "value")),
                List.of(binding("secret", secretId)));
        JsonNode concurrentEnvironment = assertConcurrentSamePost(
                environmentPath(projectId), tokenOne, environmentBody);
        assertChangedBodyConflict(
                environmentPath(projectId),
                tokenOne,
                environmentBody("Conflict environment one", List.of(), List.of()),
                environmentBody("Conflict environment two", List.of(), List.of()));
        String environmentId = concurrentEnvironment.at("/environment/environmentId").stringValue();
        String environmentRevisionId = concurrentEnvironment.at("/initialRevision/revisionId").stringValue();

        assertConcurrentSamePost(
                environmentRevisionPath(projectId, environmentId),
                tokenOne,
                environmentRevisionBody(
                        List.of(variable("plain", "STRING", "revision")), List.of(binding("secret", secretId))));
        assertChangedBodyConflict(
                environmentRevisionPath(projectId, environmentId),
                tokenOne,
                environmentRevisionBody(List.of(variable("conflict", "INTEGER", 1)), List.of()),
                environmentRevisionBody(List.of(variable("conflict", "INTEGER", 2)), List.of()));
        String profileBody = profileBody(
                "Concurrent profile create",
                environmentRevisionId,
                List.of("@regression"),
                2,
                1,
                0,
                120,
                List.of("RAW_RESULT"),
                1_000,
                2_000,
                List.of(variable("plain", "STRING", "override")));
        JsonNode concurrentProfile = assertConcurrentSamePost(profilePath(projectId), tokenOne, profileBody);
        assertChangedBodyConflict(
                profilePath(projectId),
                tokenOne,
                profileBody(
                        "Conflict profile one",
                        environmentRevisionId,
                        List.of(),
                        1,
                        1,
                        0,
                        60,
                        List.of(),
                        0,
                        0,
                        List.of()),
                profileBody(
                        "Conflict profile two",
                        environmentRevisionId,
                        List.of(),
                        1,
                        1,
                        0,
                        60,
                        List.of(),
                        0,
                        0,
                        List.of()));
        String profileId = concurrentProfile.at("/runProfile/runProfileId").stringValue();
        assertConcurrentSamePost(
                profileRevisionPath(projectId, profileId),
                tokenOne,
                profileRevisionBody(
                        environmentRevisionId,
                        List.of("@smoke"),
                        3,
                        1,
                        0,
                        180,
                        List.of("EXECUTION_LOG"),
                        2_000,
                        3_000,
                        List.of(variable("plain", "STRING", "second"))));
        assertChangedBodyConflict(
                profileRevisionPath(projectId, profileId),
                tokenOne,
                profileRevisionBody(
                        environmentRevisionId,
                        List.of(),
                        1,
                        1,
                        0,
                        60,
                        List.of(),
                        0,
                        0,
                        List.of()),
                profileRevisionBody(
                        environmentRevisionId,
                        List.of(),
                        2,
                        1,
                        0,
                        60,
                        List.of(),
                        0,
                        0,
                        List.of()));

        assertScopeIndependent(
                secretPath(projectId),
                tokenOne,
                json(Map.of("name", "principalOneSecret")),
                secretPath(projectId),
                tokenTwo,
                json(Map.of("name", "principalTwoSecret")));
        assertScopeIndependent(
                environmentPath(projectId),
                tokenOne,
                environmentBody("Principal environment one", List.of(), List.of()),
                environmentPath(projectId),
                tokenTwo,
                environmentBody("Principal environment two", List.of(), List.of()));
        assertScopeIndependent(
                environmentRevisionPath(projectId, environmentId),
                tokenOne,
                environmentRevisionBody(List.of(variable("principal", "INTEGER", 1)), List.of()),
                environmentRevisionPath(projectId, environmentId),
                tokenTwo,
                environmentRevisionBody(List.of(variable("principal", "INTEGER", 2)), List.of()));
        assertScopeIndependent(
                profilePath(projectId),
                tokenOne,
                profileBody(
                        "Principal profile one",
                        environmentRevisionId,
                        List.of(),
                        1,
                        1,
                        0,
                        60,
                        List.of(),
                        0,
                        0,
                        List.of()),
                profilePath(projectId),
                tokenTwo,
                profileBody(
                        "Principal profile two",
                        environmentRevisionId,
                        List.of(),
                        1,
                        1,
                        0,
                        60,
                        List.of(),
                        0,
                        0,
                        List.of()));
        assertScopeIndependent(
                profileRevisionPath(projectId, profileId),
                tokenOne,
                profileRevisionBody(
                        environmentRevisionId,
                        List.of("@principalOne"),
                        1,
                        1,
                        0,
                        60,
                        List.of(),
                        0,
                        0,
                        List.of()),
                profileRevisionPath(projectId, profileId),
                tokenTwo,
                profileRevisionBody(
                        environmentRevisionId,
                        List.of("@principalTwo"),
                        1,
                        1,
                        0,
                        60,
                        List.of(),
                        0,
                        0,
                        List.of()));

        String tokenOtherTenant = token(UUID.randomUUID(), "member-other-tenant");
        String otherProjectId = createProject(tokenOtherTenant);
        assertScopeIndependent(
                secretPath(projectId),
                tokenOne,
                json(Map.of("name", "tenantOneSecret")),
                secretPath(otherProjectId),
                tokenOtherTenant,
                json(Map.of("name", "tenantTwoSecret")));
        JsonNode otherEnvironment = json(post(
                environmentPath(otherProjectId),
                tokenOtherTenant,
                key(),
                environmentBody("Other tenant base", List.of(), List.of())));
        String otherEnvironmentId = otherEnvironment.at("/environment/environmentId").stringValue();
        String otherEnvironmentRevisionId = otherEnvironment.at("/initialRevision/revisionId").stringValue();
        assertScopeIndependent(
                environmentPath(projectId),
                tokenOne,
                environmentBody("Tenant environment one", List.of(), List.of()),
                environmentPath(otherProjectId),
                tokenOtherTenant,
                environmentBody("Tenant environment two", List.of(), List.of()));
        assertScopeIndependent(
                environmentRevisionPath(projectId, environmentId),
                tokenOne,
                environmentRevisionBody(List.of(variable("tenant", "INTEGER", 1)), List.of()),
                environmentRevisionPath(otherProjectId, otherEnvironmentId),
                tokenOtherTenant,
                environmentRevisionBody(List.of(variable("tenant", "INTEGER", 2)), List.of()));
        JsonNode otherProfile = json(post(
                profilePath(otherProjectId),
                tokenOtherTenant,
                key(),
                profileBody(
                        "Other tenant base profile",
                        otherEnvironmentRevisionId,
                        List.of(),
                        1,
                        1,
                        0,
                        60,
                        List.of(),
                        0,
                        0,
                        List.of())));
        String otherProfileId = otherProfile.at("/runProfile/runProfileId").stringValue();
        assertScopeIndependent(
                profilePath(projectId),
                tokenOne,
                profileBody(
                        "Tenant profile one",
                        environmentRevisionId,
                        List.of(),
                        1,
                        1,
                        0,
                        60,
                        List.of(),
                        0,
                        0,
                        List.of()),
                profilePath(otherProjectId),
                tokenOtherTenant,
                profileBody(
                        "Tenant profile two",
                        otherEnvironmentRevisionId,
                        List.of(),
                        1,
                        1,
                        0,
                        60,
                        List.of(),
                        0,
                        0,
                        List.of()));
        assertScopeIndependent(
                profileRevisionPath(projectId, profileId),
                tokenOne,
                profileRevisionBody(
                        environmentRevisionId,
                        List.of("@tenantOne"),
                        1,
                        1,
                        0,
                        60,
                        List.of(),
                        0,
                        0,
                        List.of()),
                profileRevisionPath(otherProjectId, otherProfileId),
                tokenOtherTenant,
                profileRevisionBody(
                        otherEnvironmentRevisionId,
                        List.of("@tenantTwo"),
                        1,
                        1,
                        0,
                        60,
                        List.of(),
                        0,
                        0,
                        List.of()));
    }

    @Test
    void databaseSchemaHasNoSecretMaterialAndEnforcesCompositeOwnershipAndSealing() throws Exception {
        assertThat(jdbc.queryForObject(
                        "select count(*) from flyway_schema_history where version = '2' and success",
                        Integer.class))
                .isEqualTo(1);
        List<String> secretColumns = jdbc.queryForList(
                """
                select column_name from information_schema.columns
                 where table_schema = 'public' and table_name = 'secret_references'
                 order by ordinal_position
                """,
                String.class);
        assertThat(secretColumns)
                .containsExactly(
                        "secret_reference_id",
                        "organization_id",
                        "project_id",
                        "name",
                        "created_by",
                        "created_at");

        String tokenA = token(UUID.randomUUID(), "member-a");
        String tokenB = token(UUID.randomUUID(), "member-b");
        String projectA = createProject(tokenA);
        String projectB = createProject(tokenB);
        UUID organizationA = jdbc.queryForObject(
                "select organization_id from projects where project_id = ?",
                UUID.class,
                UUID.fromString(projectA));
        UUID environmentId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                        """
                        insert into environments
                            (environment_id, organization_id, project_id, name, next_revision_number,
                             version, created_by, created_at)
                        values (?, ?, ?, 'foreign', 2, 0, 'test', now())
                        """,
                        environmentId,
                        organizationA,
                        UUID.fromString(projectB)))
                .isInstanceOf(DataIntegrityViolationException.class);

        JsonNode environment = json(post(
                environmentPath(projectA),
                tokenA,
                key(),
                environmentBody("Database environment", List.of(), List.of())));
        UUID actualEnvironmentId = UUID.fromString(
                environment.at("/environment/environmentId").stringValue());
        UUID actualEnvironmentRevisionId = UUID.fromString(
                environment.at("/initialRevision/revisionId").stringValue());
        assertThatThrownBy(() -> jdbc.update(
                        """
                        insert into environment_revisions
                            (revision_id, organization_id, project_id, environment_id, revision_number,
                             content_sha256, sealed, created_by, created_at)
                        values (?, ?, ?, ?, 1, ?, false, 'test', now())
                        """,
                        UUID.randomUUID(),
                        organizationA,
                        UUID.fromString(projectA),
                        actualEnvironmentId,
                        "0".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(exception -> assertThat(rootCause(exception).getMessage())
                        .contains("uq_environment_revisions_number"));

        String secretReferenceId = json(post(
                        secretPath(projectA), tokenA, key(), json(Map.of("name", "databaseSecret"))))
                .get("secretReferenceId")
                .stringValue();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
                    UUID aggregateRevisionId = UUID.randomUUID();
                    jdbc.update(
                            """
                            insert into environment_revisions
                                (revision_id, organization_id, project_id, environment_id, revision_number,
                                 content_sha256, sealed, created_by, created_at)
                            values (?, ?, ?, ?, 99, ?, false, 'test', now())
                            """,
                            aggregateRevisionId,
                            organizationA,
                            UUID.fromString(projectA),
                            actualEnvironmentId,
                            "0".repeat(64));
                    jdbc.update(
                            """
                            insert into environment_revision_entries
                                (organization_id, project_id, environment_id, environment_revision_id,
                                 config_key, value_kind, string_value)
                            values (?, ?, ?, ?, 'duplicate', 'STRING', 'plain')
                            """,
                            organizationA,
                            UUID.fromString(projectA),
                            actualEnvironmentId,
                            aggregateRevisionId);
                    jdbc.update(
                            """
                            insert into environment_revision_entries
                                (organization_id, project_id, environment_id, environment_revision_id,
                                 config_key, value_kind, secret_reference_id)
                            values (?, ?, ?, ?, 'duplicate', 'SECRET_REFERENCE', ?)
                            """,
                            organizationA,
                            UUID.fromString(projectA),
                            actualEnvironmentId,
                            aggregateRevisionId,
                            UUID.fromString(secretReferenceId));
                }))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(exception -> assertThat(rootCause(exception).getMessage())
                        .contains("environment_revision_entries_pkey"));

        JsonNode profile = json(post(
                profilePath(projectA),
                tokenA,
                key(),
                profileBody(
                        "Database profile",
                        actualEnvironmentRevisionId.toString(),
                        List.of(),
                        1,
                        1,
                        0,
                        60,
                        List.of(),
                        0,
                        0,
                        List.of())));
        UUID actualProfileId = UUID.fromString(profile.at("/runProfile/runProfileId").stringValue());
        assertThatThrownBy(() -> jdbc.update(
                        """
                        insert into run_profile_revisions
                            (revision_id, organization_id, project_id, run_profile_id, revision_number,
                             environment_id, environment_revision_id, parallelism, retry_max_attempts,
                             retry_delay_milliseconds, execution_timeout_seconds, max_artifact_bytes,
                             max_total_bytes, content_sha256, sealed, created_by, created_at)
                        values (?, ?, ?, ?, 1, ?, ?, 1, 1, 0, 60, 0, 0, ?, false, 'test', now())
                        """,
                        UUID.randomUUID(),
                        organizationA,
                        UUID.fromString(projectA),
                        actualProfileId,
                        actualEnvironmentId,
                        actualEnvironmentRevisionId,
                        "0".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(exception -> assertThat(rootCause(exception).getMessage())
                        .contains("uq_run_profile_revisions_number"));

        JsonNode foreignEnvironment = json(post(
                environmentPath(projectB),
                tokenB,
                key(),
                environmentBody("Foreign database environment", List.of(), List.of())));
        assertThatThrownBy(() -> jdbc.update(
                        """
                        insert into run_profile_revisions
                            (revision_id, organization_id, project_id, run_profile_id, revision_number,
                             environment_id, environment_revision_id, parallelism, retry_max_attempts,
                             retry_delay_milliseconds, execution_timeout_seconds, max_artifact_bytes,
                             max_total_bytes, content_sha256, sealed, created_by, created_at)
                        values (?, ?, ?, ?, 99, ?, ?, 1, 1, 0, 60, 0, 0, ?, false, 'test', now())
                        """,
                        UUID.randomUUID(),
                        organizationA,
                        UUID.fromString(projectA),
                        actualProfileId,
                        UUID.fromString(foreignEnvironment.at("/environment/environmentId").stringValue()),
                        UUID.fromString(foreignEnvironment.at("/initialRevision/revisionId").stringValue()),
                        "0".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(exception -> assertThat(rootCause(exception).getMessage())
                        .contains("fk_run_profile_revisions_environment_revision"));
        assertThatThrownBy(() -> jdbc.update(
                        """
                        insert into environment_revisions
                            (revision_id, organization_id, project_id, environment_id, revision_number,
                             content_sha256, sealed, created_by, created_at)
                        values (?, ?, ?, ?, 99, ?, false, 'test', now())
                        """,
                        UUID.randomUUID(),
                        organizationA,
                        UUID.fromString(projectA),
                        actualEnvironmentId,
                        "0".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void runIntentCreatesOnlyASealedReproducibleSnapshotWithSafeTenantScopedReads() throws Exception {
        UUID organizationA = UUID.randomUUID();
        UUID organizationB = UUID.randomUUID();
        String tokenA = token(organizationA, "run-creator");
        String tokenB = token(organizationB, "other-tenant");
        String projectA = createProject(tokenA);
        String projectB = createProject(tokenB);

        JsonNode featureA = json(post(
                "/api/v1/projects/" + projectA + "/features",
                tokenA,
                key(),
                json(Map.of(
                        "name", "Run feature A",
                        "logicalPath", "features/a.feature",
                        "source", "Feature: A\nScenario: one\n* match 1 == 1\n"))));
        JsonNode featureZ = json(post(
                "/api/v1/projects/" + projectA + "/features",
                tokenA,
                key(),
                json(Map.of(
                        "name", "Run feature Z",
                        "logicalPath", "features/z.feature",
                        "source", "Feature: Z\nScenario: one\n* match 1 == 1\n"))));
        JsonNode foreignFeature = json(post(
                "/api/v1/projects/" + projectB + "/features",
                tokenB,
                key(),
                json(Map.of(
                        "name", "Foreign run feature",
                        "logicalPath", "foreign.feature",
                        "source", "Feature: foreign\nScenario: one\n* match 1 == 1\n"))));
        String featureAId = featureA.at("/feature/featureId").stringValue();
        String featureARevision = featureA.at("/initialRevision/revisionId").stringValue();
        String featureZRevision = featureZ.at("/initialRevision/revisionId").stringValue();
        String foreignFeatureRevision = foreignFeature.at("/initialRevision/revisionId").stringValue();

        String secretId = json(post(
                        secretPath(projectA), tokenA, key(), json(Map.of("name", "runClientSecret"))))
                .get("secretReferenceId")
                .stringValue();
        JsonNode environment = json(post(
                environmentPath(projectA),
                tokenA,
                key(),
                environmentBody(
                        "Run environment",
                        List.of(
                                variable("timeout", "INTEGER", 10_000),
                                variable("baseUrl", "STRING", "https://environment.example"),
                                variable("enabled", "BOOLEAN", true)),
                        List.of(binding("clientSecret", secretId)))));
        String environmentId = environment.at("/environment/environmentId").stringValue();
        String environmentRevision = environment.at("/initialRevision/revisionId").stringValue();
        JsonNode profile = json(post(
                profilePath(projectA),
                tokenA,
                key(),
                profileBody(
                        "Run profile",
                        environmentRevision,
                        List.of("@smoke", "@regression"),
                        4,
                        2,
                        250,
                        300,
                        List.of("RAW_RESULT", "EXECUTION_LOG"),
                        1_000,
                        2_000,
                        List.of(variable("baseUrl", "STRING", "https://override.example")))));
        String profileId = profile.at("/runProfile/runProfileId").stringValue();
        String profileRevision = profile.at("/initialRevision/revisionId").stringValue();

        String runPath = "/api/v1/projects/" + projectA + "/runs";
        String request = json(Map.of(
                "featureRevisionIds", List.of(featureZRevision, featureARevision),
                "runProfileRevisionId", profileRevision));
        String runKey = key();
        assertProblem(post(runPath, null, runKey, request), 401, "UNAUTHENTICATED");
        assertProblem(get(runPath, null), 401, "UNAUTHENTICATED");
        var created = post(runPath, tokenA, runKey, request);
        assertThat(created.statusCode()).isEqualTo(202);
        assertThat(created.headers().firstValue("ETag")).contains("\"run-1\"");
        assertThat(created.headers().firstValue("Idempotency-Replayed")).contains("false");
        assertThat(created.headers().firstValue("Cache-Control"))
                .hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        JsonNode run = json(created);
        String runId = run.get("runId").stringValue();
        assertThat(created.headers().firstValue("Location")).contains("/api/v1/runs/" + runId);
        assertThat(run.get("projectId").stringValue()).isEqualTo(projectA);
        assertThat(run.get("runVersion").asInt()).isEqualTo(1);
        assertThat(run.get("lifecycleState").stringValue()).isEqualTo("CREATED");
        assertThat(run.get("cancellationStatus").stringValue()).isEqualTo("NOT_REQUESTED");
        assertThat(run.get("qualityGateStatus").stringValue()).isEqualTo("NOT_EVALUATED");
        assertThat(run.get("testOutcome").isNull()).isTrue();
        assertThat(run.get("infrastructureOutcome").isNull()).isTrue();
        assertThat(run.get("queueStartedAt").isNull()).isTrue();
        assertThat(run.get("queueDeadlineAt").isNull()).isTrue();
        assertThat(run.has("currentAttempt")).isFalse();
        assertThat(run.get("createdBy").stringValue()).isEqualTo("run-creator");
        assertThat(run.get("createdAt")).isEqualTo(run.get("updatedAt"));

        var fetched = get("/api/v1/runs/" + runId, tokenA);
        assertThat(fetched.statusCode()).isEqualTo(200);
        assertThat(fetched.body()).isEqualTo(created.body());
        assertThat(fetched.headers().firstValue("ETag")).contains("\"run-1\"");
        assertThat(fetched.headers().firstValue("Cache-Control")).contains("no-store");
        var initialList = get(runPath, tokenA);
        assertThat(initialList.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(json(initialList).at("/items/0/runId").stringValue()).isEqualTo(runId);

        var snapshotResponse = get("/api/v1/runs/" + runId + "/snapshot", tokenA);
        assertThat(snapshotResponse.statusCode()).isEqualTo(200);
        assertThat(snapshotResponse.headers().firstValue("Cache-Control")).contains("no-store");
        String originalSnapshotBody = snapshotResponse.body();
        JsonNode snapshot = json(snapshotResponse);
        assertThat(snapshot.get("snapshotVersion").asInt()).isEqualTo(1);
        assertThat(snapshot.get("snapshotDigest").stringValue()).matches("sha256:[a-f0-9]{64}");
        assertThat(snapshot.at("/environment/resourceId").stringValue()).isEqualTo(environmentId);
        assertThat(snapshot.at("/environment/revisionId").stringValue()).isEqualTo(environmentRevision);
        assertThat(snapshot.at("/environment/revisionNumber").asInt()).isEqualTo(1);
        assertThat(snapshot.at("/environment/contentDigest").stringValue()).matches("sha256:[a-f0-9]{64}");
        assertThat(snapshot.at("/runProfile/resourceId").stringValue()).isEqualTo(profileId);
        assertThat(snapshot.at("/runProfile/revisionId").stringValue()).isEqualTo(profileRevision);
        assertThat(snapshot.at("/runProfile/revisionNumber").asInt()).isEqualTo(1);
        assertThat(snapshot.at("/runProfile/contentDigest").stringValue()).matches("sha256:[a-f0-9]{64}");
        assertThat(snapshot.at("/features/0/featureId").stringValue()).isEqualTo(featureAId);
        assertThat(snapshot.at("/features/0/revisionId").stringValue()).isEqualTo(featureARevision);
        assertThat(snapshot.at("/features/0/revisionNumber").asInt()).isEqualTo(1);
        assertThat(snapshot.at("/features/0/sourceDigest").stringValue()).matches("sha256:[a-f0-9]{64}");
        assertThat(snapshot.at("/features/0/logicalPath").stringValue()).isEqualTo("features/a.feature");
        assertThat(snapshot.at("/features/1/logicalPath").stringValue()).isEqualTo("features/z.feature");
        assertThat(snapshot.at("/effectiveConfiguration/0/key").stringValue()).isEqualTo("baseUrl");
        assertThat(snapshot.at("/effectiveConfiguration/0/value").stringValue())
                .isEqualTo("https://override.example");
        assertThat(snapshot.at("/secretBindings/0/secretReferenceId").stringValue()).isEqualTo(secretId);
        assertThat(snapshot.at("/selection/tags").toString()).isEqualTo("[\"@regression\",\"@smoke\"]");
        assertThat(snapshot.get("parallelism").asInt()).isEqualTo(4);
        assertThat(snapshot.at("/scenarioRetry/maxAttempts").asInt()).isEqualTo(2);
        assertThat(snapshot.at("/scenarioRetry/delayMilliseconds").asInt()).isEqualTo(250);
        assertThat(snapshot.get("executionTimeoutSeconds").asInt()).isEqualTo(300);
        assertThat(snapshot.at("/artifactPolicy/types").toString())
                .isEqualTo("[\"EXECUTION_LOG\",\"RAW_RESULT\"]");
        assertThat(snapshot.at("/artifactPolicy/maxArtifactBytes").asLong()).isEqualTo(1_000);
        assertThat(snapshot.at("/artifactPolicy/maxTotalBytes").asLong()).isEqualTo(2_000);
        assertThat(snapshot.at("/engine/engine").stringValue()).isEqualTo("KARATE");
        assertThat(snapshot.at("/engine/version").stringValue()).isEqualTo("2.0.0");
        assertThat(snapshot.toString()).doesNotContain("Feature: A", "secretValue", "provider", "capability");

        String reorderedRequest = json(Map.of(
                "runProfileRevisionId", profileRevision,
                "featureRevisionIds", List.of(featureARevision, featureZRevision)));
        var replay = post(runPath, tokenA, runKey, reorderedRequest);
        assertThat(replay.statusCode()).isEqualTo(202);
        assertThat(replay.body()).isEqualTo(created.body());
        assertThat(replay.headers().firstValue("Location")).isEqualTo(created.headers().firstValue("Location"));
        assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertProblem(
                post(
                        runPath,
                        tokenA,
                        runKey,
                        json(Map.of(
                                "runProfileRevisionId", profileRevision,
                                "featureRevisionIds", List.of(featureARevision)))),
                409,
                "IDEMPOTENCY_CONFLICT");

        String concurrentKey = key();
        CyclicBarrier runBarrier = new CyclicBarrier(2);
        List<HttpResponse<String>> concurrentRuns;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = IntStream.range(0, 2)
                    .mapToObj(ignored -> executor.submit(() -> {
                        runBarrier.await();
                        return post(runPath, tokenA, concurrentKey, request);
                    }))
                    .toList();
            concurrentRuns = futures.stream()
                    .map(future -> {
                        try {
                            return future.get(30, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .toList();
        }
        assertThat(concurrentRuns).allMatch(response -> response.statusCode() == 202);
        assertThat(concurrentRuns.get(0).body()).isEqualTo(concurrentRuns.get(1).body());
        assertThat(concurrentRuns.get(0).headers().firstValue("Location"))
                .isEqualTo(concurrentRuns.get(1).headers().firstValue("Location"));
        assertThat(concurrentRuns.get(0).headers().firstValue("ETag"))
                .isEqualTo(concurrentRuns.get(1).headers().firstValue("ETag"));
        assertThat(concurrentRuns.stream()
                        .filter(response -> response.headers()
                                .firstValue("Idempotency-Replayed")
                                .map("true"::equals)
                                .orElse(false))
                        .count())
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        """
                        select count(*) from api_idempotency_keys
                         where organization_id = ? and principal_id = 'run-creator'
                           and operation = 'createRun' and scope_path = ? and idempotency_key = ?
                        """,
                        Integer.class,
                        organizationA,
                        "/projects/" + projectA + "/runs",
                        concurrentKey))
                .isEqualTo(1);

        var semanticallySame = post(runPath, tokenA, key(), reorderedRequest);
        assertThat(semanticallySame.statusCode()).isEqualTo(202);
        assertThat(json(semanticallySame).get("runId").stringValue()).isNotEqualTo(runId);
        assertThat(json(semanticallySame).get("snapshotDigest").stringValue())
                .isEqualTo(run.get("snapshotDigest").stringValue());
        JsonNode runPageZero = json(get(runPath + "?page=0&size=2", tokenA));
        JsonNode runPageOne = json(get(runPath + "?page=1&size=2", tokenA));
        assertThat(runPageZero.get("totalElements").asInt()).isEqualTo(3);
        assertThat(runPageZero.get("items").size()).isEqualTo(2);
        assertThat(runPageOne.get("items").size()).isEqualTo(1);
        assertThat(runPageZero.at("/items/0/runId").stringValue())
                .isNotEqualTo(runPageZero.at("/items/1/runId").stringValue())
                .isNotEqualTo(runPageOne.at("/items/0/runId").stringValue());
        var otherPrincipal = post(runPath, token(organizationA, "run-creator-two"), runKey, request);
        assertThat(otherPrincipal.statusCode()).isEqualTo(202);
        assertThat(json(otherPrincipal).get("runId").stringValue()).isNotEqualTo(runId);
        var oneFeatureRun = post(
                runPath,
                tokenA,
                key(),
                json(Map.of(
                        "runProfileRevisionId", profileRevision,
                        "featureRevisionIds", List.of(featureARevision))));
        assertThat(oneFeatureRun.statusCode()).isEqualTo(202);

        assertProblem(
                post(
                        runPath,
                        tokenA,
                        key(),
                        json(Map.of(
                                "runProfileRevisionId", profileRevision,
                                "featureRevisionIds", List.of(featureARevision, featureARevision)))),
                422,
                "VALIDATION_FAILED");
        assertProblem(
                post(
                        runPath,
                        tokenA,
                        key(),
                        json(Map.of(
                                "runProfileRevisionId", profileRevision,
                                "featureRevisionIds", List.of(featureARevision, foreignFeatureRevision)))),
                404,
                "NOT_FOUND");
        JsonNode foreignEnvironment = json(post(
                environmentPath(projectB),
                tokenB,
                key(),
                environmentBody("Foreign run environment", List.of(), List.of())));
        JsonNode foreignProfile = json(post(
                profilePath(projectB),
                tokenB,
                key(),
                profileBody(
                        "Foreign run profile",
                        foreignEnvironment.at("/initialRevision/revisionId").stringValue(),
                        List.of(),
                        1,
                        1,
                        0,
                        60,
                        List.of(),
                        0,
                        0,
                        List.of())));
        String foreignRunPath = "/api/v1/projects/" + projectB + "/runs";
        var independentTenantRun = post(
                foreignRunPath,
                tokenB,
                runKey,
                json(Map.of(
                        "runProfileRevisionId", foreignProfile.at("/initialRevision/revisionId").stringValue(),
                        "featureRevisionIds", List.of(foreignFeatureRevision))));
        assertThat(independentTenantRun.statusCode()).isEqualTo(202);
        assertThat(json(independentTenantRun).get("runId").stringValue()).isNotEqualTo(runId);
        assertProblem(
                post(
                        runPath,
                        tokenA,
                        key(),
                        json(Map.of(
                                "runProfileRevisionId",
                                foreignProfile.at("/initialRevision/revisionId").stringValue(),
                                "featureRevisionIds",
                                List.of(featureARevision)))),
                404,
                "NOT_FOUND");
        assertProblem(
                post(
                        runPath,
                        tokenA,
                        key(),
                        """
                        {"featureRevisionIds":["%s"],"runProfileRevisionId":"%s","engine":"evil"}
                        """.formatted(featureARevision, profileRevision)),
                400,
                "VALIDATION_FAILED");
        assertProblem(
                post(
                        runPath,
                        tokenA,
                        key(),
                        """
                        {"featureRevisionIds":["not-a-uuid"],"runProfileRevisionId":"%s"}
                        """.formatted(profileRevision)),
                400,
                "VALIDATION_FAILED");
        assertProblem(get("/api/v1/runs/" + runId, null), 401, "UNAUTHENTICATED");
        assertProblem(get("/api/v1/runs/" + runId + "/snapshot", null), 401, "UNAUTHENTICATED");
        assertProblem(get("/api/v1/runs/" + runId, tokenB), 404, "NOT_FOUND");
        assertProblem(get("/api/v1/runs/" + runId + "/snapshot", tokenB), 404, "NOT_FOUND");
        assertProblem(get(runPath, tokenB), 404, "NOT_FOUND");

        var appended = post(
                "/api/v1/projects/" + projectA + "/features/" + featureAId + "/revisions",
                tokenA,
                key(),
                json(Map.of("source", "Feature: A\nScenario: changed\n* match 2 == 2\n")));
        assertThat(appended.statusCode()).isEqualTo(201);
        assertThat(get("/api/v1/runs/" + runId + "/snapshot", tokenA).body()).isEqualTo(originalSnapshotBody);
        String featureANewRevision = json(appended).get("revisionId").stringValue();
        assertProblem(
                post(
                        runPath,
                        tokenA,
                        key(),
                        json(Map.of(
                                "runProfileRevisionId", profileRevision,
                                "featureRevisionIds", List.of(featureARevision, featureANewRevision)))),
                422,
                "VALIDATION_FAILED");
        JsonNode environmentTwo = json(post(
                environmentRevisionPath(projectA, environmentId),
                tokenA,
                key(),
                environmentRevisionBody(
                        List.of(
                                variable("timeout", "INTEGER", 20_000),
                                variable("baseUrl", "STRING", "https://environment-v2.example"),
                                variable("enabled", "BOOLEAN", false)),
                        List.of(binding("clientSecret", secretId)))));
        JsonNode profileTwo = json(post(
                profileRevisionPath(projectA, profileId),
                tokenA,
                key(),
                profileRevisionBody(
                        environmentTwo.get("revisionId").stringValue(),
                        List.of("@smoke", "@regression"),
                        4,
                        2,
                        250,
                        301,
                        List.of("RAW_RESULT", "EXECUTION_LOG"),
                        1_000,
                        2_000,
                        List.of(variable("baseUrl", "STRING", "https://override-v2.example")))));
        var runTwo = post(
                runPath,
                tokenA,
                key(),
                json(Map.of(
                        "runProfileRevisionId", profileTwo.get("revisionId").stringValue(),
                        "featureRevisionIds", List.of(featureANewRevision, featureZRevision))));
        assertThat(runTwo.statusCode()).isEqualTo(202);
        JsonNode snapshotTwo = json(get(
                "/api/v1/runs/" + json(runTwo).get("runId").stringValue() + "/snapshot", tokenA));
        assertThat(snapshotTwo.get("snapshotDigest").stringValue())
                .isNotEqualTo(snapshot.get("snapshotDigest").stringValue());
        assertThat(snapshotTwo.at("/environment/revisionId").stringValue())
                .isEqualTo(environmentTwo.get("revisionId").stringValue());
        assertThat(snapshotTwo.at("/runProfile/revisionId").stringValue())
                .isEqualTo(profileTwo.get("revisionId").stringValue());
        assertThat(snapshotTwo.at("/features/0/revisionId").stringValue()).isEqualTo(featureANewRevision);
        assertThat(get("/api/v1/runs/" + runId + "/snapshot", tokenA).body()).isEqualTo(originalSnapshotBody);

        List<UUID> maximumRevisionIds = new ArrayList<>();
        maximumRevisionIds.add(UUID.fromString(featureARevision));
        maximumRevisionIds.add(UUID.fromString(featureZRevision));
        List<Object[]> featureRows = new ArrayList<>();
        List<Object[]> revisionRows = new ArrayList<>();
        for (int index = 2; index < 1000; index++) {
            UUID featureId = UUID.randomUUID();
            UUID revisionId = UUID.randomUUID();
            maximumRevisionIds.add(revisionId);
            featureRows.add(new Object[] {
                featureId,
                organizationA,
                UUID.fromString(projectA),
                "Bulk feature " + index,
                "bulk/feature-%04d.feature".formatted(index),
                "run-creator",
                java.sql.Timestamp.from(Instant.now())
            });
            revisionRows.add(new Object[] {
                revisionId,
                organizationA,
                UUID.fromString(projectA),
                featureId,
                "Feature: bulk\nScenario: exact\n* match 1 == 1\n",
                "0".repeat(64),
                "run-creator",
                java.sql.Timestamp.from(Instant.now())
            });
        }
        jdbc.batchUpdate(
                """
                insert into features
                    (feature_id, organization_id, project_id, name, logical_path,
                     next_revision_number, version, created_by, created_at)
                values (?, ?, ?, ?, ?, 2, 0, ?, ?)
                """,
                featureRows);
        jdbc.batchUpdate(
                """
                insert into feature_revisions
                    (revision_id, organization_id, project_id, feature_id, revision_number,
                     source, source_sha256, created_by, created_at)
                values (?, ?, ?, ?, 1, ?, ?, ?, ?)
                """,
                revisionRows);
        var maximumRun = post(
                runPath,
                tokenA,
                key(),
                json(Map.of(
                        "runProfileRevisionId", profileRevision,
                        "featureRevisionIds", maximumRevisionIds)));
        assertThat(maximumRun.statusCode()).isEqualTo(202);
        List<UUID> tooManyRevisionIds = new ArrayList<>(maximumRevisionIds);
        tooManyRevisionIds.add(UUID.randomUUID());
        assertProblem(
                post(
                        runPath,
                        tokenA,
                        key(),
                        json(Map.of(
                                "runProfileRevisionId", profileRevision,
                                "featureRevisionIds", tooManyRevisionIds))),
                422,
                "VALIDATION_FAILED");
        assertProblem(
                post(
                        runPath,
                        tokenA,
                        key(),
                        json(Map.of(
                                "runProfileRevisionId", profileRevision,
                                "featureRevisionIds", List.of()))),
                422,
                "VALIDATION_FAILED");

        assertThat(jdbc.queryForObject(
                        "select count(*) from test_runs where run_id = ?", Integer.class, UUID.fromString(runId)))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from run_snapshots where run_id = ? and sealed",
                        Integer.class,
                        UUID.fromString(runId)))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from test_runs where run_id = ? and queued_at is null and queue_deadline_at is null",
                        Integer.class,
                        UUID.fromString(runId)))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from information_schema.tables where table_schema = 'public' and table_name in ('execution_commands','worker_assignments','runtime_capabilities')",
                        Integer.class))
                .isZero();
        assertThatThrownBy(() -> jdbc.update(
                        "update run_snapshots set engine_version = '9.9.9' where run_id = ?",
                        UUID.fromString(runId)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(exception -> assertThat(rootCause(exception).getMessage())
                        .contains("run snapshots are immutable"));
        assertThatThrownBy(() -> jdbc.update(
                        "delete from run_snapshots where run_id = ?", UUID.fromString(runId)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(exception -> assertThat(rootCause(exception).getMessage())
                        .contains("run snapshots are immutable"));
        assertThatThrownBy(() -> jdbc.update(
                        "update run_snapshot_tags set tag = '@changed' where run_id = ? and tag = '@smoke'",
                        UUID.fromString(runId)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(exception -> assertThat(rootCause(exception).getMessage())
                        .contains("run snapshot children are immutable"));
        assertThatThrownBy(() -> jdbc.update(
                        "delete from run_snapshot_features where run_id = ? and ordinal = 0",
                        UUID.fromString(runId)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(exception -> assertThat(rootCause(exception).getMessage())
                        .contains("run snapshot children are immutable"));
        assertThatThrownBy(() -> jdbc.update(
                        "insert into run_snapshot_tags (organization_id, project_id, run_id, tag) values (?, ?, ?, '@late')",
                        organizationA,
                        UUID.fromString(projectA),
                        UUID.fromString(runId)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(exception -> assertThat(rootCause(exception).getMessage())
                        .contains("run snapshot children require an unsealed parent"));
        assertThatThrownBy(() -> jdbc.update("delete from test_runs where run_id = ?", UUID.fromString(runId)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(exception -> assertThat(rootCause(exception).getMessage())
                        .contains("test runs cannot be deleted"));
        assertThatThrownBy(() -> jdbc.update(
                        "update test_runs set run_version = 2, snapshot_sha256 = ? where run_id = ?",
                        "f".repeat(64),
                        UUID.fromString(runId)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(exception -> assertThat(rootCause(exception).getMessage())
                        .contains("only scheduling, claim, stop, and terminal transitions"));
        assertThatThrownBy(() -> jdbc.update(
                        "update test_runs set run_version = 2, lifecycle_state = 'RUNNING' where run_id = ?",
                        UUID.fromString(runId)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(exception -> assertThat(rootCause(exception).getMessage())
                        .contains("only scheduling, claim, stop, and terminal transitions"));
        assertThatThrownBy(() -> jdbc.update(
                        """
                        insert into test_runs
                            (run_id, organization_id, project_id, run_version, lifecycle_state,
                             cancellation_status, quality_gate_status, snapshot_sha256,
                             created_by, created_at, updated_by, updated_at)
                        values (?, ?, ?, 2, 'CREATED', 'NOT_REQUESTED', 'NOT_EVALUATED', ?, 'test', now(), 'test', now())
                        """,
                        UUID.randomUUID(),
                        organizationA,
                        UUID.fromString(projectA),
                        "0".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(exception -> assertThat(rootCause(exception).getMessage())
                        .contains("exact CREATED state"));
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> jdbc.update(
                        """
                        insert into test_runs
                            (run_id, organization_id, project_id, run_version, lifecycle_state,
                             cancellation_status, quality_gate_status, snapshot_sha256,
                             created_by, created_at, updated_by, updated_at)
                        values (?, ?, ?, 1, 'CREATED', 'NOT_REQUESTED', 'NOT_EVALUATED', ?, 'test', now(), 'test', now())
                        """,
                        UUID.randomUUID(),
                        organizationA,
                        UUID.fromString(projectA),
                        "0".repeat(64))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(exception -> assertThat(rootCause(exception).getMessage())
                        .contains("requires exactly one matching sealed snapshot"));
        assertThat(jdbc.queryForList(
                        """
                        select column_name from information_schema.columns
                         where table_schema = 'public'
                           and table_name in ('run_snapshots', 'run_snapshot_configuration_entries')
                        """,
                        String.class))
                .noneMatch(column -> column.matches(".*(provider|capability|credential|ciphertext|secret_value|token|uri|path).*"));
    }

    private JsonNode assertConcurrentSamePost(String path, String bearer, String body) throws Exception {
        String idempotencyKey = key();
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<HttpResponse<String>> responses;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = IntStream.range(0, 2)
                    .mapToObj(ignored -> executor.submit(() -> {
                        barrier.await();
                        return post(path, bearer, idempotencyKey, body);
                    }))
                    .toList();
            responses = futures.stream().map(future -> {
                        try {
                            return future.get(30, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .toList();
        }
        assertThat(responses).allMatch(response -> response.statusCode() == 201, responses.toString());
        assertThat(responses.get(0).body()).isEqualTo(responses.get(1).body());
        assertThat(responses.get(0).headers().firstValue("Location"))
                .isEqualTo(responses.get(1).headers().firstValue("Location"));
        assertThat(responses.stream()
                        .filter(response -> response.headers()
                                .firstValue("Idempotency-Replayed")
                                .map("true"::equals)
                                .orElse(false))
                        .count())
                .isEqualTo(1);
        return json(responses.get(0));
    }

    private static Throwable rootCause(Throwable exception) {
        Throwable current = exception;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private void assertScopeIndependent(
            String firstPath,
            String firstBearer,
            String firstBody,
            String secondPath,
            String secondBearer,
            String secondBody)
            throws Exception {
        String sharedKey = key();
        assertThat(post(firstPath, firstBearer, sharedKey, firstBody).statusCode()).isEqualTo(201);
        assertThat(post(secondPath, secondBearer, sharedKey, secondBody).statusCode()).isEqualTo(201);
    }

    private void assertChangedBodyConflict(String path, String bearer, String originalBody, String changedBody)
            throws Exception {
        String idempotencyKey = key();
        assertThat(post(path, bearer, idempotencyKey, originalBody).statusCode()).isEqualTo(201);
        assertProblem(post(path, bearer, idempotencyKey, changedBody), 409, "IDEMPOTENCY_CONFLICT");
    }

    private String createProject(String bearer) throws Exception {
        var response = post(
                "/api/v1/projects", bearer, key(), json(Map.of("name", "Project " + UUID.randomUUID())));
        assertThat(response.statusCode()).isEqualTo(201);
        return json(response).get("projectId").stringValue();
    }

    private HttpResponse<String> post(String path, String bearer, String idempotencyKey, String body)
            throws Exception {
        return send("POST", path, bearer, idempotencyKey, body);
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        return send("GET", path, bearer, null, null);
    }

    private HttpResponse<String> send(
            String method, String path, String bearer, String idempotencyKey, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", "application/json");
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String environmentBody(
            String name, List<Map<String, Object>> variables, List<Map<String, Object>> bindings) throws Exception {
        return json(Map.of("name", name, "variables", variables, "secretBindings", bindings));
    }

    private String environmentRevisionBody(
            List<Map<String, Object>> variables, List<Map<String, Object>> bindings) throws Exception {
        return json(Map.of("variables", variables, "secretBindings", bindings));
    }

    private String profileBody(
            String name,
            String environmentRevisionId,
            List<String> tags,
            int parallelism,
            int maxAttempts,
            int delayMilliseconds,
            int timeoutSeconds,
            List<String> artifactTypes,
            long maxArtifactBytes,
            long maxTotalBytes,
            List<Map<String, Object>> overrides) throws Exception {
        Map<String, Object> body = profileRevisionMap(
                environmentRevisionId,
                tags,
                parallelism,
                maxAttempts,
                delayMilliseconds,
                timeoutSeconds,
                artifactTypes,
                maxArtifactBytes,
                maxTotalBytes,
                overrides);
        body.put("name", name);
        return json(body);
    }

    private String profileRevisionBody(
            String environmentRevisionId,
            List<String> tags,
            int parallelism,
            int maxAttempts,
            int delayMilliseconds,
            int timeoutSeconds,
            List<String> artifactTypes,
            long maxArtifactBytes,
            long maxTotalBytes,
            List<Map<String, Object>> overrides) throws Exception {
        return json(profileRevisionMap(
                environmentRevisionId,
                tags,
                parallelism,
                maxAttempts,
                delayMilliseconds,
                timeoutSeconds,
                artifactTypes,
                maxArtifactBytes,
                maxTotalBytes,
                overrides));
    }

    private static Map<String, Object> profileRevisionMap(
            String environmentRevisionId,
            List<String> tags,
            int parallelism,
            int maxAttempts,
            int delayMilliseconds,
            int timeoutSeconds,
            List<String> artifactTypes,
            long maxArtifactBytes,
            long maxTotalBytes,
            List<Map<String, Object>> overrides) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("environmentRevisionId", environmentRevisionId);
        body.put("selection", Map.of("tags", tags));
        body.put("parallelism", parallelism);
        body.put(
                "scenarioRetry",
                Map.of("maxAttempts", maxAttempts, "delayMilliseconds", delayMilliseconds));
        body.put("executionTimeoutSeconds", timeoutSeconds);
        body.put(
                "artifactPolicy",
                Map.of(
                        "types",
                        artifactTypes,
                        "maxArtifactBytes",
                        maxArtifactBytes,
                        "maxTotalBytes",
                        maxTotalBytes));
        body.put("configurationOverrides", overrides);
        return body;
    }

    private static Map<String, Object> variable(String key, String type, Object value) {
        return Map.of("key", key, "type", type, "value", value);
    }

    private static Map<String, Object> binding(String key, String secretReferenceId) {
        return Map.of("key", key, "secretReferenceId", secretReferenceId);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
    }

    private JsonNode uncheckedJson(HttpResponse<String> response) {
        try {
            return json(response);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/problem+json");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        JsonNode problem = json(response);
        assertThat(problem.get("status").asInt()).isEqualTo(status);
        assertThat(problem.get("code").stringValue()).isEqualTo(code);
        assertThat(problem.hasNonNull("requestId")).isTrue();
        assertThat(response.body())
                .doesNotContain(
                        "sensitive-sentinel",
                        "org.postgresql",
                        "constraint",
                        "Bearer ",
                        "SELECT ",
                        "INSERT ");
    }

    private static String environmentPath(String projectId) {
        return "/api/v1/projects/" + projectId + "/environments";
    }

    private static String environmentRevisionPath(String projectId, String environmentId) {
        return environmentPath(projectId) + "/" + environmentId + "/revisions";
    }

    private static String profilePath(String projectId) {
        return "/api/v1/projects/" + projectId + "/run-profiles";
    }

    private static String profileRevisionPath(String projectId, String profileId) {
        return profilePath(projectId) + "/" + profileId + "/revisions";
    }

    private static String secretPath(String projectId) {
        return "/api/v1/projects/" + projectId + "/secret-references";
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    private static String token(UUID organizationId, String subject) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(subject)
                .audience(AUDIENCE)
                .issueTime(Date.from(now.minusSeconds(5)))
                .notBeforeTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("org_id", organizationId.toString())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
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
