package com.kaas.runner.daemon;

import com.kaas.runner.sandbox.ExecutionRuntimeType;
import com.kaas.runner.sandbox.SandboxSecurityProfile;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Everything a production runner is told by its host, validated before anything starts (KAAS-DEPLOY-001).
 *
 * <p>Read from the environment once, at startup, and never again. Every problem is reported together -- an
 * operator fixing a deployment should not discover the fourth mistake on the fourth restart -- and the report
 * names variables, never their values: a misplaced secret must not be echoed into a deployment log by the code
 * that noticed it was misplaced.
 *
 * <p>What is deliberately NOT here: anything about RabbitMQ. The execution host reaches the control plane's
 * internal API and nothing else (ADR-035); a runner that needed a broker address, credential or TLS setting
 * would be a runner that widened the execution host's network boundary.
 *
 * <p>No topology is hard-coded. There is no default API address, no default image, no default key id: each is a
 * fact about somebody's deployment, and a guessed default is a way to run against the wrong one.
 */
public record RunnerConfiguration(
        String workerId,
        URI apiBaseUri,
        Credentials credentials,
        String dockerHost,
        ExecutionRuntimeType sandboxRuntime,
        Optional<Path> runscPath,
        String attestationKeyId,
        Path attestationKeyFile,
        String runtimeSubject,
        String probeImage,
        String engineImage,
        Optional<Egress> egress,
        int maxConcurrency,
        Duration claimWait,
        Duration claimBackoffInitial,
        Duration claimBackoffMax,
        Duration requestTimeout,
        Duration reconcileInterval,
        Duration sandboxWallClockTimeout,
        Duration attestationRefreshInterval,
        Duration attestationRetryInterval,
        Duration attestationMaxAge,
        Duration shutdownTimeout,
        String healthHost,
        int healthPort) {

    /** The worker namespace the control plane requires of a claiming subject. */
    static final Pattern WORKER = Pattern.compile("^kaas\\.worker\\.[A-Za-z0-9._-]{1,200}$");

    /** The control plane holds a wait at most this long; asking for more is a configuration mistake. */
    static final Duration MAX_CLAIM_WAIT = Duration.ofSeconds(20);

    /** Where Operations places the attestation signing key. A default path, not a default key. */
    static final Path DEFAULT_ATTESTATION_KEY = Path.of("/run/kaas/attestation.key");

    /** How the runner obtains its short-lived service credential. Exactly one source. */
    public sealed interface Credentials permits ClientCredentials, TokenFile {}

    /**
     * OAuth 2.0 client credentials against the platform's EXISTING issuer -- the same issuer whose tokens the
     * control plane's internal chain already verifies. No new identity provider.
     */
    public record ClientCredentials(URI tokenEndpoint, String clientId, Path clientSecretFile,
            Optional<String> audience, Optional<String> scope) implements Credentials {
        @Override
        public String toString() {
            return "ClientCredentials[tokenEndpoint=" + tokenEndpoint + ", clientId=" + clientId + "]";
        }
    }

    /** A token file an agent on the host keeps fresh. Re-read whenever the held token nears expiry. */
    public record TokenFile(Path path) implements Credentials {}

    /**
     * Allowlist egress, present only when this runner may run {@code ALLOWLIST} executions.
     *
     * @param proxyImage the digest-pinned egress proxy image
     * @param proxyControlPlaneUri the control plane as the PROXY reaches it, from the egress network
     * @param proxyCredentials the proxy's own service identity ({@code kaas.egress-proxy}), never the runner's
     */
    public record Egress(String proxyImage, URI proxyControlPlaneUri, Credentials proxyCredentials,
            String dnsServer, List<String> egressNetworkIds) {}

    /** Every problem at once, by variable name. */
    public static final class Invalid extends RuntimeException {
        private final List<String> problems;

        Invalid(List<String> problems) {
            super("Runner configuration invalid: " + String.join("; ", problems), null, false, false);
            this.problems = List.copyOf(problems);
        }

        public List<String> problems() {
            return problems;
        }
    }

    /** Reads and validates. Throws {@link Invalid} naming every problem found. */
    public static RunnerConfiguration fromEnvironment(Map<String, String> environment) {
        Reader reader = new Reader(environment);
        String workerId = reader.required("KAAS_RUNNER_WORKER_ID");
        if (workerId != null && !WORKER.matcher(workerId).matches()) {
            reader.problem("KAAS_RUNNER_WORKER_ID must be kaas.worker.<name>");
        }
        URI api = reader.uri("KAAS_RUNNER_API_URL", true);
        if (api != null && !carriesCredentialsSafely(api)) {
            // Secret bundles cross this connection. The client refuses them over anything else, so a runner
            // configured this way could never run a secret-bearing execution; fail at startup instead.
            reader.problem("KAAS_RUNNER_API_URL must be https, or http to this host's loopback");
        }
        Credentials credentials = reader.credentials("KAAS_RUNNER");

        ExecutionRuntimeType runtime = switch (reader.optional("KAAS_RUNNER_SANDBOX_RUNTIME", "gvisor")
                .toLowerCase(Locale.ROOT)) {
            // Chosen from a closed set by name. Production is gvisor; "docker" exists for development hosts and
            // is never reached by falling back -- a runner configured for gvisor on a host without runsc is NOT
            // READY, it does not quietly run under runc.
            case "gvisor" -> ExecutionRuntimeType.GVISOR;
            case "docker" -> ExecutionRuntimeType.DOCKER;
            default -> {
                reader.problem("KAAS_RUNNER_SANDBOX_RUNTIME must be gvisor or docker");
                yield null;
            }
        };
        Optional<Path> runscPath = reader.optionalPath("KAAS_RUNNER_RUNSC_PATH");
        if (runscPath.isPresent() && !runscPath.orElseThrow().isAbsolute()) {
            reader.problem("KAAS_RUNNER_RUNSC_PATH must be absolute");
        }

        String keyId = reader.required("KAAS_RUNNER_ATTESTATION_KEY_ID");
        Path keyFile = reader.optionalPath("KAAS_RUNNER_ATTESTATION_KEY_FILE").orElse(DEFAULT_ATTESTATION_KEY);
        String subject = reader.required("KAAS_RUNNER_RUNTIME_SUBJECT");

        String probe = reader.image("KAAS_RUNNER_PROBE_IMAGE");
        String engine = reader.image("KAAS_RUNNER_ENGINE_IMAGE");

        Optional<Egress> egress = Optional.empty();
        if (reader.flag("KAAS_RUNNER_EGRESS_ALLOWLIST_ENABLED")) {
            String proxy = reader.image("KAAS_RUNNER_EGRESS_PROXY_IMAGE");
            URI proxyControlPlane = reader.uri("KAAS_RUNNER_EGRESS_CONTROL_PLANE_URL", true);
            Credentials proxyCredentials = reader.credentials("KAAS_RUNNER_EGRESS");
            String dns = reader.required("KAAS_RUNNER_EGRESS_DNS_SERVER");
            List<String> networks = reader.list("KAAS_RUNNER_EGRESS_NETWORKS");
            if (networks.isEmpty()) {
                reader.problem("KAAS_RUNNER_EGRESS_NETWORKS must name at least one network");
            }
            egress = Optional.of(new Egress(proxy, proxyControlPlane, proxyCredentials, dns, networks));
        }

        int concurrency = reader.integer("KAAS_RUNNER_MAX_CONCURRENCY", 1);
        if (concurrency < 1 || concurrency > 64) {
            reader.problem("KAAS_RUNNER_MAX_CONCURRENCY must be between 1 and 64");
        }
        Duration claimWait = reader.duration("KAAS_RUNNER_CLAIM_WAIT", Duration.ofSeconds(15));
        if (claimWait != null && (claimWait.isNegative() || claimWait.compareTo(MAX_CLAIM_WAIT) > 0)) {
            reader.problem("KAAS_RUNNER_CLAIM_WAIT must be between PT0S and " + MAX_CLAIM_WAIT);
        }
        Duration backoffInitial = reader.positive("KAAS_RUNNER_CLAIM_BACKOFF_INITIAL", Duration.ofSeconds(1));
        Duration backoffMax = reader.positive("KAAS_RUNNER_CLAIM_BACKOFF_MAX", Duration.ofSeconds(30));
        if (backoffInitial != null && backoffMax != null && backoffInitial.compareTo(backoffMax) > 0) {
            reader.problem("KAAS_RUNNER_CLAIM_BACKOFF_INITIAL must not exceed KAAS_RUNNER_CLAIM_BACKOFF_MAX");
        }
        Duration requestTimeout = reader.positive("KAAS_RUNNER_REQUEST_TIMEOUT", Duration.ofSeconds(30));
        Duration reconcile = reader.positive("KAAS_RUNNER_RECONCILE_INTERVAL", Duration.ofMinutes(5));
        Duration wallClock = reader.positive("KAAS_RUNNER_SANDBOX_WALL_CLOCK_TIMEOUT", Duration.ofHours(1));
        Duration refresh = reader.positive("KAAS_RUNNER_ATTESTATION_REFRESH_INTERVAL", Duration.ofHours(20));
        Duration retry = reader.positive("KAAS_RUNNER_ATTESTATION_RETRY_INTERVAL", Duration.ofMinutes(10));
        Duration maxAge = reader.positive("KAAS_RUNNER_ATTESTATION_MAX_AGE", Duration.ofHours(24));
        if (refresh != null && maxAge != null && refresh.compareTo(maxAge) >= 0) {
            // A refresh that is not sooner than expiry is not a refresh: evidence would lapse before it was
            // replaced, every time, and the runner would spend part of every cycle unable to execute.
            reader.problem("KAAS_RUNNER_ATTESTATION_REFRESH_INTERVAL must be shorter than "
                    + "KAAS_RUNNER_ATTESTATION_MAX_AGE");
        }
        if (retry != null && refresh != null && retry.compareTo(refresh) > 0) {
            reader.problem("KAAS_RUNNER_ATTESTATION_RETRY_INTERVAL must not exceed the refresh interval");
        }
        Duration shutdown = reader.positive("KAAS_RUNNER_SHUTDOWN_TIMEOUT", Duration.ofMinutes(2));
        String healthHost = reader.optional("KAAS_RUNNER_HEALTH_HOST", "127.0.0.1");
        int healthPort = reader.integer("KAAS_RUNNER_HEALTH_PORT", 9090);
        if (healthPort < 0 || healthPort > 65_535) {
            reader.problem("KAAS_RUNNER_HEALTH_PORT must be a port number");
        }
        String dockerHost = reader.optional("KAAS_RUNNER_DOCKER_HOST", "unix:///var/run/docker.sock");

        reader.throwIfInvalid();
        return new RunnerConfiguration(workerId, api, credentials, dockerHost, runtime, runscPath, keyId, keyFile,
                subject, probe, engine, egress, concurrency, claimWait, backoffInitial, backoffMax,
                requestTimeout, reconcile, wallClock, refresh, retry, maxAge, shutdown, healthHost, healthPort);
    }

    /** https anywhere; plain http only to loopback. The same rule the client applies to secret redemption. */
    static boolean carriesCredentialsSafely(URI uri) {
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return true;
        }
        String host = uri.getHost();
        return "http".equalsIgnoreCase(uri.getScheme()) && host != null
                && (host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("[::1]"));
    }

    /** Redacted: names locations, never contents. */
    @Override
    public String toString() {
        return "RunnerConfiguration[workerId=" + workerId + ", api=" + apiBaseUri + ", runtime=" + sandboxRuntime
                + ", concurrency=" + maxConcurrency + ", egressAllowlist=" + egress.isPresent() + "]";
    }

    /** Collects problems rather than stopping at the first. */
    private static final class Reader {
        private final Map<String, String> environment;
        private final List<String> problems = new ArrayList<>();

        Reader(Map<String, String> environment) {
            this.environment = environment;
        }

        void problem(String problem) {
            problems.add(problem);
        }

        void throwIfInvalid() {
            if (!problems.isEmpty()) {
                throw new Invalid(problems);
            }
        }

        String raw(String name) {
            String value = environment.get(name);
            return value == null || value.isBlank() ? null : value.trim();
        }

        String required(String name) {
            String value = raw(name);
            if (value == null) {
                problem(name + " is required");
            }
            return value;
        }

        String optional(String name, String fallback) {
            String value = raw(name);
            return value == null ? fallback : value;
        }

        Optional<Path> optionalPath(String name) {
            String value = raw(name);
            return value == null ? Optional.empty() : Optional.of(Path.of(value));
        }

        boolean flag(String name) {
            String value = raw(name);
            if (value == null || value.equalsIgnoreCase("false")) {
                return false;
            }
            if (value.equalsIgnoreCase("true")) {
                return true;
            }
            problem(name + " must be true or false");
            return false;
        }

        URI uri(String name, boolean required) {
            String value = required ? required(name) : raw(name);
            if (value == null) {
                return null;
            }
            try {
                URI uri = new URI(value);
                if (uri.getScheme() == null || uri.getHost() == null || uri.getRawUserInfo() != null
                        || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                    problem(name + " must be an absolute http(s) URL with a host and no credentials, query or fragment");
                    return null;
                }
                return uri;
            } catch (java.net.URISyntaxException invalid) {
                problem(name + " is not a URL");
                return null;
            }
        }

        /** A content-addressed image reference: {@code repo@sha256:<64 hex>} or {@code sha256:<64 hex>}. */
        String image(String name) {
            String value = required(name);
            if (value != null && !SandboxSecurityProfile.isContentAddressedReference(value)) {
                // A tag is a mutable pointer to executable code. The profile refuses one when it launches; this
                // refuses it before anything starts, which is the difference between NOT READY and a failed run.
                problem(name + " must be pinned by digest (…@sha256:<64 hex>), not a tag");
            }
            return value;
        }

        int integer(String name, int fallback) {
            String value = raw(name);
            if (value == null) {
                return fallback;
            }
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException invalid) {
                problem(name + " must be an integer");
                return fallback;
            }
        }

        Duration duration(String name, Duration fallback) {
            String value = raw(name);
            if (value == null) {
                return fallback;
            }
            try {
                return Duration.parse(value);
            } catch (DateTimeParseException invalid) {
                problem(name + " must be an ISO-8601 duration such as PT20S");
                return null;
            }
        }

        Duration positive(String name, Duration fallback) {
            Duration value = duration(name, fallback);
            if (value != null && (value.isNegative() || value.isZero())) {
                problem(name + " must be positive");
                return null;
            }
            return value;
        }

        List<String> list(String name) {
            String value = raw(name);
            if (value == null) {
                return List.of();
            }
            return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }

        Credentials credentials(String prefix) {
            String tokenFile = raw(prefix + "_TOKEN_FILE");
            String endpoint = raw(prefix + "_TOKEN_ENDPOINT");
            if (tokenFile != null && endpoint != null) {
                problem(prefix + "_TOKEN_FILE and " + prefix + "_TOKEN_ENDPOINT are mutually exclusive");
                return null;
            }
            if (tokenFile != null) {
                return new TokenFile(Path.of(tokenFile));
            }
            if (endpoint == null) {
                problem(prefix + "_TOKEN_ENDPOINT (client credentials) or " + prefix
                        + "_TOKEN_FILE is required: there is no unauthenticated mode");
                return null;
            }
            URI uri = uri(prefix + "_TOKEN_ENDPOINT", true);
            if (uri != null && !carriesCredentialsSafely(uri)) {
                problem(prefix + "_TOKEN_ENDPOINT must be https, or http to this host's loopback");
            }
            String clientId = required(prefix + "_CLIENT_ID");
            String secretFile = required(prefix + "_CLIENT_SECRET_FILE");
            return new ClientCredentials(uri, clientId, secretFile == null ? null : Path.of(secretFile),
                    Optional.ofNullable(raw(prefix + "_TOKEN_AUDIENCE")),
                    Optional.ofNullable(raw(prefix + "_TOKEN_SCOPE")));
        }
    }
}
