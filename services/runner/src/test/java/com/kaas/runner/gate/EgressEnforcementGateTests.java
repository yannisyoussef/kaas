package com.kaas.runner.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.runner.sandbox.EgressCapability;
import com.kaas.runner.sandbox.SandboxTestAccess;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * What the egress enforcement gate actually emits when it is run.
 *
 * <p>{@link MandatoryControlContractTest} compares the shared contract against control names found in the
 * gate's <em>source</em>, which cannot tell a control that is wired into the assessment from one that merely
 * appears in a method nobody calls. Deleting the line that adds {@code EGRESS_PROXY_FAILS_CLOSED} to the
 * result left that test green, because the name is still written inside the method that computes it.
 *
 * <p>So this suite runs the gate against a real daemon and asserts the set it produced. It is the behavioural
 * pin the source scan cannot be.
 */
@DisplayName("Egress enforcement gate")
class EgressEnforcementGateTests {

    private final String generation = "egress-gate-" + UUID.randomUUID();

    @Test
    @Timeout(600)
    @DisplayName("the gate emits exactly the contracted egress controls, all passing on a healthy host")
    void theGateEmitsExactlyTheContractedControls() throws IOException {
        var checks = new EgressEnforcementGate(
                        SandboxTestAccess.docker(),
                        SandboxTestAccess.proxyImageContext(),
                        SandboxTestAccess.probeImage(),
                        generation)
                .assess();

        Set<String> emitted = new LinkedHashSet<>(checks.stream().map(SecurityCheck::control).toList());
        assertThat(emitted)
                .as("the control plane refuses an allowlist unless the assessment covers exactly this set, so "
                        + "a control that is computed but never added is a control the platform will demand "
                        + "and never receive")
                .isEqualTo(contractedEgressControls());
        assertThat(checks)
                .as("a development host running these tests can enforce an allowlist; if it cannot, the "
                        + "evidence here says which control failed rather than leaving it to be guessed")
                .allSatisfy(check -> assertThat(check.verdict())
                        .as("%s: %s", check.control(), check.evidence())
                        .isEqualTo(SecurityCheck.Verdict.PASS));
    }

    @Test
    @Timeout(600)
    @DisplayName("a passing gate is what makes the runner willing to accept an allowlist command")
    void aPassingGateEstablishesTheCapability() {
        EgressCapability capability = EgressCapability.establish(
                SandboxTestAccess.docker(),
                SandboxTestAccess.proxyImageContext(),
                SandboxTestAccess.probeImage(),
                generation);

        assertThat(capability.available()).as("%s", capability.unavailableBecause()).isTrue();
        assertThat(capability.enforceablePolicies()).containsExactlyInAnyOrder("DENY_ALL", "ALLOWLIST");
    }

    @Test
    @Timeout(300)
    @DisplayName("a host that cannot build the proxy image can enforce nothing but DENY_ALL")
    void anUnbuildableProxyLeavesOnlyDenyAll() {
        // The fail-closed direction, and the one a healthy environment never exercises on its own. Every
        // control is reported failed rather than omitted: an omitted control reads as "not covered", which the
        // control plane also refuses — but it refuses without saying why, and an operator needs the why.
        EgressCapability capability = EgressCapability.establish(
                SandboxTestAccess.docker(),
                Path.of("does", "not", "exist"),
                SandboxTestAccess.probeImage(),
                generation);

        assertThat(capability.available()).isFalse();
        assertThat(capability.unavailableBecause()).isNotBlank();
        assertThat(capability.enforceablePolicies()).containsExactly("DENY_ALL");
    }

    @Test
    @DisplayName("an empty assessment is not a passing one")
    void anEmptyAssessmentIsNotAPass() {
        // A filter for blocking checks over an empty list finds nothing, and "nothing was demonstrated" must
        // never be read as "everything passed". This is the same absent-evidence rule the control plane
        // applies to a missing egress block, asserted on the runner's side of it.
        assertThat(EgressCapability.unavailable("nothing ran").available()).isFalse();
    }

    // ---------------------------------------------------------------- red paths
    //
    // Every test above runs the gate against a healthy daemon, where each control passes. That establishes
    // the gate agrees with a working host and nothing more — it cannot distinguish a real check from one
    // replaced by "return true", and three such mutations survived until these were written. What follows
    // drives the verdicts with evidence a healthy host never produces.

    @Test
    @DisplayName("a reachable destination fails the no-direct-route control, whichever destination it is")
    void anyReachableDestinationFailsTheRouteControl() {
        // Enumerated rather than asked about one named target. A check that only looked at a destination it
        // was told to look at would pass while something nobody named was reachable, which is exactly the
        // shape of the surface an attacker finds.
        for (String reachable : new String[] {
            "egress_direct_direct_target", "egress_direct_public", "egress_direct_private",
            "egress_direct_metadata", "egress_direct_daemon"
        }) {
            var observations = new java.util.LinkedHashMap<>(isolatedSandbox());
            observations.put(reachable, "reachable");
            assertThat(EgressEnforcementGate.noDirectRouteFrom(outcome(observations)).verdict())
                    .as("%s reachable", reachable)
                    .isEqualTo(SecurityCheck.Verdict.FAIL);
        }
    }

