package com.kaas.runner.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The no-bypass property, measured against real containers on real networks.
 *
 * <p>Everything here rests on one pair of observations that only mean something together:
 *
 * <ul>
 *   <li>a request <em>through the proxy</em> to an authorized destination succeeds, and
 *   <li>a raw socket <em>straight at the same address</em> fails.
 * </ul>
 *
 * <p>Either alone is worthless. A sandbox with no network at all satisfies the second and would make a suite
 * that only checked unreachability look perfect while enforcing nothing; a sandbox on a fully routed network
 * satisfies the first while enforcing nothing either. The pair is what distinguishes "egress is controlled"
 * from "egress is absent" and from "egress is unrestricted".
 */
@DisplayName("Execution egress topology boundary")
class EgressTopologyBoundaryTests {

    private EgressTestTopology topology;

    private final String generation = "egress-topology-" + UUID.randomUUID();

    @BeforeEach
    void build() throws IOException {
        topology = new EgressTestTopology(generation);
    }

    @AfterEach
    void tear() {
        topology.close();
    }

    private Map<String, String> run(SyntheticProbe probe) {
        SandboxSecurityProfile profile = SandboxSecurityProfile.version1OnNetwork(
                SandboxTestSupport.probeImage(),
                topology.executionNetwork.name(),
                topology.sandboxEnvironment());
        SandboxOutcome outcome = SandboxTestSupport.launcher(profile, generation)
                .run(new SandboxLaunchRequest(probe, profile.version(), UUID.randomUUID()));
        assertThat(outcome.failure()).as("the sandbox itself must run cleanly: %s", outcome).isEmpty();
        return outcome.observations();
    }

    @Test
    @DisplayName("an authorized destination is reachable through the proxy and unreachable without it")
    void theProxyIsTheOnlyPath() {
        Map<String, String> allowed = run(SyntheticProbe.EGRESS_ALLOWED);
        // Through the proxy: the request is carried and the target's own sentinel comes back. Asserting the
        // body rather than only the status matters — a 200 could come from anything, including the proxy
        // itself; the sentinel could only have come from the target.
        assertThat(allowed).containsEntry("egress_allowed_status", "200");
        assertThat(allowed).containsEntry("egress_allowed_body", "present");

        Map<String, String> direct = run(SyntheticProbe.EGRESS_DIRECT_BYPASS);
        // Straight at the very same address, which the sandbox was deliberately told. Knowing exactly where
        // the target is must not help, because the sandbox has no route there at all.
        assertThat(direct).containsEntry("egress_direct_direct_target", "unreachable");
        assertThat(direct).containsEntry("egress_direct_public", "unreachable");
        assertThat(direct).containsEntry("egress_direct_private", "unreachable");
        assertThat(direct).containsEntry("egress_direct_metadata", "unreachable");
        assertThat(direct).containsEntry("egress_direct_daemon", "unreachable");
        // Independent resolution would let a workload discover addresses without any proxy decision being
        // involved, which is a channel in its own right even before anything is connected to.
        assertThat(direct).containsEntry("egress_direct_dns", "unresolvable");
    }

    @Test
    @DisplayName("with the proxy stopped, nothing is reachable and no route appears in its place")
    void stoppingTheProxyLeavesNoOtherPath() {
        // Proves the sandbox's reachability came from the proxy and only from the proxy. If the sandbox had
        // any second path, this is where it would show: the enforcement point is gone and the network is
        // otherwise unchanged.
        assertThat(run(SyntheticProbe.EGRESS_ALLOWED)).containsEntry("egress_allowed_status", "200");

        // Stopped, not removed. Removing it would also tear down its network endpoints, so the sandbox
        // would lose connectivity for a reason other than the one under test. Stopping changes exactly one
        // thing: nothing is listening any more.
        topology.proxy.stop();

        Map<String, String> afterwards = run(SyntheticProbe.EGRESS_ALLOWED);
        assertThat(afterwards).containsEntry("egress_allowed_status", "none");
        assertThat(afterwards).containsEntry("egress_allowed_body", "absent");

        Map<String, String> direct = run(SyntheticProbe.EGRESS_DIRECT_BYPASS);
        assertThat(direct).containsEntry("egress_direct_direct_target", "unreachable");
        assertThat(direct).containsEntry("egress_direct_dns", "unresolvable");
    }

    @Test
    @DisplayName("the sandbox is on exactly one network, and it is the internal one")
    void theSandboxIsOnOneNetworkOnly() {
        // The structural statement behind the reachability results above. A second attachment would give the
        // sandbox a route that no reachability probe in this suite happens to aim at, and the suite would
        // keep passing while the property was gone.
        SandboxSecurityProfile profile = SandboxSecurityProfile.version1OnNetwork(
                SandboxTestSupport.probeImage(),
                topology.executionNetwork.name(),
                topology.sandboxEnvironment());
        assertThat(profile.networkMode()).isEqualTo(topology.executionNetwork.name());
        assertThat(profile.networkMode()).startsWith(ExecutionNetwork.NAME_PREFIX);

        Map<String, String> observed = run(SyntheticProbe.NETWORK);
        assertThat(observed).containsEntry("net_global_addresses", "1");
        assertThat(observed).containsEntry("net_interfaces_up", "1");
    }
}
