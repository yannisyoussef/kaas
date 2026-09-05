package com.kaas.runner.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.kaas.runner.gate.EgressEnforcementGate;
import com.kaas.runner.gate.SecurityCheck;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Whether this runner, on this host, can actually apply a destination allowlist.
 *
 * <h2>Established by doing it, not by being told</h2>
 *
 * <p>A configuration flag would be the obvious implementation and it would be worthless: the failure this
 * guards against is a host whose container runtime does not behave the way the code assumes, and no amount of
 * configuration can tell you that. So the capability is established by building the proxy image, creating an
 * internal network, starting a proxy on it, placing a sandbox there, and observing that the sandbox can reach
 * nothing and the proxy refuses what it cannot authorize. If any of that does not happen, ALLOWLIST is not
 * enforceable here and commands carrying it are refused.
 *
 * <p>The check is expensive, so it is done once per runner and its result carried. That is a deliberate
 * trade: the properties it establishes are properties of the host's runtime, which do not change between two
 * executions on the same process. What <em>does</em> change between executions — whether the assignment is
 * still live, whether the destination is still permitted — is revalidated on every single request, by the
 * control plane, which is where per-request state belongs.
 *
 * <h2>Why the runner checks at all when the control plane already did</h2>
 *
 * <p>Because they are checking different things with different information. The control plane knows which
 * policy the run was sealed with and whether the deployment's assessment says egress is enforceable. This
 * knows whether <em>this process, on this machine, right now</em> can instantiate the mechanism. A control
 * plane that authorized an allowlist a worker could not apply would produce a run with egress nobody was
 * constraining, and the worker is the only party in a position to notice.
 */
public record EgressCapability(boolean available, String unavailableBecause, String proxyImageReference) {

    private static final String DENY_ALL = "DENY_ALL";

    private static final String ALLOWLIST = "ALLOWLIST";

    /** Nothing was established, so nothing but DENY_ALL is enforceable. The safe construction. */
    public static EgressCapability unavailable(String because) {
        return new EgressCapability(false, because, null);
    }

    /**
     * Runs the egress enforcement gate and reports what it found.
     *
     * <p>Never throws. A host where this cannot even be attempted is a host where ALLOWLIST is unavailable,
     * which is a fact to be carried rather than an exception to be handled somewhere that might swallow it.
     */
    public static EgressCapability establish(
            DockerClient docker, Path proxyImageContext, String probeImage, String generation) {
        EgressEnforcementGate gate =
                new EgressEnforcementGate(docker, proxyImageContext, probeImage, generation);
        List<SecurityCheck> checks;
        try {
            checks = gate.assess();
        } catch (RuntimeException failed) {
            return unavailable("the egress enforcement gate could not run");
        }
        List<SecurityCheck> blockers = checks.stream().filter(SecurityCheck::blocksRelease).toList();
        if (!blockers.isEmpty()) {
            return unavailable("egress controls did not pass: "
                    + blockers.stream().map(SecurityCheck::control).sorted().toList());
        }
        String proxyImageReference = gate.proxyImageReference();
        if (checks.isEmpty()) {
            // An empty assessment is not a passing one. Nothing was demonstrated, and "nothing was
            // demonstrated" must never read as "everything passed" — which is exactly what a filter over an
            // empty list produces if the emptiness is not checked for.
            return unavailable("the egress enforcement gate produced no evidence");
        }
        // The image the GATE built, carried rather than rebuilt. Executions run the same artifact the
        // assessment was taken from; building it again would mean the evidence describes one image and the
        // executions use another, which on a host where the build is not reproducible is a real difference.
        if (proxyImageReference == null) {
            // Unreachable while the gate reports a passing IMAGE_PINNED control, and refused rather than
            // trusted anyway: an available capability with no image is one whose first execution would fail
            // deep inside provisioning instead of here.
            return unavailable("the egress enforcement gate reported no proxy image");
        }
        return new EgressCapability(true, null, proxyImageReference);
    }

    /**
     * The policy types this runner will accept in a command.
     *
     * <p>DENY_ALL always. A run that wants no network must not be blocked by an unhealthy egress subsystem it
     * does not use, and making it so would increase the attack surface of precisely the runs that were
     * supposed to have none.
     */
    public Set<String> enforceablePolicies() {
        return available ? Set.of(DENY_ALL, ALLOWLIST) : Set.of(DENY_ALL);
    }
}
