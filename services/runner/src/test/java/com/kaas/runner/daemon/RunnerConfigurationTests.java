package com.kaas.runner.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaas.runner.sandbox.ExecutionRuntimeType;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/** The runner refuses to start on a configuration it could not run safely, and says why by name. */
class RunnerConfigurationTests {

    private static Map<String, String> valid() {
        Map<String, String> environment = new HashMap<>();
        environment.put("KAAS_RUNNER_WORKER_ID", "kaas.worker.exec-1");
        environment.put("KAAS_RUNNER_API_URL", "https://kaas-api.internal:8443");
        environment.put("KAAS_RUNNER_TOKEN_ENDPOINT", "https://issuer.internal/oauth/token");
        environment.put("KAAS_RUNNER_CLIENT_ID", "kaas-runner-exec-1");
        environment.put("KAAS_RUNNER_CLIENT_SECRET_FILE", "/run/kaas/runner-client-secret");
        environment.put("KAAS_RUNNER_ATTESTATION_KEY_ID", "kaas-exec-1-2026");
        environment.put("KAAS_RUNNER_RUNTIME_SUBJECT", "kaas.runtime.exec-1");
        environment.put("KAAS_RUNNER_PROBE_IMAGE", "registry.internal/kaas-probe@sha256:" + "1".repeat(64));
        environment.put("KAAS_RUNNER_ENGINE_IMAGE", "registry.internal/kaas-engine@sha256:" + "2".repeat(64));
        return environment;
    }

    @Test
    void aMinimalProductionConfigurationHasSafeDefaultsAndNoTopology() {
        RunnerConfiguration configuration = RunnerConfiguration.fromEnvironment(valid());

        assertThat(configuration.sandboxRuntime()).isEqualTo(ExecutionRuntimeType.GVISOR);
        assertThat(configuration.attestationKeyFile()).isEqualTo(Path.of("/run/kaas/attestation.key"));
        assertThat(configuration.attestationRefreshInterval()).isEqualTo(Duration.ofHours(20));
        assertThat(configuration.attestationMaxAge()).isEqualTo(Duration.ofHours(24));
        assertThat(configuration.maxConcurrency()).isEqualTo(1);
        assertThat(configuration.healthHost()).isEqualTo("127.0.0.1");
        assertThat(configuration.egress()).isEmpty();
        assertThat(configuration.credentials()).isInstanceOf(RunnerConfiguration.ClientCredentials.class);
        // Nothing about a broker exists to configure.
        assertThat(java.util.Arrays.stream(RunnerConfiguration.class.getRecordComponents())
                        .map(component -> component.getName().toLowerCase()))
                .noneMatch(name -> name.contains("rabbit") || name.contains("amqp") || name.contains("broker"));
    }

    @Test
    void everyUnsafeOrMalformedValueIsRefusedByName() {
        assertRefused(e -> e.put("KAAS_RUNNER_API_URL", "not a url"), "KAAS_RUNNER_API_URL");
        assertRefused(e -> e.put("KAAS_RUNNER_API_URL", "http://10.8.0.1:8080"), "must be https");
        assertRefused(e -> e.put("KAAS_RUNNER_API_URL", "https://user:pw@api.internal"), "no credentials");
        assertRefused(e -> e.put("KAAS_RUNNER_ENGINE_IMAGE", "registry.internal/kaas-engine:2.1.2"),
                "KAAS_RUNNER_ENGINE_IMAGE must be pinned by digest");
        assertRefused(e -> e.put("KAAS_RUNNER_PROBE_IMAGE", "kaas-probe@sha256:abc"),
                "KAAS_RUNNER_PROBE_IMAGE must be pinned by digest");
        assertRefused(e -> e.put("KAAS_RUNNER_MAX_CONCURRENCY", "0"), "KAAS_RUNNER_MAX_CONCURRENCY");
        assertRefused(e -> e.put("KAAS_RUNNER_MAX_CONCURRENCY", "-3"), "KAAS_RUNNER_MAX_CONCURRENCY");
        assertRefused(e -> e.put("KAAS_RUNNER_SANDBOX_RUNTIME", "runc"), "KAAS_RUNNER_SANDBOX_RUNTIME");
        assertRefused(e -> e.remove("KAAS_RUNNER_ATTESTATION_KEY_ID"), "KAAS_RUNNER_ATTESTATION_KEY_ID is required");
        assertRefused(e -> e.put("KAAS_RUNNER_ATTESTATION_REFRESH_INTERVAL", "PT24H"),
                "must be shorter than KAAS_RUNNER_ATTESTATION_MAX_AGE");
        assertRefused(e -> e.put("KAAS_RUNNER_ATTESTATION_REFRESH_INTERVAL", "PT25H"),
                "must be shorter than KAAS_RUNNER_ATTESTATION_MAX_AGE");
        assertRefused(e -> e.put("KAAS_RUNNER_RECONCILE_INTERVAL", "-PT5M"), "KAAS_RUNNER_RECONCILE_INTERVAL");
        assertRefused(e -> e.put("KAAS_RUNNER_RECONCILE_INTERVAL", "PT0S"), "KAAS_RUNNER_RECONCILE_INTERVAL");
        assertRefused(e -> e.put("KAAS_RUNNER_CLAIM_WAIT", "PT30S"), "KAAS_RUNNER_CLAIM_WAIT");
        assertRefused(e -> e.put("KAAS_RUNNER_CLAIM_WAIT", "ten seconds"), "KAAS_RUNNER_CLAIM_WAIT");
        assertRefused(e -> e.put("KAAS_RUNNER_WORKER_ID", ""), "KAAS_RUNNER_WORKER_ID is required");
        assertRefused(e -> e.put("KAAS_RUNNER_WORKER_ID", "kaas.egress-proxy"), "kaas.worker.");
        assertRefused(e -> e.put("KAAS_RUNNER_RUNSC_PATH", "runsc"), "KAAS_RUNNER_RUNSC_PATH must be absolute");
    }

