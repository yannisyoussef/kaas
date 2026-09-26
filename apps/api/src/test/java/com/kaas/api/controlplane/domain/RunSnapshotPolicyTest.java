package com.kaas.api.controlplane.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunSnapshotPolicyTest {
    private static final UUID PROJECT = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID ENVIRONMENT = UUID.fromString("20000000-0000-4000-8000-000000000002");
    private static final UUID ENVIRONMENT_REVISION = UUID.fromString("30000000-0000-4000-8000-000000000003");
    private static final UUID PROFILE = UUID.fromString("40000000-0000-4000-8000-000000000004");
    private static final UUID PROFILE_REVISION = UUID.fromString("50000000-0000-4000-8000-000000000005");
    private static final UUID SECRET = UUID.fromString("60000000-0000-4000-8000-000000000006");

    /** The version the snapshot pins for SECRET. Resolved from metadata by the caller, never decrypted. */
    private static final Map<UUID, Integer> ACTIVE = Map.of(SECRET, 3);

    @Test
    void materializationHasAGoldenDigestCanonicalOrderAndExactMerge() {
        RunSnapshot first = snapshot(List.of(feature("z.feature", 8), feature("a.feature", 7)), "2.0.0");
        RunSnapshot reordered = snapshot(List.of(feature("a.feature", 7), feature("z.feature", 8)), "2.0.0");

        // Changed in KAAS-22, deliberately: this snapshot binds a secret, and the pinned version is now part of
        // what the run is. The previous vector was sha256:53a5de2c... and described a run that named a secret
        // without saying which version of it -- the ambiguity this change removes.
        assertThat(first.snapshotDigest())
                .isEqualTo("sha256:796b513e8f1b5bd35536e9fe48c9386c8133949e2bcb765f424389e19e50d304")
                .isEqualTo(reordered.snapshotDigest());
        assertThat(first.features()).extracting(SnapshotFeature::logicalPath)
                .containsExactly("a.feature", "z.feature");
        assertThat(first.effectiveConfiguration())
                .extracting(ConfigurationVariable::key, ConfigurationVariable::value)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("baseUrl", "https://override.example"),
                        org.assertj.core.groups.Tuple.tuple("timeout", 10_000L));
        assertThat(first.secretBindings()).containsExactly(new PinnedSecretBinding("clientSecret", SECRET, 3));
        assertThat(snapshot(List.of(feature("a.feature", 7), feature("z.feature", 8)), "2.0.1")
                        .snapshotDigest())
                .isNotEqualTo(first.snapshotDigest());
    }

    @Test
    void runIdentityAndAuditDoNotAffectTheSemanticDigest() {
        RunSnapshot first = snapshot(List.of(feature("a.feature", 7)), "2.0.0");
        RunSnapshot otherRun = RunSnapshotPolicy.materialize(
                UUID.randomUUID(), PROJECT, first.features(), environment(), profile(), new EngineDescriptor("KARATE", "2.0.0"),
                ACTIVE);
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
                        semanticMutation(baseline, "secretVersion"),
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
                        new EngineDescriptor("KARATE", "2.0.0"), ACTIVE))
                .isInstanceOf(RunSnapshotPolicy.DuplicateFeatureSelectionException.class);
    }

    @Test
    void aRunPinsTheVersionItWasCreatedWithAndRefusesASecretWithNone() {
        RunSnapshot pinnedToThree = snapshot(List.of(feature("a.feature", 7)), "2.0.0");
        RunSnapshot pinnedToFour = RunSnapshotPolicy.materialize(
                UUID.randomUUID(), PROJECT, pinnedToThree.features(), environment(), profile(),
                new EngineDescriptor("KARATE", "2.0.0"), Map.of(SECRET, 4));

        // A rotation between two runs changes the second run and nothing about the first.
        assertThat(pinnedToThree.secretBindings().getFirst().version()).isEqualTo(3);
        assertThat(pinnedToFour.secretBindings().getFirst().version()).isEqualTo(4);
        assertThat(pinnedToFour.snapshotDigest()).isNotEqualTo(pinnedToThree.snapshotDigest());

        // No usable version -- never written, or all revoked -- is refused at creation. A snapshot is immutable,
        // so one without a version for a binding would be a run that could never execute.
        assertThatThrownBy(() -> RunSnapshotPolicy.materialize(
                        UUID.randomUUID(), PROJECT, pinnedToThree.features(), environment(), profile(),
                        new EngineDescriptor("KARATE", "2.0.0"), Map.of()))
                .isInstanceOf(SecretVersionUnavailableException.class);
    }

    @Test
    void aSecretFreeSnapshotDigestsExactlyAsItDidBeforeVersionsExisted() {
        // Pinned from before KAAS-22: an environment with no secret bindings produces the same digest, because
        // the version is fed to the digest only inside the per-binding loop. A change that altered secret-free
        // digests would change the identity of every existing run.
        var content = ConfigurationPolicy.environment(
                List.of(new ConfigurationVariable("timeout", ConfigurationValueType.INTEGER, 10_000L)), List.of());
        var environment = new EnvironmentRevision(
                ENVIRONMENT_REVISION, ENVIRONMENT, PROJECT, 3, content.variables(), content.secretBindings(),
                content.digest(), "creator", Instant.EPOCH);
        var profileContent = ConfigurationPolicy.runProfile(
                environment, List.of("@smoke"), 1, new ScenarioRetry(1, 0), 300,
                new ArtifactPolicy(List.of(ArtifactType.RAW_RESULT), 1_000, 2_000), List.of());
        var profile = new RunProfileRevision(
                PROFILE_REVISION, PROFILE, PROJECT, 5, ENVIRONMENT_REVISION, profileContent.selection(),
                profileContent.parallelism(), profileContent.scenarioRetry(), profileContent.executionTimeoutSeconds(),
                profileContent.artifactPolicy(), profileContent.configurationOverrides(), profileContent.digest(),
                "creator", Instant.EPOCH);
        RunSnapshot secretFree = RunSnapshotPolicy.materialize(
                UUID.randomUUID(), PROJECT, List.of(feature("a.feature", 7)), environment, profile,
                new EngineDescriptor("KARATE", "2.1.2"), Map.of());
        assertThat(secretFree.secretBindings()).isEmpty();
        // Computed by the pre-KAAS-22 RunSnapshotPolicy (commit d1ad2ff) over the same inputs, and identical: the
        // value below is what that code produced, not what this code produces and was then copied.
        assertThat(secretFree.snapshotDigest())
                .isEqualTo("sha256:9828b56d194a5dffd464b9b759000f8678cb02be86385f6d21fe1a12d9e595c0");
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
                UUID.randomUUID(), PROJECT, features, environment(), profile(), new EngineDescriptor("KARATE", engineVersion),
                ACTIVE);
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
                        ? List.of(new PinnedSecretBinding("clientSecret", UUID.randomUUID(), 3))
                        : dimension.equals("secretVersion")
                                ? List.of(new PinnedSecretBinding("clientSecret", SECRET, 4))
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
