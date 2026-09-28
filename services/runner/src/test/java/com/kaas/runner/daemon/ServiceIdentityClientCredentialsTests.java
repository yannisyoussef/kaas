package com.kaas.runner.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Client credentials against a token endpoint shaped like the platform issuer's. */
class ServiceIdentityClientCredentialsTests {
    private static final String WORKER = "kaas.worker.cc";

    @TempDir
    Path directory;

    private HttpServer issuer;
    private final List<Map<String, String>> forms = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private volatile String subject = WORKER;

    @AfterEach
    void stop() {
        if (issuer != null) {
            issuer.stop(0);
        }
    }

    @Test
    void obtainsAShortLivedTokenWithTheSecretFromItsFileAndRereadsTheFileOnEveryRefresh() throws Exception {
        Path secret = Files.writeString(directory.resolve("secret"), "first-secret\n");
        ServiceIdentity identity = identity(secret);

        String header = identity.header();
        assertThat(header).startsWith("Bearer ");
        assertThat(forms).hasSize(1);
        assertThat(forms.get(0)).containsEntry("grant_type", "client_credentials")
                .containsEntry("client_id", "kaas-runner")
                .containsEntry("client_secret", "first-secret")
                .containsEntry("audience", "kaas-api");

        // Rotated on disk; the next token request uses the new secret without a restart.
        Files.writeString(secret, "second-secret");
        ServiceIdentity.sourceFor(credentials(secret), HttpClient.newHttpClient(), JsonMapper.builder().build(),
                Duration.ofSeconds(5)).fetch();
        assertThat(forms.get(1)).containsEntry("client_secret", "second-secret");
        // And the identity itself never prints it.
        assertThat(identity.toString()).doesNotContain("secret-").doesNotContain(header.substring(7));
    }

    @Test
    void aRefusedOrUnreachableIssuerIsNotReadyNeverUnauthenticated() throws Exception {
        Path secret = Files.writeString(directory.resolve("secret"), "s");
        status.set(401);
        ServiceIdentity refused = identity(secret);
        assertThat(refused.available()).isFalse();
        assertThat(refused.lastFailure()).isEqualTo("TOKEN_REFUSED");

        issuer.stop(0);
        issuer = null;
        ServiceIdentity unreachable = new ServiceIdentity(WORKER, ServiceIdentity.sourceFor(
                new RunnerConfiguration.ClientCredentials(URI.create("http://127.0.0.1:9/token"), "kaas-runner", secret,
                        Optional.empty(), Optional.empty()),
                HttpClient.newHttpClient(), JsonMapper.builder().build(), Duration.ofSeconds(2)),
                Clock.systemUTC(), new RunnerMetrics(), "runner");
        assertThat(unreachable.available()).isFalse();
        assertThat(unreachable.lastFailure()).isEqualTo("TOKEN_ENDPOINT_UNREACHABLE");
    }

    @Test
    void aTokenIssuedForAnotherSubjectIsNeverPresented() throws Exception {
        Path secret = Files.writeString(directory.resolve("secret"), "s");
        subject = "kaas.worker.someone-else";
        ServiceIdentity identity = identity(secret);
        assertThat(identity.available()).isFalse();
        assertThat(identity.lastFailure()).isEqualTo("SUBJECT_MISMATCH");
    }

    @Test
    void aMissingSecretFileIsAFailureNotAnEmptySecret() throws Exception {
        start();
        ServiceIdentity identity = new ServiceIdentity(WORKER, ServiceIdentity.sourceFor(
                credentials(directory.resolve("absent")), HttpClient.newHttpClient(), JsonMapper.builder().build(),
                Duration.ofSeconds(5)), Clock.systemUTC(), new RunnerMetrics(), "runner");
        assertThat(identity.available()).isFalse();
        assertThat(identity.lastFailure()).isEqualTo("CLIENT_SECRET_UNREADABLE");
        assertThat(forms).isEmpty();
    }

    private ServiceIdentity identity(Path secret) throws Exception {
        if (issuer == null) {
            start();
        }
        return new ServiceIdentity(WORKER, ServiceIdentity.sourceFor(credentials(secret), HttpClient.newHttpClient(),
                JsonMapper.builder().build(), Duration.ofSeconds(5)), Clock.systemUTC(), new RunnerMetrics(), "runner");
    }

    private RunnerConfiguration.ClientCredentials credentials(Path secret) {
        return new RunnerConfiguration.ClientCredentials(
                URI.create("http://127.0.0.1:" + issuer.getAddress().getPort() + "/oauth/token"), "kaas-runner", secret,
                Optional.of("kaas-api"), Optional.empty());
    }

    private void start() throws Exception {
        issuer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        issuer.createContext("/oauth/token", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            forms.add(java.util.Arrays.stream(body.split("&")).map(pair -> pair.split("=", 2))
                    .collect(Collectors.toMap(pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                            pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8))));
            byte[] response = ("{\"access_token\":\"" + FakeControlPlane.token(subject, Instant.now().plusSeconds(300))
                    + "\",\"token_type\":\"Bearer\",\"expires_in\":300}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        issuer.start();
    }
}
