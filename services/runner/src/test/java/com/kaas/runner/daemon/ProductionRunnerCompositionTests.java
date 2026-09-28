package com.kaas.runner.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.kaas.runner.execution.ExecutionLoop;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The PRODUCTION composition, built by {@link RunnerComposition} and exercised part by part (KAAS-DEPLOY-001).
 *
 * <p>Every part here is the real one production builds, against the real local Docker daemon; only the control
 * plane is a recording fake. The end-to-end proof that the composition claims and executes Karate under runsc is
 * {@code DeploymentPipelineTests}, in the deployment-readiness gate. This is the part of that proof a host without
 * runsc can still make: that what the composition wires is the execution loop, the preflight, the reconciler and
 * the authenticated client -- so replacing any of them with a no-op fails here, locally, not only in CI.
 */
@Timeout(120)
class ProductionRunnerCompositionTests {
    private static final String WORKER = "kaas.worker.composition";

    @TempDir
    Path directory;

    private FakeControlPlane api;
    private DockerClient docker;

    @BeforeEach
    void start() throws Exception {
        api = new FakeControlPlane();
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        docker = DockerClientImpl.getInstance(config, new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost()).sslConfig(config.getSSLConfig())
                .responseTimeout(Duration.ofSeconds(60)).connectionTimeout(Duration.ofSeconds(30)).build());
    }

    @AfterEach
    void stop() throws Exception {
        api.close();
        docker.close();
    }

    @Test
    void theComposedExecutorIsTheExecutionLoopAndItRevalidatesAuthorityFirst() throws Exception {
        RunnerDaemon.Parts parts = parts();
        UUID runId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();

        ExecutionLoop.ExecutionReport report = parts.executor().execute(runId, attemptId, 1);

        // The loop's first act is always to ask for an execution authorization, under the runner's own
        // credential. A no-op executor, or one that skipped authority, makes no such request.
        var calls = api.requests.stream().filter(request -> request.path().startsWith("/internal/v1/runs/")).toList();
        assertThat(calls).isNotEmpty();
        assertThat(calls.get(0).path())
                .isEqualTo("/internal/v1/runs/" + runId + "/attempts/" + attemptId + "/execution-authorizations");
        assertThat(calls.get(0).authorization()).isEqualTo("Bearer " + token());
        // Refused, so nothing was provisioned: no sandbox of this process exists.
        assertThat(report.status()).isEqualTo("REFUSED");
    }

    @Test
    void theComposedPreflightIsTheRealOneAndTheComposedReconcilerTouchesOnlyManagedResources() throws Exception {
        RunnerDaemon.Parts parts = parts();

        parts.preflight().check();
        assertThat(parts.readiness().isHeld(Readiness.Condition.DOCKER)).isTrue();
        // The probe and engine digests in this configuration exist nowhere, so the real preflight must say so.
        assertThat(parts.readiness().report().get("IMAGES")).isEqualTo("IMAGE_ABSENT");

        // The composed reconciler runs against the real daemon. Which resources it may remove -- labelled,
        // abandoned, never a live or unknown one -- is proved by OrphanReconciliationTests on the same class.
        int unmanagedBefore = unmanaged();
        assertThat(parts.reconciler().reconcile()).isGreaterThanOrEqualTo(0);
        assertThat(unmanaged()).isEqualTo(unmanagedBefore);
    }

    @Test
    void theComposedClientAuthenticatesEveryRequestAndTheComposedRunnerIsNotReadyOnThisHost() throws Exception {
        RunnerDaemon.Parts parts = parts();
        parts.controlPlane().awaitWork(Duration.ZERO);
        assertThat(api.requestsTo("/internal/v1/assignments/waits").get(0).authorization())
                .isEqualTo("Bearer " + token());

        // The whole composition, started: on a host whose images are absent it is alive, NOT READY, and asks the
        // control plane for nothing.
        try (RunnerComposition.Runner runner = RunnerComposition.compose(configuration(), docker,
                HttpClient.newHttpClient(), Clock.systemUTC())) {
            runner.start();
            Thread.sleep(2_000);
            assertThat(runner.daemon().live()).isTrue();
            assertThat(runner.daemon().readiness().ready()).isFalse();
            assertThat(api.requestsTo("/internal/v1/assignments")).isEmpty();
        }
    }

    private int unmanaged() {
        return (int) docker.listContainersCmd().withShowAll(true).exec().stream()
                .filter(container -> container.getLabels() == null
                        || !"true".equals(container.getLabels().get("kaas.managed")))
                .count();
    }

    private RunnerDaemon.Parts parts() throws Exception {
        return RunnerComposition.parts(configuration(), docker, HttpClient.newHttpClient(), Clock.systemUTC(), false);
    }

    private RunnerConfiguration configuration() throws Exception {
        Path tokenFile = directory.resolve("token");
        if (!Files.exists(tokenFile)) {
            Files.writeString(tokenFile, token());
        }
        Map<String, String> environment = new HashMap<>();
        environment.put("KAAS_RUNNER_WORKER_ID", WORKER);
        environment.put("KAAS_RUNNER_API_URL", api.uri().toString());
        environment.put("KAAS_RUNNER_TOKEN_FILE", tokenFile.toString());
        environment.put("KAAS_RUNNER_ATTESTATION_KEY_ID", "kaas-test-key-1");
        environment.put("KAAS_RUNNER_ATTESTATION_KEY_FILE", directory.resolve("absent.key").toString());
        environment.put("KAAS_RUNNER_RUNTIME_SUBJECT", "kaas.runtime.composition");
        environment.put("KAAS_RUNNER_PROBE_IMAGE", "sha256:" + "1".repeat(64));
        environment.put("KAAS_RUNNER_ENGINE_IMAGE", "sha256:" + "2".repeat(64));
        environment.put("KAAS_RUNNER_HEALTH_PORT", "0");
        return RunnerConfiguration.fromEnvironment(environment);
    }

    private static String cachedToken;

    private static synchronized String token() {
        if (cachedToken == null) {
            cachedToken = FakeControlPlane.token(WORKER, Instant.now().plusSeconds(3600));
        }
        return cachedToken;
    }
}
