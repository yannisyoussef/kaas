package com.kaas.api.execution.domain;

import java.util.Map;
import java.util.Set;

/**
 * Which sandbox runtime each security profile version is produced by.
 *
 * <p>Held here rather than imported from the module that produces the assessment, for the same reason
 * {@link RequiredSecurityControls} is: the control plane is build-guarded against depending on anything that
 * holds container-runtime access. The duplication is guarded by a contract test on each side against
 * {@code packages/api-contracts/mandatory-sandbox-controls.json}.
 *
 * <h2>Why this exists at all, when the profile version already says it</h2>
 *
 * <p>It is redundant on purpose. An attestation signs the profile version <em>and</em> the runtime, and the
 * verifier requires the two to agree. A single field would have to be trusted; two fields covering one fact,
 * both under the same signature, can be caught disagreeing — and a document that says
 * {@code kaas.sandbox.gvisor.v1} with {@code DOCKER} is then refused rather than silently resolved in favour
 * of whichever one the reader consulted first.
 *
 * <p>Neither side of that comparison comes from a request. The runner reads its half off the launcher that
 * ran the probes; the control plane reads its half from here.
 */
public final class SandboxRuntimeBinding {

    private SandboxRuntimeBinding() {}

    private static final Map<String, String> BY_PROFILE_VERSION = Map.of(
            "kaas.sandbox.v1", "DOCKER",
            "kaas.sandbox.gvisor.v1", "GVISOR");

    /**
     * The runtime a given profile version must have been produced under.
     *
     * <p>Throws for a version this build does not know, rather than returning something permissive. An
     * unrecognised boundary cannot be judged, and "cannot be judged" has to fail rather than default.
     */
    public static String runtimeFor(String securityProfileVersion) {
        String runtime = BY_PROFILE_VERSION.get(securityProfileVersion);
        if (runtime == null) {
            throw new IllegalArgumentException(
                    "No sandbox runtime is defined for security profile version " + securityProfileVersion);
        }
        return runtime;
    }

    /** Every profile version this build has a runtime for, for the contract test and for operators. */
    public static Set<String> knownProfileVersions() {
        return BY_PROFILE_VERSION.keySet();
    }
}
