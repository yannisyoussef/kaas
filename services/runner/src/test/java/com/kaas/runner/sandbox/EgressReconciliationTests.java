package com.kaas.runner.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Network;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * What survives a runner that dies mid-execution, and what must not.
 *
 * <p>The asymmetry matters. Leaving an orphaned sandbox behind costs disk and memory. Leaving an orphaned
 * <em>proxy</em> behind leaves a running gateway holding a service credential, attached to the target
 * network, with nothing at all left that could stop it — the one artefact this whole slice exists to make
 * impossible. And leaving an orphaned network behind is how a host slowly runs out of address space.
 *
 * <p>The opposite failure is worse still and is pinned just as hard: the reconciler must never remove a
 * resource belonging to a live execution, or to anything that is not KaaS at all.
 */
@DisplayName("Execution egress orphan reconciliation")
class EgressReconciliationTests {

    private static final Duration DEADLINE = Duration.ofSeconds(30);

    /** Far enough past the deadline and its grace window that nothing could still be legitimately waiting. */
    private static Clock longAfterAbandonment() {
        return Clock.fixed(Instant.now().plus(Duration.ofMinutes(30)), ZoneOffset.UTC);
    }

    @Test
    @Timeout(300)
    @DisplayName("a crashed runner leaves no sandbox, no proxy, and no network behind")
    void aCrashedRunnerLeavesNothingBehind() throws IOException {
        String generation = "egress-reconcile-" + UUID.randomUUID();
        EgressTestTopology topology = new EgressTestTopology(generation);
        String proxyId = topology.proxy.containerId();
        String executionNetworkId = topology.executionNetwork.networkId();

        // The runner dies here: everything it created is still up and nothing will ever clean it up in-band.
        // Deliberately NOT topology.close() — that is the orderly path, and the orderly path is not what a
        // crash looks like.
        assertThat(containerExists(proxyId)).isTrue();
        assertThat(networkExists(executionNetworkId)).isTrue();

        try {
            int removed = new OrphanSandboxReconciler(
                            SandboxTestSupport.docker(), "a-later-generation", DEADLINE, longAfterAbandonment())
                    .reconcile();

            assertThat(removed).isGreaterThanOrEqualTo(2);
            assertThat(containerExists(proxyId)).as("an orphaned egress gateway must not survive").isFalse();
            assertThat(networkExists(executionNetworkId)).isFalse();
        } finally {
            topology.close();
        }
    }

