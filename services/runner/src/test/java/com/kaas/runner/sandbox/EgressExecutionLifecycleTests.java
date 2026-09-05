package com.kaas.runner.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaas.runner.authority.ExecutionAuthority;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The production egress path: the network, the proxy, and the sandbox that an ALLOWLIST execution creates.
 *
 * <p>The suites beside this one prove properties of the proxy and the topology. This one proves the thing that
 * actually runs them in order — {@link DockerEgressExecutions} — because a perfectly correct proxy that no
 * execution ever starts enforces nothing at all.
 *
 * <p>The workload here is {@link SyntheticProbe#WORKLOAD_EGRESS}: the platform's own workload, extended with
 * egress scenarios, reporting a workload identity and a workload outcome the way every other execution does.
 * That is what makes an allowlist run a run rather than a measurement taken outside one.
 */
@DisplayName("Egress execution lifecycle")
class EgressExecutionLifecycleTests {

    private EgressTestTopology topology;

    private final String generation = "egress-lifecycle-" + UUID.randomUUID();

    @BeforeEach
    void build() throws IOException {
        // No proxy of its own: the thing under test creates one, and two would make an assertion ambiguous.
        topology = EgressTestTopology.withoutProxy(generation);
    }

    @AfterEach
    void tear() {
        topology.close();
    }

    private EgressExecutions executions() {
        return new DockerEgressExecutions(SandboxTestSupport.docker(), topology.deployment(), generation);
    }

    /** The policy this execution runs under: exactly the one destination the authorization service permits. */
    private EgressPlan plan() {
        return new EgressPlan(
                topology.capabilityToken,
                List.of(new EgressTarget(
                        EgressTestTopology.ALLOWED_HOST, EgressTestTopology.TARGET_PORT, "HTTP")));
    }

    @Test
    @DisplayName("an allowlist execution reaches its destination through the proxy and nothing else at all")
    void anAllowlistExecutionRunsThroughTheProxyAndNowhereElse() {
        SandboxOutcome outcome;
        try (EgressExecution execution = executions().start(UUID.randomUUID(), plan())) {
            outcome = execution.launcher().run(new SandboxLaunchRequest(
                    SyntheticProbe.WORKLOAD_EGRESS, execution.profileVersion(), UUID.randomUUID()));
        }

        assertThat(outcome.failure()).as("the sandbox itself must run cleanly: %s", outcome).isEmpty();
        Map<String, String> observed = outcome.observations();

        // THE PAIR. Neither half is evidence alone: a fully routed sandbox satisfies the first, and a sandbox
        // with no network at all satisfies the second. Only together do they say anything about enforcement.
        assertThat(observed).containsEntry("scenario_egress_allowed", "PASSED");
        assertThat(observed).containsEntry("scenario_egress_no_bypass", "PASSED");
        // And the refusal, which is successful security evidence rather than a failed assertion.
        assertThat(observed).containsEntry("scenario_egress_denied", "PASSED");
        assertThat(observed)
                .as("the refusal names its reason, so a denial cannot be confused with an outage")
                .containsEntry("egress_denied_reason", "DESTINATION_NOT_ALLOWED");

        // It is a WORKLOAD, so the ordinary lifecycle can read it: identity and outcome, exactly as a
        // DENY_ALL execution reports them. Without these the run would be an infrastructure failure.
        assertThat(observed).containsEntry("workload_identity", "KAAS_SYNTHETIC_V1");
        assertThat(observed).containsEntry("workload_outcome", "PASSED");
        assertThat(observed).containsEntry("workload_failed", "0");

        // The proxy really was asked, over a socket, about the destination the policy names — the success
        // above cannot have come from a workload that skipped it.
        assertThat(topology.authorization.received())
                .anySatisfy(request -> assertThat(request.destination())
                        .isEqualTo(EgressTestTopology.ALLOWED_HOST + ":"
                                + EgressTestTopology.TARGET_PORT + "/HTTP"));
    }

    @Test
    @DisplayName("the sandbox joins the execution network and nothing joins it that should not")
    void theSandboxJoinsOnlyTheExecutionNetwork() {
        try (EgressExecution execution = executions().start(UUID.randomUUID(), plan())) {
            SandboxSecurityProfile profile = execution.launcher().profile();

            assertThat(profile.networkMode())
                    .as("one per-execution internal network, never a shared or named one")
                    .startsWith(ExecutionNetwork.NAME_PREFIX);
            // The derived profile, not the base one. A sandbox on a network is a different security posture
            // from one with none, and the evidence has to be able to say which produced it.
            assertThat(profile.version())
                    .isEqualTo(SandboxSecurityProfile.networkedVersionOf(
                            SandboxSecurityProfile.version1(SandboxTestSupport.probeImage()).version()));
            // Every other control is the base profile's, unchanged. Only the network differs.
            SandboxSecurityProfile base =
                    SandboxSecurityProfile.version1(SandboxTestSupport.probeImage());
            assertThat(profile.runAsUser()).isEqualTo(base.runAsUser());
            assertThat(profile.readOnlyRootFilesystem()).isTrue();
            assertThat(profile.droppedCapabilities()).isEqualTo(base.droppedCapabilities());
            assertThat(profile.addedCapabilities()).isEmpty();
            assertThat(profile.memoryLimitBytes()).isEqualTo(base.memoryLimitBytes());
            assertThat(profile.pidsLimit()).isEqualTo(base.pidsLimit());
        }
    }

    @Test
    @DisplayName("a proxy that cannot start leaves no network, no proxy, and no sandbox behind")
    void aProxyThatCannotStartLeavesNothingBehind() {
        EgressDeployment broken = brokenProxy(topology.deployment());
        EgressExecutions executions =
                new DockerEgressExecutions(SandboxTestSupport.docker(), broken, generation);
        UUID correlationId = UUID.randomUUID();

        assertThatThrownBy(() -> executions.start(correlationId, plan()))
                .isInstanceOf(EgressProxyStartFailed.class);

        // NOTHING SURVIVES. A half-started egress is a gateway with no execution behind it, which is the one
        // artefact this mechanism exists to make impossible — and a network left behind would also make the
        // correlation id unusable for a retry.
        assertThat(SandboxTestSupport.docker()
                        .listNetworksCmd()
                        .withFilter("label", List.of(SandboxLabels.CORRELATION + "=" + correlationId))
                        .exec())
                .as("no execution network")
                .isEmpty();
        assertThat(SandboxTestSupport.docker()
                        .listContainersCmd()
                        .withShowAll(true)
                        .withLabelFilter(Map.of(SandboxLabels.CORRELATION, correlationId.toString()))
                        .exec())
                .as("no proxy container")
                .isEmpty();
    }

    @Test
    @DisplayName("closing an execution removes the proxy and its network, in the order that works")
    void closingAnExecutionRemovesEverything() {
        UUID correlationId = UUID.randomUUID();
        EgressExecution execution = executions().start(correlationId, plan());
        assertThat(execution.proxyIsRunning()).isTrue();

        execution.close();

        // The order is load-bearing: a network with an endpoint on it cannot be removed, so removing the
        // network first silently reclaims nothing and leaves both. Asserting the network is gone is what
        // proves the proxy went first.
        assertThat(SandboxTestSupport.docker()
                        .listNetworksCmd()
                        .withFilter("label", List.of(SandboxLabels.CORRELATION + "=" + correlationId))
                        .exec())
                .isEmpty();
        assertThat(SandboxTestSupport.docker()
                        .listContainersCmd()
                        .withShowAll(true)
                        .withLabelFilter(Map.of(SandboxLabels.CORRELATION, correlationId.toString()))
                        .exec())
                .isEmpty();
    }

    @Test
    @DisplayName("a proxy that dies is observable, and its death restores no connectivity")
    void aProxyThatDiesIsObservableAndRestoresNothing() {
        try (EgressExecution execution = executions().start(UUID.randomUUID(), plan())) {
            assertThat(execution.proxyIsRunning()).isTrue();

            // Stopped rather than removed, so its network endpoints survive and the only thing that changed
            // is that nothing is listening. Removing it would tear down the endpoints too, and the sandbox
            // would then lose connectivity for a reason other than the one under test.
            stopProxy(execution);

            assertThat(execution.proxyIsRunning())
                    .as("the runner can tell, which is what lets it classify the execution truthfully")
                    .isFalse();

            SandboxOutcome outcome = execution.launcher().run(new SandboxLaunchRequest(
                    SyntheticProbe.WORKLOAD_EGRESS, execution.profileVersion(), UUID.randomUUID()));
            Map<String, String> observed = outcome.observations();

            // No route appeared in the proxy's place. Containment is topological, so it holds whether or not
            // anything noticed the proxy was gone — the detection above exists to classify, never to contain.
            assertThat(observed).containsEntry("scenario_egress_no_bypass", "PASSED");
            assertThat(observed).containsEntry("egress_allowed_status", "none");
            assertThat(observed)
                    .as("the destination is unreachable, so the workload reports a failure rather than a pass")
                    .containsEntry("workload_outcome", "FAILED");
        }
    }

    @Test
    @DisplayName("the credential the sandbox presents never reaches a log or a label")
    void theCredentialNeverReachesALogOrALabel() {
        SandboxOutcome outcome;
        String proxyLogs;
        List<com.github.dockerjava.api.model.Container> proxies;
        try (EgressExecution execution = executions().start(UUID.randomUUID(), plan())) {
            outcome = execution.launcher().run(new SandboxLaunchRequest(
                    SyntheticProbe.WORKLOAD_EGRESS, execution.profileVersion(), UUID.randomUUID()));
            proxyLogs = execution.proxyLogs();
            proxies = SandboxTestSupport.docker()
                    .listContainersCmd()
                    .withLabelFilter(Map.of(SandboxLabels.RESOURCE, SandboxLabels.RESOURCE_PROXY))
                    .exec();
        }

        // The token really was in play: the workload authenticated with it and got through. Without this the
        // assertions below would be about a credential nothing ever used.
        assertThat(outcome.observations()).containsEntry("scenario_egress_allowed", "PASSED");

        // THE PROXY SAW IT ON EVERY REQUEST AND WROTE IT NOWHERE. Its log is the most likely place for a
        // bearer credential to end up, because logging the request line is the obvious thing to do.
        assertThat(proxyLogs)
                .as("the proxy's own output must not carry the credential it was presented")
                .doesNotContain(topology.capabilityToken);
        // Nor the sandbox's output, which is collected and travels into a result document.
        assertThat(outcome.observations().toString()).doesNotContain(topology.capabilityToken);

        // NOR A LABEL. A label is readable by anything that can list containers and outlives the process; the
        // environment is where a credential has to be, and is bounded by the container's own lifetime.
        assertThat(proxies).isNotEmpty();
        assertThat(proxies)
                .allSatisfy(proxy -> assertThat(proxy.getLabels().values())
                        .noneSatisfy(value -> assertThat(value).contains(topology.capabilityToken)));
    }

    @Test
    @DisplayName("what the runner counts about egress carries no tenant dimension at all")
    void egressCountersCarryNoTenantDimension() {
        EgressMetrics metrics = new EgressMetrics();
        DockerEgressExecutions executions = new DockerEgressExecutions(
                SandboxTestSupport.docker(), topology.deployment(), generation, metrics);

        try (EgressExecution execution = executions.start(UUID.randomUUID(), plan())) {
            execution.launcher().run(new SandboxLaunchRequest(
                    SyntheticProbe.WORKLOAD_EGRESS, execution.profileVersion(), UUID.randomUUID()));
        }
        DockerEgressExecutions broken = new DockerEgressExecutions(
                SandboxTestSupport.docker(), brokenProxy(topology.deployment()), generation, metrics);
        assertThatThrownBy(() -> broken.start(UUID.randomUUID(), plan()))
                .isInstanceOf(EgressProxyStartFailed.class);

        assertThat(metrics.count("kaas_egress_proxy_launch_total")).isOne();
        assertThat(metrics.count(
                        "kaas_egress_proxy_failure_total{reason="
                                + EgressFailure.EGRESS_PROXY_START_FAILED + "}"))
                .isOne();

        // THE PROPERTY THAT MATTERS. A metrics store is read across tenants and retained far longer than a
        // log, so a run identifier, a destination hostname, or a resolved address in a counter name would be
        // a permanent disclosure. Asserted against the values actually in play — the capability, the
        // destination, and the proxy's address are all real here, so a counter that had picked any of them up
        // would be caught rather than argued about.
        String names = String.join(" ", metrics.snapshot().keySet());
        assertThat(names).doesNotContain(topology.capabilityToken);
        assertThat(names).doesNotContain(EgressTestTopology.ALLOWED_HOST);
        assertThat(names).doesNotContain(topology.targetAddress);
        assertThat(names).doesNotContain(generation);
        // And positively: every label value is a name from a closed enumeration, so the set of series this
        // runner can ever produce is bounded by the type system rather than by care.
        for (String name : metrics.snapshot().keySet()) {
            assertThat(name).matches("^kaas_egress_[a-z_]+_total(\\{[a-z]+=[A-Za-z_-]+\\})?$");
        }
    }

    /**
     * The same deployment pointed at an image that is not the proxy.
     *
     * <p>The probe image is digest-pinned, so it satisfies every control {@link EgressProxyProfile} enforces,
     * and it exits within a second — which exercises the readiness path rather than the create path. That is
     * the half where a container is easy to leave behind: creation failing removes nothing because nothing
     * was made, while a container that started and then exited is a real object somebody has to clean up.
     */
    private static EgressDeployment brokenProxy(EgressDeployment deployment) {
        return new EgressDeployment(
                deployment.probeImageReference(),
                deployment.probeImageReference(),
                deployment.controlPlaneBaseUri(),
                deployment.serviceAuthorization(),
                deployment.dnsServer(),
                deployment.egressNetworkIds(),
                deployment.hostAliases(),
                deployment.dnsTimeout(),
                deployment.authorizationTimeout(),
                deployment.revalidationInterval(),
                deployment.connectTimeout(),
                ExecutionRuntimeType.DOCKER);
    }

    /** Stops the proxy without removing it. Reaches through the execution because nothing else may. */
    private void stopProxy(EgressExecution execution) {
        String proxyId = SandboxTestSupport.docker()
                .listContainersCmd()
                .withLabelFilter(Map.of(SandboxLabels.RESOURCE, SandboxLabels.RESOURCE_PROXY))
                .exec()
                .stream()
                .filter(container -> generation.equals(container.getLabels().get(SandboxLabels.GENERATION)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("The execution started no proxy."))
                .getId();
        SandboxTestSupport.docker().stopContainerCmd(proxyId).withTimeout(2).exec();
        awaitStopped(execution);
    }

    private static void awaitStopped(EgressExecution execution) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (!execution.proxyIsRunning()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for the proxy to stop.");
            }
        }
        throw new AssertionError("The proxy never stopped.");
    }
    @Test
    @org.junit.jupiter.api.Timeout(300)
    @DisplayName("revoking authority terminates an egress sandbox, its proxy and its network together")
    void revocationStopsAnEgressExecutionEntirely() throws Exception {
        // CASE F, at the layer where a long-running egress workload can actually be produced.
        //
        // The allowlist path already revalidates an established tunnel and closes it once the assignment is
        // fenced. That is useful and it is NOT workload termination: a sandbox whose egress was cut is still a
        // sandbox running code, and for hostile content the compute is the problem rather than the
        // connectivity. So this asserts the three layers converge -- the workload stops, the proxy stops, and
        // the per-execution network disappears.
        var authority = new java.util.concurrent.atomic.AtomicReference<
                com.kaas.runner.authority.AuthorityDecision>();
        ExecutionAuthority revocable = new ExecutionAuthority() {
            @Override
            public com.kaas.runner.authority.AuthorityDecision lostReason() {
                return authority.get();
            }

            @Override
            public java.time.Duration remainingBudget() {
                return authority.get() == null ? java.time.Duration.ofMinutes(5) : java.time.Duration.ZERO;
            }
        };

        String networkName;
        var outcome = new java.util.concurrent.atomic.AtomicReference<SandboxOutcome>();
        try (EgressExecution execution = executions().start(UUID.randomUUID(), plan())) {
            networkName = execution.profileVersion();
            assertThat(execution.proxyIsRunning()).as("the proxy must be up before it is taken away").isTrue();

            // An hour-long workload ON the egress network, so it is genuinely a live egress execution rather
            // than one that finished before anything was revoked.
            Thread workload = new Thread(() -> outcome.set(execution.launcher().run(
                    new SandboxLaunchRequest(
                            SyntheticProbe.SLEEP, execution.profileVersion(), UUID.randomUUID()),
                    revocable)));
            workload.start();
            waitForSandbox();

            authority.set(com.kaas.runner.authority.AuthorityDecision.RUN_NOT_OWNED);
            workload.join(java.time.Duration.ofSeconds(120).toMillis());

            assertThat(workload.isAlive()).as("the workload must stop, not merely lose its network").isFalse();
            assertThat(outcome.get().failure()).contains(SandboxFailure.SANDBOX_AUTHORITY_LOST);
        }

        // AND THE REST OF THE EXECUTION WENT WITH IT. Asserted after the close, because the proxy and the
        // network belong to the execution rather than to the sandbox -- and a test that only checked the
        // sandbox would pass while an orphaned egress gateway kept running with a service credential.
        assertThat(SandboxTestSupport.docker()
                        .listContainersCmd()
                        .withShowAll(true)
                        .withLabelFilter(java.util.Map.of(SandboxLabels.GENERATION, generation))
                        .exec())
                .as("no sandbox and no proxy survive")
                .isEmpty();
        assertThat(SandboxTestSupport.docker()
                        .listNetworksCmd()
                        .withNameFilter(networkName)
                        .exec())
                .as("no execution network survives")
                .isEmpty();
    }

    private void waitForSandbox() throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
        while (SandboxTestSupport.docker()
                .listContainersCmd()
                .withLabelFilter(java.util.Map.of(SandboxLabels.GENERATION, generation))
                .exec()
                .size()
                < 2) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the sandbox never joined the proxy on the execution network");
            }
            Thread.sleep(100);
        }
    }

}
