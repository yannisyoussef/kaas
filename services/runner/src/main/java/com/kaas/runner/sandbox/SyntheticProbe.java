package com.kaas.runner.sandbox;

import java.util.List;

/**
 * The complete set of workloads this sandbox will run.
 *
 * <p>It is an enumeration rather than a parameter because the alternative — letting a caller name a
 * command — is the whole vulnerability. Each constant carries a fixed argument vector; nothing a caller
 * supplies is ever concatenated into a shell string, because nothing a caller supplies reaches the argument
 * vector at all.
 *
 * <p>Every one of these is an observation or a bounded, self-limiting attempt whose <em>failure</em> is the
 * evidence. None of them tries to exploit the host: a probe that could damage a host would be a worse thing to
 * run than the untrusted content it exists to make safe.
 */
public enum SyntheticProbe {
    /** Reports uid, filesystem writability, capability sets, device surface, and environment. */
    INSPECT(List.of("inspect")),
    /** Attempts public, loopback, private, link-local metadata, and Docker-bridge destinations, plus DNS. */
    NETWORK(List.of("network")),
    /**
     * Starts more background processes than the profile's ceiling permits, so the ceiling is what stops it
     * rather than the loop running out. Every child exits on its own, so this is bounded and finite — it
     * establishes that a limit exists without ever becoming the fork bomb it is testing for.
     */
    PROCESSES(List.of("processes", "200")),
    /** Allocates in one-megabyte steps until something stops it. Being stopped is the expected outcome. */
    MEMORY(List.of("memory", "256")),
    /** Sleeps far past any deadline, so termination cannot depend on the workload cooperating. */
    SLEEP(List.of("sleep", "3600")),
    /** Emits far more output than the collector is allowed to keep. */
    OUTPUT(List.of("output", "200000")),

    // The two below are not security probes. Every value above exists to attack the sandbox's own
    // confinement and prove it holds; these two are the platform's synthetic WORKLOAD, and they are what a
    // run actually executes in this slice. They live in the same enumeration because they run through the
    // same trusted image and the same hardened launcher — the sandbox does not get a second, softer entry
    // point just because what it is running is benign.

    /** The synthetic workload, passing. Fixed identity KAAS_SYNTHETIC_V1; not Karate and never reported as it. */
    WORKLOAD_PASS(List.of("workload", "pass")),
    /**
     * The synthetic workload, failing one scenario.
     *
     * <p>Exists so the FAILED terminal outcome is reachable in a test. Without it that transition would ship
     * having never once executed, and the first genuine test failure in production would be the first time
     * that path ran.
     */
    WORKLOAD_FAIL(List.of("workload", "fail")),

    /**
     * Announces the trusted identity and then reports no verdict.
     *
     * <p>Exists so the runner's "the workload reported no outcome" check can be proven on its own. Without it
     * that check and the identity check are a jointly-covered pair — delete either and the other refuses, so
     * neither is actually tested.
     */
    WORKLOAD_SILENT(List.of("workload", "silent")),

    /** Reports a confident verdict under the wrong identity: something that is not our workload at all. */
    WORKLOAD_IMPOSTER(List.of("workload", "imposter")),

    // The egress probes. Every destination they use arrives in an environment variable the trusted launcher
    // sets from the execution's own policy — none of it is an argument, and none of it is tenant input. The
    // sub-mode is a constant here for the same reason the modes above are: a caller that could name one could
    // name something else.

    /** An ordinary proxied request to a destination the policy names. The success path. */
    EGRESS_ALLOWED(List.of("egress", "allowed")),

    /** A proxied request to a destination the policy does not name. */
    EGRESS_DENIED(List.of("egress", "denied")),

    /** A permitted name whose DNS answer is a private address — the only way to reach the classifier. */
    EGRESS_PRIVATE_ADDRESS(List.of("egress", "private")),

    /** An allowed target that redirects elsewhere, with the client following it into a second authorization. */
    EGRESS_REDIRECT_ESCAPE(List.of("egress", "redirect")),

    /**
     * Raw sockets straight at the addresses the proxy would reach, ignoring every proxy setting.
     *
     * <p>The half of the pair that has to fail. On its own it proves nothing — a workload with no network at
     * all passes it — so it is only ever evidence alongside {@link #EGRESS_ALLOWED} succeeding.
     */
    EGRESS_DIRECT_BYPASS(List.of("egress", "bypass")),

    /** Opens a CONNECT tunnel and holds it, so fencing can be applied underneath and its latency measured. */
    EGRESS_LONG_LIVED_TUNNEL(List.of("egress", "tunnel")),

    /**
     * The synthetic workload for an ALLOWLIST execution.
     *
     * <p>A <em>workload</em> rather than an egress probe, and the distinction is load-bearing. The probes
     * above report {@code egress_*} observations for a security suite to assert on; this one reports the
     * fixed workload identity and a workload outcome, which is what makes an ALLOWLIST run a run — it
     * completes through the ordinary lifecycle with {@code infrastructureOutcome=SUCCEEDED} and
     * {@code testOutcome=PASSED} rather than being a measurement taken outside one.
     *
     * <p>Its scenarios are the pair the whole slice rests on — a destination the policy names is reachable
     * through the proxy, and nothing is reachable without it — plus a destination the policy does not name
     * being refused. A deliberate denial is <em>successful security evidence</em> here, not a failed test:
     * there is no tenant test, and confusing "the policy correctly refused" with "an assertion failed" is the
     * single most misleading thing this workload could report.
     */
    WORKLOAD_EGRESS(List.of("workload", "egress"));

    private final List<String> arguments;

    SyntheticProbe(List<String> arguments) {
        this.arguments = List.copyOf(arguments);
    }

    /** The fixed argument vector. Immutable, server-owned, and never derived from an input. */
    public List<String> arguments() {
        return arguments;
    }
}
