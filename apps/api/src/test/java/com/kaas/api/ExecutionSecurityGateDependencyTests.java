package com.kaas.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.api.execution.SignedAttestationFixture;
import com.kaas.api.execution.application.AttestationTrustStore;
import com.kaas.api.execution.application.SandboxSecurityAttestationSource;
import com.kaas.api.execution.domain.AttestationVerification;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * What the control plane does when it cannot show that this deployment's sandbox confines anything.
 *
 * <p>Separate classes rather than separate test methods, and that separation is the point. The attestation and
 * the trust root are read once at startup from deployment configuration; making either vary per test would
 * mean making them settable at runtime, which is precisely the property they must not have. A security posture
 * a running process can be told to change is a security posture an attacker can tell it to change.
 *
 * <p>These start no database. Every decision here happens before anything is read, and the outcome is that
 * nothing is issued.
 */
class ExecutionSecurityGateDependencyTests {

    /** The source needs a pinned trust root and somewhere to count. Neither is a database. */
    @TestConfiguration
    static class Wiring {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    private static final String CLASSES_UNDER_TEST = "";

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            classes = {SandboxSecurityAttestationSource.class, AttestationTrustStore.class, Wiring.class})
    @TestPropertySource(properties = "kaas.execution.sandbox-attestation=")
    @DisplayName("no attestation configured")
    class WhenNoAssessmentIsConfigured {
        @Autowired
        private SandboxSecurityAttestationSource source;

        @Test
        void thereIsNoAttestationAndTheOutcomeSaysWhy() {
            // The production default, and the honest one. Nothing has demonstrated that this host's sandbox
            // enforces what the platform requires, so authorization refuses rather than proceeding on the
            // assumption that silence means everything is fine.
            assertThat(source.attestation()).isEmpty();
            // TRUST_ROOT_UNAVAILABLE rather than ABSENT, because no key is configured either — and the
            // verifier refuses on that first. An operator with neither needs to fix the key before the
            // document is worth producing.
            assertThat(source.outcome()).isEqualTo(AttestationVerification.TRUST_ROOT_UNAVAILABLE);
        }
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            classes = {SandboxSecurityAttestationSource.class, AttestationTrustStore.class, Wiring.class})
    @DisplayName("a trust root but no document")
    class WhenAKeyIsPinnedButNoDocumentSupplied {
        @Autowired
        private SandboxSecurityAttestationSource source;

        @DynamicPropertySource
        static void keysButNoDocument(DynamicPropertyRegistry registry) {
            registry.add("kaas.execution.sandbox-attestation", () -> "");
            registry.add(
                    "kaas.execution.attestation-trusted-keys",
                    () -> SignedAttestationFixture.trustedKeys(SignedAttestationFixture.KEY_ID));
        }

        @Test
        void absentEvidenceIsNotAPass() {
            assertThat(source.attestation()).isEmpty();
            assertThat(source.outcome()).isEqualTo(AttestationVerification.ABSENT);
        }
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            classes = {SandboxSecurityAttestationSource.class, AttestationTrustStore.class, Wiring.class})
    @DisplayName("a boolean supplied instead of evidence")
    class WhenABooleanIsSuppliedInstead {
        @Autowired
        private SandboxSecurityAttestationSource source;

        @DynamicPropertySource
        static void shortcut(DynamicPropertyRegistry registry) {
            // The shape somebody reaches for when producing real evidence is inconvenient.
            registry.add("kaas.execution.sandbox-attestation", () -> "{\"securityGatePassed\":true}");
            registry.add(
                    "kaas.execution.attestation-trusted-keys",
                    () -> SignedAttestationFixture.trustedKeys(SignedAttestationFixture.KEY_ID));
        }

        @Test
        void theShortcutIsRefusedRatherThanRead() {
            assertThat(source.attestation()).isEmpty();
            assertThat(source.outcome()).isEqualTo(AttestationVerification.MALFORMED);
        }
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            classes = {SandboxSecurityAttestationSource.class, AttestationTrustStore.class, Wiring.class})
    @DisplayName("a perfectly formed document signed by a key nobody pinned")
    class WhenSignedByAnUnpinnedKey {
        @Autowired
        private SandboxSecurityAttestationSource source;

