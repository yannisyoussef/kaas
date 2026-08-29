package com.kaas.api.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.api.execution.domain.SandboxSecurityAttestation;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The gate bridge: what makes an assessment believable, and every way one can fail to be.
 *
 * <p>These are unit tests on purpose. The property under test is a decision about a document, and running it
 * against a container would add a database without adding evidence — while making it expensive enough that the
 * awkward cases get written less thoroughly, which is exactly where the defects live.
 */
class SandboxSecurityAttestationTest {
    private static final String PROFILE = "kaas.sandbox.v1";
    private static final String PROBE = "sha256:" + "a".repeat(64);
    private static final Duration MAX_AGE = Duration.ofHours(24);

    @Test
    void aCompleteRecentPassingAssessmentIsTrusted() {
        // The baseline. Without it every test below would be satisfied by an attestation that was never
        // trustworthy to begin with.
        Instant now = Instant.parse("2026-08-29T12:00:00Z");
        assertThat(attestation(now.minusSeconds(60), allPassing()).reasonItCannotBeTrusted(now, MAX_AGE, PROFILE))
                .isEmpty();
    }

    @Test
    void anAssessmentMissingOneRequiredControlIsRefused() {
        // The case that matters most. A truncated document could otherwise pass by simply omitting the control
        // it failed, which is the difference between "everything passed" and "everything I chose to mention".
        Instant now = Instant.parse("2026-08-29T12:00:00Z");
        Map<String, String> partial = allPassing();
        partial.remove("NETWORK_DENIED");

        assertThat(attestation(now.minusSeconds(60), partial).reasonItCannotBeTrusted(now, MAX_AGE, PROFILE))
                .hasValueSatisfying(reason -> assertThat(reason).contains("assessment does not cover exactly the required mandatory controls"));
    }

    @Test
    void anAssessmentCoveringAnUnknownExtraControlIsRefused() {
        // Equality in the other direction. An assessment produced for a different, possibly weaker, control set
        // is not evidence about this one, and containment would have accepted it.
        Instant now = Instant.parse("2026-08-29T12:00:00Z");
        Map<String, String> extra = allPassing();
        extra.put("SOMETHING_ELSE_ENTIRELY", "PASS");

        assertThat(attestation(now.minusSeconds(60), extra).reasonItCannotBeTrusted(now, MAX_AGE, PROFILE))
                .hasValueSatisfying(reason -> assertThat(reason).contains("assessment does not cover exactly the required mandatory controls"));
    }

    @Test
    void anAssessmentWithAFailingControlIsRefusedAndNamesIt() {
        Instant now = Instant.parse("2026-08-29T12:00:00Z");
        Map<String, String> failing = allPassing();
        failing.put("READ_ONLY_ROOT", "FAIL");

        assertThat(attestation(now.minusSeconds(60), failing).reasonItCannotBeTrusted(now, MAX_AGE, PROFILE))
                .hasValueSatisfying(reason -> assertThat(reason).contains("mandatory controls did not pass: [READ_ONLY_ROOT]"));
    }

    @Test
    void anUnsupportedControlIsNotAPass() {
        // UNSUPPORTED is an honest verdict for deployment-specific hardening, and it is still not a pass for
        // something the platform declared mandatory.
        Instant now = Instant.parse("2026-08-29T12:00:00Z");
        Map<String, String> unsupported = allPassing();
        unsupported.put("MEMORY_LIMIT", "UNSUPPORTED");

        assertThat(attestation(now.minusSeconds(60), unsupported).reasonItCannotBeTrusted(now, MAX_AGE, PROFILE))
                .hasValueSatisfying(reason -> assertThat(reason).contains("mandatory controls did not pass: [MEMORY_LIMIT]"));
    }

    @Test
    void anAssessmentOlderThanTheMaximumAgeIsRefused() {
        // It describes a running host, not a source tree. A host reconfigured since is not described by it.
        Instant now = Instant.parse("2026-08-29T12:00:00Z");
        assertThat(attestation(now.minus(Duration.ofDays(3)), allPassing())
                        .reasonItCannotBeTrusted(now, MAX_AGE, PROFILE))
                .hasValueSatisfying(reason -> assertThat(reason).contains("older than the configured maximum age"));
    }

    @Test
    void anAssessmentDatedInTheFutureIsRefused() {
        // A clock problem or a forgery. Neither is a reason to proceed, and accepting it would let a
        // far-future date defeat the freshness bound entirely.
        Instant now = Instant.parse("2026-08-29T12:00:00Z");
        assertThat(attestation(now.plusSeconds(3600), allPassing()).reasonItCannotBeTrusted(now, MAX_AGE, PROFILE))
                .hasValueSatisfying(reason -> assertThat(reason).contains("not dated in the past"));
    }

