package com.kaas.runner.command;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The runner's own check of a command the control plane sent it.
 *
 * <p><strong>Why this is not shared code.</strong> The control plane computes a digest over the command and
 * sends both. If the runner verified that by calling the control plane's own implementation, the two would
 * agree by construction and the comparison would prove nothing at all — a bug in the shared code would be
 * invisible precisely because both sides had it. Re-deriving the digest here from the parsed document, in a
 * module that structurally cannot depend on the control plane, is what makes agreement evidence.
 *
 * <p>It also means the runner recomputes from the document it actually PARSED, not from the bytes it received.
 * Digesting the raw body would verify the transport and nothing else: a field the parser silently dropped or
 * coerced would still be covered by a digest over bytes, and the runner would then act on a value different
 * from the one it verified. Verifying the projection you are going to use is the only version of this check
 * that means anything.
 *
 * <p><strong>Unknown fields are fatal.</strong> Not ignored, not logged. A field the runner does not understand
 * is either a control plane that has moved ahead of it — in which case acting on a partially understood command
 * is how a security-relevant instruction gets skipped — or an injected field, in which case ignoring it is
 * exactly what the injector wants. Both cases end the execution.
 */
public final class CommandValidator {

    private static final String FORMAT = "kaas.execution-command.v1";
    private static final String SCHEMA_VERSION = "1.0";

    /** Every field the runner understands at the top level. Anything else is fatal. */
    private static final Set<String> KNOWN_ROOT_FIELDS = Set.of(
            "schemaVersion", "commandId", "commandDigest", "organizationId", "projectId", "runId", "runVersion",
            "attemptId", "attemptNumber", "assignmentEpoch", "runSnapshotDigest", "issuedAt", "expiresAt",
            "engine", "sourceBundle", "secretCapabilities", "networkPolicy", "sandboxSecurityProfile",
            "configurationSnapshot", "selection", "parallelism", "scenarioRetry", "executionTimeoutSeconds",
            "artifactPolicy");

    private static final Set<String> KNOWN_ENGINE_FIELDS = Set.of("type", "version");
    private static final Set<String> KNOWN_BUNDLE_FIELDS = Set.of("contentDigest", "features");
    private static final Set<String> KNOWN_FEATURE_FIELDS =
            Set.of("featureId", "revisionId", "logicalPath", "contentDigest");
    private static final Set<String> KNOWN_SECRET_FIELDS =
            Set.of("capabilityId", "provider", "referenceId", "bindingKey", "expiresAt");
    private static final Set<String> KNOWN_NETWORK_FIELDS =
            Set.of("policyRevisionId", "type", "version", "digest");
    private static final Set<String> KNOWN_SANDBOX_FIELDS =
            Set.of("profileVersion", "sandboxRuntime", "assessmentDigest");
    private static final Set<String> KNOWN_SELECTION_FIELDS = Set.of("tags");
    private static final Set<String> KNOWN_RETRY_FIELDS = Set.of("maxAttempts", "delayMilliseconds");
    private static final Set<String> KNOWN_ARTIFACT_FIELDS =
            Set.of("types", "maxArtifactBytes", "maxTotalBytes");

    /**
     * The network policy every runner can always enforce.
     *
     * <p>A sandbox with no network needs nothing from the egress subsystem, which is why DENY_ALL is
     * unconditional here and deliberately keeps the simpler proven path. Nothing about the allowlist mechanism
     * being unhealthy should stop a run that wanted no network in the first place.
     */
    private static final String ALWAYS_ENFORCEABLE = "DENY_ALL";

    /**
     * The only engine this runner can execute.
     *
     * <p>KARATE is a value the model carries and this runner does not have. Refusing it here is what stops a
     * misconfigured control plane from having its synthetic workload reported as a Karate suite: the command
     * would name an engine, the runner would run shell assertions, and every consumer downstream would believe
     * a real engine had produced the result. Refusing is loud; running something else under that name is not.
     */
    private static final String EXECUTABLE_ENGINE = "SYNTHETIC";

    private ObjectMapper mapper;

    private final java.util.Set<String> enforceablePolicies;

    /**
     * The evidence identity this runtime was deployed with, when it has one.
     *
     * <p>Present means a command must name exactly this digest or be refused — the runner's own, independent
     * refusal of evidence that does not describe it. Absent means this runner has not been given its own
     * attestation and the check does not apply; the control plane's verification still stands, and a runner in
     * that state simply adds nothing to it.
     *
     * <p>Absent is not a weakening of anything that existed before: until this slice no runner had evidence to
     * compare against at all. It is stated explicitly rather than defaulted quietly, so a deployment that
     * meant to bind and did not can be seen to have not bound.
     */
    private final java.util.Optional<String> expectedAssessmentDigest;

