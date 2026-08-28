package com.kaas.api;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The only suite that runs with the production timers switched on. Every other suite disables them so it can
 * assert exact state, which means nothing else exercises the interval properties, {@code @EnableScheduling}, the
 * {@code matchIfMissing} defaults on both trigger conditionals, or the fully automatic path.
 *
 * <p>Here a run is created over HTTP and nothing else is called: the scheduler and the relay must move it to the
 * broker on their own.
 */
@Testcontainers
@Import(AutomaticDispatchIntegrationTests.JwtTestConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            // Timers left at their production defaults, only shortened so the test does not wait needlessly.
            "kaas.scheduling.auto.interval=PT0.2S",
            "kaas.scheduling.auto.initial-delay=PT0.2S",
            "kaas.outbox.relay.interval=PT0.2S",
            "kaas.outbox.relay.initial-delay=PT0.2S",
            "kaas.outbox.relay.batch-size=5",
            "kaas.outbox.relay.claim-ttl=PT1M"
        })
class AutomaticDispatchIntegrationTests {
    private static final String ISSUER = "https://issuer.kaas.test";
    private static final String AUDIENCE = "kaas-api";
    private static final KeyPair SIGNING_KEY = keyPair();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16.10-alpine").withDatabaseName("kaas-automatic");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    private int port;

    @Value("${kaas.outbox.rabbit.queue}")
    private String queue;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Test
    @Timeout(120)
    void aCreatedRunReachesTheBrokerWithNoManualStepAtAll() throws Exception {
        UUID organizationId = UUID.randomUUID();
        String bearer = token(organizationId);
        var created = createRun(bearer);
        UUID runId = UUID.fromString(json(created).get("runId").stringValue());
        assertThat(json(created).get("lifecycleState").stringValue()).isEqualTo("CREATED");

        // Nothing below invokes the scheduler or the relay. Both timers must do it.
        JsonNode queued = awaitQueued(runId, bearer);
        assertThat(queued.get("runVersion").asInt()).isEqualTo(2);
        assertThat(queued.get("queueStartedAt").isNull()).isFalse();
        assertThat(queued.get("queueDeadlineAt").isNull()).isFalse();

        Message delivered = rabbitTemplate.receive(queue, 30_000);
        assertThat(delivered).isNotNull();
        JsonNode dispatch = objectMapper.readTree(new String(delivered.getBody(), StandardCharsets.UTF_8));
        assertThat(dispatch.get("runId").stringValue()).isEqualTo(runId.toString());
        assertThat(dispatch.get("messageType").stringValue()).isEqualTo("EXECUTION_DISPATCH");

        // The relay marks it published only after the broker confirms, so this is true once the message exists.
        awaitPublished(runId, bearer);
    }

    private JsonNode awaitQueued(UUID runId, String bearer) throws Exception {
        for (int attempt = 0; attempt < 300; attempt++) {
            JsonNode run = json(get("/api/v1/runs/" + runId, bearer));
            if ("QUEUED".equals(run.get("lifecycleState").stringValue())) {
                return run;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the scheduler never moved the run to QUEUED");
    }

    private void awaitPublished(UUID runId, String bearer) throws Exception {
        for (int attempt = 0; attempt < 300; attempt++) {
            var etag = get("/api/v1/runs/" + runId, bearer).headers().firstValue("ETag");
            if (etag.filter(value -> value.contains("run-2")).isPresent()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the run never reached its published representation");
    }

    private HttpResponse<String> createRun(String bearer) throws Exception {
        String projectId = json(post(
                        "/api/v1/projects", bearer, key(), json(Map.of("name", "Project " + UUID.randomUUID()))))
                .get("projectId")
                .stringValue();
        String featureRevision = json(post(
                        "/api/v1/projects/" + projectId + "/features",
                        bearer,
                        key(),
                        json(Map.of(
                                "name", "Automatic feature",
                                "logicalPath", "features/auto-" + UUID.randomUUID() + ".feature",
                                "source", "Feature: auto\nScenario: one\n* match 1 == 1\n"))))
                .at("/initialRevision/revisionId")
                .stringValue();
        String environmentRevision = json(post(
                        "/api/v1/projects/" + projectId + "/environments",
                        bearer,
                        key(),
                        json(Map.of(
                                "name", "Automatic environment",
                                "variables",
                                        List.of(Map.of(
                                                "key", "baseUrl", "type", "STRING",
                                                "value", "https://environment.example")),
                                "secretBindings", List.of()))))
                .at("/initialRevision/revisionId")
                .stringValue();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("name", "Automatic profile");
        profile.put("environmentRevisionId", environmentRevision);
        profile.put("selection", Map.of("tags", List.of("@smoke")));
        profile.put("parallelism", 1);
        profile.put("scenarioRetry", Map.of("maxAttempts", 1, "delayMilliseconds", 0));
        profile.put("executionTimeoutSeconds", 60);
        profile.put(
                "artifactPolicy",
                Map.of("types", List.of("RAW_RESULT"), "maxArtifactBytes", 1_000, "maxTotalBytes", 2_000));
        profile.put("configurationOverrides", List.of());
        String profileRevision = json(post(
                        "/api/v1/projects/" + projectId + "/run-profiles", bearer, key(), json(profile)))
                .at("/initialRevision/revisionId")
                .stringValue();

        var response = post(
                "/api/v1/projects/" + projectId + "/runs",
                bearer,
                key(),
                json(Map.of(
                        "featureRevisionIds", List.of(featureRevision),
                        "runProfileRevisionId", profileRevision)));
        assertThat(response.statusCode()).isEqualTo(202);
        return response;
    }

    private HttpResponse<String> post(String path, String bearer, String idempotencyKey, String body)
            throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Accept", "application/json")
                        .header("Authorization", "Bearer " + bearer)
                        .header("Idempotency-Key", idempotencyKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Accept", "application/json")
                        .header("Authorization", "Bearer " + bearer)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
    }

    private static String key() {
        return "key-" + UUID.randomUUID();
    }

    private static String token(UUID organizationId) throws Exception {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("automatic-test")
                .audience(AUDIENCE)
                .issueTime(Date.from(now.minusSeconds(5)))
                .notBeforeTime(Date.from(now.minusSeconds(5)))
                .expirationTime(Date.from(now.plusSeconds(900)))
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
