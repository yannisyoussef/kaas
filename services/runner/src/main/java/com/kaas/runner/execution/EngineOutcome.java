package com.kaas.runner.execution;

import com.kaas.runner.command.CommandValidator;
import com.kaas.runner.sandbox.ProtocolScanner;
import com.kaas.runner.sandbox.SandboxOutcome;

/**
 * What the engine said, read strictly.
 *
 * <p>The adapter prints four platform lines: which engine it loaded, that it consumed the secret channel, and
 * a verdict (with a category when the verdict is an engine error). They are read from the RAW protocol branch
 * ({@link ProtocolScanner}), before redaction, as counts and closed-vocabulary words — so a tenant secret that
 * happens to equal {@code PASSED} cannot change a verdict, and no line a tenant printed is ever kept verbatim.
 *
 * <p>Every key must appear EXACTLY ONCE. Tenant code runs in the same process and can print any of these lines;
 * it cannot be stopped from doing so. A key seen twice is a stream that answered twice, and the platform refuses
 * it rather than choosing between the answers. That rule, from KAAS-21, now covers the engine's identity and the
 * secret-channel marker as well as the verdict.
 *
 * <h2>Why the identity is enforced here and not only in CI</h2>
 *
 * <p>Until KAAS-22 the runner never looked at {@code kaas.engine}; only tests and the CI gate did. A deployment
 * whose image carried a different engine would have produced results the platform believed. The runner now
 * refuses any verdict from an engine that did not name itself {@code karate} at the exact adjudicated version.
 * Tenant code can print that line too — and printing it a second time makes the identity ambiguous, which is
 * refused, not believed.
 */
public record EngineOutcome(Verdict verdict, String detail) {

    public static final String PROTOCOL = ProtocolScanner.RESULT_KEY;

    /** The only engine identity this runner accepts: the adjudicated version, and nothing else. */
    public static final String EXPECTED_IDENTITY = "karate " + CommandValidator.KARATE_VERSION;

    public enum Verdict {
        PASSED,
        FAILED,
        ENGINE_ERROR,
        ABSENT,
        MALFORMED,
        /** The engine did not name itself as the adjudicated Karate, or named itself twice. */
        UNIDENTIFIED,
        /** The adapter did not confirm, exactly once, that it consumed and closed the secret channel. */
        SECRET_CHANNEL_UNCONFIRMED
    }

    public static EngineOutcome of(SandboxOutcome sandbox) {
        ProtocolScanner.Observed protocol = sandbox.protocol();
        int results = protocol.count(PROTOCOL);
        if (results > 1) {
            return new EngineOutcome(Verdict.MALFORMED, "The engine reported more than one result.");
        }
        if (results == 0) {
            // The adapter's last act is to print this. Its absence means the process did not get there: a
            // crash, a kill, an OOM, or a hostile System.exit before the suite finished. None of those is a test
            // outcome, and inferring one from a zero exit status is how "the JVM vanished" becomes "the tests
            // passed".
            return new EngineOutcome(Verdict.ABSENT, "The engine reported no result.");
        }
        String reported = protocol.single(PROTOCOL);
        if (reported == null) {
            return new EngineOutcome(Verdict.MALFORMED, "The engine reported an unrecognised result.");
        }
        if ("ENGINE_ERROR".equals(reported)) {
            // The adapter's own category, from a closed platform vocabulary. A free-text field would be
            // tenant-chosen text in a control-plane record.
            String category = protocol.single(ProtocolScanner.ENGINE_ERROR_KEY);
            return new EngineOutcome(Verdict.ENGINE_ERROR, category == null ? "UNSPECIFIED" : category);
        }
        if (!EXPECTED_IDENTITY.equals(protocol.single(ProtocolScanner.ENGINE_KEY))) {
            return new EngineOutcome(
                    Verdict.UNIDENTIFIED, "The engine did not identify itself as " + EXPECTED_IDENTITY + ".");
        }
        if (!"CONSUMED".equals(protocol.single(ProtocolScanner.SECRET_CHANNEL_KEY))) {
            return new EngineOutcome(
                    Verdict.SECRET_CHANNEL_UNCONFIRMED,
                    "The engine did not confirm it consumed and closed its secret channel.");
        }
        return switch (reported) {
            case "PASSED" -> new EngineOutcome(Verdict.PASSED, null);
            case "FAILED" -> new EngineOutcome(Verdict.FAILED, null);
            default -> new EngineOutcome(Verdict.MALFORMED, "The engine reported an unrecognised result.");
        };
    }

    public boolean completed() {
        return verdict == Verdict.PASSED || verdict == Verdict.FAILED;
    }
}