        @DynamicPropertySource
        static void signedByTheOtherKey(DynamicPropertyRegistry registry) {
            registry.add(
                    "kaas.execution.sandbox-attestation",
                    () -> SignedAttestationFixture.builder("kaas.sandbox.v1", Instant.now())
                            .sign(SignedAttestationFixture.SECOND_KEY_ID));
            // Only the FIRST key is pinned. Every control in the document says PASS and the signature is
            // genuine — it is simply not from anybody this deployment trusts.
            registry.add(
                    "kaas.execution.attestation-trusted-keys",
                    () -> SignedAttestationFixture.trustedKeys(SignedAttestationFixture.KEY_ID));
        }

        @Test
        void allPassingTextSignedByTheWrongKeyIsWorthNothing() {
            assertThat(source.attestation()).isEmpty();
            assertThat(source.outcome()).isEqualTo(AttestationVerification.UNKNOWN_KEY);
        }
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            classes = {SandboxSecurityAttestationSource.class, AttestationTrustStore.class, Wiring.class})
    @DisplayName("an unsigned v2 document")
    class WhenTheOldUnsignedDocumentIsSupplied {
        @Autowired
        private SandboxSecurityAttestationSource source;

        @DynamicPropertySource
        static void unsignedV2(DynamicPropertyRegistry registry) {
            registry.add(
                    "kaas.execution.sandbox-attestation",
                    () -> AttestationSigningVectors.document("invalid/unsigned-v2.json"));
            registry.add(
                    "kaas.execution.attestation-trusted-keys",
                    () -> SignedAttestationFixture.trustedKeys(SignedAttestationFixture.KEY_ID));
        }

        @Test
        void thereIsNoFallbackToTheUnsignedPath() {
            // The breaking change, asserted rather than assumed. No migration window, no
            // allow-unsigned flag, no development-trust-all: a v2 document authorizes nothing.
            assertThat(source.attestation()).isEmpty();
            assertThat(source.outcome()).isEqualTo(AttestationVerification.UNSUPPORTED_SCHEMA);
        }
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            classes = {SandboxSecurityAttestationSource.class, AttestationTrustStore.class, Wiring.class})
    @DisplayName("malformed trusted-key configuration")
    class WhenTheTrustRootIsMalformed {
        @Autowired
        private SandboxSecurityAttestationSource source;

        @DynamicPropertySource
        static void unusableKeys(DynamicPropertyRegistry registry) {
            registry.add(
                    "kaas.execution.sandbox-attestation",
                    () -> SignedAttestationFixture.mandatoryOnly("kaas.sandbox.v1", Instant.now()));
            registry.add("kaas.execution.attestation-trusted-keys", () -> "kaas-test-key-1=not-a-key");
        }

        @Test
        void theSubsystemIsUnavailableRatherThanTheProcessBeingDead() {
            // The application context STARTED. That is the assertion: a misconfigured signing key must not
            // take down read-only product endpoints, because an outage of everything is the consequence that
            // gets a security control switched off rather than fixed.
            assertThat(source).isNotNull();
            assertThat(source.attestation()).isEmpty();
            assertThat(source.outcome()).isEqualTo(AttestationVerification.TRUST_ROOT_UNAVAILABLE);
        }
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            classes = {SandboxSecurityAttestationSource.class, AttestationTrustStore.class, Wiring.class})
    @DisplayName("a valid signature over evidence that says a control failed")
    class WhenTheAssessmentSaysAControlDidNotPass {
        @Autowired
        private SandboxSecurityAttestationSource source;

        @DynamicPropertySource
        static void signedFailure(DynamicPropertyRegistry registry) {
            registry.add(
                    "kaas.execution.sandbox-attestation",
                    () -> SignedAttestationFixture.signed(
                            SignedAttestationFixture.builder("kaas.sandbox.v1", Instant.now())
                                    .withMandatoryControl("NO_DOCKER_SOCKET", "FAIL")));
            registry.add(
                    "kaas.execution.attestation-trusted-keys",
                    () -> SignedAttestationFixture.trustedKeys(SignedAttestationFixture.KEY_ID));
            registry.add(
                    "kaas.execution.attestation-runtime-subjects",
                    () -> SignedAttestationFixture.RUNTIME_SUBJECT);
        }