    /**
     * A validator that can enforce nothing but DENY_ALL and is bound to no runtime evidence.
     *
     * <p>The safe default for the policy set, and the one every caller gets unless it has positively
     * established otherwise. A default that included ALLOWLIST would mean forgetting to pass the capability
     * produced a runner that accepted allowlist commands and applied nothing.
     */
    public CommandValidator(ObjectMapper mapper) {
        this(mapper, java.util.Set.of(ALWAYS_ENFORCEABLE));
    }

    /**
     * @param enforceablePolicies the policy types this runner has established it can actually apply on this
     *     host. DENY_ALL is added unconditionally; a caller cannot remove it, and a caller that passes an
     *     empty set gets a runner that can still execute the runs that need no network.
     */
    public CommandValidator(ObjectMapper mapper, java.util.Set<String> enforceablePolicies) {
        this(mapper, enforceablePolicies, java.util.Optional.empty());
    }

    /**
     * @param expectedAssessmentDigest the payload digest of the attestation describing THIS runtime. A command
     *     naming any other evidence is refused, whatever the control plane concluded — which is what stops a
     *     command authorized against one runtime's evidence from executing on another
     */
    public CommandValidator(
            ObjectMapper mapper,
            java.util.Set<String> enforceablePolicies,
            java.util.Optional<String> expectedAssessmentDigest) {
        java.util.Set<String> policies = new java.util.LinkedHashSet<>(enforceablePolicies);
        policies.add(ALWAYS_ENFORCEABLE);
        this.enforceablePolicies = java.util.Set.copyOf(policies);
        this.expectedAssessmentDigest = expectedAssessmentDigest;
        initialize(mapper);
    }

    private void initialize(ObjectMapper mapper) {
        this.mapper = mapper;
    }


    /**
     * <strong>{@code executionTimeoutSeconds} is validated and digested but NOT enforced here, and is therefore
     * deliberately not carried onto {@link ValidatedCommand}.</strong>
     *
     * <p>The sandbox's wall-clock ceiling comes from the immutable security profile, which is a platform
     * control rather than a tenant setting, and this runner has no way to tighten it per run. Carrying the
     * tenant's value into the execution path would imply an enforcement that does not happen — a field a
     * tenant sets, that reaches the executing process, and that changes nothing. Leaving it off the validated
     * command makes the gap visible instead of plausible.
     */
    public ValidatedCommand validate(String body, Instant now) throws CommandRejected {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (RuntimeException unreadable) {
            throw new CommandRejected("The command could not be parsed: " + unreadable.getClass().getName());
        }
        if (!root.isObject()) {
            throw new CommandRejected("A command is a JSON object.");
        }
        rejectUnknown(root, KNOWN_ROOT_FIELDS, "command");

        if (!SCHEMA_VERSION.equals(text(root, "schemaVersion"))) {
            throw new CommandRejected("Unsupported command schema version: " + text(root, "schemaVersion"));
        }

        JsonNode engine = object(root, "engine");
        rejectUnknown(engine, KNOWN_ENGINE_FIELDS, "engine");
        JsonNode bundle = object(root, "sourceBundle");
        rejectUnknown(bundle, KNOWN_BUNDLE_FIELDS, "sourceBundle");
        JsonNode network = object(root, "networkPolicy");
        rejectUnknown(network, KNOWN_NETWORK_FIELDS, "networkPolicy");
        JsonNode sandbox = object(root, "sandboxSecurityProfile");
        rejectUnknown(sandbox, KNOWN_SANDBOX_FIELDS, "sandboxSecurityProfile");
        JsonNode selection = object(root, "selection");
        rejectUnknown(selection, KNOWN_SELECTION_FIELDS, "selection");
        JsonNode retry = object(root, "scenarioRetry");
        rejectUnknown(retry, KNOWN_RETRY_FIELDS, "scenarioRetry");
        JsonNode artifacts = object(root, "artifactPolicy");
        rejectUnknown(artifacts, KNOWN_ARTIFACT_FIELDS, "artifactPolicy");

        String recomputed = digest(root);
        String claimed = text(root, "commandDigest");
        // Constant-time, because this compares a value the caller controls against one derived from a secret-
        // free but integrity-critical computation, and a timing-dependent comparison here is a needless
        // side channel in a check whose whole purpose is integrity.
        if (!MessageDigest.isEqual(
                recomputed.getBytes(StandardCharsets.UTF_8), claimed.getBytes(StandardCharsets.UTF_8))) {
            throw new CommandRejected(
                    "The command digest does not match the document: expected " + recomputed + ".");
        }

        Instant expiresAt = instant(root, "expiresAt");
        Instant issuedAt = instant(root, "issuedAt");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new CommandRejected("A command expires after it is issued.");
        }
        if (!expiresAt.isAfter(now)) {
            throw new CommandRejected("The command has expired.");
        }

