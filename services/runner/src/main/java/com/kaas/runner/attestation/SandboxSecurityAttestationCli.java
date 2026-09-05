package com.kaas.runner.attestation;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.kaas.runner.gate.EgressEnforcementAssessment;
import com.kaas.runner.gate.EgressEnforcementGate;
import com.kaas.runner.gate.HostileExecutionAssessment;
import com.kaas.runner.gate.HostileExecutionSecurityGate;
import com.kaas.runner.sandbox.DockerSandboxLauncher;
import com.kaas.runner.sandbox.ProbeImage;
import com.kaas.runner.sandbox.ExecutionRuntimeType;
import com.kaas.runner.sandbox.SandboxSecurityProfile;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

/**
 * Runs the security gates on this host and writes one signed attestation describing what they observed.
 *
 * <h2>The whole point: nobody types a verdict</h2>
 *
 * <p>The old workflow was a gate that printed results, a human who read them, and a JSON file the human wrote.
 * Every step after the first was a place to record something that was never observed. This runs the gates, maps
 * their structured output, and signs it. There is no input for a control verdict, and there is no way to ask it
 * to attest something the gates did not report.
 *
 * <h2>What it will not do</h2>
 *
 * <p>It will not generate a signing key. An automatically generated signer destroys pinning continuity: the
 * control plane's trust map would not contain the new key, every attestation would be refused, and the obvious
 * repair for that is to make the control plane trust whatever turned up. A missing or unusable key is a
 * failure, loudly.
 *
 * <p>It will not print the signing key, the artifact's key material, or anything a daemon told it about the
 * host beyond what the attestation itself carries. What reaches stdout is a summary an operator can paste into
 * a ticket.
 *
 * <h2>CI evidence is not deployment evidence</h2>
 *
 * <p>The same producer runs in CI and on a real runner host, and the artifacts are not interchangeable. A CI
 * attestation says the mechanism passes on a GitHub-hosted runner. It says nothing whatever about the host that
 * will actually execute anything, and its runtime subject is different precisely so a control plane cannot
 * accept one for the other.
 */
public final class SandboxSecurityAttestationCli {

    /** Every input, named so an operator can see there is no verdict among them. */
    public record Options(
            String keyId,
            Path privateKeyFile,
            String runtimeSubject,
            ExecutionRuntimeType sandboxRuntime,
            Path output,
            boolean includeEgressEvidence,
            Path probeContext,
            Path proxyImageContext) {}

    private SandboxSecurityAttestationCli() {}

    public static void main(String[] args) {
        try {
            Options options = optionsFrom(System.getProperties());
            Path written = run(options, System.out);
            System.out.println("Wrote " + written);
        } catch (AttestationProductionFailed failed) {
            // The category and the message, never the cause chain: a daemon error carries socket paths, host
            // directories, and image references, and this output is pasted into tickets.
            System.err.println("Attestation production failed: " + failed.failure() + " - " + failed.getMessage());
            System.exit(1);
        }
    }

    /**
     * Runs both gates, signs the result, and writes it.
     *
     * @return the file written
     */
    public static Path run(Options options, PrintStream out) {
        AttestationSigner signer = AttestationSigner.fromFile(options.keyId(), options.privateKeyFile());
        DockerClient docker = docker();
        String generation = "attestation-" + UUID.randomUUID();

        String probeImage = ProbeImage.build(docker, options.probeContext());
        // The runtime the OPERATOR names, not a hardcoded one.
        //
        // This defaulted to the baseline, which meant a deployment running the mediating runtime had no way
        // to produce evidence for it: the control plane could be configured to expect
        // kaas.sandbox.gvisor.v1 and nothing in the product could sign such a document. The mediating path
        // was reachable only from tests -- which is the same as not being reachable.
        SandboxSecurityProfile profile =
                SandboxSecurityProfile.version1(probeImage, options.sandboxRuntime());
        HostileExecutionAssessment mandatory =
                new HostileExecutionSecurityGate(
                                new DockerSandboxLauncher(docker, profile, generation), "docker")
                        .assess();

        // Egress evidence is gathered only when asked for. A deployment that runs no allowlist executions
        // should not have to stand up a proxy to produce an attestation, and an artifact that made an egress
        // claim it never measured would be the exact failure this slice exists to end.
        EgressEnforcementAssessment egress = options.includeEgressEvidence()
                ? new EgressEnforcementGate(docker, options.proxyImageContext(), probeImage, generation).assess()
                : EgressEnforcementAssessment.nothingObserved();

        RuntimeIdentity runtime = RuntimeIdentity.ofDaemon(docker, options.runtimeSubject());
        SignedAttestation signed =
                new SandboxSecurityAttestationProducer(signer).produce(mandatory, egress, runtime, probeImage);

        writeAtomically(options.output(), signed.toJson());

        // A summary, and deliberately not the artifact. The digest and the id are what an operator needs to
        // correlate this with what a control plane later reports; the controls are in the file.
        out.println("attestationId=" + signed.payload().attestationId());
        out.println("keyId=" + signed.payload().keyId());
        out.println("payloadDigest=" + signed.payloadDigest());
        out.println("mandatoryPassed=" + mandatory.passed());
        out.println("egressObserved=" + !egress.checks().isEmpty());
        out.println("egressPassed=" + egress.passed());
        return options.output();
    }