        @Test
        void theDocumentIsAuthenticAndStillAuthorizesNothing() {
            // AUTHENTIC AND USELESS, which is exactly the intended shape. The producer signs what it observed
            // including failures, because a truthful signed failure is more useful to an operator than a
            // refusal to serialize — and authorization is a separate question with a separate answer.
            assertThat(source.outcome()).isEqualTo(AttestationVerification.VALID);
            assertThat(source.attestation()).isPresent();

            var verified = source.attestation().orElseThrow();
            assertThat(verified.controlsNotPassing()).containsExactly("NO_DOCKER_SOCKET");
            assertThat(verified.reasonItCannotAuthorize(
                            Instant.now(),
                            java.time.Duration.ofHours(24),
                            "kaas.sandbox.v1",
                            java.util.Set.of(SignedAttestationFixture.RUNTIME_SUBJECT)))
                    .contains(AttestationVerification.CONTROL_FAILED);
        }
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            classes = {SandboxSecurityAttestationSource.class, AttestationTrustStore.class, Wiring.class})
    @DisplayName("an authentic document describing another runtime")
    class WhenTheEvidenceDescribesAnotherRuntime {
        @Autowired
        private SandboxSecurityAttestationSource source;

        @DynamicPropertySource
        static void otherSubject(DynamicPropertyRegistry registry) {
            registry.add(
                    "kaas.execution.sandbox-attestation",
                    () -> SignedAttestationFixture.signed(
                            SignedAttestationFixture.builder("kaas.sandbox.v1", Instant.now())
                                    .withRuntimeSubject("kaas.runtime.somewhere-else")));
            registry.add(
                    "kaas.execution.attestation-trusted-keys",
                    () -> SignedAttestationFixture.trustedKeys(SignedAttestationFixture.KEY_ID));
            registry.add(
                    "kaas.execution.attestation-runtime-subjects",
                    () -> SignedAttestationFixture.RUNTIME_SUBJECT);
        }

        @Test
        void evidenceFromOneHostDoesNotAuthorizeAnother() {
            // THE host-A-authorizes-host-B case. The signature is genuine, the key is pinned, every control
            // passes, and it describes a runtime this control plane was never told to accept evidence for.
            assertThat(source.outcome()).isEqualTo(AttestationVerification.VALID);
            assertThat(source.attestation().orElseThrow().reasonItCannotAuthorize(
                            Instant.now(),
                            java.time.Duration.ofHours(24),
                            "kaas.sandbox.v1",
                            java.util.Set.of(SignedAttestationFixture.RUNTIME_SUBJECT)))
                    .contains(AttestationVerification.WRONG_SUBJECT);
        }
    }

    @Nested
    @SpringBootTest(
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            classes = {SandboxSecurityAttestationSource.class, AttestationTrustStore.class, Wiring.class})
    @DisplayName("an authentic document that has aged out")
    class WhenTheEvidenceIsStale {
        @Autowired
        private SandboxSecurityAttestationSource source;

        @DynamicPropertySource
        static void old(DynamicPropertyRegistry registry) {
            registry.add(
                    "kaas.execution.sandbox-attestation",
                    () -> SignedAttestationFixture.mandatoryOnly(
                            "kaas.sandbox.v1", Instant.now().minus(30, ChronoUnit.DAYS)));
            registry.add(
                    "kaas.execution.attestation-trusted-keys",
                    () -> SignedAttestationFixture.trustedKeys(SignedAttestationFixture.KEY_ID));
            registry.add(
                    "kaas.execution.attestation-runtime-subjects",
                    () -> SignedAttestationFixture.RUNTIME_SUBJECT);
        }

        @Test
        void aSignatureDoesNotMakeAnOldStatementCurrent() {
            // The signature verifies perfectly. It says nothing whatever about when the host was last like
            // this, which is why freshness is a separate check and why it is evaluated per authorization
            // rather than once at startup.
            assertThat(source.outcome()).isEqualTo(AttestationVerification.VALID);
            assertThat(source.attestation().orElseThrow().reasonItCannotAuthorize(
                            Instant.now(),
                            java.time.Duration.ofHours(24),
                            "kaas.sandbox.v1",
                            java.util.Set.of(SignedAttestationFixture.RUNTIME_SUBJECT)))
                    .contains(AttestationVerification.STALE);
        }
    }
}
