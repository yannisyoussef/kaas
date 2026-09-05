package com.kaas.runner.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What a caller may and may not say about an execution's egress.
 *
 * <p>Structural rather than behavioural on purpose. The security of this design rests on dangerous
 * configurations being <em>unrepresentable</em> rather than validated away — validation is something you can
 * forget to do and a type is not — so the assertions below are about shapes: how many parameters a method
 * takes, what a record's components are, and which strings a constructor refuses.
 *
 * <p>These need no container and run in the ordinary suite. The Docker-heavy egress gate proves what the
 * mechanism does; this proves what nobody can ask it to do.
 */
@DisplayName("Execution egress boundary")
class ExecutionEgressBoundaryTests {

    private static final String DIGEST = "sha256:" + "a".repeat(64);

    // ---------------------------------------------------------------------------------------------------
    // Nothing a caller supplies is a container setting
    // ---------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a sandbox launch request names a probe, a profile, and a correlation, and nothing else")
    void aLaunchRequestCarriesNoContainerSetting() {
        List<String> components = componentsOf(SandboxLaunchRequest.class);

        // Three fields, none of which is an image, a network, a mount, a user, a limit, or an environment.
        // There is therefore no argument a caller could pass that would weaken the policy — which is stronger
        // than refusing a dangerous value, because a refusal is a branch somebody can delete.
        assertThat(components).containsExactly("probe", "profileVersion", "correlationId");
    }

    @Test
    @DisplayName("starting an execution's egress names only the execution and its plan")
    void startingEgressNamesOnlyTheExecutionAndItsPlan() throws Exception {
        Method start = EgressExecutions.class.getMethod("start", UUID.class, EgressPlan.class);

        // NO IMAGE PARAMETER, and that is the assertion. A caller that could choose the proxy image would be
        // choosing the enforcement, and every other control here would be decoration. Nor is there a network
        // parameter: a caller that could name a network could name the one the control plane, the database,
        // or the daemon is on.
        assertThat(start.getParameterTypes()).containsExactly(UUID.class, EgressPlan.class);
        assertThat(componentsOf(EgressPlan.class)).containsExactly("capabilityToken", "destinations");
        // The image lives in deployment wiring, established once at startup and never per execution.
        assertThat(componentsOf(EgressDeployment.class)).contains("proxyImageReference");
    }

