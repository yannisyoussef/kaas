package com.kaas.runner.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.kaas.runner.attestation.RuntimeImplementation;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The real preflight against the real daemon: the CONFIGURED runtime or nothing.
 *
 * <p>On a host without runsc -- a developer's Docker Desktop, the backend CI job -- a runner configured for gVisor
 * must be NOT READY, and nothing it reports may describe runc. On a host with runsc registered -- the
 * deployment-readiness job -- the digest it reports must be runsc's. Both branches are assertions: which one runs
 * depends on the host, and neither is skipped.
 */
class RuntimePreflightTests {
    private static DockerClient docker;

    @BeforeAll
    static void connect() {
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        docker = DockerClientImpl.getInstance(config, new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .responseTimeout(Duration.ofSeconds(60))
                .connectionTimeout(Duration.ofSeconds(30))
                .build());
    }

    @AfterAll
    static void close() throws Exception {
        docker.close();
    }

    @Test
    void aRunnerConfiguredForGvisorIsReadyOnlyUnderRunscAndNeverFallsBackToRunc() {
        Readiness readiness = new Readiness();
        Optional<String> measured = new RuntimePreflight(docker, configuration(Map.of()), readiness).check();

        boolean runscRegistered = docker.infoCmd().exec().getRuntimes() instanceof Map<?, ?> runtimes
                && runtimes.containsKey("runsc");
        assertThat(readiness.isHeld(Readiness.Condition.DOCKER)).isTrue();
        if (runscRegistered) {
            assertThat(readiness.isHeld(Readiness.Condition.RUNTIME)).isTrue();
            assertThat(measured).contains(RuntimeImplementation.measure(docker, "runsc").digest());
        } else {
            assertThat(readiness.isHeld(Readiness.Condition.RUNTIME)).isFalse();
            assertThat(readiness.report().get("RUNTIME")).isEqualTo("RUNTIME_NOT_REGISTERED");
            assertThat(measured).as("no digest of any other runtime").isEmpty();
        }
    }

    @Test
    void aRegistrationAtAPathOtherThanTheConfiguredOneIsNotReady() {
        Readiness readiness = new Readiness();
        Optional<String> measured = new RuntimePreflight(docker,
                configuration(Map.of("KAAS_RUNNER_RUNSC_PATH", "/opt/not-where-it-is/runsc")), readiness).check();

        assertThat(readiness.isHeld(Readiness.Condition.RUNTIME)).isFalse();
        assertThat(readiness.report().get("RUNTIME")).isIn("RUNTIME_PATH_MISMATCH", "RUNTIME_NOT_REGISTERED");
        assertThat(measured).isEmpty();
    }

    @Test
    void imagesAbsentByDigestAreNotReadyAndAreNotPulled() {
        Readiness readiness = new Readiness();
        new RuntimePreflight(docker, configuration(Map.of()), readiness).check();

        // Digests nothing on this host was ever built or pulled as.
        assertThat(readiness.isHeld(Readiness.Condition.IMAGES)).isFalse();
        assertThat(readiness.report().get("IMAGES")).isEqualTo("IMAGE_ABSENT");
    }

    @Test
    void anUnreachableDaemonIsNotReadyEverywhere() {
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost("tcp://127.0.0.1:9").build();
        try (DockerClient nowhere = DockerClientImpl.getInstance(config, new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost()).connectionTimeout(Duration.ofSeconds(2))
                .responseTimeout(Duration.ofSeconds(2)).build())) {
            Readiness readiness = new Readiness();
            assertThat(new RuntimePreflight(nowhere, configuration(Map.of()), readiness).check()).isEmpty();
            assertThat(readiness.report().get("DOCKER")).isEqualTo("DOCKER_UNREACHABLE");
            assertThat(readiness.isHeld(Readiness.Condition.RUNTIME)).isFalse();
            assertThat(readiness.isHeld(Readiness.Condition.IMAGES)).isFalse();
        } catch (Exception closing) {
            throw new IllegalStateException(closing);
        }
    }

    private static RunnerConfiguration configuration(Map<String, String> overrides) {
        Map<String, String> environment = new HashMap<>(Map.of(
                "KAAS_RUNNER_WORKER_ID", "kaas.worker.preflight",
                "KAAS_RUNNER_API_URL", "https://api.internal",
                "KAAS_RUNNER_TOKEN_FILE", "/run/kaas/token",
                "KAAS_RUNNER_ATTESTATION_KEY_ID", "k",
                "KAAS_RUNNER_RUNTIME_SUBJECT", "kaas.runtime.preflight",
                "KAAS_RUNNER_PROBE_IMAGE", "registry.invalid/kaas-probe@sha256:" + "e".repeat(64),
                "KAAS_RUNNER_ENGINE_IMAGE", "registry.invalid/kaas-engine@sha256:" + "f".repeat(64)));
        environment.putAll(overrides);
        return RunnerConfiguration.fromEnvironment(environment);
    }
}
