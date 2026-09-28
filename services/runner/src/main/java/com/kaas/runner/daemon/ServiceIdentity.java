package com.kaas.runner.daemon;

import com.kaas.runner.client.ControlPlaneClient;
import com.kaas.runner.client.ControlPlaneUnavailable;
import java.io.IOException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A short-lived, refreshable service credential for one platform identity (KAAS-DEPLOY-001).
 *
 * <h2>What replaced what</h2>
 *
 * <p>The runner used to be handed one bearer string at construction and hold it for its whole life. A
 * long-lived runner therefore needed a long-lived token -- exactly the credential nobody should issue. This holds
 * a token that expires, obtains the next one before it does, and reports honestly when it cannot.
 *
 * <p>It is the SAME authentication the control plane already enforces: a JWT from the platform's existing issuer,
 * verified by the internal chain, subject in the {@code kaas.} namespace. No new identity provider exists; this
 * only changes how often the runner asks the one that does.
 *
 * <h2>Where the credential goes, and where it never goes</h2>
 *
 * <p>Into an {@code Authorization} header on a request to the control plane, and nowhere else. Never a sandbox
 * (the launcher builds sandbox environments from nothing), never a log (every {@code toString} is redacted),
 * never the database or a queue (the runner has neither). The proxy's own identity is a separate instance of this
 * class for a separate subject, so the proxy never holds the runner's credential and the reverse.
 *
 * <h2>Failure</h2>
 *
 * <p>If a token cannot be obtained, {@link #header()} fails and the runner becomes NOT READY: it claims nothing
 * new. There is no unauthenticated fallback and no stale-token fallback past expiry. The process stays alive and
 * keeps trying, because a token endpoint outage is something to recover from, not a reason to be restarted.
 */
public final class ServiceIdentity implements ControlPlaneClient.Authorization {
    /** A token this close to expiry is treated as expired: requests in flight must not carry one that lapses. */
    static final Duration EXPIRY_SKEW = Duration.ofSeconds(30);

    /** Refresh once this fraction of a token's life has passed. */
    private static final double REFRESH_AT = 0.7;

    private final String expectedSubject;
    private final TokenSource source;
    private final Clock clock;
    private final RunnerMetrics metrics;
    private final String metricIdentity;

    private Token current;
    private Instant refreshAt = Instant.MIN;
    private String lastFailure = "NOT_YET_OBTAINED";

    /**
     * @param expectedSubject the identity the token must carry. A token for anybody else is refused: a runner
     *     configured as {@code kaas.worker.a} must never present itself as {@code kaas.worker.b}, whatever its
     *     issuer handed it.
     * @param metricIdentity a closed label -- {@code runner} or {@code egress_proxy} -- never the subject
     */
    public ServiceIdentity(String expectedSubject, TokenSource source, Clock clock, RunnerMetrics metrics,
            String metricIdentity) {
        this.expectedSubject = Objects.requireNonNull(expectedSubject);
        this.source = Objects.requireNonNull(source);
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
        this.metricIdentity = Objects.requireNonNull(metricIdentity);
    }

    /**
     * The header value for the next request, refreshing first if the held token is due.
     *
     * @throws ControlPlaneUnavailable when no currently valid token can be had; the request is not sent
     */
    @Override
    public synchronized String header() throws ControlPlaneUnavailable {
        Instant now = clock.instant();
        if (current == null || !now.isBefore(refreshAt)) {
            refresh(now);
        }
        if (current == null || !now.isBefore(current.expiresAt().minus(EXPIRY_SKEW))) {
            throw new ControlPlaneUnavailable("No valid service credential: " + lastFailure, null);
        }
        return "Bearer " + current.value();
    }

    /** Whether a request made now would carry a valid credential. For readiness; attempts a due refresh. */
    public synchronized boolean available() {
        try {
            header();
            return true;
        } catch (ControlPlaneUnavailable unavailable) {
            return false;
        }
    }

    /** A closed reason code for the last failure, or {@code NONE}. Never the token or a URL. */
    public synchronized String lastFailure() {
        return lastFailure;
    }

    /** When the held token expires, for metrics; {@link Instant#EPOCH} if none. */
    public synchronized Instant expiresAt() {
        return current == null ? Instant.EPOCH : current.expiresAt();
    }

    private void refresh(Instant now) {
        Token next;
        try {
            next = source.fetch();
        } catch (ServiceIdentityUnavailable failed) {
            lastFailure = failed.reason();
            metrics.serviceAuthRefreshFailed(metricIdentity);
            // Keep the old token while it is still valid: a refresh that fails early is not an outage yet.
            // Try again soon rather than on every request.
            refreshAt = now.plusSeconds(5);
            return;
        }
        if (!expectedSubject.equals(next.subject())) {
            lastFailure = "SUBJECT_MISMATCH";
            metrics.serviceAuthRefreshFailed(metricIdentity);
            refreshAt = now.plusSeconds(5);
            return;
        }
        if (!now.isBefore(next.expiresAt().minus(EXPIRY_SKEW))) {
            lastFailure = "ISSUED_EXPIRED";
            metrics.serviceAuthRefreshFailed(metricIdentity);
            refreshAt = now.plusSeconds(5);
            return;
        }
        current = next;
        lastFailure = "NONE";
        long lifetime = Math.max(1, Duration.between(now, next.expiresAt()).toMillis());
        refreshAt = now.plusMillis((long) (lifetime * REFRESH_AT));
        metrics.serviceAuthRefreshed(metricIdentity);
    }

    @Override
    public String toString() {
        return "ServiceIdentity[" + expectedSubject + ", credential=<redacted>]";
    }

    // ---------------------------------------------------------------- tokens

    /** A bearer token and what the runner reads from it. The value never reaches {@link #toString()}. */
    public record Token(String value, String subject, Instant expiresAt) {
        public Token {
            Objects.requireNonNull(value);
            Objects.requireNonNull(subject);
            Objects.requireNonNull(expiresAt);
        }

        @Override
        public String toString() {
            return "Token[subject=" + subject + ", expiresAt=" + expiresAt + ", value=<redacted>]";
        }

        /**
         * Reads {@code sub} and {@code exp} from a JWT, WITHOUT verifying it.
         *
         * <p>Not verification and not trusted as such: the control plane verifies every token it receives. The
         * runner reads these two claims only to know whose token it holds and when to replace it, and a token
         * that lies about either is refused by the control plane regardless.
         */
        static Token fromJwt(String jwt, ObjectMapper mapper) throws ServiceIdentityUnavailable {
            String[] parts = jwt == null ? new String[0] : jwt.trim().split("\\.");
            if (parts.length != 3) {
                throw new ServiceIdentityUnavailable("MALFORMED_TOKEN");
            }
            try {
                JsonNode claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
                JsonNode sub = claims.get("sub");
                JsonNode exp = claims.get("exp");
                if (sub == null || !sub.isString() || exp == null || !exp.isNumber()) {
                    throw new ServiceIdentityUnavailable("MALFORMED_TOKEN");
                }
                return new Token(jwt.trim(), sub.stringValue(), Instant.ofEpochSecond(exp.longValue()));
            } catch (IllegalArgumentException | tools.jackson.core.JacksonException malformed) {
                throw new ServiceIdentityUnavailable("MALFORMED_TOKEN");
            }
        }
    }

    /** Where tokens come from. */
    @FunctionalInterface
    public interface TokenSource {
        Token fetch() throws ServiceIdentityUnavailable;
    }

    /** A closed failure reason; never a message that could carry a token, secret or URL. */
    public static final class ServiceIdentityUnavailable extends Exception {
        private final String reason;

        public ServiceIdentityUnavailable(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /** The token source a configuration names. */
    static TokenSource sourceFor(RunnerConfiguration.Credentials credentials, HttpClient http, ObjectMapper mapper,
            Duration timeout) {
        return switch (credentials) {
            case RunnerConfiguration.ClientCredentials client -> clientCredentials(client, http, mapper, timeout);
            case RunnerConfiguration.TokenFile file -> tokenFile(file.path(), mapper);
        };
    }

    /**
     * A token a host agent keeps in a file. Re-read on every refresh, so a rotated file is picked up without a
     * restart.
     */
    static TokenSource tokenFile(Path path, ObjectMapper mapper) {
        return () -> {
            String text;
            try {
                if (Files.size(path) > 16 * 1024) {
                    throw new ServiceIdentityUnavailable("TOKEN_FILE_TOO_LARGE");
                }
                text = Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException unreadable) {
                throw new ServiceIdentityUnavailable("TOKEN_FILE_UNREADABLE");
            }
            return Token.fromJwt(text, mapper);
        };
    }

    /**
     * OAuth 2.0 client credentials (RFC 6749 §4.4) against the existing issuer.
     *
     * <p>The client secret is read from its file on every request, never cached in this object, so rotating the
     * file rotates the secret; and it travels in the form body over https (or loopback) only -- the configuration
     * refuses anything else before this is ever built.
     */
    static TokenSource clientCredentials(RunnerConfiguration.ClientCredentials client, HttpClient http,
            ObjectMapper mapper, Duration timeout) {
        return () -> {
            String secret;
            try {
                secret = Files.readString(client.clientSecretFile(), StandardCharsets.UTF_8).strip();
            } catch (IOException unreadable) {
                throw new ServiceIdentityUnavailable("CLIENT_SECRET_UNREADABLE");
            }
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "client_credentials");
            form.put("client_id", client.clientId());
            form.put("client_secret", secret);
            client.audience().ifPresent(audience -> form.put("audience", audience));
            client.scope().ifPresent(scope -> form.put("scope", scope));
            String body = form.entrySet().stream()
                    .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "="
                            + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8))
                    .collect(Collectors.joining("&"));
            HttpResponse<String> response;
            try {
                response = http.send(
                        HttpRequest.newBuilder(client.tokenEndpoint())
                                .timeout(timeout)
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .header("Accept", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                                .build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException unreachable) {
                throw new ServiceIdentityUnavailable("TOKEN_ENDPOINT_UNREACHABLE");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ServiceIdentityUnavailable("INTERRUPTED");
            }
            if (response.statusCode() == 400 || response.statusCode() == 401 || response.statusCode() == 403) {
                throw new ServiceIdentityUnavailable("TOKEN_REFUSED");
            }
            if (response.statusCode() != 200) {
                throw new ServiceIdentityUnavailable("TOKEN_ENDPOINT_ERROR");
            }
            JsonNode token;
            try {
                token = mapper.readTree(response.body()).get("access_token");
            } catch (tools.jackson.core.JacksonException malformed) {
                throw new ServiceIdentityUnavailable("MALFORMED_TOKEN_RESPONSE");
            }
            if (token == null || !token.isString()) {
                throw new ServiceIdentityUnavailable("MALFORMED_TOKEN_RESPONSE");
            }
            return Token.fromJwt(token.stringValue(), mapper);
        };
    }
}
