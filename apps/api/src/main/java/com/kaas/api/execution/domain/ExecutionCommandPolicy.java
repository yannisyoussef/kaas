package com.kaas.api.execution.domain;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.kaas.api.controlplane.domain.ArtifactType;
import com.kaas.api.controlplane.domain.ConfigurationVariable;
import com.kaas.api.controlplane.domain.SnapshotFeature;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * The canonical form of an execution command: what it digests to, and what it looks like on the wire.
 *
 * <p><strong>Which model this is.</strong> The digest is a semantic canonicalization, not a hash of the JSON
 * bytes. Fields are enumerated explicitly here, in a fixed order, length-prefixed, and every collection is
 * sorted before it contributes. The JSON document is produced separately and is not what the digest describes.
 *
 * <p>That is stated because the alternative caused a real defect in an earlier slice: a verifier that inspected
 * a parsed projection while different raw bytes travelled, so the thing checked and the thing transmitted were
 * not the same object. A consumer verifying this digest must therefore recanonicalize from the parsed document
 * rather than hashing the bytes it received. Hashing bytes would be the other legitimate design; it is not this
 * one, and mixing them is what produces a verifier that proves nothing.
 *
 * <p><strong>The digest covers every field the document emits.</strong> That rule replaces an earlier design
 * which excluded {@code commandId}, {@code issuedAt}, {@code expiresAt}, and the capability identifiers on the
 * grounds that a retry would otherwise digest differently. The reasoning was simply wrong: a retry returns the
 * <em>stored</em> document unchanged, and the unique constraint on the assignment guarantees the row is written
 * exactly once, so none of those values ever varies across retries. Covering them costs nothing, and not
 * covering them cost a great deal — three independent reviews produced collisions, including one where a
 * command binding a key to {@code vault / secret-ref:AAAA} digested identically to the same key bound to
 * {@code aws.secretsmanager / secret-ref:BBBB}, and another where an expiry moved from 2026 to 2126 changed
 * nothing.
 *
 * <p>The rule that follows is the useful one: <em>a field the digest cannot cover must not be emitted.</em> The
 * source capability identifier was removed from the document for exactly that reason — it rotates per delivery,
 * so it could not be covered, so it had no business being in an artifact a consumer is told to verify.
 *
 * <p>Only the digest itself is excluded, because a value cannot contain its own hash.
 */
public final class ExecutionCommandPolicy {
    private static final String FORMAT = "kaas.execution-command.v1";

    /** The contract version the emitted document conforms to. */
    public static final String SCHEMA_VERSION = "1.0";

    private ExecutionCommandPolicy() {}

    public static String digest(ExecutionCommand command) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            update(sha, FORMAT);
            update(sha, "SCHEMA_VERSION");
            update(sha, SCHEMA_VERSION);
            update(sha, "COMMAND_ID");
            update(sha, command.commandId().toString());
            update(sha, "ISSUED_AT");
            update(sha, command.issuedAt().toString());
            update(sha, "EXPIRES_AT");
            update(sha, command.expiresAt().toString());
            update(sha, "ORGANIZATION");
            update(sha, command.organizationId().toString());
            update(sha, "PROJECT");
            update(sha, command.projectId().toString());
            update(sha, "RUN");
            update(sha, command.runId().toString());
            update(sha, "RUN_VERSION");
            update(sha, Long.toString(command.runVersion()));
            update(sha, "ATTEMPT");
            update(sha, command.attemptId().toString());
            update(sha, "ATTEMPT_NUMBER");
            update(sha, Integer.toString(command.attemptNumber()));
            update(sha, "ASSIGNMENT_EPOCH");
            update(sha, Integer.toString(command.assignmentEpoch()));
            update(sha, "RUN_SNAPSHOT");
            update(sha, command.runSnapshotSha256());
            update(sha, "ENGINE");
            update(sha, command.engine().engine());
            update(sha, command.engine().version());

            update(sha, "SOURCE_BUNDLE_DIGEST");
            update(sha, command.sourceBundle().contentDigest());
            update(sha, "SOURCE_FEATURE_COUNT");
            update(sha, Integer.toString(command.sourceBundle().features().size()));
            for (SnapshotFeature feature : command.sourceBundle().features().stream()
                    .sorted(Comparator.comparing(SnapshotFeature::logicalPath))
                    .toList()) {
                update(sha, "SOURCE_FEATURE");
                update(sha, feature.featureId().toString());
                update(sha, feature.revisionId().toString());
                update(sha, feature.logicalPath());
                // The bare hex, matching the worker's own recomputation, which strips the prefix before
                // digesting. The two sides must agree on the exact bytes fed to SHA-256, and the prefix is a
                // presentation detail of the field rather than part of its value.
                update(sha, stripSha256(feature.sourceDigest()));
            }