    @Test
    void thereIsNoUnauthenticatedMode() {
        assertRefused(e -> {
            e.remove("KAAS_RUNNER_TOKEN_ENDPOINT");
            e.remove("KAAS_RUNNER_CLIENT_ID");
            e.remove("KAAS_RUNNER_CLIENT_SECRET_FILE");
        }, "there is no unauthenticated mode");
        assertRefused(e -> e.put("KAAS_RUNNER_TOKEN_FILE", "/run/kaas/token"), "mutually exclusive");
        assertRefused(e -> e.put("KAAS_RUNNER_TOKEN_ENDPOINT", "http://issuer.internal/token"), "must be https");
    }

    @Test
    void allowlistEgressNeedsItsOwnPinnedProxyAndItsOwnIdentity() {
        assertRefused(e -> e.put("KAAS_RUNNER_EGRESS_ALLOWLIST_ENABLED", "true"),
                "KAAS_RUNNER_EGRESS_PROXY_IMAGE is required");
        Map<String, String> environment = valid();
        environment.put("KAAS_RUNNER_EGRESS_ALLOWLIST_ENABLED", "true");
        environment.put("KAAS_RUNNER_EGRESS_PROXY_IMAGE", "registry.internal/kaas-proxy@sha256:" + "3".repeat(64));
        environment.put("KAAS_RUNNER_EGRESS_CONTROL_PLANE_URL", "https://kaas-api.internal:8443");
        environment.put("KAAS_RUNNER_EGRESS_TOKEN_FILE", "/run/kaas/egress-proxy.token");
        environment.put("KAAS_RUNNER_EGRESS_DNS_SERVER", "10.20.0.53:53");
        environment.put("KAAS_RUNNER_EGRESS_NETWORKS", "kaas-egress");
        Map<String, String> tagged = new HashMap<>(environment);
        tagged.put("KAAS_RUNNER_EGRESS_PROXY_IMAGE", "registry.internal/kaas-proxy:1.0");
        assertThatThrownBy(() -> RunnerConfiguration.fromEnvironment(tagged))
                .hasMessageContaining("KAAS_RUNNER_EGRESS_PROXY_IMAGE must be pinned by digest");
        RunnerConfiguration configuration = RunnerConfiguration.fromEnvironment(environment);
        assertThat(configuration.egress()).hasValueSatisfying(egress -> {
            assertThat(egress.proxyCredentials()).isInstanceOf(RunnerConfiguration.TokenFile.class);
            assertThat(egress.egressNetworkIds()).containsExactly("kaas-egress");
        });
    }

    @Test
    void problemsAreReportedTogetherAndNeverEchoAValue() {
        Map<String, String> environment = valid();
        environment.put("KAAS_RUNNER_API_URL", "https://api.internal/?secret=kaas-canary-not-to-be-printed");
        environment.put("KAAS_RUNNER_MAX_CONCURRENCY", "kaas-canary-not-to-be-printed");
        environment.remove("KAAS_RUNNER_RUNTIME_SUBJECT");

        assertThatThrownBy(() -> RunnerConfiguration.fromEnvironment(environment))
                .isInstanceOfSatisfying(RunnerConfiguration.Invalid.class, invalid -> {
                    List<String> problems = invalid.problems();
                    assertThat(problems).hasSize(3);
                    assertThat(String.join("\n", problems)).doesNotContain("kaas-canary-not-to-be-printed");
                });
    }

    private static void assertRefused(Consumer<Map<String, String>> change, String expected) {
        Map<String, String> environment = valid();
        change.accept(environment);
        assertThatThrownBy(() -> RunnerConfiguration.fromEnvironment(environment))
                .isInstanceOf(RunnerConfiguration.Invalid.class)
                .hasMessageContaining(expected);
    }
}
