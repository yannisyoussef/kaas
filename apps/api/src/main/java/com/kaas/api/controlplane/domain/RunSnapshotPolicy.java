package com.kaas.api.controlplane.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class RunSnapshotPolicy {
    private static final String FORMAT = "kaas.run-snapshot-content.v1";
    private static final Comparator<SnapshotFeature> FEATURE_ORDER = Comparator
            .comparing(SnapshotFeature::logicalPath)
            .thenComparing(feature -> feature.featureId().toString())
            .thenComparing(feature -> feature.revisionId().toString());

    private RunSnapshotPolicy() {}

    public static RunSnapshot materialize(
            UUID runId,
            UUID projectId,
            List<SnapshotFeature> selectedFeatures,
            EnvironmentRevision environment,
            RunProfileRevision profile,
            EngineDescriptor engine) {
        requireValidSources(projectId, selectedFeatures, environment, profile);
        List<SnapshotFeature> features = selectedFeatures.stream().sorted(FEATURE_ORDER).toList();
        requireUniqueFeatures(features);

        Map<String, ConfigurationVariable> effective = new HashMap<>();
        environment.variables().forEach(value -> effective.put(value.key(), value));
        profile.configurationOverrides().forEach(value -> effective.put(value.key(), value));
        List<ConfigurationVariable> configuration = effective.values().stream()
                .sorted(Comparator.comparing(ConfigurationVariable::key))
                .toList();
        List<SecretBinding> secrets = environment.secretBindings().stream()
                .sorted(Comparator.comparing(SecretBinding::key))
                .toList();
        List<String> tags = profile.selection().tags().stream().sorted().toList();
        List<ArtifactType> artifactTypes = profile.artifactPolicy().types().stream()
                .sorted(Comparator.comparing(Enum::name))
                .toList();

        var snapshot = new RunSnapshot(
                runId,
                projectId,
                1,
                features,
                new SnapshotRevision(
                        environment.environmentId(),
                        environment.revisionId(),
                        environment.revisionNumber(),
                        environment.contentDigest()),
                new SnapshotRevision(
                        profile.runProfileId(),
                        profile.revisionId(),
                        profile.revisionNumber(),
                        profile.contentDigest()),
                configuration,
                secrets,
                new RunSelection(tags),
                profile.parallelism(),
                profile.scenarioRetry(),
                profile.executionTimeoutSeconds(),
                new ArtifactPolicy(
                        artifactTypes,
                        profile.artifactPolicy().maxArtifactBytes(),
                        profile.artifactPolicy().maxTotalBytes()),
                engine,
                "");
        return withDigest(snapshot, digest(snapshot));
    }

    public static String digest(RunSnapshot snapshot) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, FORMAT);
            update(digest, Integer.toString(snapshot.snapshotVersion()));
            update(digest, snapshot.projectId().toString());
            update(digest, "FEATURE_COUNT");
            update(digest, Integer.toString(snapshot.features().size()));
            for (SnapshotFeature feature : snapshot.features().stream().sorted(FEATURE_ORDER).toList()) {
                update(digest, "FEATURE");
                update(digest, feature.featureId().toString());
                update(digest, feature.revisionId().toString());
                update(digest, Long.toString(feature.revisionNumber()));
                update(digest, feature.logicalPath());
                update(digest, feature.sourceDigest());
            }
            revision(digest, "ENVIRONMENT", snapshot.environment());
            revision(digest, "RUN_PROFILE", snapshot.runProfile());
            update(digest, "CONFIGURATION_COUNT");
            update(digest, Integer.toString(snapshot.effectiveConfiguration().size()));
            for (ConfigurationVariable value : snapshot.effectiveConfiguration().stream()
                    .sorted(Comparator.comparing(ConfigurationVariable::key))
                    .toList()) {
                update(digest, "CONFIGURATION");
                update(digest, value.key());
                update(digest, value.type().name());
                update(digest, String.valueOf(value.value()));
            }
            update(digest, "SECRET_REFERENCE_COUNT");
            update(digest, Integer.toString(snapshot.secretBindings().size()));
            for (SecretBinding binding : snapshot.secretBindings().stream()
                    .sorted(Comparator.comparing(SecretBinding::key))
                    .toList()) {
                update(digest, "SECRET_REFERENCE");
                update(digest, binding.key());
                update(digest, binding.secretReferenceId().toString());
            }
            update(digest, "TAG_COUNT");
            update(digest, Integer.toString(snapshot.selection().tags().size()));
            snapshot.selection().tags().stream().sorted().forEach(tag -> {
                update(digest, "TAG");
                update(digest, tag);
            });
            update(digest, "PARALLELISM");
            update(digest, Integer.toString(snapshot.parallelism()));
            update(digest, "RETRY_MAX_ATTEMPTS");
            update(digest, Integer.toString(snapshot.scenarioRetry().maxAttempts()));
            update(digest, "RETRY_DELAY_MILLISECONDS");
            update(digest, Integer.toString(snapshot.scenarioRetry().delayMilliseconds()));
            update(digest, "EXECUTION_TIMEOUT_SECONDS");
            update(digest, Integer.toString(snapshot.executionTimeoutSeconds()));
            update(digest, "ARTIFACT_TYPE_COUNT");
            update(digest, Integer.toString(snapshot.artifactPolicy().types().size()));
            snapshot.artifactPolicy().types().stream().map(Enum::name).sorted().forEach(type -> {
                update(digest, "ARTIFACT_TYPE");
                update(digest, type);
            });
            update(digest, "MAX_ARTIFACT_BYTES");
            update(digest, Long.toString(snapshot.artifactPolicy().maxArtifactBytes()));
            update(digest, "MAX_TOTAL_BYTES");
            update(digest, Long.toString(snapshot.artifactPolicy().maxTotalBytes()));
            update(digest, "ENGINE");
            update(digest, snapshot.engine().engine());
            update(digest, snapshot.engine().version());
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void requireUniqueFeatures(List<SnapshotFeature> features) {
        Set<UUID> revisions = new HashSet<>();
        Set<UUID> identities = new HashSet<>();
        Set<String> paths = new HashSet<>();
        for (SnapshotFeature feature : features) {
            if (!revisions.add(feature.revisionId()) || !identities.add(feature.featureId()) || !paths.add(feature.logicalPath())) {
                throw new DuplicateFeatureSelectionException();
            }
        }
    }

    private static void requireValidSources(
            UUID projectId,
            List<SnapshotFeature> features,
            EnvironmentRevision environment,
            RunProfileRevision profile) {
        if (features == null
                || features.isEmpty()
                || features.size() > 1000
                || !projectId.equals(environment.projectId())
                || !projectId.equals(profile.projectId())
                || !profile.environmentRevisionId().equals(environment.revisionId())) {
            throw new IllegalArgumentException("Snapshot inputs do not belong to one exact project configuration.");
        }
        var environmentContent = ConfigurationPolicy.environment(environment.variables(), environment.secretBindings());
        if (!environmentContent.digest().equals(environment.contentDigest())) {
            throw new IllegalArgumentException("The EnvironmentRevision content digest is inconsistent.");
        }
        var profileContent = ConfigurationPolicy.runProfile(
                environment,
                profile.selection().tags(),
                profile.parallelism(),
                profile.scenarioRetry(),
                profile.executionTimeoutSeconds(),
                profile.artifactPolicy(),
                profile.configurationOverrides());
        if (!profileContent.digest().equals(profile.contentDigest())) {
            throw new IllegalArgumentException("The RunProfileRevision content digest is inconsistent.");
        }
    }

    private static RunSnapshot withDigest(RunSnapshot value, String digest) {
        return new RunSnapshot(
                value.runId(), value.projectId(), value.snapshotVersion(), value.features(), value.environment(),
                value.runProfile(), value.effectiveConfiguration(), value.secretBindings(), value.selection(),
                value.parallelism(), value.scenarioRetry(), value.executionTimeoutSeconds(), value.artifactPolicy(),
                value.engine(), digest);
    }

    private static void revision(MessageDigest digest, String kind, SnapshotRevision revision) {
        update(digest, kind);
        update(digest, revision.resourceId().toString());
        update(digest, revision.revisionId().toString());
        update(digest, Long.toString(revision.revisionNumber()));
        update(digest, revision.contentDigest());
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    public static final class DuplicateFeatureSelectionException extends RuntimeException {}
}
