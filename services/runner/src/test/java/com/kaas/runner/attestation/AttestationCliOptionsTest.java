package com.kaas.runner.attestation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaas.runner.sandbox.ExecutionRuntimeType;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which runtime the producer attests, chosen by the operator running it.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The producer hardcoded the baseline runtime. Everything else about the mediating runtime worked — the
 * launcher, the gate, the runtime-scoped control set, the signed field, the verifier's cross-check — and none
 * of it could be reached by the one component that actually produces an attestation for a deployment. A
 * control plane could be configured to expect {@code kaas.sandbox.gvisor.v1} and nothing in the product could
 * sign such a document.
 *
 * <p>Nothing failed. Every test passed, because every test built its own profile. That is the failure mode
 * these assertions exist for: a mechanism that is complete except for the one call site that would use it.
 */
@DisplayName("Attestation CLI runtime selection")
class AttestationCliOptionsTest {

    @Test
    @DisplayName("an operator names the runtime, and the default is the baseline")
    void theRuntimeIsChosenByTheOperator() {
        assertThat(SandboxSecurityAttestationCli.sandboxRuntimeFrom(new Properties()))
                .as("a deployment that has not been told otherwise is running the baseline; an attestation "
                        + "is a statement about a host rather than an aspiration for one")
                .isEqualTo(ExecutionRuntimeType.DOCKER);
        assertThat(SandboxSecurityAttestationCli.sandboxRuntimeFrom(withRuntime("gvisor")))
                .isEqualTo(ExecutionRuntimeType.GVISOR);
        // Spelling is not the operator's problem; naming an arbitrary runtime is not their privilege.
        assertThat(SandboxSecurityAttestationCli.sandboxRuntimeFrom(withRuntime("  GVISOR ")))
                .isEqualTo(ExecutionRuntimeType.GVISOR);
    }

    @Test
    @DisplayName("an unrecognised runtime is refused rather than resolved")
    void anUnknownRuntimeIsRefused() {
        // Not valueOf over whatever was supplied. The enum's constant names are an implementation detail, and
        // a property that could name an arbitrary runtime would be a property that names a program the daemon
        // executes.
        assertThatThrownBy(() -> SandboxSecurityAttestationCli.sandboxRuntimeFrom(withRuntime("kata")))
                .isInstanceOf(AttestationProductionFailed.class)
                .hasMessageContaining("docker");
        assertThatThrownBy(() -> SandboxSecurityAttestationCli.sandboxRuntimeFrom(withRuntime("")))
                .isInstanceOf(AttestationProductionFailed.class);
    }

    @Test
    @DisplayName("the runtime the operator names is the one the evidence is bound to")
    void theNamedRuntimeDecidesTheProfileVersion() {
        // The link that makes the choice meaningful: an operator naming the mediating runtime must get
        // evidence bound to that runtime's own profile version, or the control plane cannot tell the two
        // apart and the whole runtime-scoped control set is decoration.
        assertThat(ExecutionRuntimeType.GVISOR.profileVersion())
                .isNotEqualTo(ExecutionRuntimeType.DOCKER.profileVersion());
    }

    private static Properties withRuntime(String value) {
        Properties properties = new Properties();
        properties.setProperty("kaas.attestation.sandbox-runtime", value);
        return properties;
    }
}