            // WHICH secret each key resolves to, not merely which keys exist. Covering the binding key alone
            // meant two commands binding API_TOKEN to different references, from different providers, in
            // different tenants, produced one digest — demonstrated. A reference is not per-issuance identity;
            // it is the single most execution-relevant fact about a secret binding.
            update(sha, "SECRET_BINDING_COUNT");
            update(sha, Integer.toString(command.secretCapabilities().size()));
            for (ExecutionCommand.SecretCapabilityReference secret : command.secretCapabilities().stream()
                    .sorted(Comparator.comparing(ExecutionCommand.SecretCapabilityReference::bindingKey))
                    .toList()) {
                update(sha, "SECRET_BINDING");
                update(sha, secret.bindingKey());
                update(sha, secret.provider());
                update(sha, secret.referenceId());
                update(sha, secret.capabilityId().toString());
                update(sha, secret.expiresAt().toString());
            }

            update(sha, "NETWORK_POLICY");
            update(sha, command.networkPolicy().policyRevisionId().toString());
            update(sha, command.networkPolicy().type().name());
            update(sha, Integer.toString(command.networkPolicy().version()));
            update(sha, command.networkPolicy().digest());
            update(sha, "SANDBOX_PROFILE");
            update(sha, command.sandboxSecurityProfile().profileVersion());
            update(sha, command.sandboxSecurityProfile().sandboxRuntime());
            update(sha, command.sandboxSecurityProfile().assessmentDigest());

            update(sha, "CONFIGURATION_COUNT");
            update(sha, Integer.toString(command.configuration().size()));
            for (ConfigurationVariable value : command.configuration().stream()
                    .sorted(Comparator.comparing(ConfigurationVariable::key))
                    .toList()) {
                update(sha, "CONFIGURATION");
                update(sha, value.key());
                update(sha, value.type().name());
                update(sha, String.valueOf(value.value()));
            }
            update(sha, "TAG_COUNT");
            update(sha, Integer.toString(command.selection().tags().size()));
            command.selection().tags().stream().sorted().forEach(tag -> {
                update(sha, "TAG");
                update(sha, tag);
            });
            update(sha, "PARALLELISM");
            update(sha, Integer.toString(command.parallelism()));
            update(sha, "RETRY_MAX_ATTEMPTS");
            update(sha, Integer.toString(command.scenarioRetry().maxAttempts()));
            update(sha, "RETRY_DELAY_MILLISECONDS");
            update(sha, Integer.toString(command.scenarioRetry().delayMilliseconds()));
            update(sha, "EXECUTION_TIMEOUT_SECONDS");
            update(sha, Integer.toString(command.executionTimeoutSeconds()));
            update(sha, "ARTIFACT_TYPE_COUNT");
            update(sha, Integer.toString(command.artifactPolicy().types().size()));
            command.artifactPolicy().types().stream().map(Enum::name).sorted().forEach(type -> {
                update(sha, "ARTIFACT_TYPE");
                update(sha, type);
            });
            update(sha, "MAX_ARTIFACT_BYTES");
            update(sha, Long.toString(command.artifactPolicy().maxArtifactBytes()));
            update(sha, "MAX_TOTAL_BYTES");
            update(sha, Long.toString(command.artifactPolicy().maxTotalBytes()));
            return "sha256:" + HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * The command as the contract document, matching {@code runner-command.schema.json}.
     *
     * <p>Built field by field rather than by reflecting over the record, so a field added to the record does not
     * silently appear on the wire, and so the property names are the contract's rather than Java's.
     *
     * <p>The contract is {@code execution-command.schema.json}. It is deliberately <em>not</em>
     * {@code runner-command.schema.json}, which an earlier version of this comment named: that one is the broker
     * envelope, and this document is not a message.
     */
    public static ObjectNode document(ExecutionCommand command, ObjectMapper mapper) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("commandId", command.commandId().toString());
        root.put("commandDigest", command.commandDigest());
        root.put("organizationId", command.organizationId().toString());
        root.put("projectId", command.projectId().toString());
        root.put("runId", command.runId().toString());
        root.put("runVersion", command.runVersion());
        root.put("attemptId", command.attemptId().toString());
        root.put("attemptNumber", command.attemptNumber());
        root.put("assignmentEpoch", command.assignmentEpoch());
        root.put("runSnapshotDigest", "sha256:" + command.runSnapshotSha256());
        root.put("issuedAt", command.issuedAt().toString());
        root.put("expiresAt", command.expiresAt().toString());

