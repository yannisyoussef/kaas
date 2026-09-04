package com.kaas.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.api.execution.application.SandboxSecurityAttestationSource;
import com.kaas.api.execution.domain.SandboxSecurityAttestation;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * What the control plane does when it cannot show that this deployment's sandbox confines anything.
 *
 * <p>Separate classes rather than separate test methods, and that separation is the point. The attestation is
 * read once at startup from deployment configuration; making it vary per test would mean making it settable at
 * runtime, which is precisely the property it must not have. A security posture that a running process can be
 * told to change is a security posture an attacker can tell it to change.
 *
 * <p>These start no database. The decision under test happens before anything is read, and the outcome is that
 * nothing is issued.
 */
class ExecutionSecurityGateDependencyTests {

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, classes = SandboxSecurityAttestationSource.class)
    @TestPropertySource(properties = "kaas.execution.sandbox-attestation=")
    class WhenNoAssessmentIsConfigured {
        @Autowired
        private SandboxSecurityAttestationSource source;

        @Test
        void thereIsNoAttestationAndTheReasonSaysSo() {
            // The production default, and the honest one. Nothing has demonstrated that this host's sandbox
            // enforces what the platform requires, so authorization refuses with SECURITY_GATE_UNAVAILABLE
            // rather than proceeding on the assumption that silence means everything is fine.
            assertThat(source.attestation()).isEmpty();
            assertThat(source.unavailableReason())
                    .hasValueSatisfying(reason -> assertThat(reason).contains("no sandbox security assessment"));
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, classes = SandboxSecurityAttestationSource.class)
    @TestPropertySource(properties = "kaas.execution.sandbox-attestation={\"securityGatePassed\":true}")
    class WhenABooleanIsSuppliedInstead {
        @Autowired
        private SandboxSecurityAttestationSource source;

        @Test
        void theShortcutIsRefusedRatherThanRead() {
            // The shape an attacker or a hurried operator would reach for. Strict parsing refuses it outright:
            // an unknown property is a refusal rather than a silent discard, so this cannot be read as an
            // attestation with a misspelled field and everything else defaulted.
            assertThat(source.attestation()).isEmpty();
            assertThat(source.unavailableReason())
                    .hasValueSatisfying(reason -> assertThat(reason).contains("could not be parsed"));
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, classes = SandboxSecurityAttestationSource.class)
    @TestPropertySource(properties = "kaas.execution.sandbox-attestation=not json at all")
    class WhenTheDocumentIsMalformed {
        @Autowired
        private SandboxSecurityAttestationSource source;

        @Test
        void nothingPartiallyParsedIsRetained() {
            // A half-read attestation would be the worst outcome: some controls present, the rest absent, and
            // absence read as coverage. Either the whole document parses or there is no attestation at all.
            assertThat(source.attestation()).isEmpty();
            assertThat(source.unavailableReason()).isPresent();
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, classes = SandboxSecurityAttestationSource.class)
    class WhenTheAssessmentSaysAControlDidNotPass {
        @Autowired
        private SandboxSecurityAttestationSource source;

        /**
         * Generated fresh, not a hardcoded literal.
         *
         * <p>This class used a constant carrying a fixed {@code assessedAt}, and it went red the day that date
         * aged past the 24-hour maximum — caught by the guard below, which is exactly what that guard is for.
         * Moving the date forward would only re-arm the same bomb, so the document is built relative to now and
         * digested on the spot. The guard stays, because it is what would notice if this ever stopped working.
         */
        @org.springframework.test.context.DynamicPropertySource
        static void freshFailingAttestation(
                org.springframework.test.context.DynamicPropertyRegistry registry) {
            registry.add("kaas.execution.sandbox-attestation", () -> failingAttestation(Instant.now()));
        }

        @Test
        void theFixtureIsStillFreshEnoughToBeTestingWhatItsNameSays() {
            // A stale assessment and a failing control both refuse with SECURITY_GATE_FAILED, so if the fixture
            // ever ages out this class silently becomes a freshness test wearing a verdict test's name — and
            // the verdict check could then be deleted without anything going red. Asserting the REASON rather
            // than the enum is what turns that into a failure instead of a quiet pass.
            assertThat(source.attestation()
                            .orElseThrow()
                            .reasonItCannotBeTrusted(
                                    Instant.now(), java.time.Duration.ofHours(24), "kaas.sandbox.v1"))
                    .hasValueSatisfying(reason ->
                            assertThat(reason).contains("mandatory controls did not pass"));
        }

        @Test
        void theDocumentParsesAndIsThenRefusedOnItsContent() {
            // Parsing and trusting are different steps. This document is well-formed, which is exactly why the
            // verdict check has to be separate: a readable attestation that says the sandbox is broken must not
            // be mistaken for evidence that it works.
            //
            // The age bound is deliberately enormous so freshness cannot be what refuses this one. That is the
            // isolation the test above depends on: one of these asserts the control verdict with age ruled out,
            // the other asserts the fixture is fresh enough for that to be the reason.
            var attestation = source.attestation().orElseThrow();
            assertThat(attestation.reasonItCannotBeTrusted(
                            Instant.now(), java.time.Duration.ofDays(3650), "kaas.sandbox.v1"))
                    .hasValueSatisfying(reason ->
                            assertThat(reason).contains("mandatory controls did not pass: [READ_ONLY_ROOT]"));
        }
    }


    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, classes = SandboxSecurityAttestationSource.class)
    class WhenAnOtherwiseCompleteDocumentCarriesAnUnknownProperty {
        @Autowired
        private SandboxSecurityAttestationSource source;

        /**
         * Generated fresh, like the failing-control fixture beside it.
         *
         * <p>This class was DELETED during the synthetic-execution slice, by a regex intended to remove only the
         * stale hardcoded literal it depended on. Nothing noticed: the whole suite stayed green, because the
         * control it covers is covered by nothing else. It is restored here generated rather than pasted, which
         * is what should have happened to the literal in the first place.
         */
        @org.springframework.test.context.DynamicPropertySource
        static void freshDocumentWithAnUnknownProperty(
                org.springframework.test.context.DynamicPropertyRegistry registry) {
            registry.add(
                    "kaas.execution.sandbox-attestation",
                    () -> completeAttestationWithUnknownProperty(Instant.now()));
        }

        @Test
        void theUnknownPropertyIsRefusedRatherThanDiscarded() {
            // Every required field is present, correct, and fresh, so this isolates STRICT PARSING from the
            // missing-field path and from the freshness path. Mutation testing established that it has to:
            // with the property allowlist disabled, every other fixture in this class still failed for some
            // other reason, so the strictness itself was covered by nothing.
            //
            // The property matters more than it looks. A document carrying an extra field is a document written
            // against a different understanding of this contract, and silently discarding it is how a misspelled
            // control name becomes a control that was never assessed and never missed.
            assertThat(source.attestation()).isEmpty();
            assertThat(source.unavailableReason())
                    .hasValueSatisfying(reason -> assertThat(reason).contains("could not be parsed"));
        }

        @Test
        void theSameDocumentWithoutTheUnknownPropertyIsAccepted() {
            // The anti-vacuity half, and the reason the deletion went unnoticed for as long as it did: without
            // this, a fixture refused for ANY unrelated reason would satisfy the test above. Parsing the same
            // document minus the one extra property must succeed.
            var accepted = new SandboxSecurityAttestationSource(completeAttestation(Instant.now()));
            assertThat(accepted.unavailableReason()).isEmpty();
            assertThat(accepted.attestation()).isPresent();
        }
    }

    /** A complete, correctly digested, all-PASS assessment. */
    static String completeAttestation(Instant assessedAt) {
        return attestation(assessedAt, java.util.Map.of(), "");
    }

    /** The same document, plus one top-level property this contract does not define. */
    static String completeAttestationWithUnknownProperty(Instant assessedAt) {
        return attestation(assessedAt, java.util.Map.of(), ",\"securityGatePassed\":true");
    }

    /**
     * A complete, correctly digested assessment in which one mandatory control reports FAIL.
     *
     * <p>Generated relative to {@code assessedAt} rather than written out as a literal. The literal this
     * replaced carried a fixed date, and the day it aged past the 24-hour freshness maximum every test built on
     * it started refusing for the wrong reason — freshness rather than the failing control — which is exactly
     * the confusion the guard in {@code WhenTheAssessmentSaysAControlDidNotPass} exists to catch.
     *
     * <p>Shared, because two test classes need the same document and two copies of it would drift.
     */
    static String failingAttestation(Instant assessedAt) {
        return attestation(assessedAt, java.util.Map.of("READ_ONLY_ROOT", "FAIL"), "");
    }

    /**
     * The one builder every fixture in this class uses.
     *
     * <p>Three near-identical hand-written literals is how the deleted one came to differ from its siblings
     * without anyone noticing. One builder means a fixture can only differ in the way its caller names.
     *
     * @param overrides controls to report as something other than PASS
     * @param extraJson raw JSON appended inside the object, for documents that must carry an undefined property
     */
    private static String attestation(
            Instant assessedAt, java.util.Map<String, String> overrides, String extraJson) {
        Map<String, String> controls = new TreeMap<>();
        SandboxSecurityAttestation.REQUIRED_MANDATORY_CONTROLS.forEach(control -> controls.put(control, "PASS"));
        controls.putAll(overrides);
        String probe = "sha256:" + "a".repeat(64);
        Instant truncated = assessedAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var draft = new SandboxSecurityAttestation(
                SandboxSecurityAttestation.SCHEMA_VERSION,
                "kaas.sandbox.v1", probe, "docker", truncated, controls, "");
        String body = controls.entrySet().stream()
                .map(entry -> "\"" + entry.getKey() + "\":\"" + entry.getValue() + "\"")
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();
        return "{\"schemaVersion\":\"" + SandboxSecurityAttestation.SCHEMA_VERSION
                + "\",\"securityProfileVersion\":\"kaas.sandbox.v1\",\"probeImageDigest\":\"" + probe
                + "\",\"runtime\":\"docker\",\"assessedAt\":\"" + truncated
                + "\",\"mandatoryControls\":{" + body
                + "},\"digest\":\"" + draft.expectedDigest() + "\"" + extraJson + "}";
    }

}
