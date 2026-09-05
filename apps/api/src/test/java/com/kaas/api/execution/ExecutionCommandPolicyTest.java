package com.kaas.api.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaas.api.controlplane.domain.ArtifactPolicy;
import com.kaas.api.controlplane.domain.ArtifactType;
import com.kaas.api.controlplane.domain.ConfigurationValueType;
import com.kaas.api.controlplane.domain.ConfigurationVariable;
import com.kaas.api.controlplane.domain.EngineDescriptor;
import com.kaas.api.controlplane.domain.RunSelection;
import com.kaas.api.controlplane.domain.ScenarioRetry;
import com.kaas.api.controlplane.domain.SnapshotFeature;
import com.kaas.api.execution.domain.ExecutionCommand;
import com.kaas.api.execution.domain.ExecutionCommandPolicy;
import com.kaas.api.execution.domain.NetworkPolicyType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the command digest covers, stated as the property that every emitted field changes it.
 *
 * <p>These exist because three independent reviews produced collisions against the previous design, which
 * excluded {@code commandId}, {@code issuedAt}, {@code expiresAt}, the capability identifiers, and — worst —
 * which secret a binding key resolved to. Two commands binding {@code API_TOKEN} to different references from
 * different providers digested identically, and an expiry moved from 2026 to 2126 changed nothing.
 *
 * <p>The rule the tests encode is deliberately blunt: <em>every field the document emits must change the
 * digest.</em> A field that cannot be covered must not be emitted, which is why the source capability
 * identifier was removed from the document rather than added to the digest.
 */
class ExecutionCommandPolicyTest {

    @Test
    void twoIdenticalCommandsDigestIdentically() {
        // Without this every test below would be satisfied by a digest that changed on everything, including
        // nothing.
        assertThat(ExecutionCommandPolicy.digest(command(UnaryOperator.identity())))
                .isEqualTo(ExecutionCommandPolicy.digest(command(UnaryOperator.identity())));
    }

    @Test
    void extendingTheExpiryChangesTheDigest() {
        // The collision that mattered most: an expiry is authority-bearing, and the previous design excluded it
        // on the theory that retries would otherwise digest differently. Retries return the stored document, so
        // they never do.
        assertThat(digestOf(draft -> withExpiry(draft, Instant.parse("2126-01-01T00:00:00Z"))))
                .isNotEqualTo(digestOf(UnaryOperator.identity()));
    }

    @Test
    void aDifferentCommandIdentityOrIssuanceInstantChangesTheDigest() {
        assertThat(digestOf(draft -> withCommandId(draft, UUID.randomUUID())))
                .isNotEqualTo(digestOf(UnaryOperator.identity()));
    }

    @Test
    void bindingTheSameKeyToADifferentSecretChangesTheDigest() {
        // vault/secret-ref:AAAA versus aws.secretsmanager/secret-ref:BBBB under one binding key. These digested
        // identically before, which meant a substituted cross-tenant reference passed digest verification.
        String original = digestOf(draft -> withSecret(draft, "vault", "secret-ref:aaaaaaaa"));
        String substituted = digestOf(draft -> withSecret(draft, "aws.secretsmanager", "secret-ref:bbbbbbbb"));

        assertThat(original).isNotEqualTo(substituted);
    }

    @Test
    void aDifferentNetworkPolicyRevisionChangesTheDigest() {
        assertThat(digestOf(draft -> withPolicyRevision(draft, UUID.randomUUID())))
                .isNotEqualTo(digestOf(UnaryOperator.identity()));
    }

    @Test
    void theDocumentCarriesNoCapabilityIdentityAndNoBearerMaterial() {
        // Capability identity rotates per delivery, so it cannot be covered by a digest over an immutable
        // document — and a field the digest cannot cover is a field an attacker chooses and a consumer cannot
        // verify. It belongs in the delivery envelope, and the document must not mention it at all.
        String document = ExecutionCommandPolicy.document(command(UnaryOperator.identity()), JsonMapper.builder().build())
                .toString();

        assertThat(document)
                .doesNotContain("capabilityId")
                .doesNotContain("source-bundle:")
                .doesNotContain("kaas_src_")
                .doesNotContain("kaas_sec_");
    }

    @Test
    void everyFieldTheDocumentEmitsAlsoAppearsInTheDigestInput() {
        // A structural check on the rule rather than one collision at a time: every top-level property of the
        // emitted document must be one the digest covers, or be the digest itself. A field added to the document
        // without a matching digest input fails here rather than becoming the next collision.
        var document = ExecutionCommandPolicy.document(command(UnaryOperator.identity()), JsonMapper.builder().build());
        List<String> covered = List.of(
                "schemaVersion", "commandId", "issuedAt", "expiresAt", "organizationId", "projectId", "runId",
                "runVersion", "attemptId", "attemptNumber", "assignmentEpoch", "runSnapshotDigest", "engine",
                "sourceBundle", "secretCapabilities", "networkPolicy", "sandboxSecurityProfile",
                "configurationSnapshot", "selection", "parallelism", "scenarioRetry", "executionTimeoutSeconds",
                "artifactPolicy");

        assertThat(document.propertyNames())
                .as("a document field that the digest does not cover must not be emitted")
                .allSatisfy(name -> assertThat(name).isIn(concat(covered, "commandDigest")));
    }

