package com.kaas.runner.daemon;

import com.github.dockerjava.api.DockerClient;
import com.kaas.runner.attestation.AttestationProductionFailed;
import com.kaas.runner.attestation.AttestationSigner;
import com.kaas.runner.attestation.RuntimeIdentity;
import com.kaas.runner.attestation.RuntimeImplementation;
import com.kaas.runner.attestation.SandboxSecurityAttestationProducer;
import com.kaas.runner.attestation.SignedAttestation;
import com.kaas.runner.client.ControlPlaneClient;
import com.kaas.runner.client.ControlPlaneUnavailable;
import com.kaas.runner.gate.EgressEnforcementAssessment;
import com.kaas.runner.gate.EgressEnforcementGate;
import com.kaas.runner.gate.HostileExecutionAssessment;
import com.kaas.runner.gate.HostileExecutionSecurityGate;
import com.kaas.runner.sandbox.DockerSandboxLauncher;
import com.kaas.runner.sandbox.SandboxSecurityProfile;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Keeps this runner's sandbox security evidence fresh without a restart (KAAS-DEPLOY-001).
 *
 * <h2>Every refresh is a new measurement</h2>
 *
 * <p>Refreshing does not re-sign a stored document or a remembered verdict. Each refresh runs the security gates
 * against this host's runtime again, measures the {@code runsc} the daemon has registered NOW, and signs what it
 * observed. A runtime replaced since the last refresh therefore produces evidence naming the new binary -- which
 * the control plane refuses unless an operator accepted it, and a refused refresh leaves this runner without
 * current evidence, which makes it NOT READY. Nothing here can make new evidence describe an old runtime.
 *
 * <h2>Failure does not revoke what is still valid</h2>
 *
 * <p>A refresh that fails -- the gates, the key, the submission -- is retried on a shorter interval, and the
 * previously accepted evidence keeps this runner ready for as long as it is itself still usable. When that runs
 * out the runner is NOT READY, and it stays alive: evidence lapsing is a reason to stop taking work, not a
 * reason to be killed.
 */
final class AttestationRefresher {
    /** Evidence closer than this to expiring is not used to take new work: the authorization follows the claim. */
    static final Duration EXPIRY_MARGIN = Duration.ofMinutes(5);

    /** Produces a freshly measured, signed attestation. The production form runs the real gates. */
    @FunctionalInterface
    interface Assessor {
        Assessment assess() throws AttestationProductionFailed;
    }

    /** A signed document, and the runtime implementation it describes. */
    record Assessment(String document, String runtimeImplementationDigest) {
        @Override
        public String toString() {
            return "Assessment[runtimeImplementationDigest=" + runtimeImplementationDigest + "]";
        }
    }

    private final Assessor assessor;
    private final ControlPlaneClient controlPlane;
    private final Readiness readiness;
    private final RunnerMetrics metrics;
    private final Clock clock;
    private final ObjectMapper mapper;

    private volatile Instant usableUntil = Instant.EPOCH;
    private volatile Instant assessedAt = Instant.EPOCH;
    private volatile String acceptedRuntimeDigest;
    private volatile Assessment pending;

    AttestationRefresher(Assessor assessor, ControlPlaneClient controlPlane, Readiness readiness,
            RunnerMetrics metrics, Clock clock, ObjectMapper mapper) {
        this.assessor = assessor;
        this.controlPlane = controlPlane;
        this.readiness = readiness;
        this.metrics = metrics;
        this.clock = clock;
        this.mapper = mapper;
        metrics.gauge("kaas_runner_attestation_age_seconds",
                "Seconds since the accepted evidence was assessed; -1 when there is none.",
                () -> assessedAt.equals(Instant.EPOCH) ? -1 : Duration.between(assessedAt, clock.instant()).toSeconds());
        metrics.gauge("kaas_runner_attestation_valid_seconds",
                "Seconds the accepted evidence remains usable; 0 when there is none or it expired.",
                () -> Math.max(0, Duration.between(clock.instant(), usableUntil).toSeconds()));
    }

    /**
     * Measures and signs, without submitting. Startup does this before reconciliation and before it has a
     * service credential, so a host that cannot produce evidence is found out first.
     */
    boolean assess() {
        try {
            pending = assessor.assess();
            return true;
        } catch (AttestationProductionFailed failed) {
            metrics.attestationRefreshed("ASSESSMENT_FAILED");
            readiness.set(Readiness.Condition.ATTESTATION, evidenceCurrent(), "ASSESSMENT_FAILED_" + failed.failure());
            return false;
        } catch (RuntimeException failed) {
            metrics.attestationRefreshed("ASSESSMENT_FAILED");
            readiness.set(Readiness.Condition.ATTESTATION, evidenceCurrent(), "ASSESSMENT_FAILED");
            return false;
        }
    }