        String networkType = text(network, "type");
        if (!enforceablePolicies.contains(networkType)) {
            // NOT_ENFORCEABLE, and refused rather than degraded.
            //
            // This is the runner's OWN refusal, independent of the control plane's. Both sides check, because
            // a single check is a single thing to get wrong: a control plane that authorized an allowlist a
            // worker could not apply would produce a run with egress nobody was constraining, and the worker
            // is the only party in a position to know what its own host can do.
            throw new CommandRejected(
                    "This runner cannot enforce the network policy " + networkType
                            + "; enforceable here: " + enforceablePolicies + ".");
        }

        // THE RUNNER'S OWN REFUSAL OF FOREIGN EVIDENCE.
        //
        // The control plane verified an attestation and bound its digest into this command. That is one
        // party's decision, made about a runtime it cannot see. This one is made by the runtime itself: a
        // command naming evidence other than the evidence describing THIS runner is refused here.
        //
        // What it defeats is a command reaching the wrong place — routed to another runner, or issued before
        // this one was re-assessed. Neither depends on the control plane getting the routing right, and
        // neither is something control-plane verification can notice.
        if (expectedAssessmentDigest.isPresent()) {
            String named = text(sandbox, "assessmentDigest");
            if (!java.security.MessageDigest.isEqual(
                    named.getBytes(StandardCharsets.UTF_8),
                    expectedAssessmentDigest.orElseThrow().getBytes(StandardCharsets.UTF_8))) {
                throw new CommandRejected(
                        "This command names security evidence that does not describe this runtime.");
            }
        }

        String engineType = text(engine, "type");
        if (!EXECUTABLE_ENGINE.equals(engineType)) {
            throw new CommandRejected(
                    "This runner cannot execute the engine " + engineType
                            + "; only " + EXECUTABLE_ENGINE + " is executable here.");
        }

        // A source bundle may be described but must never be fetched or executed by this runner. The synthetic
        // workload is platform-owned, and nothing in the execution path reads these entries — an architecture
        // test asserts that separately, because a comment is not a boundary.
        if (!bundle.get("features").isArray()) {
            throw new CommandRejected("A source bundle carries a feature array.");
        }
        if (!root.get("secretCapabilities").isArray() || !root.get("secretCapabilities").isEmpty()) {
            // No production secret provider exists, so a command carrying secret capabilities is describing
            // something that cannot have been issued honestly.
            throw new CommandRejected("This runner redeems no secrets, and this command binds some.");
        }

        List<String> tags = new ArrayList<>();
        selection.get("tags").forEach(tag -> tags.add(tag.asString()));

        String snapshotDigest = text(root, "runSnapshotDigest");
        if (!snapshotDigest.startsWith("sha256:")) {
            throw new CommandRejected("A run snapshot digest is sha256-prefixed.");
        }

