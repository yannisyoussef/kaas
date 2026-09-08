package com.kaas.runner.execution;

import com.kaas.runner.sandbox.SandboxOutcome;
import java.util.Set;

/**
 * What the engine reported, read as untrusted input.
 *
 * <h2>Why this is deliberately tiny</h2>
 *
 * <p>Everything this parses was printed inside a sandbox running arbitrary tenant code. The engine adapter is
 * platform-owned, but it shares a JVM with the tenant's program, so nothing it prints arrives with any more
 * authority than anything the tenant printed itself.
 *
 * <p>So the surface is one key and three values. There is no run identity here, no timing, no provenance, no
 * counts, no paths and no free text — the runner reconstructs all of that from the command and from the
 * control plane, because a field a hostile process can choose is a field the platform does not own.
 *
 * <h2>What tenant code can and cannot do to a result</h2>
 *
 * <p>It CAN influence its own test outcome. That is a property of running arbitrary code, not a defect to be
 * engineered away, and ADR-032 accepted it explicitly: the platform's requirement is that a tenant cannot
 * forge platform authority or affect another tenant, not that a tenant cannot lie about its own assertion.
 *
 * <p>It CANNOT forge a platform field, because none is read from here. And it cannot quietly substitute an
 * outcome either: a stream carrying the result key twice is refused rather than resolved, so printing a
 * verdict and letting the adapter print the real one turns the run into an infrastructure failure instead of
 * a forged pass.
 */
public record EngineOutcome(Verdict verdict, String detail) {

    /** The result key. Versioned so a runner that does not understand a later shape refuses rather than guesses. */
    public static final String PROTOCOL = "kaas.karate-result.v1";

    /** What the adapter may say. A closed set: anything else is malformed, never "probably a failure". */
    public enum Verdict {
        /** The authorized suite ran and passed. */
        PASSED,
        /** The authorized suite ran and something in it failed. A tenant result, not a platform one. */
        FAILED,
        /** The engine could not run the suite. A platform problem, and never reported as a test failure. */
        ENGINE_ERROR,
        /** Nothing usable was reported. The engine died, was killed, or never reached its own reporting. */
        ABSENT,
        /** The stream carried more than one verdict, or a value outside the closed set. */
        MALFORMED
    }

    /**
     * Reads the engine's verdict out of a sandbox outcome.
     *
     * <p>Ordering matters. Duplicates are checked before the value, so a stream that answered twice is
     * malformed regardless of what the answers were — otherwise the last one wins and the first is invisible.
     */
    public static EngineOutcome of(SandboxOutcome sandbox) {
        Set<String> duplicated = sandbox.duplicatedObservations();
        if (duplicated.contains(PROTOCOL)) {
            return new EngineOutcome(
                    Verdict.MALFORMED, "The engine reported more than one result.");
        }
        String reported = sandbox.observations().get(PROTOCOL);
        if (reported == null) {
            // The adapter's last act is to print this. Its absence means the process did not get there:
            // a crash, a kill, an OOM, or a hostile System.exit before the suite finished. None of those is
            // a test outcome, and inferring one from a zero exit status is how "the JVM vanished" becomes
            // "the tests passed".
            return new EngineOutcome(Verdict.ABSENT, "The engine reported no result.");
        }
        return switch (reported) {
            case "PASSED" -> new EngineOutcome(Verdict.PASSED, null);
            case "FAILED" -> new EngineOutcome(Verdict.FAILED, null);
            case "ENGINE_ERROR" -> new EngineOutcome(
                    Verdict.ENGINE_ERROR,
                    // The adapter's own category, which is a closed platform vocabulary, and only when the
                    // adapter said ENGINE_ERROR. A free-text field would be tenant-chosen text in a
                    // control-plane record.
                    categoryOf(sandbox.observations().get("engine_error")));
            default -> new EngineOutcome(Verdict.MALFORMED, "The engine reported an unrecognised result.");
        };
    }

    /** Whether the engine ran the suite at all, whatever the suite then did. */
    public boolean completed() {
        return verdict == Verdict.PASSED || verdict == Verdict.FAILED;
    }

    /**
     * Maps an adapter category onto the closed set this runner will repeat.
     *
     * <p>Not passed through. The adapter is platform-owned but shares a process with tenant code, so a value
     * from it is a value a tenant could have printed — and an unrecognised one becomes a fixed word rather
     * than travelling into a control-plane record.
     */
    private static String categoryOf(String reported) {
        if (reported == null) {
            return "UNSPECIFIED";
        }
        return switch (reported) {
            case "SOURCE_UNREADABLE", "NO_AUTHORIZED_FEATURES", "ENGINE_FAILURE" -> reported;
            default -> "UNSPECIFIED";
        };
    }
}
