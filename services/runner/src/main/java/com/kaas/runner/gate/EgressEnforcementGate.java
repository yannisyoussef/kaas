package com.kaas.runner.gate;

import com.github.dockerjava.api.DockerClient;
import com.kaas.runner.gate.SecurityCheck.Enforcement;
import com.kaas.runner.gate.SecurityCheck.Verdict;
import com.kaas.runner.sandbox.DockerSandboxLauncher;
import com.kaas.runner.sandbox.EgressProxy;
import com.kaas.runner.sandbox.EgressProxyImage;
import com.kaas.runner.sandbox.EgressProxyProfile;
import com.kaas.runner.sandbox.ExecutionNetwork;
import com.kaas.runner.sandbox.SandboxLaunchRequest;
import com.kaas.runner.sandbox.SandboxOutcome;
import com.kaas.runner.sandbox.SandboxSecurityProfile;
import com.kaas.runner.sandbox.SyntheticProbe;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Asks whether <em>this host</em> can actually enforce a destination allowlist.
 *
 * <h2>Why this is measured per deployment rather than assumed from the source tree</h2>
 *
 * <p>Everything the allowlist rests on is a property of the machine, not of the code: whether the container
 * runtime creates an internal network that is genuinely internal, whether the proxy image can be built from
 * the repository at all, whether a container comes up on that network, and whether a sandbox placed there is
 * left with no route of its own. A build with a perfectly correct proxy running on a host whose runtime
 * behaves differently would enforce nothing, and would have no way to notice.
 *
 * <p>So the control plane refuses an ALLOWLIST execution unless the deployment's assessment carries these
 * five controls passing. An operator cannot supply them with a flag; they are produced by doing the thing and
 * observing the result.
 *
 * <h2>What is deliberately not asserted here</h2>
 *
 * <p>Whether the proxy honours a control-plane decision. Assessing that would require this gate to hold a
 * control plane, and this module's build fails if it acquires one — the guard that lets it hold a Docker
 * client in the first place. That property belongs to the code rather than to the machine, so it is proven by
 * the egress test suites and the full-pipeline test, which run everywhere the code does.
 *
 * <p>What <em>is</em> asserted about authorization is the fail-closed direction, and it is asserted the honest
 * way: the proxy is started pointing at a control plane that does not exist, and must refuse. A proxy that
 * carried traffic when it could not ask anyone would be the single worst failure in this design, and it is
 * exactly the one that a healthy environment never exercises.
 */
public final class EgressEnforcementGate {

    private final DockerClient docker;

    private final Path proxyImageContext;

    private final String probeImage;

    private final String generation;

    /**
     * @param probeImage the trusted probe image, already built and content-addressed. Passed in rather than
     *     built here because the caller has already built it for the sandbox assessment, and building it twice
     *     would mean two images could differ — the evidence would then describe one of them and not say which.
     */
    public EgressEnforcementGate(
            DockerClient docker, Path proxyImageContext, String probeImage, String generation) {
        this.docker = docker;
        this.proxyImageContext = proxyImageContext;
        this.probeImage = probeImage;
        this.generation = generation;
    }