    @Test
    @Timeout(300)
    @DisplayName("a live execution's proxy and network are left alone, whoever owns them")
    void aLiveExecutionIsNeverReclaimed() throws IOException {
        String generation = "egress-live-" + UUID.randomUUID();
        EgressTestTopology topology = new EgressTestTopology(generation);
        try {
            // A different generation reconciling, with the real clock: everything here is young, so a
            // launcher could still legitimately be waiting on it. Age is the only signal another process has.
            int removed = new OrphanSandboxReconciler(
                            SandboxTestSupport.docker(), "another-generation", DEADLINE)
                    .reconcile();

            assertThat(removed).isZero();
            assertThat(containerExists(topology.proxy.containerId())).isTrue();
            assertThat(networkExists(topology.executionNetwork.networkId())).isTrue();
            // Still working, which is the point of not having been reclaimed.
            assertThat(topology.proxy.isRunning()).isTrue();
        } finally {
            topology.close();
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("an unrelated network is never removed, however unused it looks")
    void anUnrelatedNetworkIsNeverRemoved() {
        // The failure mode being excluded is a broad "remove every network nothing is attached to", which
        // would take a developer's compose network, another tool's network, and anything created a moment
        // ago by a process this one cannot see.
        String bystander = SandboxTestSupport.docker()
                .createNetworkCmd()
                .withName("not-kaas-at-all-" + UUID.randomUUID())
                .withLabels(Map.of("com.example.kaas-lookalike", "true"))
                .exec()
                .getId();
        // A network that is ours but is not a per-execution network. The resource label is what separates
        // them, and without it the reconciler would act on anything KaaS ever labelled.
        String managedButNotAnExecutionNetwork = SandboxTestSupport.docker()
                .createNetworkCmd()
                .withName("kaas-something-else-" + UUID.randomUUID())
                .withLabels(Map.of(SandboxLabels.MANAGED, "true", SandboxLabels.RESOURCE, "something-else"))
                .exec()
                .getId();
        try {
            new OrphanSandboxReconciler(
                            SandboxTestSupport.docker(), "any", DEADLINE, longAfterAbandonment())
                    .reconcile();

            assertThat(networkExists(bystander)).isTrue();
            assertThat(networkExists(managedButNotAnExecutionNetwork)).isTrue();
        } finally {
            removeNetwork(bystander);
            removeNetwork(managedButNotAnExecutionNetwork);
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("an abandoned execution network that still has something attached is left alone")
    void anAttachedNetworkIsNeverRemoved() {
        // Age alone is not enough. A container still on the network is something that is running, whatever
        // its age says, and removing the network out from under it would break a live execution to reclaim a
        // resource that was not actually abandoned.
        UUID correlationId = UUID.randomUUID();
        ExecutionNetwork network =
                ExecutionNetwork.create(SandboxTestSupport.docker(), "attached-generation", correlationId);
        String occupant = SandboxTestSupport.docker()
                .createContainerCmd(SandboxTestSupport.probeImage())
                .withHostConfig(HostConfig.newHostConfig().withNetworkMode(network.name()))
                .withCmd(List.of("sleep", "300"))
                .withLabels(Map.of("com.example.not-kaas", "true"))
                .exec()
                .getId();
        try {
            SandboxTestSupport.docker().startContainerCmd(occupant).exec();

            new OrphanSandboxReconciler(
                            SandboxTestSupport.docker(), "any", DEADLINE, longAfterAbandonment())
                    .reconcile();

            assertThat(networkExists(network.networkId())).isTrue();
        } finally {
            try {
                SandboxTestSupport.docker().removeContainerCmd(occupant).withForce(true).exec();
            } catch (RuntimeException gone) {
                // Already removed.
            }
            network.close();
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("a newly created execution network is not reclaimed while it is still empty")
    void aNewlyCreatedNetworkIsNotReclaimed() {
        // The race the age check exists to prevent, and the only case that isolates it. A network is created
        // before anything joins it, so for a moment a perfectly healthy execution owns an empty managed
        // network. Without the age rule a reconciler running in that instant deletes it out from under the
        // launcher that is still starting up.
        //
        // Every other network case here is empty-and-old or attached-and-old, and in those the "still
        // attached" rule or the daemon's own refusal decides the outcome — so removing the age check changes
        // nothing and the mutation survives. It has to be empty AND young to reach the age rule alone.
        UUID correlationId = UUID.randomUUID();
        ExecutionNetwork network =
                ExecutionNetwork.create(SandboxTestSupport.docker(), "starting-generation", correlationId);
        try {
            int removed = new OrphanSandboxReconciler(SandboxTestSupport.docker(), "any", DEADLINE).reconcile();

            assertThat(removed).isZero();
            assertThat(networkExists(network.networkId()))
                    .as("an execution that is still starting must keep the network it just created")
                    .isTrue();
        } finally {
            network.close();
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("only managed networks are ever considered, and the filter is not vacuous")
    void onlyManagedNetworksAreConsidered() {
        String bystander = SandboxTestSupport.docker()
                .createNetworkCmd()
                .withName("unrelated-" + UUID.randomUUID())
                .exec()
                .getId();
        UUID correlationId = UUID.randomUUID();
        ExecutionNetwork managed =
                ExecutionNetwork.create(SandboxTestSupport.docker(), "listing-generation", correlationId);
        try {
            List<Network> considered = new OrphanSandboxReconciler(
                            SandboxTestSupport.docker(), "any", DEADLINE)
                    .managedNetworks();

            // Asserting only the bystander's absence would pass on an empty list, which a broken label filter
            // returning nothing also produces — and a reconciler that considers nothing reclaims nothing
            // while looking perfectly safe.
            assertThat(considered).extracting(Network::getId).contains(managed.networkId());
            assertThat(considered).noneSatisfy(network -> assertThat(network.getId()).isEqualTo(bystander));
        } finally {
            managed.close();
            removeNetwork(bystander);
        }
    }

    private boolean containerExists(String id) {
        try {
            SandboxTestSupport.docker().inspectContainerCmd(id).exec();
            return true;
        } catch (RuntimeException gone) {
            return false;
        }
    }

    private boolean networkExists(String id) {
        try {
            SandboxTestSupport.docker().inspectNetworkCmd().withNetworkId(id).exec();
            return true;
        } catch (RuntimeException gone) {
            return false;
        }
    }

    private void removeNetwork(String id) {
        try {
            SandboxTestSupport.docker().removeNetworkCmd(id).exec();
        } catch (RuntimeException gone) {
            // Already removed.
        }
    }
}
