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
        /**
         * Keys the sandbox reported more than once.
         *
         * <p>A map keeps the last value and forgets there was another, which is fine for a diagnostic and
         * wrong for a result. Tenant code runs in this sandbox: a workload that announces an outcome and then
         * lets the platform adapter announce the real one would leave a map holding one plausible answer with
         * no trace of the other. This is what lets a caller refuse a stream that answered twice rather than
         * pick whichever it preferred.
         */
        java.util.Set<String> duplicatedObservations,
        boolean outputTruncated,
        int retainedBytes,
        Duration elapsed,
        boolean outOfMemory,
        Optional<SandboxFailure> failure,
        /**
         * The result protocol as the RAW stream carried it: counts per platform key, and the vocabulary word
         * each carried. Read before redaction on a separate branch, so a secret equal to a protocol word
         * cannot change a verdict. See {@link ProtocolScanner}.
         */
        ProtocolScanner.Observed protocol,
        /** What the redactor found and what it kept. See {@link Redaction}. */
        Redaction redaction,
        /**
         * The runtime the daemon reports it assigned this sandbox, read back before the workload started, or
         * null if no container was created. The launcher refuses to start anything under a runtime other than
         * the profile's, so this is evidence of what ran rather than of what was requested.
         */
        String assignedRuntime) {

    public SandboxOutcome {
        observations = Map.copyOf(observations);
        duplicatedObservations = java.util.Set.copyOf(duplicatedObservations);
        protocol = protocol == null ? ProtocolScanner.Observed.NONE : protocol;
        redaction = redaction == null ? Redaction.NONE : redaction;
    }

    /** The form every probe-only sandbox produced before KAAS-22, with no protocol and nothing redacted. */
    public SandboxOutcome(
            Optional<Integer> exitCode,
            Map<String, String> observations,
            java.util.Set<String> duplicatedObservations,
            boolean outputTruncated,
            int retainedBytes,
            Duration elapsed,
            boolean outOfMemory,
            Optional<SandboxFailure> failure) {
        this(exitCode, observations, duplicatedObservations, outputTruncated, retainedBytes, elapsed, outOfMemory,
                failure, ProtocolScanner.Observed.NONE, Redaction.NONE, null);
    }

    /** The form a collected sandbox produces, before the launcher records which runtime it was assigned. */
    public SandboxOutcome(
            Optional<Integer> exitCode,
            Map<String, String> observations,
            java.util.Set<String> duplicatedObservations,
            boolean outputTruncated,
            int retainedBytes,
            Duration elapsed,
            boolean outOfMemory,
            Optional<SandboxFailure> failure,
            ProtocolScanner.Observed protocol,
            Redaction redaction) {
        this(exitCode, observations, duplicatedObservations, outputTruncated, retainedBytes, elapsed, outOfMemory,
                failure, protocol, redaction, null);
    }

    /** This outcome, naming the runtime the daemon assigned. */
    public SandboxOutcome withAssignedRuntime(String runtime) {
        return new SandboxOutcome(
                exitCode, observations, duplicatedObservations, outputTruncated, retainedBytes, elapsed,
                outOfMemory, failure, protocol, redaction, runtime);
    }

    /**
     * The trusted redactor's own account of one execution.
     *
     * <p>{@code stdoutMatches} and {@code stderrMatches} count raw occurrences of a registered secret the
     * redactor saw and replaced, per stream. They are the instrumentation that makes a redaction test
     * non-vacuous: a transcript with no secret in it proves nothing unless the redactor also saw one arrive.
     *
     * <p>The transcripts are the ONLY form in which a tenant's output leaves the collector: bounded, redacted
     * before the ceiling, decoded, and stripped of control characters. They are platform-owned output. No raw
     * byte of what the sandbox printed survives anywhere else in this object.
     */
    public record Redaction(long stdoutMatches, long stderrMatches, String stdout, String stderr) {
        public static final Redaction NONE = new Redaction(0, 0, "", "");

        @Override
        public String toString() {
            return "Redaction[stdoutMatches=" + stdoutMatches + ", stderrMatches=" + stderrMatches + "]";
        }
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
                exitCode, observations, duplicatedObservations, outputTruncated, retainedBytes, elapsed,
                outOfMemory, Optional.of(cleanupFailure), protocol, redaction, assignedRuntime);
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
