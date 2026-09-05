package com.kaas.runner.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a sandbox on a per-execution internal network can actually reach.
 *
 * <p><strong>This is the release-blocking test.</strong> If a target remains reachable with no proxy present,
 * an allowlist is not enforceable and no amount of checking in application code makes it so — the answer is to
 * fix the topology, not to add a check around the bypass.
 *
 * <p>The observations are taken from inside the sandbox by the trusted probe, which reports what it can see
 * rather than answering questions about paths somebody thought to name. Positive evidence comes first:
 * reachability alone cannot distinguish "no network" from "a network with nothing routable on it", and this
 * sandbox deliberately HAS a network.
 */
class ExecutionNetworkTopologyTests {

    @Test
    @DisplayName("a sandbox on an internal network has an address and still cannot reach anything")
    void theInternalNetworkCarriesNoRouteToAnyTarget() {
        UUID correlationId = UUID.randomUUID();
        try (ExecutionNetwork network =
                ExecutionNetwork.create(SandboxTestSupport.docker(), "topology", correlationId)) {

            SandboxSecurityProfile profile = SandboxSecurityProfile.version1OnNetwork(
                    SandboxTestSupport.probeImage(), network.name());
            SandboxOutcome outcome = SandboxTestSupport.launcher(profile, "topology")
                    .run(new SandboxLaunchRequest(SyntheticProbe.NETWORK, profile.version(), correlationId));

            assertThat(outcome.failure()).isEmpty();
            Map<String, String> seen = outcome.observations();
            assertThat(seen).containsEntry("probe_tooling", "present");

            // POSITIVE EVIDENCE. The sandbox really is on a network — it has a global address and a live
            // interface. Without this the assertions below would also hold for a container with no networking
            // at all, and would prove nothing about the topology under test.
            assertThat(Integer.parseInt(seen.get("net_global_addresses")))
                    .as("the sandbox must genuinely be attached, or this test is about the wrong thing")
                    .isGreaterThanOrEqualTo(1);
            assertThat(Integer.parseInt(seen.get("net_interfaces_up"))).isGreaterThanOrEqualTo(1);

            // A DEFAULT ROUTE EXISTS, AND THAT IS THE POINT OF THIS ASSERTION.
            //
            // Docker's --internal network still installs a default route to a gateway that cannot forward
            // externally. ADR-025 requirement 1 was written as "no default route", which is a false description
            // of a true property, and believing it would send the next reader looking for a route that is
            // supposed to be absent and finding one — then either weakening the requirement or concluding the
            // topology was broken.
            //
            // The real invariant is not the absence of a route. It is that no target is reachable through it,
            // which the assertions below measure directly from inside the sandbox.
            assertThat(Integer.parseInt(seen.get("net_default_routes")))
                    .as("this asserts observed reality: --internal DOES present a default route")
                    .isEqualTo(1);

            // NO TARGET IS REACHABLE. Every destination the probe attempts, with no proxy running.
            for (String target : new String[] {
                    "net_public", "net_private", "net_metadata", "net_link_local",
                    "net_metadata_v6", "net_docker_host", "net_gateway"}) {
                assertThat(seen.get(target))
                        .as("%s must be unreachable from an internal network, or ALLOWLIST is not enforceable",
                                target)
                        .isEqualTo("unreachable");
            }

            // DNS IS A SEPARATE CHANNEL FROM TCP REACHABILITY, and Docker's embedded resolver is reachable on
            // an internal network. It forwards to the host's resolvers, which an internal network cannot reach
            // — so external names do not resolve. Measured rather than assumed, because the forwarding
            // behaviour is a property of Docker's networking rather than of anything this repository controls,
            // and a Docker change that made it resolve would be a silent exfiltration channel.
            assertThat(seen.get("net_dns"))
                    .as("a sandbox must not resolve target names for itself; the proxy resolves")
                    .isEqualTo("unresolvable");
        }
    }

    @Test
    @DisplayName("the network is created internal, and that is verified with the daemon rather than assumed")
    void theNetworkIsVerifiedInternal() {
        UUID correlationId = UUID.randomUUID();
        try (ExecutionNetwork network =
                ExecutionNetwork.create(SandboxTestSupport.docker(), "topology", correlationId)) {
            var inspected = SandboxTestSupport.docker().inspectNetworkCmd()
                    .withNetworkId(network.networkId())
                    .exec();
            // The internal flag is the entire isolation guarantee. A network that exists under the right name
            // with the wrong flag would attach successfully and route freely.
            assertThat(inspected.getInternal()).isTrue();
            assertThat(inspected.getLabels()).containsEntry(SandboxLabels.MANAGED, "true");
            assertThat(inspected.getName()).startsWith(ExecutionNetwork.NAME_PREFIX);
        }
    }

    @Test
    @DisplayName("a network that is not internal is refused, so the flag is verified rather than assumed")
    void aNonInternalNetworkIsRefused() {
        // The guard cannot fire on the happy path — create() always sets the flag — so without this it is a
        // branch no test reaches and deleting it would kill nothing. Here the daemon is asked for a network
        // that is genuinely not internal, which is the state the guard exists to catch: one that exists under
        // the right name and would attach successfully while routing freely.
        String name = ExecutionNetwork.NAME_PREFIX + UUID.randomUUID();
        String id = SandboxTestSupport.docker().createNetworkCmd()
                .withName(name)
                .withInternal(false)
                .withLabels(Map.of(SandboxLabels.MANAGED, "true"))
                .exec()
                .getId();
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> ExecutionNetwork.requireInternal(SandboxTestSupport.docker(), id))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("route out");
        } finally {
            SandboxTestSupport.docker().removeNetworkCmd(id).exec();
        }
    }

    @Test
    @DisplayName("closing the network removes it, so an execution leaves none behind")
    void closingRemovesTheNetwork() {
        UUID correlationId = UUID.randomUUID();
        String id;
        try (ExecutionNetwork network =
                ExecutionNetwork.create(SandboxTestSupport.docker(), "topology", correlationId)) {
            id = network.networkId();
        }
        assertThat(ExecutionNetwork.managedNetworks(SandboxTestSupport.docker()))
                .as("a closed execution network must not survive")
                .noneSatisfy(network -> assertThat(network.getId()).isEqualTo(id));
    }
}
