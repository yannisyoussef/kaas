package com.kaas.runner.sandbox;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * What a sandbox did, as the launcher observed it from outside.
 *
 * <p>The observations come from the probe's own stdout and are therefore untrusted data. They are safe to
 * reason about only because everything they could claim is a claim about the sandbox's confinement, and the
 * conclusions drawn from them are always of the form "the sandbox reported it could not do X". A probe that
 * lied would make the gate fail, not pass.
 *
 * <p>{@code outOfMemory} is the exception, and deliberately so: it comes from the daemon rather than the
 * probe. Under the real profile the kernel kills the probe mid-allocation, so the process that would have
 * reported the memory ceiling working is precisely the process the ceiling destroyed. Evidence about a control
 * that terminates the reporter has to come from somewhere the control cannot reach.
 */
public record SandboxOutcome(
        Optional<Integer> exitCode,
        Map<String, String> observations,
        boolean outputTruncated,
        int retainedBytes,
        Duration elapsed,
        boolean outOfMemory,
        Optional<SandboxFailure> failure) {

    public SandboxOutcome {
        observations = Map.copyOf(observations);
    }

    public boolean timedOut() {
        return failure.filter(SandboxFailure.SANDBOX_TIMEOUT::equals).isPresent();
    }

    /**
     * Whether this outcome's observations are a complete view of what the probe reported.
     *
     * <p>A partial view must never be read as evidence. An absent observation and an observation that says a
     * control is off look identical to a check that only asks "is this line missing?", which is how five
     * mandatory controls came to report success on runs that produced nothing at all.
     */
    public boolean evidenceIsComplete() {
        return failure.isEmpty() || timedOut();
    }

    /** The same outcome with a failure recorded, used to fold a cleanup failure into a completed run. */
    public SandboxOutcome withFailure(SandboxFailure cleanupFailure) {
        return new SandboxOutcome(
                exitCode, observations, outputTruncated, retainedBytes, elapsed, outOfMemory,
                Optional.of(cleanupFailure));
    }

    /** An observation the probe reported, or empty when it never got far enough to report one. */
    public Optional<String> observation(String key) {
        return Optional.ofNullable(observations.get(key));
    }

    /**
     * An observation split into its comma-separated members, with blanks dropped.
     *
     * <p>The probe reports whole sets — every mount point, every device node, every network address — because
     * a check that asks after named paths can only find the surfaces somebody thought to name.
     */
    public java.util.Set<String> observedSet(String key) {
        return observation(key)
                .map(value -> java.util.Arrays.stream(value.split(","))
                        .map(String::trim)
                        .filter(member -> !member.isEmpty())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()))
                .orElse(java.util.Set.of());
    }
}