    // ---------------------------------------------------------------------------------------------------
    // The network is the whole of the isolation, so only one shape of it is representable
    // ---------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"host", "bridge", "none", "kaas-egress-test-1", "default", "", "kaas-exec"})
    @DisplayName("a sandbox cannot be placed on any network but a per-execution internal one")
    void aSandboxJoinsNoOtherNetwork(String networkName) {
        // Refused by the RULE — the required prefix — rather than by a denylist of unsafe names. A denylist
        // of dangerous networks is a list that stops being complete the first time somebody creates one.
        assertThatThrownBy(() -> SandboxSecurityProfile.version1OnNetwork(DIGEST, networkName, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("per-execution internal network");
    }

    @Test
    @DisplayName("a per-execution network is accepted, so the rule above is not simply always red")
    void aPerExecutionNetworkIsAccepted() {
        SandboxSecurityProfile profile = SandboxSecurityProfile.version1OnNetwork(
                DIGEST, ExecutionNetwork.NAME_PREFIX + UUID.randomUUID(), Map.of());

        assertThat(profile.networkMode()).startsWith(ExecutionNetwork.NAME_PREFIX);
        // And it is a DIFFERENT profile version from the airgapped one. An attestation gathered against a
        // sandbox with no network must not be able to vouch for one that has a peer.
        assertThat(profile.version())
                .isEqualTo(SandboxSecurityProfile.networkedVersionOf(
                        SandboxSecurityProfile.version1(DIGEST).version()))
                .isNotEqualTo(SandboxSecurityProfile.version1(DIGEST).version());
    }

    @Test
    @DisplayName("the networked profile weakens no control but the network")
    void theNetworkedProfileWeakensNothingElse() {
        SandboxSecurityProfile base = SandboxSecurityProfile.version1(DIGEST);
        SandboxSecurityProfile networked = SandboxSecurityProfile.version1OnNetwork(
                DIGEST, ExecutionNetwork.NAME_PREFIX + UUID.randomUUID(), Map.of("KAAS_EGRESS_PROXY_PORT", "3128"));

        assertThat(networked.runAsUser()).isEqualTo(base.runAsUser());
        assertThat(networked.readOnlyRootFilesystem()).isTrue();
        assertThat(networked.noNewPrivileges()).isTrue();
        assertThat(networked.droppedCapabilities()).isEqualTo(base.droppedCapabilities());
        assertThat(networked.addedCapabilities()).isEmpty();
        assertThat(networked.memoryLimitBytes()).isEqualTo(base.memoryLimitBytes());
        assertThat(networked.memorySwapLimitBytes()).isEqualTo(base.memorySwapLimitBytes());
        assertThat(networked.pidsLimit()).isEqualTo(base.pidsLimit());
        assertThat(networked.wallClockTimeout()).isEqualTo(base.wallClockTimeout());
        // The egress environment is ADDED to the base allowlist, so nothing supplied here can remove PATH or
        // the sandbox marker by shadowing the map.
        assertThat(networked.environment()).containsAllEntriesOf(base.environment());
    }

    // ---------------------------------------------------------------------------------------------------
    // Image pinning
    // ---------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "kaas/egress-proxy:latest",
        "kaas/egress-proxy:v1",
        "egress-proxy",
        // A short Docker image ID. Content-derived, but NOT a digest — the kaas-10 lesson, restated as a
        // test because it is the mistake that looks correct.
        "a1b2c3d4e5f6",
        "sha256:tooshort",
        // Sixty-four characters, but not hex: length alone is not a content address.
        "sha256:zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"
    })
    @DisplayName("the proxy image must be a content address, and a tag or a short id is not one")
    void theProxyImageIsPinnedByDigest(String reference) {
        assertThatThrownBy(() -> EgressProxyProfile.version1(reference, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pinned by digest");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "registry.example.com/kaas/egress-proxy@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    })
    @DisplayName("the three spellings of a content address are accepted, so the rule is not always red")
    void theThreeSpellingsOfAContentAddressAreAccepted(String reference) {
        assertThatCode(() -> EgressProxyProfile.version1(reference, Map.of())).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------------------------------------
    // Nothing that carries a credential prints one
    // ---------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("neither the plan nor the deployment prints its credential")
    void nothingPrintsACredential() {
        String capability = "kaas_egr_" + "s".repeat(43);
        String serviceCredential = "Bearer kaas-egress-proxy-service-sentinel";

        EgressPlan plan = new EgressPlan(capability, List.of(new EgressTarget("api.example.com", 443, "HTTPS")));
        EgressDeployment deployment = new EgressDeployment(
                DIGEST, DIGEST, "http://control-plane", serviceCredential, "10.0.0.1:53",
                List.of(), List.of(), Duration.ofSeconds(5), Duration.ofSeconds(2),
                Duration.ofSeconds(2), Duration.ofSeconds(3),
                ExecutionRuntimeType.DOCKER);

        // A record's generated toString prints every component, and these are exactly the objects that reach
        // a log by being interpolated into a message about something else entirely.
        assertThat(plan.toString()).doesNotContain(capability).contains("redacted");
        assertThat(deployment.toString()).doesNotContain(serviceCredential).contains("redacted");
        // Still useful, though: the destination is not a secret and a redacted-everything toString would just
        // be replaced by somebody printing the fields.
        assertThat(plan.toString()).contains("api.example.com");
    }

    // ---------------------------------------------------------------------------------------------------
    // The plan itself
    // ---------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the port used to demonstrate a refusal is never one the policy names")
    void theDeniedPortIsNeverAllowlisted() {
        EgressPlan plan = new EgressPlan(
                "kaas_egr_token",
                List.of(
                        new EgressTarget("api.example.com", 1, "HTTPS"),
                        new EgressTarget("api.example.com", 2, "HTTPS"),
                        new EgressTarget("api.example.com", 3, "HTTP"),
                        new EgressTarget("other.example.com", 4, "HTTPS")));

        int denied = plan.unlistedPortOnPrimary();

        // The whole value of the denial scenario is that it must be refused. A port the policy happens to
        // name would make the workload assert the opposite of what it claims, and pass.
        assertThat(denied).isEqualTo(4);
        assertThat(plan.destinations())
                .noneSatisfy(destination -> {
                    assertThat(destination.host()).isEqualTo("api.example.com");
                    assertThat(destination.port()).isEqualTo(denied);
                });
    }

    @Test
    @DisplayName("an allowlist plan with no destinations and no credential is unrepresentable")
    void anEmptyPlanIsUnrepresentable() {
        assertThatThrownBy(() -> new EgressPlan("kaas_egr_token", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EgressPlan("  ", List.of(new EgressTarget("a.example.com", 1, "HTTP"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"API.example.com", "example.com.", "exa mple.com", "user@example.com", ""})
    @DisplayName("a destination that is not already canonical is refused rather than repaired")
    void aNonCanonicalDestinationIsRefused(String host) {
        // Refused, not lower-cased or trimmed. Canonicalization belongs to the control plane and to the
        // proxy, which implement one written contract independently; quietly repairing a value here would
        // produce a runner that disagrees with both about what it was told.
        assertThatThrownBy(() -> new EgressTarget(host, 443, "HTTPS"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FTP", "SOCKS", "ws", "", "HTTP/1.1"})
    @DisplayName("v1 carries HTTP and HTTPS, and refuses every other transport class")
    void onlyTwoTransportClassesExist(String scheme) {
        assertThatThrownBy(() -> new EgressTarget("api.example.com", 443, scheme))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the revocation bound is derived from the intervals it is made of")
    void theRevocationBoundIsDerived() {
        EgressDeployment deployment = new EgressDeployment(
                DIGEST, DIGEST, "http://control-plane", "Bearer x", "10.0.0.1:53",
                List.of(), List.of(), Duration.ofSeconds(5), Duration.ofSeconds(2),
                Duration.ofSeconds(30), Duration.ofSeconds(3),
                ExecutionRuntimeType.DOCKER);

        // Derived rather than written down separately, so the number in the documentation cannot drift away
        // from the number the proxy is actually configured with. It is a POLLING bound and is named as one.
        assertThat(deployment.maximumRevocationLatency()).isEqualTo(Duration.ofSeconds(32));
    }

    private static List<String> componentsOf(Class<?> record) {
        return Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList();
    }
}