    /** Submits the pending assessment. True when the control plane accepted it. */
    boolean submit() {
        Assessment assessment = pending;
        if (assessment == null) {
            return false;
        }
        ControlPlaneClient.Response response;
        try {
            response = controlPlane.submitAttestation(assessment.document());
        } catch (ControlPlaneUnavailable unavailable) {
            metrics.attestationRefreshed("SUBMISSION_FAILED");
            readiness.set(Readiness.Condition.ATTESTATION, evidenceCurrent(), "SUBMISSION_FAILED");
            return false;
        }
        String code = codeOf(response);
        if ((response.status() == 200 || response.status() == 201) && "ACCEPTED".equals(code)) {
            try {
                JsonNode body = mapper.readTree(response.body());
                usableUntil = Instant.parse(body.get("usableUntil").stringValue());
                assessedAt = Instant.parse(body.get("assessedAt").stringValue());
            } catch (RuntimeException unreadable) {
                metrics.attestationRefreshed("SUBMISSION_FAILED");
                readiness.set(Readiness.Condition.ATTESTATION, evidenceCurrent(), "SUBMISSION_UNREADABLE");
                return false;
            }
            acceptedRuntimeDigest = assessment.runtimeImplementationDigest();
            pending = null;
            metrics.attestationRefreshed("ACCEPTED");
            readiness.set(Readiness.Condition.ATTESTATION, evidenceCurrent(), "EXPIRED");
            readiness.holds(Readiness.Condition.RUNTIME_DIGEST_MATCH);
            return true;
        }
        // Refused -- an unpinned key, an unaccepted runtime, a failed control. Previously accepted evidence
        // still counts while it lasts; this document does not.
        metrics.attestationRefreshed("REFUSED");
        readiness.set(Readiness.Condition.ATTESTATION, evidenceCurrent(), "REFUSED_" + code);
        if ("RUNTIME_IMPLEMENTATION_MISMATCH".equals(code)) {
            readiness.set(Readiness.Condition.RUNTIME_DIGEST_MATCH, false, "RUNTIME_NOT_ACCEPTED");
        }
        pending = null;
        return false;
    }

    /** Assess then submit: one refresh. */
    boolean refresh() {
        return assess() && submit();
    }

    /** Re-evaluates freshness without doing anything else; called on every tick. */
    void recheck() {
        readiness.set(Readiness.Condition.ATTESTATION, evidenceCurrent(), "EXPIRED");
    }

    /**
     * Compares the runtime measured now with the one the accepted evidence describes. A difference makes the
     * runner NOT READY at once -- not at the next scheduled refresh -- and asks for a refresh.
     *
     * @return whether they match
     */
    boolean runtimeMatches(String measuredDigest) {
        String accepted = acceptedRuntimeDigest;
        boolean matches = accepted != null && accepted.equals(measuredDigest);
        readiness.set(Readiness.Condition.RUNTIME_DIGEST_MATCH, matches,
                accepted == null ? "NO_ACCEPTED_EVIDENCE" : "RUNTIME_CHANGED");
        return matches;
    }

    boolean evidenceCurrent() {
        return clock.instant().plus(EXPIRY_MARGIN).isBefore(usableUntil);
    }

    Optional<String> acceptedRuntimeDigest() {
        return Optional.ofNullable(acceptedRuntimeDigest);
    }

    private String codeOf(ControlPlaneClient.Response response) {
        try {
            JsonNode code = mapper.readTree(response.body()).get("code");
            String value = code == null ? "UNKNOWN" : code.stringValue();
            return value != null && value.matches("[A-Z_]{1,64}") ? value : "UNKNOWN";
        } catch (RuntimeException unreadable) {
            return "UNKNOWN";
        }
    }

    /**
     * The production assessor: the real gates, against the real daemon, signed with the operator's key.
     *
     * <p>The key is read from its file on EVERY assessment, so a rotated key is used without a restart and a
     * key that disappears makes the next refresh fail rather than being remembered. A missing key is never
     * generated: an automatically generated signer destroys pinning continuity, and the obvious "fix" for the
     * refusals that follow is to make the control plane trust whatever turned up.
     */
    static Assessor gates(DockerClient docker, RunnerConfiguration configuration) {
        return () -> {
            if (!Files.isRegularFile(configuration.attestationKeyFile())) {
                throw new AttestationProductionFailed(
                        com.kaas.runner.attestation.AttestationFailure.SIGNING_KEY_UNUSABLE,
                        "The attestation signing key is not at its configured path.");
            }
            AttestationSigner signer = AttestationSigner.fromFile(
                    configuration.attestationKeyId(), configuration.attestationKeyFile());
            String generation = "attestation-" + UUID.randomUUID();
            SandboxSecurityProfile profile =
                    SandboxSecurityProfile.version1(configuration.probeImage(), configuration.sandboxRuntime());
            HostileExecutionAssessment mandatory = new HostileExecutionSecurityGate(
                            new DockerSandboxLauncher(docker, profile, generation), "docker")
                    .assess();
            EgressEnforcementAssessment egress = configuration.egress()
                    .map(allowlist -> EgressEnforcementGate.forDeployedImage(
                                    docker, allowlist.proxyImage(), configuration.probeImage(), generation)
                            .assess())
                    .orElseGet(EgressEnforcementAssessment::nothingObserved);
            RuntimeIdentity runtime = RuntimeIdentity.ofDaemon(docker, configuration.runtimeSubject());
            // Measured from the daemon's registration, now. There is no input for a digest.
            RuntimeImplementation implementation =
                    RuntimeImplementation.measure(docker, configuration.sandboxRuntime().daemonRuntimeName());
            SignedAttestation signed = new SandboxSecurityAttestationProducer(signer)
                    .produce(mandatory, egress, runtime, implementation, configuration.probeImage());
            return new Assessment(signed.toJson(), implementation.digest());
        };
    }
}