    @Test
    @DisplayName("a sandbox that can resolve names on its own fails the no-direct-route control")
    void independentResolutionFailsTheRouteControl() {
        // Resolution is a channel before anything is connected to, and it is how a workload would learn what
        // to aim a raw socket at. It is reported with a different word from the reachability probes, so a
        // check that only looked for "reachable" would miss it entirely.
        var observations = new java.util.LinkedHashMap<>(isolatedSandbox());
        observations.put("egress_direct_dns", "resolvable");

        assertThat(EgressEnforcementGate.noDirectRouteFrom(outcome(observations)).verdict())
                .isEqualTo(SecurityCheck.Verdict.FAIL);
    }

    @Test
    @DisplayName("unusable evidence fails the no-direct-route control rather than passing it")
    void unusableEvidenceFailsTheRouteControl() {
        // A missing applet and a denied operation are indistinguishable at the exit code. Absence has to fail
        // closed, because absence is what a sandbox that never started, a daemon that went away, and an
        // output stream that never drained all look like.
        var missingTooling = new java.util.LinkedHashMap<>(isolatedSandbox());
        missingTooling.put("probe_tooling", "missing:nc,");
        assertThat(EgressEnforcementGate.noDirectRouteFrom(outcome(missingTooling)).verdict())
                .isEqualTo(SecurityCheck.Verdict.FAIL);

        assertThat(EgressEnforcementGate.noDirectRouteFrom(new com.kaas.runner.sandbox.SandboxOutcome(
                                java.util.Optional.of(0),
                                isolatedSandbox(),
                                false,
                                0,
                                java.time.Duration.ofSeconds(1),
                                false,
                                java.util.Optional.of(com.kaas.runner.sandbox.SandboxFailure.SANDBOX_OBSERVE_FAILED)))
                        .verdict())
                .isEqualTo(SecurityCheck.Verdict.FAIL);
    }

    @Test
    @DisplayName("an isolated sandbox passes the no-direct-route control, so the check is not simply always red")
    void anIsolatedSandboxPassesTheRouteControl() {
        assertThat(EgressEnforcementGate.noDirectRouteFrom(outcome(isolatedSandbox())).verdict())
                .isEqualTo(SecurityCheck.Verdict.PASS);
    }

    @Test
    @DisplayName("a proxy that carried a request it could not authorize fails the fail-closed control")
    void carryingAnUnauthorizableRequestFailsTheClosedControl() {
        // The single worst failure in this design, and the one a healthy environment never exercises: the
        // control plane is unreachable and the proxy carried the traffic anyway.
        for (String[] carried : new String[][] {
            {"200", "present"}, {"200", "absent"}, {"403", "absent"}, {"unreported", "unreported"}
        }) {
            var observations = new java.util.LinkedHashMap<String, String>();
            observations.put("probe_tooling", "present");
            observations.put("egress_allowed_status", carried[0]);
            observations.put("egress_allowed_body", carried[1]);
            assertThat(EgressEnforcementGate.failsClosedFrom(outcome(observations)).verdict())
                    .as("status %s body %s", carried[0], carried[1])
                    .isEqualTo(SecurityCheck.Verdict.FAIL);
        }
    }

    @Test
    @DisplayName("a proxy that refused with 503 passes the fail-closed control")
    void refusingWithUnavailablePassesTheClosedControl() {
        var observations = new java.util.LinkedHashMap<String, String>();
        observations.put("probe_tooling", "present");
        observations.put("egress_allowed_status", "503");
        observations.put("egress_allowed_body", "absent");

        // The positive half. Without it every assertion above would be satisfied by a control that always
        // failed, and "the proxy fails closed" would be a statement nothing could ever confirm.
        assertThat(EgressEnforcementGate.failsClosedFrom(outcome(observations)).verdict())
                .isEqualTo(SecurityCheck.Verdict.PASS);
    }

    /** A sandbox that reports every destination unreachable and no resolver of its own. */
    private static java.util.Map<String, String> isolatedSandbox() {
        var observations = new java.util.LinkedHashMap<String, String>();
        observations.put("probe_tooling", "present");
        observations.put("egress_direct_direct_target", "unreachable");
        observations.put("egress_direct_public", "unreachable");
        observations.put("egress_direct_private", "unreachable");
        observations.put("egress_direct_metadata", "unreachable");
        observations.put("egress_direct_daemon", "unreachable");
        observations.put("egress_direct_dns", "unresolvable");
        return observations;
    }

    private static com.kaas.runner.sandbox.SandboxOutcome outcome(java.util.Map<String, String> observations) {
        return new com.kaas.runner.sandbox.SandboxOutcome(
                java.util.Optional.of(0),
                observations,
                false,
                0,
                java.time.Duration.ofSeconds(1),
                false,
                java.util.Optional.empty());
    }

    private static Set<String> contractedEgressControls() throws IOException {
        Path contract = Path.of("packages/api-contracts/mandatory-sandbox-controls.json");
        if (!Files.isRegularFile(contract)) {
            contract = Path.of("..", "..", "packages", "api-contracts", "mandatory-sandbox-controls.json");
        }
        String json = Files.readString(contract);
        int open = json.indexOf('[', json.indexOf("\"egressControls\""));
        Matcher matcher = Pattern.compile("\"([A-Z_]{3,})\"").matcher(json.substring(open, json.indexOf(']', open)));
        Set<String> controls = new LinkedHashSet<>();
        while (matcher.find()) {
            controls.add(matcher.group(1));
        }
        assertThat(controls).isNotEmpty();
        return controls;
    }
}
