package com.kaas.api.controlplane.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunSnapshotPolicyTest {
    private static final UUID PROJECT = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID ENVIRONMENT = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID ENVIRONMENT_REVISION = UUID.fromString("30000000-0000-4000-8000-000000000003");
    private static final UUID PROFILE = UUID.fromString("40000000-0000-4000-8000-000000000004");
    private static final UUID PROFILE_REVISION = UUID.fromString("50000000-0000-4000-8000-000000000005");
    private static final UUID SECRET = UUID.fromString("60000000-0000-4000-8000-000000000006");

    @Test
    void materializationHasAGoldenDigestCanonicalOrderAndExactMerge() {
        RunSnapshot first = snapshot(List.of(feature("z.feature", 8), feature("a.feature", 7)), "2.0.0");
        RunSnapshot reordered = snapshot(List.of(feature("a.feature", 7), feature("z.feature", 8)), "2.0.0");

        assertThat(first.snapshotDigest())
                .isEqualTo("sha256:53a5de2ceda4d720b70e43ab3629ee57fc8093e77c47c69caa3ca21de9b62f88")
                .isEqualTo(reordered.snapshotDigest());
        assertThat(first.features()).extracting(SnapshotFeature::logicalPath)
                .containsExactly("a.feature", "z.feature");
        assertThat(first.effectiveConfiguration())
                .extracting(ConfigurationVariable::key, ConfigurationVariable::value)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("baseUrl", "https://override.example"),
                        org.assertj.core.groups.Tuple.tuple("timeout", 10_000L));
        assertThat(first.secretBindings()).containsExactly(new SecretBinding("clientSecret", SECRET));
        assertThat(snapshot(List.of(feature("a.feature", 7), feature("z.feature", 8)), "2.0.1")
                        .snapshotDigest())
                .isNotEqualTo(first.snapshotDigest());
    }

    @Test
    void runIdentityAndAuditDoNotAffectTheSemanticDigest() {
        RunSnapshot first = snapshot(List.of(feature("a.feature", 7)), "2.0.0");
        RunSnapshot otherRun = RunSnapshotPolicy.materialize(
                UUID.randomUUID(), PROJECT, first.features(), environment(), profile(), new EngineDescriptor("KARATE", "2.0.0"));
        assertThat(otherRun.snapshotDigest()).isEqualTo(first.snapshotDigest());
    }

    @Test
    void everySnapshotSemanticGroupAffectsTheDigest() {
        RunSnapshot baseline = snapshot(List.of(feature("a.feature", 7)), "2.0.0");

        assertThat(List.of(
                        semanticMutation(baseline, "project"),
                        semanticMutation(baseline, "feature"),
                        semanticMutation(baseline, "environment"),
                        semanticMutation(baseline, "profile"),
                        semanticMutation(baseline, "configuration"),
                        semanticMutation(baseline, "secret"),
                        semanticMutation(baseline, "selection"),
                        semanticMutation(baseline, "parallelism"),
                        semanticMutation(baseline, "retry"),
                        semanticMutation(baseline, "timeout"),
                        semanticMutation(baseline, "artifact"),
                        semanticMutation(baseline, "engine")))
                .allSatisfy(changed -> assertThat(RunSnapshotPolicy.digest(changed))
                        .isNotEqualTo(baseline.snapshotDigest()));
    }

    @Test
    void duplicateFeatureIdentityIsRejectedEvenWhenRevisionIdsDiffer() {
        SnapshotFeature first = feature("a.feature", 7);
        SnapshotFeature otherRevision = new SnapshotFeature(
                first.featureId(), UUID.randomUUID(), 8, first.logicalPath(), "sha256:" + "3".repeat(64));
        assertThatThrownBy(() -> RunSnapshotPolicy.materialize(
                        UUID.randomUUID(), PROJECT, List.of(first, otherRevision), environment(), profile(),
                        new EngineDescriptor("KARATE", "2.0.0")))
                .isInstanceOf(RunSnapshotPolicy.DuplicateFeatureSelectionException.class);
    }

    @Test
    void lifecycleOracleMatchesTheEstablishedTransitionTable() {
        assertThat(RunLifecycle.CREATED.canTransitionTo(RunLifecycle.QUEUED)).isTrue();
        assertThat(RunLifecycle.CREATED.canTransitionTo(RunLifecycle.COMPLETED)).isTrue();
        assertThat(RunLifecycle.CREATED.canTransitionTo(RunLifecycle.RUNNING)).isFalse();
        assertThat(RunLifecycle.CLAIMED.canTransitionTo(RunLifecycle.STOPPING)).isTrue();
        assertThat(RunLifecycle.CLAIMED.canTransitionTo(RunLifecycle.COMPLETED)).isFalse();
        assertThat(RunLifecycle.RUNNING.canTransitionTo(RunLifecycle.STOPPING)).isTrue();
        assertThat(RunLifecycle.PROCESSING_RESULTS.canTransitionTo(RunLifecycle.COMPLETED)).isTrue();
        assertThat(RunLifecycle.values()).filteredOn(RunLifecycle::terminal).containsExactly(RunLifecycle.COMPLETED);
        assertThat(RunLifecycle.COMPLETED.canTransitionTo(RunLifecycle.CREATED)).isFalse();
        assertThatThrownBy(() -> new EngineDescriptor("KARATE", "latest"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RunSnapshot snapshot(List<SnapshotFeature> features, String engineVersion) {
        return RunSnapshotPolicy.materialize(
                UUID.randomUUID(), PROJECT, features, environment(), profile(), new EngineDescriptor("KARATE", engineVersion));
    }

    private static SnapshotFeature feature(String path, int suffix) {
        return new SnapshotFeature(
                UUID.fromString("70000000-0000-4000-8000-%012d".formatted(suffix)),
                UUID.fromString("80000000-0000-4000-8000-%012d".formatted(suffix)),
                suffix,
                path,
                "sha256:" + Integer.toHexString(suffix).repeat(64).substring(0, 64));
    }

    private static RunSnapshot semanticMutation(RunSnapshot value, String dimension) {
        SnapshotFeature feature = value.features().getFirst();
        return new RunSnapshot(
                value.runId(),
                dimension.equals("project") ? UUID.randomUUID() : value.projectId(),
                value.snapshotVersion(),
                dimension.equals("feature")
                        ? List.of(new SnapshotFeature(
                                feature.featureId(), feature.revisionId(), feature.revisionNumber(),
                                feature.logicalPath(), "sha256:" + "a".repeat(64)))
                        : value.features(),
                dimension.equals("environment")
                        ? new SnapshotRevision(
                                value.environment().resourceId(), value.environment().revisionId(),
                                value.environment().revisionNumber() + 1, value.environment().contentDigest())
                        : value.environment(),
                dimension.equals("profile")
                        ? new SnapshotRevision(
                                value.runProfile().resourceId(), value.runProfile().revisionId(),
                                value.runProfile().revisionNumber(), "sha256:" + "b".repeat(64))
                        : value.runProfile(),
                dimension.equals("configuration")
                        ? List.of(new ConfigurationVariable(
                                "baseUrl", ConfigurationValueType.STRING, "https://changed.example"))
                        : value.effectiveConfiguration(),
                dimension.equals("secret")
                        ? List.of(new SecretBinding("clientSecret", UUID.randomUUID()))
                        : value.secretBindings(),
                dimension.equals("selection") ? new RunSelection(List.of("@changed")) : value.selection(),
                dimension.equals("parallelism") ? value.parallelism() + 1 : value.parallelism(),
                dimension.equals("retry")
                        ? new ScenarioRetry(value.scenarioRetry().maxAttempts() + 1, value.scenarioRetry().delayMilliseconds())
                        : value.scenarioRetry(),
                dimension.equals("timeout") ? value.executionTimeoutSeconds() + 1 : value.executionTimeoutSeconds(),
                dimension.equals("artifact")
                        ? new ArtifactPolicy(List.of(ArtifactType.OTHER), 10, 20)
                        : value.artifactPolicy(),
                dimension.equals("engine") ? new EngineDescriptor("KARATE", "2.0.1") : value.engine(),
                value.snapshotDigest());
    }

    private static EnvironmentRevision environment() {
        var content = ConfigurationPolicy.environment(
                List.of(
                        new ConfigurationVariable("timeout", ConfigurationValueType.INTEGER, 10_000L),
                        new ConfigurationVariable("baseUrl", ConfigurationValueType.STRING, "https://environment.example")),
                List.of(new SecretBinding("clientSecret", SECRET)));
        return new EnvironmentRevision(
                ENVIRONMENT_REVISION,
                ENVIRONMENT,
                PROJECT,
                3,
                content.variables(),
                content.secretBindings(),
                content.digest(),
                "creator",
                Instant.EPOCH);
    }

    private static RunProfileRevision profile() {
        EnvironmentRevision environment = environment();
        var content = ConfigurationPolicy.runProfile(
                environment,
                List.of("@smoke", "@regression"),
                4,
                new ScenarioRetry(2, 250),
                300,
                new ArtifactPolicy(List.of(ArtifactType.RAW_RESULT, ArtifactType.EXECUTION_LOG), 1_000, 2_000),
                List.of(new ConfigurationVariable(
                        "baseUrl", ConfigurationValueType.STRING, "https://override.example")));
        return new RunProfileRevision(
                PROFILE_REVISION,
                PROFILE,
                PROJECT,
                5,
                ENVIRONMENT_REVISION,
                content.selection(),
                content.parallelism(),
                content.scenarioRetry(),
                content.executionTimeoutSeconds(),
                content.artifactPolicy(),
                content.configurationOverrides(),
                content.digest(),
                "creator",
                Instant.EPOCH);
    }
}
