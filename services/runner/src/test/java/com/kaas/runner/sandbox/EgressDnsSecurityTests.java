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
 * Name resolution as a security decision, measured at a real authoritative DNS server.
 *
 * <p>The queries counted here are counted by the server that answered them, not reported by the code that
 * made them. That distinction is the whole value of this suite: an implementation cannot tell you about a
 * resolution it did not know it performed, and the failure being guarded against — a second lookup happening
 * inside a connect call — is exactly a resolution the application does not know about.
 */
@DisplayName("Execution egress DNS security")
class EgressDnsSecurityTests {

    private EgressTestTopology topology;

    private final String generation = "egress-dns-" + UUID.randomUUID();

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
    @DisplayName("a permitted name resolving into private space is refused after resolution, not before")
    void aPermittedNameResolvingPrivatelyIsRefused() {
        // The only shape that reaches the address classifier at all. A name the policy refuses is stopped
        // before anything is resolved, so a test using one would prove nothing about addresses — it would
        // pass with the classifier deleted.
        topology.authorization.allowOnly(EgressTestTopology.PRIVATE_HOST + ":80/HTTP");

        Map<String, String> observed = run(SyntheticProbe.EGRESS_PRIVATE_ADDRESS);

        assertThat(observed).containsEntry("egress_private_status", "403");
        assertThat(observed).containsEntry("egress_private_reason", "ADDRESS_NOT_GLOBAL");
        // Policy said yes and the address said no, which is the ordering being asserted: the destination was
        // authorized, then resolved, then refused on what came back.
        assertThat(topology.authorization.received())
                .anySatisfy(request -> assertThat(request.destination())
                        .isEqualTo(EgressTestTopology.PRIVATE_HOST + ":80/HTTP"));
        assertThat(topology.dns.queries())
                .anySatisfy(query -> assertThat(query).startsWith(EgressTestTopology.PRIVATE_HOST));
    }

    @Test
    @DisplayName("cloud instance metadata is refused through a name a tenant was allowed to use")
    void metadataBehindAnAllowedNameIsRefused() {
        // The realistic attack: the tenant allowlists a name they control, and points it at the metadata
        // service. Nothing about the name is suspicious, so only the resolved address can catch it.
        topology.dns.answering(EgressTestTopology.PRIVATE_HOST, "169.254.169.254");
        topology.authorization.allowOnly(EgressTestTopology.PRIVATE_HOST + ":80/HTTP");

        Map<String, String> observed = run(SyntheticProbe.EGRESS_PRIVATE_ADDRESS);

        assertThat(observed).containsEntry("egress_private_status", "403");
        assertThat(observed).containsEntry("egress_private_reason", "ADDRESS_NOT_GLOBAL");
    }

    @Test
    @DisplayName("one proxied request causes exactly one resolution, counted by the server that answered it")
    void oneRequestResolvesOnce() {
        topology.dns.resetCounters();

        Map<String, String> observed = run(SyntheticProbe.EGRESS_ALLOWED);

        assertThat(observed).containsEntry("egress_allowed_status", "200");
        assertThat(observed).containsEntry("egress_allowed_body", "present");
        assertThat(topology.dns.queryCount()).isEqualTo(1);
        assertThat(topology.dns.queries()).containsExactly(EgressTestTopology.ALLOWED_HOST + "./A");
    }

    @Test
    @DisplayName("an answer that changes between requests is classified afresh each time")
    void eachRequestIsClassifiedIndependently() {
        // Rebinding at the protocol level, against a real resolver. The name answers safely, then with
        // loopback, then safely again. Each request resolves for itself and classifies what it got, so the
        // middle one is refused while its neighbours succeed — neither inheriting a previous verdict nor
        // being retroactively condemned by a later one.
        topology.dns.answeringInTurn(
                EgressTestTopology.ALLOWED_HOST, topology.targetAddress, "127.0.0.1", topology.targetAddress);
        topology.dns.resetCounters();

        assertThat(run(SyntheticProbe.EGRESS_ALLOWED)).containsEntry("egress_allowed_status", "200");
        Map<String, String> rebound = run(SyntheticProbe.EGRESS_ALLOWED);
        assertThat(rebound).containsEntry("egress_allowed_status", "403");
        assertThat(run(SyntheticProbe.EGRESS_ALLOWED)).containsEntry("egress_allowed_status", "200");

        assertThat(topology.dns.queryCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("the sandbox cannot resolve names for itself, so the proxy's decision is the only one")
    void theSandboxHasNoResolverOfItsOwn() {
        // A workload that could resolve independently would learn addresses without any proxy decision being
        // involved. That is a channel before anything is even connected to, and it is also how a workload
        // would discover what to aim a raw socket at.
        Map<String, String> observed = run(SyntheticProbe.EGRESS_DIRECT_BYPASS);
        assertThat(observed).containsEntry("egress_direct_dns", "unresolvable");
    }
}
