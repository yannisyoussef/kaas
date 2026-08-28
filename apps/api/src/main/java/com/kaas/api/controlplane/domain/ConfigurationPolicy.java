package com.kaas.api.controlplane.domain;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class ConfigurationPolicy {
    public static final int MAX_VARIABLES = 100;
    public static final int MAX_SECRET_BINDINGS = 50;
    public static final int MAX_OVERRIDES = 100;
    public static final int MAX_TAGS = 100;
    public static final int MAX_STRING_BYTES = 4096;
    public static final long MIN_INTEGER = -9_007_199_254_740_991L;
    public static final long MAX_INTEGER = 9_007_199_254_740_991L;
    public static final long MAX_ARTIFACT_BYTES = 104_857_600L;
    public static final long MAX_TOTAL_ARTIFACT_BYTES = 524_288_000L;

    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]{0,127}");
    private static final Pattern TAG = Pattern.compile("@[A-Za-z0-9_.:-]{1,127}");

    private ConfigurationPolicy() {}

    public static EnvironmentContent environment(
            List<ConfigurationVariable> variables, List<SecretBinding> secretBindings) {
        if (variables == null || variables.size() > MAX_VARIABLES) {
            throw invalid("variables", "variables must contain at most " + MAX_VARIABLES + " entries");
        }
        if (secretBindings == null || secretBindings.size() > MAX_SECRET_BINDINGS) {
            throw invalid(
                    "secretBindings", "secretBindings must contain at most " + MAX_SECRET_BINDINGS + " entries");
        }

        Set<String> keys = new HashSet<>();
        List<ConfigurationVariable> normalizedVariables = new ArrayList<>(variables.size());
        for (int index = 0; index < variables.size(); index++) {
            ConfigurationVariable variable = normalizeVariable(variables.get(index), "variables/" + index);
            if (!keys.add(variable.key())) {
                throw invalid("variables/" + index + "/key", "configuration keys must be unique");
            }
            normalizedVariables.add(variable);
        }

        List<SecretBinding> normalizedBindings = new ArrayList<>(secretBindings.size());
        for (int index = 0; index < secretBindings.size(); index++) {
            SecretBinding binding = secretBindings.get(index);
            if (binding == null || !validKey(binding.key())) {
                throw invalid("secretBindings/" + index + "/key", "secret binding key is invalid");
            }
            if (binding.secretReferenceId() == null) {
                throw invalid(
                        "secretBindings/" + index + "/secretReferenceId", "secretReferenceId is required");
            }
            if (!keys.add(binding.key())) {
                throw invalid("secretBindings/" + index + "/key", "configuration keys must be unique");
            }
            normalizedBindings.add(binding);
        }

        normalizedVariables.sort(Comparator.comparing(ConfigurationVariable::key));
        normalizedBindings.sort(Comparator.comparing(SecretBinding::key));
        String digest = environmentDigest(normalizedVariables, normalizedBindings);
        return new EnvironmentContent(normalizedVariables, normalizedBindings, digest);
    }

    public static RunProfileContent runProfile(
            EnvironmentRevision environmentRevision,
            List<String> tags,
            int parallelism,
            ScenarioRetry retry,
            int timeoutSeconds,
            ArtifactPolicy artifactPolicy,
            List<ConfigurationVariable> overrides) {
        if (environmentRevision == null) {
            throw invalid("environmentRevisionId", "environment revision is required");
        }
        if (tags == null || tags.size() > MAX_TAGS) {
            throw invalid("selection/tags", "tags must contain at most " + MAX_TAGS + " entries");
        }
        Set<String> uniqueTags = new HashSet<>();
        List<String> normalizedTags = new ArrayList<>(tags.size());
        for (int index = 0; index < tags.size(); index++) {
            String tag = tags.get(index);
            if (tag == null || !TAG.matcher(tag).matches() || !uniqueTags.add(tag)) {
                throw invalid("selection/tags/" + index, "tags must be valid and unique");
            }
            normalizedTags.add(tag);
        }
        normalizedTags.sort(String::compareTo);

        if (parallelism < 1 || parallelism > 32) {
            throw invalid("parallelism", "parallelism must be between 1 and 32");
        }
        if (retry == null || retry.maxAttempts() < 1 || retry.maxAttempts() > 5) {
            throw invalid("scenarioRetry/maxAttempts", "maxAttempts must be between 1 and 5");
        }
        if (retry.delayMilliseconds() < 0 || retry.delayMilliseconds() > 30_000) {
            throw invalid(
                    "scenarioRetry/delayMilliseconds", "delayMilliseconds must be between 0 and 30000");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 3600) {
            throw invalid("executionTimeoutSeconds", "executionTimeoutSeconds must be between 1 and 3600");
        }
        ArtifactPolicy normalizedPolicy = normalizeArtifactPolicy(artifactPolicy);

        if (overrides == null || overrides.size() > MAX_OVERRIDES) {
            throw invalid(
                    "configurationOverrides",
                    "configurationOverrides must contain at most " + MAX_OVERRIDES + " entries");
        }
        Set<String> overrideKeys = new HashSet<>();
        List<ConfigurationVariable> normalizedOverrides = new ArrayList<>(overrides.size());
        for (int index = 0; index < overrides.size(); index++) {
            ConfigurationVariable override = normalizeVariable(overrides.get(index), "configurationOverrides/" + index);
            if (!overrideKeys.add(override.key())) {
                throw invalid(
                        "configurationOverrides/" + index + "/key", "configuration override keys must be unique");
            }
            normalizedOverrides.add(override);
        }
        normalizedOverrides.sort(Comparator.comparing(ConfigurationVariable::key));

        var environmentVariables = environmentRevision.variables().stream()
                .collect(java.util.stream.Collectors.toMap(ConfigurationVariable::key, variable -> variable));
        Set<String> secretKeys = environmentRevision.secretBindings().stream()
                .map(SecretBinding::key)
                .collect(java.util.stream.Collectors.toSet());
        for (ConfigurationVariable override : normalizedOverrides) {
            if (secretKeys.contains(override.key())) {
                throw new ConfigurationConflictException("a plain override cannot replace a secret binding");
            }
            ConfigurationVariable existing = environmentVariables.get(override.key());
            if (existing != null && existing.type() != override.type()) {
                throw new ConfigurationConflictException("a plain override cannot change an existing value type");
            }
        }
        long effectivePlainCount = environmentVariables.keySet().stream()
                .filter(key -> !overrideKeys.contains(key))
                .count() + normalizedOverrides.size();
        if (effectivePlainCount > 500 || secretKeys.size() > 100) {
            throw new ConfigurationConflictException("the effective configuration exceeds execution limits");
        }

        RunSelection selection = new RunSelection(normalizedTags);
        String digest = runProfileDigest(
                environmentRevision,
                selection,
                parallelism,
                retry,
                timeoutSeconds,
                normalizedPolicy,
                normalizedOverrides);
        return new RunProfileContent(
                environmentRevision.environmentId(),
                environmentRevision.revisionId(),
                selection,
                parallelism,
                retry,
                timeoutSeconds,
                normalizedPolicy,
                normalizedOverrides,
                digest);
    }

    private static ConfigurationVariable normalizeVariable(ConfigurationVariable variable, String pointer) {
        if (variable == null || !validKey(variable.key())) {
            throw invalid(pointer + "/key", "configuration key is invalid");
        }
        if (variable.type() == null) {
            throw invalid(pointer + "/type", "configuration type is required");
        }
        Object normalizedValue = switch (variable.type()) {
            case STRING -> normalizeString(variable.value(), pointer + "/value");
            case INTEGER -> normalizeInteger(variable.value(), pointer + "/value");
            case BOOLEAN -> normalizeBoolean(variable.value(), pointer + "/value");
        };
        return new ConfigurationVariable(variable.key(), variable.type(), normalizedValue);
    }

    private static String normalizeString(Object value, String pointer) {
        if (!(value instanceof String string)) {
            throw invalid(pointer, "STRING values must be JSON strings");
        }
        byte[] bytes = strictUtf8(string, pointer);
        if (bytes.length > MAX_STRING_BYTES) {
            throw invalid(pointer, "STRING values must not exceed 4096 UTF-8 bytes");
        }
        if (string.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid(pointer, "STRING values must not contain control characters");
        }
        return string;
    }

    private static Long normalizeInteger(Object value, String pointer) {
        BigInteger integer;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            integer = BigInteger.valueOf(((Number) value).longValue());
        } else if (value instanceof BigInteger bigInteger) {
            integer = bigInteger;
        } else {
            throw invalid(pointer, "INTEGER values must be exact JSON integers");
        }
        if (integer.compareTo(BigInteger.valueOf(MIN_INTEGER)) < 0
                || integer.compareTo(BigInteger.valueOf(MAX_INTEGER)) > 0) {
            throw invalid(pointer, "INTEGER value is outside the interoperable range");
        }
        return integer.longValueExact();
    }

    private static Boolean normalizeBoolean(Object value, String pointer) {
        if (!(value instanceof Boolean bool)) {
            throw invalid(pointer, "BOOLEAN values must be JSON booleans");
        }
        return bool;
    }

    private static ArtifactPolicy normalizeArtifactPolicy(ArtifactPolicy policy) {
        if (policy == null || policy.types() == null || policy.types().size() > 4) {
            throw invalid("artifactPolicy/types", "artifact types must contain at most four entries");
        }
        Set<ArtifactType> uniqueTypes = new HashSet<>(policy.types());
        if (uniqueTypes.size() != policy.types().size() || uniqueTypes.contains(null)) {
            throw invalid("artifactPolicy/types", "artifact types must be valid and unique");
        }
        if (policy.maxArtifactBytes() < 0 || policy.maxArtifactBytes() > MAX_ARTIFACT_BYTES) {
            throw invalid("artifactPolicy/maxArtifactBytes", "maxArtifactBytes is outside the allowed range");
        }
        if (policy.maxTotalBytes() < 0 || policy.maxTotalBytes() > MAX_TOTAL_ARTIFACT_BYTES) {
            throw invalid("artifactPolicy/maxTotalBytes", "maxTotalBytes is outside the allowed range");
        }
        if (policy.maxArtifactBytes() > policy.maxTotalBytes()) {
            throw invalid("artifactPolicy/maxArtifactBytes", "maxArtifactBytes must not exceed maxTotalBytes");
        }
        List<ArtifactType> sorted = uniqueTypes.stream().sorted().toList();
        return new ArtifactPolicy(sorted, policy.maxArtifactBytes(), policy.maxTotalBytes());
    }

    private static String environmentDigest(
            List<ConfigurationVariable> variables, List<SecretBinding> secretBindings) {
        MessageDigest digest = sha256();
        update(digest, "kaas.environment-revision-content.v1");
        update(digest, Integer.toString(variables.size()));
        for (ConfigurationVariable variable : variables) {
            updateVariable(digest, variable);
        }
        update(digest, Integer.toString(secretBindings.size()));
        for (SecretBinding binding : secretBindings) {
            update(digest, "SECRET_REFERENCE");
            update(digest, binding.key());
            update(digest, binding.secretReferenceId().toString());
        }
        return formatted(digest);
    }

    private static String runProfileDigest(
            EnvironmentRevision environmentRevision,
            RunSelection selection,
            int parallelism,
            ScenarioRetry retry,
            int timeoutSeconds,
            ArtifactPolicy artifactPolicy,
            List<ConfigurationVariable> overrides) {
        MessageDigest digest = sha256();
        update(digest, "kaas.run-profile-revision-content.v1");
        update(digest, environmentRevision.environmentId().toString());
        update(digest, environmentRevision.revisionId().toString());
        update(digest, environmentRevision.contentDigest());
        update(digest, Integer.toString(selection.tags().size()));
        selection.tags().forEach(tag -> update(digest, tag));
        update(digest, Integer.toString(parallelism));
        update(digest, Integer.toString(retry.maxAttempts()));
        update(digest, Integer.toString(retry.delayMilliseconds()));
        update(digest, Integer.toString(timeoutSeconds));
        update(digest, Integer.toString(artifactPolicy.types().size()));
        artifactPolicy.types().forEach(type -> update(digest, type.name()));
        update(digest, Long.toString(artifactPolicy.maxArtifactBytes()));
        update(digest, Long.toString(artifactPolicy.maxTotalBytes()));
        update(digest, Integer.toString(overrides.size()));
        overrides.forEach(override -> updateVariable(digest, override));
        return formatted(digest);
    }

    private static void updateVariable(MessageDigest digest, ConfigurationVariable variable) {
        update(digest, variable.type().name());
        update(digest, variable.key());
        update(digest, switch (variable.type()) {
            case STRING -> (String) variable.value();
            case INTEGER -> Long.toString((Long) variable.value());
            case BOOLEAN -> Boolean.toString((Boolean) variable.value());
        });
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String formatted(MessageDigest digest) {
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    private static boolean validKey(String value) {
        return value != null && KEY.matcher(value).matches();
    }

    private static byte[] strictUtf8(String value, String pointer) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(value));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) {
            throw invalid(pointer, "value is not valid Unicode");
        }
    }

    private static ValidationException invalid(String pointer, String message) {
        return new ValidationException(pointer, message);
    }

    public record EnvironmentContent(
            List<ConfigurationVariable> variables, List<SecretBinding> secretBindings, String digest) {
        public EnvironmentContent {
            variables = List.copyOf(variables);
            secretBindings = List.copyOf(secretBindings);
        }
    }

    public record RunProfileContent(
            UUID environmentId,
            UUID environmentRevisionId,
            RunSelection selection,
            int parallelism,
            ScenarioRetry scenarioRetry,
            int executionTimeoutSeconds,
            ArtifactPolicy artifactPolicy,
            List<ConfigurationVariable> configurationOverrides,
            String digest) {
        public RunProfileContent {
            configurationOverrides = List.copyOf(configurationOverrides);
        }
    }

    public static final class ValidationException extends IllegalArgumentException {
        private final String pointer;

        ValidationException(String pointer, String message) {
            super(message);
            this.pointer = pointer;
        }

        public String pointer() {
            return pointer;
        }
    }

    public static final class ConfigurationConflictException extends IllegalArgumentException {
        ConfigurationConflictException(String message) {
            super(message);
        }
    }
}
