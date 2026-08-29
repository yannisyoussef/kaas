package com.kaas.api.controlplane.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ConfigurationPolicyTest {
    private static final UUID SECRET_REFERENCE =
            UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test
    void environmentDigestHasAGoldenVectorAndIgnoresInputOrder() {
        var first = ConfigurationPolicy.environment(
                List.of(
                        variable("timeout", ConfigurationValueType.INTEGER, 10_000L),
                        variable("baseUrl", ConfigurationValueType.STRING, "https://qa.example"),
                        variable("flag", ConfigurationValueType.BOOLEAN, true)),
                List.of(new SecretBinding("clientSecret", SECRET_REFERENCE)));
        var reordered = ConfigurationPolicy.environment(
                List.of(
                        variable("flag", ConfigurationValueType.BOOLEAN, true),
                        variable("baseUrl", ConfigurationValueType.STRING, "https://qa.example"),
                        variable("timeout", ConfigurationValueType.INTEGER, 10_000)),
                List.of(new SecretBinding("clientSecret", SECRET_REFERENCE)));

        assertThat(first.digest())
                .isEqualTo("sha256:b8351d1f6fe3c3215fb7470eb2b3d972bf205fbdbe9191fc03ca59dc90284637")
                .isEqualTo(reordered.digest());
        assertThat(first.variables()).extracting(ConfigurationVariable::key)
                .containsExactly("baseUrl", "flag", "timeout");

        assertThat(ConfigurationPolicy.environment(
                                List.of(
                                        variable("timeout", ConfigurationValueType.INTEGER, 10_001L),
                                        variable("baseUrl", ConfigurationValueType.STRING, "https://qa.example"),
                                        variable("flag", ConfigurationValueType.BOOLEAN, true)),
                                List.of(new SecretBinding("clientSecret", SECRET_REFERENCE)))
                        .digest())
                .isNotEqualTo(first.digest());
        assertThat(ConfigurationPolicy.environment(
                                List.of(
                                        variable("timeout", ConfigurationValueType.INTEGER, 10_000L),
                                        variable("baseUri", ConfigurationValueType.STRING, "https://qa.example"),
                                        variable("flag", ConfigurationValueType.BOOLEAN, true)),
                                List.of(new SecretBinding("clientSecret", SECRET_REFERENCE)))
                        .digest())
                .isNotEqualTo(first.digest());
        assertThat(ConfigurationPolicy.environment(
                                List.of(
                                        variable("timeout", ConfigurationValueType.INTEGER, 10_000L),
                                        variable("baseUrl", ConfigurationValueType.STRING, "https://qa.example"),
                                        variable("flag", ConfigurationValueType.STRING, "true")),
                                List.of(new SecretBinding("clientSecret", SECRET_REFERENCE)))
                        .digest())
                .isNotEqualTo(first.digest());
        assertThat(ConfigurationPolicy.environment(
                                first.variables(),
                                List.of(new SecretBinding(
                                        "clientSecret",
                                        UUID.fromString("99999999-9999-4999-8999-999999999999"))))
                        .digest())
                .isNotEqualTo(first.digest());
    }

    @Test
    void runProfileDigestHasAGoldenVectorAndSortsSemanticSets() {
        var content = ConfigurationPolicy.environment(
                List.of(
                        variable("timeout", ConfigurationValueType.INTEGER, 10_000L),
                        variable("baseUrl", ConfigurationValueType.STRING, "https://qa.example"),
                        variable("flag", ConfigurationValueType.BOOLEAN, true)),
                List.of(new SecretBinding("clientSecret", SECRET_REFERENCE)));
        EnvironmentRevision environmentRevision = new EnvironmentRevision(
                UUID.fromString("33333333-3333-4333-8333-333333333333"),
                UUID.fromString("22222222-2222-4222-8222-222222222222"),
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                7,
                content.variables(),
                content.secretBindings(),
                content.digest(),
                "member",
                Instant.EPOCH);
        var first = ConfigurationPolicy.runProfile(
                environmentRevision,
                List.of("@smoke", "@regression"),
                4,
                new ScenarioRetry(1, 0),
                300,
                new ArtifactPolicy(List.of(ArtifactType.RAW_RESULT, ArtifactType.EXECUTION_LOG), 1_000, 2_000),
                List.of(variable("baseUrl", ConfigurationValueType.STRING, "https://override")));
        var reordered = ConfigurationPolicy.runProfile(
                environmentRevision,
                List.of("@regression", "@smoke"),
                4,
                new ScenarioRetry(1, 0),
                300,
                new ArtifactPolicy(List.of(ArtifactType.EXECUTION_LOG, ArtifactType.RAW_RESULT), 1_000, 2_000),
                List.of(variable("baseUrl", ConfigurationValueType.STRING, "https://override")));

        assertThat(first.digest())
                .isEqualTo("sha256:76eaf477180f86e0893a9e00e95280354adb692462a17544803f592b055d0bb0")
                .isEqualTo(reordered.digest());
        assertThat(ConfigurationPolicy.runProfile(
                                environmentRevision,
                                first.selection().tags(),
                                5,
                                first.scenarioRetry(),
                                first.executionTimeoutSeconds(),
                                first.artifactPolicy(),
                                first.configurationOverrides())
                        .digest())
                .isNotEqualTo(first.digest());
        EnvironmentRevision anotherEnvironmentRevision = new EnvironmentRevision(
                UUID.fromString("55555555-5555-4555-8555-555555555555"),
                environmentRevision.environmentId(),
                environmentRevision.projectId(),
                8,
                content.variables(),
                content.secretBindings(),
                content.digest(),
                "member",
                Instant.EPOCH);
        assertThat(profileDigest(anotherEnvironmentRevision, first.selection().tags(), first.scenarioRetry(), 300,
                                first.artifactPolicy(), first.configurationOverrides()))
                .isNotEqualTo(first.digest());
        EnvironmentRevision changedEnvironmentDigest = new EnvironmentRevision(
                environmentRevision.revisionId(),
                environmentRevision.environmentId(),
                environmentRevision.projectId(),
                environmentRevision.revisionNumber(),
                content.variables(),
                content.secretBindings(),
                "sha256:" + "0".repeat(64),
                "member",
                Instant.EPOCH);
        assertThat(profileDigest(changedEnvironmentDigest, first.selection().tags(), first.scenarioRetry(), 300,
                                first.artifactPolicy(), first.configurationOverrides()))
                .isNotEqualTo(first.digest());
        assertThat(profileDigest(environmentRevision, List.of("@other"), first.scenarioRetry(), 300,
                                first.artifactPolicy(), first.configurationOverrides()))
                .isNotEqualTo(first.digest());
        assertThat(profileDigest(environmentRevision, first.selection().tags(), new ScenarioRetry(2, 0), 300,
                                first.artifactPolicy(), first.configurationOverrides()))
                .isNotEqualTo(first.digest());
        assertThat(profileDigest(environmentRevision, first.selection().tags(), new ScenarioRetry(1, 1), 300,
                                first.artifactPolicy(), first.configurationOverrides()))
                .isNotEqualTo(first.digest());
        assertThat(profileDigest(environmentRevision, first.selection().tags(), first.scenarioRetry(), 301,
                                first.artifactPolicy(), first.configurationOverrides()))
                .isNotEqualTo(first.digest());
        assertThat(profileDigest(
                        environmentRevision,
                        first.selection().tags(),
                        first.scenarioRetry(),
                        300,
                        new ArtifactPolicy(List.of(ArtifactType.RAW_RESULT), 1_000, 2_000),
                        first.configurationOverrides()))
                .isNotEqualTo(first.digest());
        assertThat(profileDigest(
                        environmentRevision,
                        first.selection().tags(),
                        first.scenarioRetry(),
                        300,
                        new ArtifactPolicy(first.artifactPolicy().types(), 999, 2_000),
                        first.configurationOverrides()))
                .isNotEqualTo(first.digest());
        assertThat(profileDigest(
                        environmentRevision,
                        first.selection().tags(),
                        first.scenarioRetry(),
                        300,
                        first.artifactPolicy(),
                        List.of(variable("baseUrl", ConfigurationValueType.STRING, "https://different"))))
                .isNotEqualTo(first.digest());
        assertThat(profileDigest(
                        environmentRevision,
                        first.selection().tags(),
                        first.scenarioRetry(),
                        300,
                        first.artifactPolicy(),
                        List.of(variable("added", ConfigurationValueType.BOOLEAN, true))))
                .isNotEqualTo(first.digest());
        assertThat(profileDigest(
                        environmentRevision,
                        first.selection().tags(),
                        first.scenarioRetry(),
                        300,
                        first.artifactPolicy(),
                        List.of(variable("added", ConfigurationValueType.STRING, "true"))))
                .isNotEqualTo(profileDigest(
                        environmentRevision,
                        first.selection().tags(),
                        first.scenarioRetry(),
                        300,
                        first.artifactPolicy(),
                        List.of(variable("added", ConfigurationValueType.BOOLEAN, true))));
        assertThat(profileDigest(
                        environmentRevision,
                        first.selection().tags(),
                        first.scenarioRetry(),
                        300,
                        new ArtifactPolicy(first.artifactPolicy().types(), 1_000, 2_001),
                        first.configurationOverrides()))
                .isNotEqualTo(first.digest());
    }

    @Test
    void validationRejectsAmbiguousUnsafeAndCrossKindConfiguration() {
        assertThatThrownBy(() -> ConfigurationPolicy.environment(
                        List.of(
                                variable("same", ConfigurationValueType.STRING, "one"),
                                variable("same", ConfigurationValueType.STRING, "two")),
                        List.of()))
                .isInstanceOf(ConfigurationPolicy.ValidationException.class);
        assertThatThrownBy(() -> ConfigurationPolicy.environment(
                        List.of(variable("same", ConfigurationValueType.STRING, "value")),
                        List.of(new SecretBinding("same", SECRET_REFERENCE))))
                .isInstanceOf(ConfigurationPolicy.ValidationException.class);
        assertThatThrownBy(() -> ConfigurationPolicy.environment(
                        List.of(),
                        List.of(
                                new SecretBinding("same", SECRET_REFERENCE),
                                new SecretBinding("same", UUID.randomUUID()))))
                .isInstanceOf(ConfigurationPolicy.ValidationException.class);
        assertThatThrownBy(() -> ConfigurationPolicy.environment(
                        List.of(variable("bad", ConfigurationValueType.INTEGER, 1.5)), List.of()))
                .isInstanceOf(ConfigurationPolicy.ValidationException.class);
        assertThatThrownBy(() -> ConfigurationPolicy.environment(
                        List.of(variable("bad", ConfigurationValueType.BOOLEAN, "true")), List.of()))
                .isInstanceOf(ConfigurationPolicy.ValidationException.class);
        assertThatThrownBy(() -> ConfigurationPolicy.environment(
                        List.of(variable("bad", ConfigurationValueType.STRING, "a\u0000b")), List.of()))
                .isInstanceOf(ConfigurationPolicy.ValidationException.class);

        var environmentContent = ConfigurationPolicy.environment(
                List.of(variable("plain", ConfigurationValueType.STRING, "value")),
                List.of(new SecretBinding("secret", SECRET_REFERENCE)));
        EnvironmentRevision revision = new EnvironmentRevision(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                environmentContent.variables(),
                environmentContent.secretBindings(),
                environmentContent.digest(),
                "member",
                Instant.EPOCH);
        assertThatThrownBy(() -> ConfigurationPolicy.runProfile(
                        revision,
                        List.of(),
                        1,
                        new ScenarioRetry(1, 0),
                        60,
                        new ArtifactPolicy(List.of(), 0, 0),
                        List.of(variable("secret", ConfigurationValueType.STRING, "not allowed"))))
                .isInstanceOf(ConfigurationPolicy.ConfigurationConflictException.class);
        assertThatThrownBy(() -> ConfigurationPolicy.runProfile(
                        revision,
                        List.of(),
                        1,
                        new ScenarioRetry(1, 0),
                        60,
                        new ArtifactPolicy(List.of(), 0, 0),
                        List.of(variable("plain", ConfigurationValueType.BOOLEAN, true))))
                .isInstanceOf(ConfigurationPolicy.ConfigurationConflictException.class);
    }

    @Test
    void exactConfigurationAndExecutionBoundariesAreEnforced() {
        List<ConfigurationVariable> maximumVariables = IntStream.range(0, 100)
                .mapToObj(index -> variable("key" + index, ConfigurationValueType.INTEGER, index))
                .toList();
        List<SecretBinding> maximumBindings = IntStream.range(0, 50)
                .mapToObj(index -> new SecretBinding("secret" + index, UUID.randomUUID()))
                .toList();
        assertThat(ConfigurationPolicy.environment(maximumVariables, maximumBindings).variables()).hasSize(100);
        assertThat(ConfigurationPolicy.environment(
                                List.of(variable(
                                        "k" + "a".repeat(127), ConfigurationValueType.BOOLEAN, true)),
                                List.of())
                        .variables())
                .hasSize(1);
        assertThatThrownBy(() -> ConfigurationPolicy.environment(
                        List.of(variable(
                                "k" + "a".repeat(128), ConfigurationValueType.BOOLEAN, true)),
                        List.of()))
                .isInstanceOf(ConfigurationPolicy.ValidationException.class);
        assertThatThrownBy(() -> ConfigurationPolicy.environment(
                        IntStream.range(0, 101)
                                .mapToObj(index -> variable("key" + index, ConfigurationValueType.INTEGER, index))
                                .toList(),
                        List.of()))
                .isInstanceOf(ConfigurationPolicy.ValidationException.class);
        assertThatThrownBy(() -> ConfigurationPolicy.environment(
                        List.of(),
                        IntStream.range(0, 51)
                                .mapToObj(index -> new SecretBinding("secret" + index, UUID.randomUUID()))
                                .toList()))
                .isInstanceOf(ConfigurationPolicy.ValidationException.class);

        String exactUtf8Limit = "é".repeat(2048);
        assertThat(ConfigurationPolicy.environment(
                                List.of(variable("text", ConfigurationValueType.STRING, exactUtf8Limit)),
                                List.of())
                        .variables())
                .singleElement()
                .extracting(ConfigurationVariable::value)
                .isEqualTo(exactUtf8Limit);
        assertThatThrownBy(() -> ConfigurationPolicy.environment(
                        List.of(variable("text", ConfigurationValueType.STRING, exactUtf8Limit + "é")),
                        List.of()))
                .isInstanceOf(ConfigurationPolicy.ValidationException.class);
        assertThat(ConfigurationPolicy.environment(
                                List.of(
                                        variable("min", ConfigurationValueType.INTEGER, -9_007_199_254_740_991L),
                                        variable("max", ConfigurationValueType.INTEGER, 9_007_199_254_740_991L)),
                                List.of())
                        .variables())
                .hasSize(2);
        assertThatThrownBy(() -> ConfigurationPolicy.environment(
                        List.of(variable(
                                "tooLarge", ConfigurationValueType.INTEGER, 9_007_199_254_740_992L)),
                        List.of()))
                .isInstanceOf(ConfigurationPolicy.ValidationException.class);

        EnvironmentRevision emptyEnvironment = environmentRevision(List.of(), List.of());
        assertThatThrownBy(() -> ConfigurationPolicy.runProfile(
                        emptyEnvironment,
                        List.of(),
                        1,
                        new ScenarioRetry(1, 0),
                        60,
                        new ArtifactPolicy(List.of(), -1, 0),
                        List.of()))
                .isInstanceOf(ConfigurationPolicy.ValidationException.class);
        List<String> maximumTags = IntStream.range(0, 100).mapToObj(index -> "@tag" + index).toList();
        List<ConfigurationVariable> maximumOverrides = IntStream.range(0, 100)
                .mapToObj(index -> variable("override" + index, ConfigurationValueType.BOOLEAN, true))
                .toList();
        var maximumProfile = ConfigurationPolicy.runProfile(
                emptyEnvironment,
                maximumTags,
                32,
                new ScenarioRetry(5, 30_000),
                ConfigurationPolicy.MAX_EXECUTION_TIMEOUT_SECONDS,
                new ArtifactPolicy(List.of(ArtifactType.values()), 104_857_600, 524_288_000),
                maximumOverrides);
        assertThat(maximumProfile.selection().tags()).hasSize(100);
        assertThat(maximumProfile.configurationOverrides()).hasSize(100);

        // One past the ceiling. The bound is matched to the sandbox's own wall-clock limit, so a value the
        // runtime could never honour cannot be sealed into an immutable snapshot.
        assertInvalidProfile(
                emptyEnvironment, List.of(), 1, new ScenarioRetry(1, 0),
                ConfigurationPolicy.MAX_EXECUTION_TIMEOUT_SECONDS + 1);
        assertInvalidProfile(emptyEnvironment, List.of(), 0, new ScenarioRetry(1, 0), 60);
        assertInvalidProfile(emptyEnvironment, maximumTags, 33, new ScenarioRetry(1, 0), 60);
        assertInvalidProfile(emptyEnvironment, List.of(), 1, new ScenarioRetry(0, 0), 60);
        assertInvalidProfile(emptyEnvironment, List.of(), 1, new ScenarioRetry(6, 0), 60);
        assertInvalidProfile(emptyEnvironment, List.of(), 1, new ScenarioRetry(1, -1), 60);
        assertInvalidProfile(emptyEnvironment, List.of(), 1, new ScenarioRetry(1, 30_001), 60);
        assertInvalidProfile(emptyEnvironment, List.of(), 1, new ScenarioRetry(1, 0), 0);
        assertInvalidProfile(emptyEnvironment, List.of(), 1, new ScenarioRetry(1, 0), 3601);
        assertThatThrownBy(() -> ConfigurationPolicy.runProfile(
                        emptyEnvironment,
                        IntStream.range(0, 101).mapToObj(index -> "@tag" + index).toList(),
                        1,
                        new ScenarioRetry(1, 0),
                        60,
                        new ArtifactPolicy(List.of(), 0, 0),
                        List.of()))
                .isInstanceOf(ConfigurationPolicy.ValidationException.class);
        assertThatThrownBy(() -> ConfigurationPolicy.runProfile(
                        emptyEnvironment,
                        List.of(),
                        1,
                        new ScenarioRetry(1, 0),
                        60,
                        new ArtifactPolicy(List.of(), 0, 0),
                        IntStream.range(0, 101)
                                .mapToObj(index -> variable(
                                        "override" + index, ConfigurationValueType.BOOLEAN, true))
                                .toList()))
                .isInstanceOf(ConfigurationPolicy.ValidationException.class);
        assertThatThrownBy(() -> ConfigurationPolicy.runProfile(
                        emptyEnvironment,
                        List.of(),
                        1,
                        new ScenarioRetry(1, 0),
                        60,
                        new ArtifactPolicy(
                                List.of(
                                        ArtifactType.KARATE_HTML_REPORT,
                                        ArtifactType.RAW_RESULT,
                                        ArtifactType.EXECUTION_LOG,
                                        ArtifactType.OTHER,
                                        ArtifactType.OTHER),
                                0,
                                0),
                        List.of()))
                .isInstanceOf(ConfigurationPolicy.ValidationException.class);
        assertThatThrownBy(() -> ConfigurationPolicy.runProfile(
                        emptyEnvironment,
                        List.of(),
                        1,
                        new ScenarioRetry(1, 0),
                        60,
                        new ArtifactPolicy(List.of(), 104_857_601, 524_288_000),
                        List.of()))
                .isInstanceOf(ConfigurationPolicy.ValidationException.class);
        assertThatThrownBy(() -> ConfigurationPolicy.runProfile(
                        emptyEnvironment,
                        List.of(),
                        1,
                        new ScenarioRetry(1, 0),
                        60,
                        new ArtifactPolicy(List.of(), 0, 524_288_001),
                        List.of()))
                .isInstanceOf(ConfigurationPolicy.ValidationException.class);
    }

    private static void assertInvalidProfile(
            EnvironmentRevision environment,
            List<String> tags,
            int parallelism,
            ScenarioRetry retry,
            int timeoutSeconds) {
        assertThatThrownBy(() -> ConfigurationPolicy.runProfile(
                        environment,
                        tags,
                        parallelism,
                        retry,
                        timeoutSeconds,
                        new ArtifactPolicy(List.of(), 0, 0),
                        List.of()))
                .isInstanceOf(ConfigurationPolicy.ValidationException.class);
    }

    private static String profileDigest(
            EnvironmentRevision environment,
            List<String> tags,
            ScenarioRetry retry,
            int timeoutSeconds,
            ArtifactPolicy artifactPolicy,
            List<ConfigurationVariable> overrides) {
        return ConfigurationPolicy.runProfile(
                        environment, tags, 4, retry, timeoutSeconds, artifactPolicy, overrides)
                .digest();
    }

    private static EnvironmentRevision environmentRevision(
            List<ConfigurationVariable> variables, List<SecretBinding> bindings) {
        var content = ConfigurationPolicy.environment(variables, bindings);
        return new EnvironmentRevision(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                content.variables(),
                content.secretBindings(),
                content.digest(),
                "member",
                Instant.EPOCH);
    }

    private static ConfigurationVariable variable(String key, ConfigurationValueType type, Object value) {
        return new ConfigurationVariable(key, type, value);
    }
}
