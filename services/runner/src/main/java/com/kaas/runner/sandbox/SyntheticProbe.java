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
    WORKLOAD_IMPOSTER(List.of("workload", "imposter"));

    private final List<String> arguments;

    SyntheticProbe(List<String> arguments) {
        this.arguments = List.copyOf(arguments);
    }

    /** The fixed argument vector. Immutable, server-owned, and never derived from an input. */
    public List<String> arguments() {
        return arguments;
    }
}