        ObjectNode engine = root.putObject("engine");
        engine.put("type", command.engine().engine());
        engine.put("version", command.engine().version());

        ObjectNode bundle = root.putObject("sourceBundle");
        bundle.put("contentDigest", command.sourceBundle().contentDigest());
        ArrayNode features = bundle.putArray("features");
        command.sourceBundle().features().stream()
                .sorted(Comparator.comparing(SnapshotFeature::logicalPath))
                .forEach(feature -> {
                    ObjectNode node = features.addObject();
                    node.put("featureId", feature.featureId().toString());
                    node.put("revisionId", feature.revisionId().toString());
                    node.put("logicalPath", feature.logicalPath());
                    // Already prefixed. It used to be assembled here, which meant the value inside the record
                    // and the value in the document were different strings -- and the bundle digest computed
                    // from one did not match a digest computed from the other.
                    node.put("contentDigest", feature.sourceDigest());
                });

        ArrayNode secrets = root.putArray("secretCapabilities");
        command.secretCapabilities().stream()
                .sorted(Comparator.comparing(ExecutionCommand.SecretCapabilityReference::bindingKey))
                .forEach(secret -> {
                    ObjectNode node = secrets.addObject();
                    node.put("capabilityId", secret.capabilityId().toString());
                    node.put("provider", secret.provider());
                    node.put("referenceId", secret.referenceId());
                    node.put("bindingKey", secret.bindingKey());
                    node.put("expiresAt", secret.expiresAt().toString());
                });

        ObjectNode network = root.putObject("networkPolicy");
        network.put("policyRevisionId", command.networkPolicy().policyRevisionId().toString());
        network.put("type", command.networkPolicy().type().name());
        network.put("version", command.networkPolicy().version());
        network.put("digest", command.networkPolicy().digest());

        ObjectNode sandbox = root.putObject("sandboxSecurityProfile");
        sandbox.put("profileVersion", command.sandboxSecurityProfile().profileVersion());
        sandbox.put("sandboxRuntime", command.sandboxSecurityProfile().sandboxRuntime());
        sandbox.put("assessmentDigest", command.sandboxSecurityProfile().assessmentDigest());

        ObjectNode configuration = root.putObject("configurationSnapshot");
        command.configuration().stream()
                .sorted(Comparator.comparing(ConfigurationVariable::key))
                .forEach(value -> putScalar(configuration, value));

        ObjectNode selection = root.putObject("selection");
        ArrayNode tags = selection.putArray("tags");
        command.selection().tags().stream().sorted().forEach(tags::add);

        root.put("parallelism", command.parallelism());
        ObjectNode retry = root.putObject("scenarioRetry");
        retry.put("maxAttempts", command.scenarioRetry().maxAttempts());
        retry.put("delayMilliseconds", command.scenarioRetry().delayMilliseconds());
        root.put("executionTimeoutSeconds", command.executionTimeoutSeconds());

        ObjectNode artifacts = root.putObject("artifactPolicy");
        ArrayNode types = artifacts.putArray("types");
        command.artifactPolicy().types().stream().map(ArtifactType::name).sorted().forEach(types::add);
        artifacts.put("maxArtifactBytes", command.artifactPolicy().maxArtifactBytes());
        artifacts.put("maxTotalBytes", command.artifactPolicy().maxTotalBytes());
        return root;
    }

    private static void putScalar(ObjectNode target, ConfigurationVariable value) {
        switch (value.type()) {
            case STRING -> target.put(value.key(), String.valueOf(value.value()));
            case BOOLEAN -> target.put(value.key(), Boolean.parseBoolean(String.valueOf(value.value())));
            case INTEGER -> target.put(value.key(), Long.parseLong(String.valueOf(value.value())));
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    /** Feature list access used by the digest, kept here so callers cannot pass an unsorted view by accident. */
    /** A digest without its algorithm prefix. */
    private static String stripSha256(String digest) {
        return digest != null && digest.startsWith("sha256:") ? digest.substring("sha256:".length()) : digest;
    }

    public static List<SnapshotFeature> canonicalFeatures(List<SnapshotFeature> features) {
        return features.stream().sorted(Comparator.comparing(SnapshotFeature::logicalPath)).toList();
    }
}