    @Test
    void anAssessmentForADifferentSecurityProfileIsRefused() {
        Instant now = Instant.parse("2026-08-29T12:00:00Z");
        assertThat(attestation(now.minusSeconds(60), allPassing())
                        .reasonItCannotBeTrusted(now, MAX_AGE, "kaas.sandbox.v2"))
                .hasValueSatisfying(reason -> assertThat(reason).contains("different sandbox security profile"));
    }

    @Test
    void anAssessmentWhoseVerdictsWereEditedAfterwardsIsRefused() {
        // The digest is recomputed from the content rather than read back, so a document edited after it was
        // produced does not keep the digest that described what it used to say.
        Instant now = Instant.parse("2026-08-29T12:00:00Z");
        var honest = attestation(now.minusSeconds(60), allPassing());
        Map<String, String> tamperedControls = allPassing();
        tamperedControls.put("NETWORK_DENIED", "PASS");
        var tampered = new SandboxSecurityAttestation(
                SandboxSecurityAttestation.SCHEMA_VERSION,
                PROFILE,
                PROBE,
                "docker",
                now.minusSeconds(60),
                tamperedControls,
                // Keeps the digest of a document that said something else.
                "sha256:" + "b".repeat(64));

        assertThat(honest.reasonItCannotBeTrusted(now, MAX_AGE, PROFILE)).isEmpty();
        assertThat(tampered.reasonItCannotBeTrusted(now, MAX_AGE, PROFILE))
                .hasValueSatisfying(reason -> assertThat(reason).contains("digest does not match its content"));
    }

    @Test
    void aProbeImageNamedByTagRatherThanDigestIsRefused() {
        Instant now = Instant.parse("2026-08-29T12:00:00Z");
        var tagged = new SandboxSecurityAttestation(
                SandboxSecurityAttestation.SCHEMA_VERSION,
                PROFILE,
                "busybox:latest",
                "docker",
                now.minusSeconds(60),
                allPassing(),
                "sha256:" + "c".repeat(64));

        assertThat(tagged.reasonItCannotBeTrusted(now, MAX_AGE, PROFILE))
                .hasValueSatisfying(reason -> assertThat(reason).contains("probe image is not identified by a digest"));
    }

    @Test
    void aDifferentSchemaVersionIsRefused() {
        Instant now = Instant.parse("2026-08-29T12:00:00Z");
        var future = new SandboxSecurityAttestation(
                "kaas.sandbox-security-attestation.v2",
                PROFILE,
                PROBE,
                "docker",
                now.minusSeconds(60),
                allPassing(),
                "sha256:" + "d".repeat(64));

        assertThat(future.reasonItCannotBeTrusted(now, MAX_AGE, PROFILE)).hasValueSatisfying(reason -> assertThat(reason).contains("schema version"));
    }

    @Test
    void theDigestDependsOnTheVerdictsRatherThanOnTheirOrder() {
        // Two documents that say the same thing must digest the same, whatever order a parser produced them in;
        // two that say different things must not.
        Instant assessedAt = Instant.parse("2026-08-29T11:00:00Z");
        Map<String, String> reversed = new LinkedHashMap<>();
        allPassing().entrySet().stream()
                .sorted(Map.Entry.<String, String>comparingByKey().reversed())
                .forEach(entry -> reversed.put(entry.getKey(), entry.getValue()));

        assertThat(attestation(assessedAt, reversed).expectedDigest())
                .isEqualTo(attestation(assessedAt, allPassing()).expectedDigest());

        Map<String, String> different = allPassing();
        different.put("PID_LIMIT", "FAIL");
        assertThat(attestation(assessedAt, different).expectedDigest())
                .isNotEqualTo(attestation(assessedAt, allPassing()).expectedDigest());
    }

    /** Builds an attestation whose digest genuinely describes its own content. */
    private static SandboxSecurityAttestation attestation(Instant assessedAt, Map<String, String> controls) {
        var draft = new SandboxSecurityAttestation(
                SandboxSecurityAttestation.SCHEMA_VERSION, PROFILE, PROBE, "docker", assessedAt, controls, "");
        return new SandboxSecurityAttestation(
                draft.schemaVersion(),
                draft.securityProfileVersion(),
                draft.probeImageDigest(),
                draft.runtime(),
                draft.assessedAt(),
                draft.mandatoryControls(),
                draft.expectedDigest());
    }

    private static Map<String, String> allPassing() {
        Map<String, String> controls = new LinkedHashMap<>();
        SandboxSecurityAttestation.REQUIRED_MANDATORY_CONTROLS.forEach(control -> controls.put(control, "PASS"));
        return controls;
    }
}
