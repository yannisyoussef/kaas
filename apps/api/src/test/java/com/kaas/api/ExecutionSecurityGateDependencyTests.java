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
    @TestPropertySource(properties = "kaas.execution.sandbox-attestation=" + FAILING)
    class WhenTheAssessmentSaysAControlDidNotPass {
        @Autowired
        private SandboxSecurityAttestationSource source;

        @Test
        void theFixtureIsStillFreshEnoughToBeTestingWhatItsNameSays() {
            // The FAILING literal carries a hardcoded assessedAt, and a stale assessment and a failing control
            // both refuse with SECURITY_GATE_FAILED — so once the fixture ages past attestation-max-age this
            // class silently becomes a freshness test wearing a verdict test's name, and the verdict check
            // could be deleted without anything going red. Asserting the REASON rather than the enum means
            // ageing out turns this red instead of quietly green.
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
            var attestation = source.attestation().orElseThrow();
            assertThat(attestation.reasonItCannotBeTrusted(
                            Instant.parse("2026-08-29T12:00:00Z"),
                            java.time.Duration.ofDays(3650),
                            "kaas.sandbox.v1"))
                    .hasValueSatisfying(reason ->
                            assertThat(reason).contains("mandatory controls did not pass: [READ_ONLY_ROOT]"));
        }
    }


    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, classes = SandboxSecurityAttestationSource.class)
    @TestPropertySource(properties = "kaas.execution.sandbox-attestation=" + COMPLETE_WITH_UNKNOWN_PROPERTY)
    class WhenAnOtherwiseCompleteDocumentCarriesAnUnknownProperty {
        @Autowired
        private SandboxSecurityAttestationSource source;

        @Test
        void theUnknownPropertyIsRefusedRatherThanDiscarded() {
            // Every required field is present and correct, so this isolates strict parsing from the missing-field
            // path. Mutation testing found that necessary: with the property allowlist disabled, the earlier
            // shortcut fixture still failed on an absent schemaVersion, so the strictness itself was covered by
            // nothing.
            //
            // The property here is the one that matters most. A document carrying an extra field is a document
            // written against a different understanding of this contract, and silently discarding it is how a
            // misspelled control name becomes a control that was never assessed and never missed.
            assertThat(source.attestation()).isEmpty();
            assertThat(source.unavailableReason())
                    .hasValueSatisfying(reason -> assertThat(reason).contains("could not be parsed"));
        }
    }

    /** A complete, correctly digested assessment that also carries a property this contract does not define. */
    static final String COMPLETE_WITH_UNKNOWN_PROPERTY =
            "{\"schemaVersion\":\"kaas.sandbox-security-attestation.v1\",\"securityProfileVersion\":\"kaas.sandbox.v1\",\"probeImageDigest\":\"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"runtime\":\"docker\",\"assessedAt\":\"2026-08-29T11:00:00Z\",\"mandatoryControls\":{\"CAPABILITIES_DROPPED\":\"PASS\",\"KERNEL_PATHS_MASKED\":\"PASS\",\"MEMORY_LIMIT\":\"PASS\",\"MINIMAL_ENVIRONMENT\":\"PASS\",\"NETWORK_DENIED\":\"PASS\",\"NON_ROOT_GID\":\"PASS\",\"NON_ROOT_UID\":\"PASS\",\"NO_DOCKER_SOCKET\":\"PASS\",\"NO_HOST_DEVICES\":\"PASS\",\"NO_HOST_MOUNTS\":\"PASS\",\"NO_NEW_PRIVILEGES\":\"PASS\",\"OUTPUT_BOUNDED\":\"PASS\",\"PID_LIMIT\":\"PASS\",\"READ_ONLY_ROOT\":\"FAIL\",\"WALL_CLOCK_TIMEOUT\":\"PASS\",\"WRITABLE_TMPFS\":\"PASS\"},\"digest\":\"sha256:93b58ff79dad0044efba0caeeae02f52423e7afae94a8d996f32d81fecc5ead3\",\"securityGatePassed\":true}";

    /**
     * A well-formed assessment that reports one mandatory control as failing.
     *
     * <p>A literal rather than a computed value, because an annotation argument must be a compile-time
     * constant. Its digest genuinely describes its content — {@code SandboxSecurityAttestationTest} proves the
     * digest rule independently, so a wrong constant here would surface as this document being refused for the
     * wrong reason rather than passing silently.
     */
    static final String FAILING =
            "{\"schemaVersion\":\"kaas.sandbox-security-attestation.v1\",\"securityProfileVersion\":\"kaas.sandbox.v1\",\"probeImageDigest\":\"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"runtime\":\"docker\",\"assessedAt\":\"2026-08-29T11:00:00Z\",\"mandatoryControls\":{\"CAPABILITIES_DROPPED\":\"PASS\",\"KERNEL_PATHS_MASKED\":\"PASS\",\"MEMORY_LIMIT\":\"PASS\",\"MINIMAL_ENVIRONMENT\":\"PASS\",\"NETWORK_DENIED\":\"PASS\",\"NON_ROOT_GID\":\"PASS\",\"NON_ROOT_UID\":\"PASS\",\"NO_DOCKER_SOCKET\":\"PASS\",\"NO_HOST_DEVICES\":\"PASS\",\"NO_HOST_MOUNTS\":\"PASS\",\"NO_NEW_PRIVILEGES\":\"PASS\",\"OUTPUT_BOUNDED\":\"PASS\",\"PID_LIMIT\":\"PASS\",\"READ_ONLY_ROOT\":\"FAIL\",\"WALL_CLOCK_TIMEOUT\":\"PASS\",\"WRITABLE_TMPFS\":\"PASS\"},\"digest\":\"sha256:93b58ff79dad0044efba0caeeae02f52423e7afae94a8d996f32d81fecc5ead3\"}";
}
