package com.kaas.runner.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Assignment-scoped authority, including the part that is easy to get wrong: what happens to traffic that is
 * already flowing when the authority goes away.
 */
@DisplayName("Execution egress authorization")
class EgressAuthorizationTests {

    private EgressTestTopology topology;

    private final String generation = "egress-auth-" + UUID.randomUUID();

    @BeforeEach
    void build() throws IOException {
        topology = new EgressTestTopology(generation);
    }

    @AfterEach
    void tear() {
        topology.close();
    }

    private Map<String, String> run(SyntheticProbe probe) {
        return run(probe, topology.sandboxEnvironment()).observations();
    }

    private SandboxOutcome run(SyntheticProbe probe, Map<String, String> environment) {
        SandboxSecurityProfile profile = SandboxSecurityProfile.version1OnNetwork(
                SandboxTestSupport.probeImage(), topology.executionNetwork.name(), environment);
        SandboxOutcome outcome = SandboxTestSupport.launcher(profile, generation)
                .run(new SandboxLaunchRequest(probe, profile.version(), UUID.randomUUID()));
        assertThat(outcome.failure()).as("the sandbox itself must run cleanly: %s", outcome).isEmpty();
        return outcome;
    }

    @Test
    @DisplayName("a destination outside the policy is refused, with the reason said out loud")
    void aDestinationOutsideThePolicyIsRefused() {
        Map<String, String> observed = run(SyntheticProbe.EGRESS_DENIED);

        assertThat(observed).containsEntry("egress_denied_status", "403");
        assertThat(observed).containsEntry("egress_denied_reason", "DESTINATION_NOT_ALLOWED");
        // The denied name resolves to the very same container the allowed name does. Being refused therefore
        // cannot be an accident of reachability: the only difference between the two is policy.
        assertThat(topology.dns.queries())
                .as("a refused destination is never resolved, so the proxy is no lookup oracle")
                .noneSatisfy(query -> assertThat(query).startsWith(EgressTestTopology.DENIED_HOST));
    }

    @Test
    @DisplayName("a redirect out of the policy is stopped by the second request, not by inspecting the first")
    void aRedirectOutOfThePolicyIsStopped() {
        Map<String, String> observed = run(SyntheticProbe.EGRESS_REDIRECT_ESCAPE);

        // The proxy hands the 3xx back untouched. It does not follow redirects on anyone's behalf and it does
        // not inspect the Location header — claiming otherwise would be documenting a control that does not
        // exist. What it does is authorize every request it receives, and the client following the redirect
        // produces one of those.
        assertThat(observed).containsEntry("egress_redirect_first_status", "302");
        assertThat(observed).containsEntry("egress_redirect_location", "present");
        assertThat(observed).containsEntry("egress_redirect_second_status", "403");
        assertThat(observed).containsEntry("egress_redirect_second_reason", "DESTINATION_NOT_ALLOWED");
    }

    @Test
    @DisplayName("an authority that cannot answer refuses the request rather than allowing it")
    void anUnavailableAuthorityRefuses() {
        topology.authorization.stopAnswering();

        Map<String, String> observed = run(SyntheticProbe.EGRESS_ALLOWED);

        // 503 rather than 403, and rather than a success. Availability loss is preferable to carrying traffic
        // on an authority nobody can confirm, and the evidence must be able to tell the two apart.
        assertThat(observed).containsEntry("egress_allowed_status", "503");
        assertThat(observed).containsEntry("egress_allowed_body", "absent");
    }

    @Test
    @DisplayName("an established tunnel stops carrying traffic within the documented bound after fencing")
    void anEstablishedTunnelIsFencedWithinTheDocumentedBound() throws Exception {
        // The failure this exists to prevent: authorize at CONNECT, then relay for as long as the workload
        // likes. No further HTTP request crosses an established tunnel, so without a timer nothing would ever
        // cause the authority to be checked again, and an assignment fenced a second after CONNECT would
        // leave a working channel open indefinitely.
        Map<String, String> environment = new HashMap<>(topology.sandboxEnvironment());
        environment.put("KAAS_EGRESS_ALLOWED_PORT", String.valueOf(EgressTestTopology.TARGET_HOLD_PORT));
        environment.put("KAAS_EGRESS_TUNNEL_SECONDS", "20");
        topology.authorization.allowOnly(
                EgressTestTopology.ALLOWED_HOST + ":" + EgressTestTopology.TARGET_HOLD_PORT + "/HTTPS");

        ExecutorService background = Executors.newSingleThreadExecutor();
        try {
            Future<SandboxOutcome> running =
                    background.submit(() -> run(SyntheticProbe.EGRESS_LONG_LIVED_TUNNEL, environment));

            // Wait for the tunnel to be established AND revalidated at least once, so what is measured below
            // is a live tunnel being cut rather than one that was never authorized.
            await(Duration.ofSeconds(20), () -> topology.authorization.received().size() >= 2);
            int beforeFencing = topology.authorization.received().size();
            long fencedAt = System.nanoTime();

            topology.authorization.fence();

            SandboxOutcome outcome = running.get(60, TimeUnit.SECONDS);
            long latencyMs = (System.nanoTime() - fencedAt) / 1_000_000;
            Map<String, String> observed = outcome.observations();

            assertThat(observed).containsEntry("egress_tunnel_open_status", "200");
            // The tunnel ended long before the workload was finished with it, which is the difference between
            // "revoked" and "the client hung up".
            assertThat(Integer.parseInt(observed.get("egress_tunnel_held_seconds")))
                    .as("the tunnel must be cut, not run to its own end")
                    .isLessThan(15);
            // The latency measured here includes the container noticing and exiting, so it is an upper bound
            // on the revocation itself and generous in the safe direction. The claim is the documented
            // polling bound, not immediacy.
            assertThat(latencyMs)
                    .as("assignment fenced at T, tunnel unusable by T + %dms",
                            EgressTestTopology.MAXIMUM_REVOCATION_LATENCY_MS)
                    .isLessThan(EgressTestTopology.MAXIMUM_REVOCATION_LATENCY_MS + 8_000);
            // Revalidation is what noticed. More questions were asked after the tunnel opened than the single
            // one that authorized it, so the timer genuinely ran.
            assertThat(beforeFencing).isGreaterThanOrEqualTo(2);
        } finally {
            background.shutdownNow();
        }
    }

    private static void await(Duration limit, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + limit.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("The condition never became true within " + limit);
    }
}