    /**
     * Builds a real network, a real proxy, and a real sandbox, and reports what it observed.
     *
     * <p>Every failure produces a FAIL verdict rather than an exception. An assessment that throws is an
     * assessment that produces nothing, and "nothing" is what an operator would then have to interpret — the
     * control plane's fail-closed reading of an absent control does the right thing, but a named failure with
     * evidence is what someone can act on.
     */
    public EgressEnforcementAssessment assess() {
        List<SecurityCheck> checks = new ArrayList<>();
        UUID correlationId = UUID.randomUUID();

        String imageReference;
        try {
            imageReference = EgressProxyImage.build(docker, proxyImageContext);
        } catch (RuntimeException cannotBuild) {
            // Without an image nothing below can be attempted, so every remaining control is reported failed
            // rather than omitted. An omitted control reads as "not covered", and the control plane's exact
            // coverage rule would refuse anyway — but it would refuse without saying why.
            // No image, so nothing below can be attempted and NOTHING was demonstrated with an image.
            // Reported as an assessment with no image reference rather than one naming an image that does not
            // exist: the attestation binds the image it observed, and there was none.
            return new EgressEnforcementAssessment(
                    Optional.empty(),
                    allFailed("the egress proxy image could not be built from the repository context"));
        }
        checks.add(check(
                "EGRESS_PROXY_IMAGE_PINNED",
                SandboxSecurityProfile.isContentAddressedReference(imageReference),
                // The digest itself is deliberately not the evidence string. It identifies a build artifact,
                // which is not sensitive, but the evidence field travels into an attestation document that
                // operators paste around, and a fixed-shape statement is what a reader actually needs.
                "image identity is content-addressed"));

        ExecutionNetwork network = null;
        EgressProxy proxy = null;
        try {
            try {
                network = ExecutionNetwork.create(docker, generation, correlationId);
                // ExecutionNetwork.create verifies the internal flag WITH THE DAEMON and destroys the network
                // if it is not set, so reaching this line is the observation.
                checks.add(check("EGRESS_NETWORK_INTERNAL", true, "network created and verified internal"));
            } catch (RuntimeException notInternal) {
                checks.add(check("EGRESS_NETWORK_INTERNAL", false, "an internal network could not be created"));
                return new EgressEnforcementAssessment(
                        Optional.of(imageReference), withRemainingFailed(checks, "no isolated network"));
            }

            try {
                proxy = EgressProxy.start(
                        docker,
                        EgressProxyProfile.version1(imageReference, unreachableControlPlane()),
                        generation,
                        correlationId,
                        network,
                        List.of(),
                        List.of());
                checks.add(check("EGRESS_PROXY_READY", true, "proxy started and reported itself serving"));
            } catch (RuntimeException cannotStart) {
                checks.add(check("EGRESS_PROXY_READY", false, "the proxy did not become ready"));
                return new EgressEnforcementAssessment(
                        Optional.of(imageReference), withRemainingFailed(checks, "no proxy"));
            }

            checks.add(noDirectRoute(network));
            checks.add(failsClosed(network, proxy));
            return new EgressEnforcementAssessment(Optional.of(imageReference), List.copyOf(checks));
        } finally {
            if (proxy != null) {
                proxy.close();
            }
            if (network != null) {
                network.close();
            }
        }
    }

    /**
     * A sandbox on the execution network reaches nothing on its own.
     *
     * <p>Run with the proxy present, because that is the configuration an execution actually uses — proving
     * isolation only on an empty network would prove it for a topology nobody runs.
     */
    private SecurityCheck noDirectRoute(ExecutionNetwork network) {
        SandboxSecurityProfile profile = SandboxSecurityProfile.version1OnNetwork(
                probeImage, network.name(), Map.of());
        return noDirectRouteFrom(new DockerSandboxLauncher(docker, profile, generation)
                .run(new SandboxLaunchRequest(
                        SyntheticProbe.EGRESS_DIRECT_BYPASS, profile.version(), UUID.randomUUID())));
    }

    /**
     * The verdict, separated from the act of obtaining the evidence.
     *
     * <p>Split out so it can be driven with evidence a healthy host never produces. On a working machine every
     * check here passes, which means a check replaced by {@code return true} looks identical to the real one —
     * three such mutations survived the suite that only ever ran the gate against a healthy daemon. Feeding
     * this method a failing observation is the only way to tell the two apart.
     */
    static SecurityCheck noDirectRouteFrom(SandboxOutcome outcome) {
        if (!outcome.evidenceIsComplete()
                || !outcome.observation("probe_tooling").map("present"::equals).orElse(false)) {
            // A missing applet and a denied operation are indistinguishable at the exit code, so unusable
            // evidence must never be read as a pass.
            return check("EGRESS_NO_DIRECT_ROUTE", false, "evidence was not usable");
        }
        // Enumerated rather than asked about one named target. A surface nobody thought to name is exactly the
        // one that would still be reachable.
        List<String> reachable = new ArrayList<>();
        outcome.observations().forEach((key, value) -> {
            if (key.startsWith("egress_direct_") && ("reachable".equals(value) || "resolvable".equals(value))) {
                reachable.add(key);
            }
        });
        return check(
                "EGRESS_NO_DIRECT_ROUTE",
                reachable.isEmpty(),
                reachable.isEmpty() ? "no destination reachable without the proxy" : "reachable: " + reachable);
    }