        return new ValidatedCommand(
                uuid(root, "commandId"),
                claimed,
                uuid(root, "organizationId"),
                uuid(root, "projectId"),
                uuid(root, "runId"),
                integral(root, "runVersion"),
                uuid(root, "attemptId"),
                (int) integral(root, "attemptNumber"),
                (int) integral(root, "assignmentEpoch"),
                snapshotDigest.substring("sha256:".length()),
                issuedAt,
                expiresAt,
                engineType,
                text(engine, "version"),
                networkType,
                text(sandbox, "profileVersion"),
                text(sandbox, "sandboxRuntime"),
                Collections.unmodifiableList(tags));
    }

    /**
     * The digest, re-derived from the parsed document.
     *
     * <p>Package-private rather than private so a test can build a command that is correctly digested AND
     * unenforceable. Without that, every negative test would tamper with a field, fail the digest check first,
     * and leave the policy and engine refusals below it never once executed — which is precisely what happened:
     * removing the network-policy check killed no test at all.
     *
     * <p>Length-prefixed, like the control plane's. Concatenating variable-length fields without their lengths
     * is not injective — a delimiter can be moved into a value — so the prefix is what makes a collision
     * require breaking SHA-256 rather than choosing a clever identifier.
     */
    static String digest(JsonNode root) throws CommandRejected {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            update(sha, FORMAT);
            update(sha, "SCHEMA_VERSION");
            update(sha, SCHEMA_VERSION);
            update(sha, "COMMAND_ID");
            update(sha, text(root, "commandId"));
            update(sha, "ISSUED_AT");
            update(sha, text(root, "issuedAt"));
            update(sha, "EXPIRES_AT");
            update(sha, text(root, "expiresAt"));
            update(sha, "ORGANIZATION");
            update(sha, text(root, "organizationId"));
            update(sha, "PROJECT");
            update(sha, text(root, "projectId"));
            update(sha, "RUN");
            update(sha, text(root, "runId"));
            update(sha, "RUN_VERSION");
            update(sha, Long.toString(integral(root, "runVersion")));
            update(sha, "ATTEMPT");
            update(sha, text(root, "attemptId"));
            update(sha, "ATTEMPT_NUMBER");
            update(sha, Long.toString(integral(root, "attemptNumber")));
            update(sha, "ASSIGNMENT_EPOCH");
            update(sha, Long.toString(integral(root, "assignmentEpoch")));
            update(sha, "RUN_SNAPSHOT");
            // Bare hex in the preimage, sha256-prefixed in the document. The two forms are deliberate and the
            // runner must strip rather than assume; getting this wrong produces a mismatch on every command,
            // which is at least loud.
            update(sha, stripSha256(text(root, "runSnapshotDigest")));

            JsonNode engine = root.get("engine");
            update(sha, "ENGINE");
            update(sha, text(engine, "type"));
            update(sha, text(engine, "version"));

            JsonNode bundle = root.get("sourceBundle");
            update(sha, "SOURCE_BUNDLE_DIGEST");
            update(sha, text(bundle, "contentDigest"));
            JsonNode features = bundle.get("features");
            update(sha, "SOURCE_FEATURE_COUNT");
            update(sha, Integer.toString(features.size()));
            List<JsonNode> orderedFeatures = new ArrayList<>();
            features.forEach(orderedFeatures::add);
            // Sorted here rather than trusted from the wire. The control plane sorts before digesting, so a
            // document delivered out of order would otherwise digest differently on the two sides and be
            // rejected for the wrong reason — and, worse, a reordering attack would look like corruption.
            orderedFeatures.sort(java.util.Comparator.comparing(node -> node.get("logicalPath").asString()));
            for (JsonNode feature : orderedFeatures) {
                rejectUnknown(feature, KNOWN_FEATURE_FIELDS, "sourceBundle.features[]");
                update(sha, "SOURCE_FEATURE");
                update(sha, text(feature, "featureId"));
                update(sha, text(feature, "revisionId"));
                update(sha, text(feature, "logicalPath"));
                update(sha, stripSha256(text(feature, "contentDigest")));
            }

            JsonNode secrets = root.get("secretCapabilities");
            update(sha, "SECRET_BINDING_COUNT");
            update(sha, Integer.toString(secrets.size()));
            List<JsonNode> orderedSecrets = new ArrayList<>();
            secrets.forEach(orderedSecrets::add);
            orderedSecrets.sort(java.util.Comparator.comparing(node -> node.get("bindingKey").asString()));
            for (JsonNode secret : orderedSecrets) {
                rejectUnknown(secret, KNOWN_SECRET_FIELDS, "secretCapabilities[]");
                update(sha, "SECRET_BINDING");
                update(sha, text(secret, "bindingKey"));
                update(sha, text(secret, "provider"));
                update(sha, text(secret, "referenceId"));
                update(sha, text(secret, "capabilityId"));
                update(sha, text(secret, "expiresAt"));
            }

            JsonNode network = root.get("networkPolicy");
            update(sha, "NETWORK_POLICY");
            update(sha, text(network, "policyRevisionId"));
            update(sha, text(network, "type"));
            update(sha, Long.toString(integral(network, "version")));
            update(sha, text(network, "digest"));

            JsonNode sandbox = root.get("sandboxSecurityProfile");
            update(sha, "SANDBOX_PROFILE");
            update(sha, text(sandbox, "profileVersion"));
            update(sha, text(sandbox, "sandboxRuntime"));
            update(sha, text(sandbox, "assessmentDigest"));

            JsonNode configuration = object(root, "configurationSnapshot");
            update(sha, "CONFIGURATION_COUNT");
            update(sha, Integer.toString(configuration.size()));
            List<String> keys = new ArrayList<>();
            configuration.propertyNames().forEach(keys::add);
            Collections.sort(keys);
            for (String key : keys) {
                JsonNode value = configuration.get(key);
                update(sha, "CONFIGURATION");
                update(sha, key);
                // The type is recovered from the JSON node rather than carried alongside it. That is only sound
                // because the three types the contract allows map onto three disjoint JSON kinds; a fourth type
                // sharing a representation with an existing one would make this ambiguous, and the else-branch
                // below refuses rather than guessing.
                update(sha, configurationType(value, key));
                update(sha, value.isString() ? value.asString() : value.toString());
            }

            JsonNode selection = root.get("selection");
            JsonNode tags = selection.get("tags");
            update(sha, "TAG_COUNT");
            update(sha, Integer.toString(tags.size()));
            List<String> orderedTags = new ArrayList<>();
            tags.forEach(tag -> orderedTags.add(tag.asString()));
            Collections.sort(orderedTags);
            for (String tag : orderedTags) {
                update(sha, "TAG");
                update(sha, tag);
            }

            update(sha, "PARALLELISM");
            update(sha, Long.toString(integral(root, "parallelism")));
            JsonNode retry = root.get("scenarioRetry");
            update(sha, "RETRY_MAX_ATTEMPTS");
            update(sha, Long.toString(integral(retry, "maxAttempts")));
            update(sha, "RETRY_DELAY_MILLISECONDS");
            update(sha, Long.toString(integral(retry, "delayMilliseconds")));
            update(sha, "EXECUTION_TIMEOUT_SECONDS");
            update(sha, Long.toString(integral(root, "executionTimeoutSeconds")));

            JsonNode artifacts = root.get("artifactPolicy");
            JsonNode types = artifacts.get("types");
            update(sha, "ARTIFACT_TYPE_COUNT");
            update(sha, Integer.toString(types.size()));
            List<String> orderedTypes = new ArrayList<>();
            types.forEach(type -> orderedTypes.add(type.asString()));
            Collections.sort(orderedTypes);
            for (String type : orderedTypes) {
                update(sha, "ARTIFACT_TYPE");
                update(sha, type);
            }
            update(sha, "MAX_ARTIFACT_BYTES");
            update(sha, Long.toString(integral(artifacts, "maxArtifactBytes")));
            update(sha, "MAX_TOTAL_BYTES");
            update(sha, Long.toString(integral(artifacts, "maxTotalBytes")));
            return "sha256:" + HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM.", impossible);
        }
    }

    private static String configurationType(JsonNode value, String key) throws CommandRejected {
        if (value.isString()) {
            return "STRING";
        }
        if (value.isBoolean()) {
            return "BOOLEAN";
        }
        if (value.isIntegralNumber()) {
            return "INTEGER";
        }
        throw new CommandRejected("Configuration value " + key + " is not a supported scalar.");
    }

    private static String stripSha256(String value) throws CommandRejected {
        if (!value.startsWith("sha256:")) {
            throw new CommandRejected("Expected a sha256-prefixed digest, got: " + value);
        }
        return value.substring("sha256:".length());
    }

    private static void rejectUnknown(JsonNode node, Set<String> known, String where) throws CommandRejected {
        List<String> unknown = new ArrayList<>();
        node.propertyNames().forEach(name -> {
            if (!known.contains(name)) {
                unknown.add(name);
            }
        });
        if (!unknown.isEmpty()) {
            Collections.sort(unknown);
            throw new CommandRejected("Unknown field(s) in " + where + ": " + String.join(", ", unknown));
        }
    }

    private static JsonNode object(JsonNode root, String field) throws CommandRejected {
        JsonNode node = root.get(field);
        if (node == null || !node.isObject()) {
            throw new CommandRejected("A command carries an object at " + field + ".");
        }
        return node;
    }

    private static String text(JsonNode root, String field) throws CommandRejected {
        JsonNode node = root.get(field);
        if (node == null || !node.isString()) {
            throw new CommandRejected("A command carries a textual " + field + ".");
        }
        return node.asString();
    }

    private static long integral(JsonNode root, String field) throws CommandRejected {
        JsonNode node = root.get(field);
        if (node == null || !node.isIntegralNumber()) {
            throw new CommandRejected("A command carries an integral " + field + ".");
        }
        return node.asLong();
    }

    private static Instant instant(JsonNode root, String field) throws CommandRejected {
        try {
            return Instant.parse(text(root, field));
        } catch (java.time.format.DateTimeParseException malformed) {
            throw new CommandRejected("A command carries a well-formed " + field + ".");
        }
    }

    private static UUID uuid(JsonNode root, String field) throws CommandRejected {
        try {
            return UUID.fromString(text(root, field));
        } catch (IllegalArgumentException malformed) {
            throw new CommandRejected("A command carries a well-formed " + field + ".");
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
