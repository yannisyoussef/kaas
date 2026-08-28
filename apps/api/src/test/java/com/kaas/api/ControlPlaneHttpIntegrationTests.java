package com.kaas.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@Import(ControlPlaneHttpIntegrationTests.JwtTestConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ControlPlaneHttpIntegrationTests {
    private static final String ISSUER = "https://issuer.kaas.test";
    private static final String AUDIENCE = "kaas-api";
    private static final KeyPair SIGNING_KEY = keyPair();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16.10-alpine").withDatabaseName("kaas");

    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void healthIsPublicAndProductEndpointsRequireAValidSignedTenantToken() throws Exception {
        assertThat(send("GET", "/actuator/health", null, null, null).statusCode()).isEqualTo(200);

        var missing = send("GET", "/api/v1/projects", null, null, null);
        assertProblem(missing, 401, "UNAUTHENTICATED");
        assertThat(missing.headers().firstValue("WWW-Authenticate")).contains("Bearer");

        var malformed = send("GET", "/api/v1/projects", "not-a-jwt", null, null);
        assertProblem(malformed, 401, "UNAUTHENTICATED");

        var wrongSignature = send(
                "GET", "/api/v1/projects", token(UUID.randomUUID(), "member", keyPair(), AUDIENCE, true), null, null);
        assertProblem(wrongSignature, 401, "UNAUTHENTICATED");

        var wrongAudience = send(
                "GET", "/api/v1/projects", token(UUID.randomUUID(), "member", SIGNING_KEY, "other-api", true), null, null);
        assertProblem(wrongAudience, 401, "UNAUTHENTICATED");

        var missingOrganization = send("GET", "/api/v1/projects", token(null, "member", SIGNING_KEY, AUDIENCE, true), null, null);
        assertProblem(missingOrganization, 401, "UNAUTHENTICATED");

        var missingSubject = send("GET", "/api/v1/projects", token(UUID.randomUUID(), null, SIGNING_KEY, AUDIENCE, true), null, null);
        assertProblem(missingSubject, 401, "UNAUTHENTICATED");

        var malformedOrganization = send(
                "GET", "/api/v1/projects", tokenWithOrganizationClaim("not-a-uuid", "member"), null, null);
        assertProblem(malformedOrganization, 401, "UNAUTHENTICATED");

        var expired = send(
                "GET", "/api/v1/projects", token(UUID.randomUUID(), "member", SIGNING_KEY, AUDIENCE, false), null, null);
        assertProblem(expired, 401, "UNAUTHENTICATED");

        var noExpiration = send(
                "GET", "/api/v1/projects", tokenWithoutExpiration(UUID.randomUUID()), null, null);
        assertProblem(noExpiration, 401, "UNAUTHENTICATED");

        var valid = send("GET", "/api/v1/projects", token(UUID.randomUUID()), null, null);
        assertThat(valid.statusCode()).isEqualTo(200);

        var proposedRun = send(
                "GET", "/api/v1/runs/" + UUID.randomUUID(), token(UUID.randomUUID()), null, null);
        assertProblem(proposedRun, 404, "NOT_FOUND");
    }

    @Test
    void projectCreateReadListUniquenessIdempotencyAndTenantConcealment() throws Exception {
        UUID organizationA = UUID.randomUUID();
        UUID organizationB = UUID.randomUUID();
        String tokenA = token(organizationA);
        String tokenB = token(organizationB);
        String name = "Payments " + UUID.randomUUID();
        String key = key();
        String body = json("name", name);

        var created = send("POST", "/api/v1/projects", tokenA, key, body);
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode project = json(created);
        String projectId = project.get("projectId").stringValue();
        assertThat(project.get("name").stringValue()).isEqualTo(name);
        assertThat(project.get("createdBy").stringValue()).isEqualTo("member");
        assertThat(project.has("organizationId")).isFalse();
        assertThat(created.headers().firstValue("Location")).contains("/api/v1/projects/" + projectId);

        var replay = send("POST", "/api/v1/projects", tokenA, key, body);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(json(replay).get("projectId").stringValue()).isEqualTo(projectId);
        assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");

        assertProblem(send("POST", "/api/v1/projects", tokenA, key, json("name", name + " changed")), 409, "IDEMPOTENCY_CONFLICT");
        assertProblem(send("POST", "/api/v1/projects", tokenA, key(), body), 409, "PROJECT_NAME_CONFLICT");
        assertProblem(send("POST", "/api/v1/projects", tokenA, key(), json("name", "   ")), 422, "VALIDATION_FAILED");
        assertProblem(send("POST", "/api/v1/projects", tokenA, key(), "{\"name\":\"ok\",\"organizationId\":\"" + organizationB + "\"}"), 400, "VALIDATION_FAILED");

        assertThat(send("GET", "/api/v1/projects/" + projectId, tokenA, null, null).statusCode()).isEqualTo(200);
        assertThat(json(send("GET", "/api/v1/projects", tokenA, null, null)).get("items").toString()).contains(projectId);

        var concealed = send("GET", "/api/v1/projects/" + projectId, tokenB, null, null);
        assertProblem(concealed, 404, "NOT_FOUND");
        assertThat(concealed.body()).doesNotContain(organizationA.toString());
        assertThat(json(send("GET", "/api/v1/projects", tokenB, null, null)).get("items").toString()).doesNotContain(projectId);

        var independent = send("POST", "/api/v1/projects", tokenB, key, body);
        assertThat(independent.statusCode()).isEqualTo(201);
        assertThat(json(independent).get("projectId").stringValue()).isNotEqualTo(projectId);
    }

    @Test
    void concurrentProjectIdempotencySerializesSameAndConflictingFirstUse() throws Exception {
        String bearer = token(UUID.randomUUID());
        String sameName = "Concurrent same " + UUID.randomUUID();
        String sameKey = key();
        CyclicBarrier sameBarrier = new CyclicBarrier(2);
        List<HttpResponse<String>> same;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = IntStream.range(0, 2)
                    .mapToObj(ignored -> executor.submit(() -> {
                        sameBarrier.await();
                        return send("POST", "/api/v1/projects", bearer, sameKey, json("name", sameName));
                    }))
                    .toList();
            same = futures.stream().map(future -> {
                        try {
                            return future.get(30, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .toList();
        }
        assertThat(same).allMatch(response -> response.statusCode() == 201);
        assertThat(same.stream().map(response -> {
                    try {
                        return json(response).get("projectId").stringValue();
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                }).distinct().count())
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from projects where name = ?", Integer.class, sameName))
                .isEqualTo(1);

        String firstName = "Concurrent first " + UUID.randomUUID();
        String secondName = "Concurrent second " + UUID.randomUUID();
        String conflictKey = key();
        CyclicBarrier conflictBarrier = new CyclicBarrier(2);
        List<HttpResponse<String>> conflict;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var bodies = List.of(json("name", firstName), json("name", secondName));
            var futures = bodies.stream().map(body -> executor.submit(() -> {
                        conflictBarrier.await();
                        return send("POST", "/api/v1/projects", bearer, conflictKey, body);
                    })).toList();
            conflict = futures.stream().map(future -> {
                        try {
                            return future.get(30, TimeUnit.SECONDS);
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .toList();
        }
        assertThat(conflict.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(201, 409);
        assertThat(conflict.stream().filter(response -> response.statusCode() == 409).findFirst())
                .hasValueSatisfying(response -> {
                    try {
                        assertProblem(response, 409, "IDEMPOTENCY_CONFLICT");
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                });
        assertThat(jdbc.queryForObject(
                        "select count(*) from projects where name in (?, ?)", Integer.class, firstName, secondName))
                .isEqualTo(1);
    }

    @Test
    void featureCreationIsAtomicAndRevisionsAreExactImmutableAndTenantScoped() throws Exception {
        UUID organizationA = UUID.randomUUID();
        UUID organizationB = UUID.randomUUID();
        String tokenA = token(organizationA);
        String tokenB = token(organizationB);
        String projectId = createProject(tokenA);
        String source = "Feature: café\r\n\r\nScenario: exact bytes\r\n* match 'é' == 'é'\r\n";
        String logicalPath = "features/" + UUID.randomUUID() + ".feature";
        String createKey = key();
        String createBody = objectMapper.writeValueAsString(new FeatureRequest("Feature", logicalPath, source));
        var created = send("POST", "/api/v1/projects/" + projectId + "/features", tokenA, createKey, createBody);
        JsonNode value = json(created);
        String featureId = value.at("/feature/featureId").stringValue();
        String revisionOneId = value.at("/initialRevision/revisionId").stringValue();
        assertThat(value.at("/initialRevision/revisionNumber").asLong()).isEqualTo(1);
        assertThat(value.at("/initialRevision/source").stringValue()).isEqualTo(source);
        assertThat(value.at("/initialRevision/sourceDigest").stringValue()).isEqualTo(digest(source));

        String revisionTwoSource = source + "# revision two\n";
        String revisionKey = key();
        var revisionTwo = send(
                "POST",
                featurePath(projectId, featureId) + "/revisions",
                tokenA,
                revisionKey,
                json("source", revisionTwoSource));
        assertThat(revisionTwo.statusCode()).isEqualTo(201);
        assertThat(json(revisionTwo).get("revisionNumber").asLong()).isEqualTo(2);
        var revisionReplay = send(
                "POST",
                featurePath(projectId, featureId) + "/revisions",
                tokenA,
                revisionKey,
                json("source", revisionTwoSource));
        assertThat(json(revisionReplay)).isEqualTo(json(revisionTwo));

        var featureReplay = send(
                "POST", "/api/v1/projects/" + projectId + "/features", tokenA, createKey, createBody);
        assertThat(json(featureReplay)).isEqualTo(value);

        assertThat(json(send("GET", featurePath(projectId, featureId), tokenA, null, null)))
                .isEqualTo(value.get("feature"));
        assertThat(json(send("GET", "/api/v1/projects/" + projectId + "/features", tokenA, null, null))
                        .get("items")
                        .toString())
                .contains(featureId);

        var revisionOne = send(
                "GET", featurePath(projectId, featureId) + "/revisions/" + revisionOneId, tokenA, null, null);
        assertThat(json(revisionOne).get("source").stringValue()).isEqualTo(source);
        assertThat(json(revisionOne).get("sourceDigest").stringValue()).isEqualTo(digest(source));

        var history = send("GET", featurePath(projectId, featureId) + "/revisions", tokenA, null, null);
        assertThat(json(history).get("items").size()).isEqualTo(2);
        assertThat(json(history).at("/items/0/revisionNumber").asLong()).isEqualTo(2);
        assertThat(json(history).at("/items/0").has("source")).isFalse();

        assertProblem(send("GET", featurePath(projectId, featureId), tokenB, null, null), 404, "NOT_FOUND");
        assertProblem(send("GET", "/api/v1/projects/" + projectId + "/features", tokenB, null, null), 404, "NOT_FOUND");
        assertProblem(send("GET", featurePath(projectId, featureId) + "/revisions", tokenB, null, null), 404, "NOT_FOUND");
        assertProblem(
                send("GET", featurePath(projectId, featureId) + "/revisions/" + revisionOneId, tokenB, null, null),
                404,
                "NOT_FOUND");
        assertProblem(
                send("POST", featurePath(projectId, featureId) + "/revisions", tokenB, key(), json("source", "x")),
                404,
                "NOT_FOUND");
        assertProblem(
                send("POST", "/api/v1/projects/" + projectId + "/features", tokenB, key(), createBody),
                404,
                "NOT_FOUND");

        String projectB = createProject(tokenB);
        JsonNode featureB = json(createFeature(
                tokenB, projectB, "foreign/" + UUID.randomUUID() + ".feature", "Feature: foreign\n"));
        String featureBId = featureB.at("/feature/featureId").stringValue();
        assertProblem(send("GET", featurePath(projectId, featureBId), tokenA, null, null), 404, "NOT_FOUND");
        assertProblem(send("GET", featurePath(projectB, featureId), tokenA, null, null), 404, "NOT_FOUND");

        assertProblem(send("POST", "/api/v1/projects/" + projectId + "/features", tokenA, key(), createBody), 409, "FEATURE_PATH_CONFLICT");
        assertProblem(
                send("PUT", featurePath(projectId, featureId) + "/revisions/" + revisionOneId, tokenA, null, json("source", "mutate")),
                405,
                "UNSUPPORTED_OPERATION");

        UUID revisionId = UUID.fromString(revisionOneId);
        assertThatThrownBy(() -> jdbc.update(
                        "update feature_revisions set source = ? where revision_id = ?", "mutated", revisionId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("delete from feature_revisions where revision_id = ?", revisionId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentRevisionAllocationIsUniqueContiguousAndLossless() throws Exception {
        String bearer = token(UUID.randomUUID());
        String projectId = createProject(bearer);
        JsonNode created = json(createFeature(
                bearer, projectId, "concurrency/" + UUID.randomUUID() + ".feature", "Feature: initial\n"));
        String featureId = created.at("/feature/featureId").stringValue();
        int writers = 10;
        CyclicBarrier barrier = new CyclicBarrier(writers);

        List<HttpResponse<String>> responses;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = IntStream.range(0, writers)
                    .mapToObj(index -> executor.submit(() -> {
                        barrier.await();
                        return send(
                                "POST",
                                featurePath(projectId, featureId) + "/revisions",
                                bearer,
                                key(),
                                json("source", "Feature: concurrent " + index + "\n"));
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
        assertThat(responses.stream().map(response -> {
                    try {
                        return json(response).get("revisionNumber").asInt();
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                }))
                .containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(2, writers + 1).boxed().toList());

        for (int index = 0; index < writers; index++) {
            JsonNode createdRevision = json(responses.get(index));
            String expectedSource = "Feature: concurrent " + index + "\n";
            JsonNode stored = json(send(
                    "GET",
                    featurePath(projectId, featureId) + "/revisions/" + createdRevision.get("revisionId").stringValue(),
                    bearer,
                    null,
                    null));
            assertThat(stored.get("source").stringValue()).isEqualTo(expectedSource);
            assertThat(stored.get("sourceDigest").stringValue()).isEqualTo(digest(expectedSource));
        }

        JsonNode history = json(send(
                "GET", featurePath(projectId, featureId) + "/revisions?page=0&size=20", bearer, null, null));
        assertThat(history.get("totalElements").asInt()).isEqualTo(writers + 1);
    }

    @Test
    void sourceByteBoundaryNulAndUtf8DigestPoliciesAreEnforced() throws Exception {
        String bearer = token(UUID.randomUUID());
        String projectId = createProject(bearer);
        String exact = "a".repeat(512 * 1024);
        var accepted = createFeature(bearer, projectId, "limits/" + UUID.randomUUID() + ".feature", exact);
        assertThat(accepted.statusCode()).isEqualTo(201);
        assertThat(json(accepted).at("/initialRevision/sourceDigest").stringValue()).isEqualTo(digest(exact));

        var oversized = createFeature(
                bearer, projectId, "limits/" + UUID.randomUUID() + ".feature", exact + "a");
        assertProblem(oversized, 413, "PAYLOAD_TOO_LARGE");

        var nul = send(
                "POST",
                "/api/v1/projects/" + projectId + "/features",
                bearer,
                key(),
                "{\"name\":\"nul\",\"logicalPath\":\"limits/nul-" + UUID.randomUUID()
                        + ".feature\",\"source\":\"abc\\u0000def\"}");
        assertProblem(nul, 422, "VALIDATION_FAILED");
        assertThat(nul.body()).doesNotContain("abc\\u0000def", "abc\u0000def");
    }

    @Test
    void transportLimitChunkedBodiesMalformedUtf8AndDuplicateKeysReturnSafeProblems() throws Exception {
        String bearer = token(UUID.randomUUID());
        String projectId = createProject(bearer);
        String path = "/api/v1/projects/" + projectId + "/features";
        byte[] huge = objectMapper
                .writeValueAsBytes(new FeatureRequest(
                        "huge", "transport/" + UUID.randomUUID() + ".feature", "a".repeat(1024 * 1024))) ;
        assertProblem(sendBytes(path, bearer, key(), huge, false), 413, "PAYLOAD_TOO_LARGE");
        assertProblem(sendBytes(path, bearer, key(), huge, true), 413, "PAYLOAD_TOO_LARGE");

        byte[] prefix = ("{\"name\":\"bad utf8\",\"logicalPath\":\"transport/" + UUID.randomUUID()
                        + ".feature\",\"source\":\"")
                .getBytes(StandardCharsets.UTF_8);
        byte[] suffix = "\"}".getBytes(StandardCharsets.UTF_8);
        byte[] malformed = new byte[prefix.length + 2 + suffix.length];
        System.arraycopy(prefix, 0, malformed, 0, prefix.length);
        malformed[prefix.length] = (byte) 0xC3;
        malformed[prefix.length + 1] = (byte) 0x28;
        System.arraycopy(suffix, 0, malformed, prefix.length + 2, suffix.length);
        assertProblem(sendBytes(path, bearer, key(), malformed, false), 400, "VALIDATION_FAILED");

        assertProblem(
                send("POST", "/api/v1/projects", bearer, key(), "{\"name\":\"one\",\"name\":\"two\"}"),
                400,
                "VALIDATION_FAILED");
    }

    @Test
    void flywayAndDatabaseConstraintsDefendTenantHierarchyRevisionNumbersAndSourceSize() throws Exception {
        String tokenA = token(UUID.randomUUID());
        String tokenB = token(UUID.randomUUID());
        String projectA = createProject(tokenA);
        String projectB = createProject(tokenB);
        JsonNode created = json(createFeature(
                tokenA, projectA, "database/" + UUID.randomUUID() + ".feature", "Feature: database\n"));
        UUID featureId = UUID.fromString(created.at("/feature/featureId").stringValue());
        UUID organizationB = jdbc.queryForObject(
                "select organization_id from projects where project_id = ?", UUID.class, UUID.fromString(projectB));
        String digest = "0".repeat(64);

        assertThat(jdbc.queryForObject(
                        "select count(*) from flyway_schema_history where success", Integer.class))
                .isPositive();
        assertThatThrownBy(() -> jdbc.update(
                        """
                        insert into features
                          (feature_id, organization_id, project_id, name, logical_path, next_revision_number,
                           version, created_by, created_at)
                        values (?, ?, ?, 'foreign', 'foreign.feature', 2, 0, 'test', now())
                        """,
                        UUID.randomUUID(),
                        organizationB,
                        UUID.fromString(projectA)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update(
                        """
                        insert into feature_revisions
                          (revision_id, organization_id, project_id, feature_id, revision_number, source,
                           source_sha256, created_by, created_at)
                        select ?, organization_id, project_id, feature_id, 1, 'duplicate', ?, 'test', now()
                          from features where feature_id = ?
                        """,
                        UUID.randomUUID(),
                        digest,
                        featureId))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update(
                        """
                        insert into feature_revisions
                          (revision_id, organization_id, project_id, feature_id, revision_number, source,
                           source_sha256, created_by, created_at)
                        select ?, organization_id, project_id, feature_id, 99, ?, ?, 'test', now()
                          from features where feature_id = ?
                        """,
                        UUID.randomUUID(),
                        "a".repeat(512 * 1024 + 1),
                        digest,
                        featureId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private HttpResponse<String> createFeature(String bearer, String projectId, String logicalPath, String source)
            throws Exception {
        String body = objectMapper.writeValueAsString(new FeatureRequest("Feature", logicalPath, source));
        return send("POST", "/api/v1/projects/" + projectId + "/features", bearer, key(), body);
    }

    private String createProject(String bearer) throws Exception {
        var response = send(
                "POST", "/api/v1/projects", bearer, key(), json("name", "Project " + UUID.randomUUID()));
        assertThat(response.statusCode()).isEqualTo(201);
        return json(response).get("projectId").stringValue();
    }

    private HttpResponse<String> send(String method, String path, String bearer, String idempotencyKey, String body)
            throws Exception {
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

    private HttpResponse<String> sendBytes(
            String path, String bearer, String idempotencyKey, byte[] body, boolean unknownLength) throws Exception {
        HttpRequest.BodyPublisher publisher = unknownLength
                ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))
                : HttpRequest.BodyPublishers.ofByteArray(body);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + bearer)
                .header("Idempotency-Key", idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(publisher)
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/problem+json");
        JsonNode problem = json(response);
        assertThat(problem.get("status").asInt()).isEqualTo(status);
        assertThat(problem.get("code").stringValue()).isEqualTo(code);
        assertThat(problem.hasNonNull("requestId")).isTrue();
        assertThat(response.body()).doesNotContain("org.postgresql", "constraint", "Bearer ", "SELECT ", "INSERT ");
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
    }

    private String json(String field, String value) throws Exception {
        return objectMapper.writeValueAsString(java.util.Map.of(field, value));
    }

    private static String featurePath(String projectId, String featureId) {
        return "/api/v1/projects/" + projectId + "/features/" + featureId;
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    private static String digest(String source) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(source.getBytes(StandardCharsets.UTF_8)));
    }

    private static String token(UUID organizationId) throws Exception {
        return token(organizationId, "member", SIGNING_KEY, AUDIENCE, true);
    }

    private static String token(
            UUID organizationId, String subject, KeyPair keyPair, String audience, boolean validLifetime) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(subject)
                .audience(audience)
                .issueTime(Date.from(now.minusSeconds(5)))
                .notBeforeTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(validLifetime ? now.plusSeconds(300) : now.minusSeconds(300)));
        if (organizationId != null) {
            claims.claim("org_id", organizationId.toString());
        }
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner((RSAPrivateKey) keyPair.getPrivate()));
        return jwt.serialize();
    }

    private static String tokenWithOrganizationClaim(Object organizationClaim, String subject) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(subject)
                .audience(AUDIENCE)
                .issueTime(Date.from(now.minusSeconds(5)))
                .notBeforeTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("org_id", organizationClaim)
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) SIGNING_KEY.getPrivate()));
        return jwt.serialize();
    }

    private static String tokenWithoutExpiration(UUID organizationId) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("member")
                .audience(AUDIENCE)
                .issueTime(Date.from(now.minusSeconds(5)))
                .notBeforeTime(Date.from(now.minusSeconds(5)))
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

    private record FeatureRequest(String name, String logicalPath, String source) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class JwtTestConfiguration {
        @Bean
        @Primary
        NimbusJwtDecoder jwtDecoder() {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) SIGNING_KEY.getPublic()).build();
            var audience = new JwtClaimValidator<List<String>>("aud", values -> values != null && values.contains(AUDIENCE));
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(ISSUER), audience));
            return decoder;
        }
    }
}