    /**
     * The proxy refuses when it cannot authorize.
     *
     * <p>It was started pointing at a control plane that does not exist, so the only correct behaviour is to
     * refuse everything. A request with no credential must be refused before any lookup, and a request with
     * one must be refused because the authority cannot be reached — never carried on the strength of the
     * token's own shape.
     */
    private SecurityCheck failsClosed(ExecutionNetwork network, EgressProxy proxy) {
        // Asked from a container on the execution network, because that is the only place the proxy is
        // reachable from — the whole point of the topology is that this host cannot reach it directly.
        Map<String, String> environment = new HashMap<>();
        environment.put("KAAS_EGRESS_PROXY_HOST", proxy.addressOn(network.networkId()));
        environment.put("KAAS_EGRESS_PROXY_PORT", String.valueOf(EgressProxy.LISTEN_PORT));
        environment.put("KAAS_EGRESS_CAPABILITY", "kaas_egr_gate_probe_not_a_real_capability");
        environment.put("KAAS_EGRESS_ALLOWED_HOST", "gate-probe.invalid.example");
        environment.put("KAAS_EGRESS_ALLOWED_PORT", "80");

        SandboxSecurityProfile profile = SandboxSecurityProfile.version1OnNetwork(
                probeImage, network.name(), environment);
        SandboxOutcome outcome = new DockerSandboxLauncher(docker, profile, generation)
                .run(new SandboxLaunchRequest(SyntheticProbe.EGRESS_ALLOWED, profile.version(), UUID.randomUUID()));

        return failsClosedFrom(outcome);
    }

    /** The verdict, separated from the act of obtaining it, for the same reason as above. */
    static SecurityCheck failsClosedFrom(SandboxOutcome outcome) {
        String status = outcome.observation("egress_allowed_status").orElse("unreported");
        String body = outcome.observation("egress_allowed_body").orElse("unreported");
        // 503 is "I could not ask", which is the answer when the control plane does not exist. Anything that
        // carried the request — a 200, or a body from a target — is the failure this control exists to catch.
        boolean refused = "503".equals(status) && "absent".equals(body);
        return check(
                "EGRESS_PROXY_FAILS_CLOSED",
                refused,
                "with no reachable authority the proxy answered " + status);
    }

    /**
     * Configuration for a proxy that is meant to be unable to authorize anything.
     *
     * <p>The control plane address is a name that cannot resolve, so the failure is total rather than
     * dependent on something not listening on a port. The credential is a placeholder that is never sent
     * anywhere real.
     */
    private static Map<String, String> unreachableControlPlane() {
        Map<String, String> environment = new HashMap<>();
        environment.put("KAAS_EGRESS_LISTEN_PORT", String.valueOf(EgressProxy.LISTEN_PORT));
        // Resolvable to nothing, on an internal network with no resolver reachable anyway.
        environment.put("KAAS_EGRESS_DNS_SERVER", "127.0.0.1:53");
        environment.put("KAAS_EGRESS_CONTROL_PLANE", "http://control-plane.invalid");
        environment.put("KAAS_EGRESS_SERVICE_AUTHORIZATION", "Bearer kaas-gate-probe");
        environment.put("KAAS_EGRESS_DNS_TIMEOUT_MS", "2000");
        environment.put("KAAS_EGRESS_AUTHORIZATION_TIMEOUT_MS", "2000");
        environment.put("KAAS_EGRESS_REVALIDATION_INTERVAL_MS", "5000");
        environment.put("KAAS_EGRESS_CONNECT_TIMEOUT_MS", "2000");
        return environment;
    }

    private List<SecurityCheck> allFailed(String evidence) {
        List<SecurityCheck> checks = new ArrayList<>();
        for (String control : List.of(
                "EGRESS_PROXY_IMAGE_PINNED",
                "EGRESS_NETWORK_INTERNAL",
                "EGRESS_PROXY_READY",
                "EGRESS_NO_DIRECT_ROUTE",
                "EGRESS_PROXY_FAILS_CLOSED")) {
            checks.add(check(control, false, evidence));
        }
        return List.copyOf(checks);
    }

    private List<SecurityCheck> withRemainingFailed(List<SecurityCheck> so_far, String evidence) {
        List<SecurityCheck> checks = new ArrayList<>(so_far);
        for (SecurityCheck missing : allFailed(evidence)) {
            if (checks.stream().noneMatch(present -> present.control().equals(missing.control()))) {
                checks.add(missing);
            }
        }
        return List.copyOf(checks);
    }

    private static SecurityCheck check(String control, boolean satisfied, String evidence) {
        return new SecurityCheck(
                control, satisfied ? Verdict.PASS : Verdict.FAIL, Enforcement.MANDATORY, evidence);
    }
}
