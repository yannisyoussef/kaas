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
    OUTPUT(List.of("output", "200000"));

    private final List<String> arguments;

    SyntheticProbe(List<String> arguments) {
        this.arguments = List.copyOf(arguments);
    }

    /** The fixed argument vector. Immutable, server-owned, and never derived from an input. */
    public List<String> arguments() {
        return arguments;
    }
}