    /**
     * Writes to a temporary file in the same directory, then replaces atomically.
     *
     * <p>A half-written security artifact fails closed — a truncated document does not parse and does not
     * verify — but "fails closed" is not the same as "is fine". An operator who finds a partial file has to
     * work out whether the producer crashed or whether somebody edited it, and an atomic replace means that
     * question never arises.
     *
     * <p>Permissions are narrowed where the filesystem supports it. The artifact is not secret — it is signed
     * evidence meant to be transported — but it is also not something an unprivileged process on the host
     * should be able to replace.
     */
    private static void writeAtomically(Path output, String document) {
        try {
            Path directory = output.toAbsolutePath().getParent();
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, ".kaas-attestation", ".tmp");
            try {
                Files.writeString(temporary, document);
                try {
                    Files.setPosixFilePermissions(
                            temporary,
                            Set.of(
                                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
                } catch (UnsupportedOperationException notPosix) {
                    // A filesystem without POSIX permissions. Not a reason to refuse to write evidence.
                }
                Files.move(
                        temporary,
                        output,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (RuntimeException | java.io.IOException failed) {
                Files.deleteIfExists(temporary);
                throw failed;
            }
        } catch (Exception failed) {
            throw new AttestationProductionFailed(
                    AttestationFailure.OUTPUT_FAILED, "The attestation could not be written to " + output + ".");
        }
    }

    /**
     * Options from system properties.
     *
     * <p>The private key is named by a FILE PATH rather than supplied inline. A key in a process argument is
     * visible in {@code ps} to every user on the host, and one in an environment variable is visible in
     * {@code /proc/<pid>/environ} and inherited by children. A path is neither.
     */
    static Options optionsFrom(java.util.Properties properties) {
        return new Options(
                required(properties, "kaas.attestation.key-id"),
                Path.of(required(properties, "kaas.attestation.private-key-file")),
                required(properties, "kaas.attestation.runtime-subject"),
                sandboxRuntimeFrom(properties),
                Path.of(required(properties, "kaas.attestation.output")),
                Boolean.parseBoolean(properties.getProperty("kaas.attestation.include-egress", "false")),
                Path.of(properties.getProperty("kaas.attestation.probe-context",
                        "services/runner/src/main/docker/probe")),
                Path.of(properties.getProperty("kaas.attestation.proxy-image-context", "")));
    }

    /**
     * Which sandbox runtime this host will be attested for.
     *
     * <p>Chosen from a closed set by name, never by {@code valueOf} over whatever was supplied. The enum's
     * constant names are an implementation detail; the accepted spellings are the two written here, and
     * anything else is refused rather than resolved. A property that could name an arbitrary runtime would be
     * a property that names a program the daemon executes.
     *
     * <p>Defaults to the baseline, because that is what a deployment that has not been told otherwise is
     * running — and an attestation is a statement about a host rather than an aspiration for one. It does
     * not default to the stronger value: an operator who has not installed the mediating runtime would then
     * produce evidence naming a boundary this host does not have, and the gate would refuse it with a
     * confusing message instead of the operator getting the truthful one.
     */
    static ExecutionRuntimeType sandboxRuntimeFrom(java.util.Properties properties) {
        String named = properties.getProperty("kaas.attestation.sandbox-runtime", "docker").trim();
        return switch (named.toLowerCase(java.util.Locale.ROOT)) {
            case "docker" -> ExecutionRuntimeType.DOCKER;
            case "gvisor" -> ExecutionRuntimeType.GVISOR;
            default -> throw new AttestationProductionFailed(
                    AttestationFailure.ASSESSMENT_INCOMPLETE,
                    "kaas.attestation.sandbox-runtime must be 'docker' or 'gvisor'.");
        };
    }

    private static String required(java.util.Properties properties, String name) {
        String value = properties.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new AttestationProductionFailed(
                    AttestationFailure.ASSESSMENT_INCOMPLETE, name + " must be set.");
        }
        return value;
    }

    private static DockerClient docker() {
        var config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        return DockerClientImpl.getInstance(
                config,
                new ApacheDockerHttpClient.Builder()
                        .dockerHost(config.getDockerHost())
                        .sslConfig(config.getSSLConfig())
                        .responseTimeout(Duration.ofSeconds(60))
                        .connectionTimeout(Duration.ofSeconds(30))
                        .build());
    }
}