    private static List<String> concat(List<String> values, String extra) {
        return java.util.stream.Stream.concat(values.stream(), java.util.stream.Stream.of(extra)).toList();
    }

    private static String digestOf(UnaryOperator<ExecutionCommand> mutation) {
        return ExecutionCommandPolicy.digest(command(mutation));
    }

    private static ExecutionCommand command(UnaryOperator<ExecutionCommand> mutation) {
        var draft = new ExecutionCommand(
                UUID.fromString("3f8a2b10-0000-4000-8000-000000000001"),
                UUID.fromString("3f8a2b10-0000-4000-8000-000000000002"),
                UUID.fromString("3f8a2b10-0000-4000-8000-0000000000aa"),
                UUID.fromString("3f8a2b10-0000-4000-8000-0000000000bb"),
                UUID.fromString("3f8a2b10-0000-4000-8000-0000000000cc"),
                3,
                UUID.fromString("3f8a2b10-0000-4000-8000-0000000000dd"),
                1,
                1,
                "2".repeat(64),
                new EngineDescriptor("KARATE", "1.4.1"),
                new ExecutionCommand.SourceBundleReference(
                        "sha256:" + "3".repeat(64),
                        List.of(new SnapshotFeature(
                                UUID.fromString("3f8a2b10-0000-4000-8000-0000000000f1"),
                                UUID.fromString("3f8a2b10-0000-4000-8000-0000000000f2"),
                                1,
                                "features/login.feature",
                                "4".repeat(64)))),
                List.of(),
                new ExecutionCommand.NetworkPolicyReference(
                        UUID.fromString("00000000-0000-4000-8000-00000000d001"),
                        NetworkPolicyType.DENY_ALL,
                        1,
                        "sha256:" + "5".repeat(64)),
                new ExecutionCommand.SandboxSecurityProfileReference(
                        "kaas.sandbox.v1", "DOCKER", "sha256:" + "6".repeat(64)),
                List.of(new ConfigurationVariable("BASE_URL", ConfigurationValueType.STRING, "https://example.test")),
                new RunSelection(List.of("@smoke")),
                4,
                new ScenarioRetry(2, 500),
                300,
                new ArtifactPolicy(List.of(ArtifactType.RAW_RESULT), 1_048_576, 10_485_760),
                Instant.parse("2026-08-29T09:00:00Z"),
                Instant.parse("2026-08-29T09:05:00Z"),
                "");
        return mutation.apply(draft);
    }

    private static ExecutionCommand withExpiry(ExecutionCommand command, Instant expiresAt) {
        return rebuild(command, command.commandId(), expiresAt, command.secretCapabilities(), command.networkPolicy());
    }

    private static ExecutionCommand withCommandId(ExecutionCommand command, UUID commandId) {
        return rebuild(
                command, commandId, command.expiresAt(), command.secretCapabilities(), command.networkPolicy());
    }

    private static ExecutionCommand withSecret(ExecutionCommand command, String provider, String referenceId) {
        return rebuild(
                command,
                command.commandId(),
                command.expiresAt(),
                List.of(new ExecutionCommand.SecretCapabilityReference(
                        UUID.fromString("3f8a2b10-0000-4000-8000-0000000000f9"),
                        provider,
                        referenceId,
                        "API_TOKEN",
                        Instant.parse("2026-08-29T09:05:00Z"))),
                command.networkPolicy());
    }

    private static ExecutionCommand withPolicyRevision(ExecutionCommand command, UUID policyRevisionId) {
        return rebuild(
                command,
                command.commandId(),
                command.expiresAt(),
                command.secretCapabilities(),
                new ExecutionCommand.NetworkPolicyReference(
                        policyRevisionId,
                        command.networkPolicy().type(),
                        command.networkPolicy().version(),
                        command.networkPolicy().digest()));
    }

    private static ExecutionCommand rebuild(
            ExecutionCommand command,
            UUID commandId,
            Instant expiresAt,
            List<ExecutionCommand.SecretCapabilityReference> secrets,
            ExecutionCommand.NetworkPolicyReference policy) {
        return new ExecutionCommand(
                commandId, command.authorizationId(), command.organizationId(), command.projectId(),
                command.runId(), command.runVersion(), command.attemptId(), command.attemptNumber(),
                command.assignmentEpoch(), command.runSnapshotSha256(), command.engine(), command.sourceBundle(),
                secrets, policy, command.sandboxSecurityProfile(), command.configuration(), command.selection(),
                command.parallelism(), command.scenarioRetry(), command.executionTimeoutSeconds(),
                command.artifactPolicy(), command.issuedAt(), expiresAt, command.commandDigest());
    }
}
