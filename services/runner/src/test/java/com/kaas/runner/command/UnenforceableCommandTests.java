package com.kaas.runner.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Refusals that only happen AFTER the digest check passes.
 *
 * <p>These exist because of a hole this slice's own mutation battery found. The pipeline tests reach the
 * validator with real commands and tamper with them to prove integrity checking works — but tampering changes
 * the digest, so every one of those tests fails at the digest comparison and returns. The checks below it, on
 * network policy and engine, were never executed by any test: deleting the network-policy check entirely killed
 * nothing.
 *
 * <p>So each command here is built by hand and then correctly digested with the validator's own function. That
 * makes the digest check pass, which is the whole point — it is the only way to reach the refusals underneath.
 * The digest is not what these tests are about, and using the production function to compute it is deliberate.
 */
class UnenforceableCommandTests {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("a correctly digested ALLOWLIST command is refused rather than run with unenforced egress")
    void allowlistIsRefusedOnItsOwnAccount() throws Exception {
        ObjectNode command = sealed(node -> ((ObjectNode) node.get("networkPolicy")).put("type", "ALLOWLIST"));

        assertThatThrownBy(() -> new CommandValidator(mapper).validate(command.toString(), NOW))
                .isInstanceOf(CommandRejected.class)
                // Named specifically. A run that appeared to have egress control nothing was applying would be
                // worse than one with none, because somebody would rely on it.
                .hasMessageContaining("cannot enforce the network policy ALLOWLIST");
    }

    @Test
    @DisplayName("a correctly digested KARATE command is refused rather than run as synthetic under that name")
    void karateIsRefusedOnItsOwnAccount() throws Exception {
        ObjectNode command = sealed(node -> ((ObjectNode) node.get("engine")).put("type", "KARATE"));

        assertThatThrownBy(() -> new CommandValidator(mapper).validate(command.toString(), NOW))
                .isInstanceOf(CommandRejected.class)
                // The failure this prevents: shell assertions reported to every dashboard as a Karate suite.
                .hasMessageContaining("cannot execute the engine KARATE");
    }

    @Test
    @DisplayName("a correctly digested command binding secrets is refused")
    void secretBearingCommandsAreRefused() throws Exception {
        ObjectNode command = sealed(node -> {
            ObjectNode secret =
                    ((tools.jackson.databind.node.ArrayNode) node.get("secretCapabilities")).addObject();
            secret.put("capabilityId", UUID.randomUUID().toString());
            secret.put("provider", "aws-secrets-manager");
            secret.put("referenceId", "ref-1");
            secret.put("bindingKey", "API_TOKEN");
            secret.put("expiresAt", NOW.plusSeconds(300).toString());
        });

        assertThatThrownBy(() -> new CommandValidator(mapper).validate(command.toString(), NOW))
                .isInstanceOf(CommandRejected.class)
                .hasMessageContaining("redeems no secrets");
    }

    @Test
    @DisplayName("the baseline this class builds on is itself accepted")
    void theBaselineIsValid() throws Exception {
        // Anti-vacuity. Every test above asserts a rejection, and a baseline that was rejected for some
        // unrelated reason would make all of them pass while testing nothing.
        var validated = new CommandValidator(mapper).validate(sealed(node -> {}).toString(), NOW);
        assertThat(validated.engineType()).isEqualTo("SYNTHETIC");
        assertThat(validated.networkPolicyType()).isEqualTo("DENY_ALL");
    }

    private static final Instant NOW = Instant.parse("2026-09-03T12:00:00Z");

    /** Builds a well-formed command, applies the mutation, then digests the RESULT so the digest is correct. */
    private ObjectNode sealed(java.util.function.Consumer<ObjectNode> mutation) throws Exception {
        ObjectNode command = baseline();
        mutation.accept(command);
        command.put("commandDigest", CommandValidator.digest(command));
        return command;
    }

    private ObjectNode baseline() {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", "1.0");
        root.put("commandId", "11111111-1111-4111-8111-111111111111");
        root.put("commandDigest", "sha256:" + "0".repeat(64));
        root.put("organizationId", "22222222-2222-4222-8222-222222222222");
        root.put("projectId", "33333333-3333-4333-8333-333333333333");
        root.put("runId", "44444444-4444-4444-8444-444444444444");
        root.put("runVersion", 3);
        root.put("attemptId", "55555555-5555-4555-8555-555555555555");
        root.put("attemptNumber", 1);
        root.put("assignmentEpoch", 1);
        root.put("runSnapshotDigest", "sha256:" + "a".repeat(64));
        root.put("issuedAt", NOW.minusSeconds(60).toString());
        root.put("expiresAt", NOW.plusSeconds(600).toString());

        ObjectNode engine = root.putObject("engine");
        engine.put("type", "SYNTHETIC");
        engine.put("version", "1.0.0");

        ObjectNode bundle = root.putObject("sourceBundle");
        bundle.put("contentDigest", "sha256:" + "b".repeat(64));
        ObjectNode feature = bundle.putArray("features").addObject();
        feature.put("featureId", "66666666-6666-4666-8666-666666666666");
        feature.put("revisionId", "77777777-7777-4777-8777-777777777777");
        feature.put("logicalPath", "features/one.feature");
        feature.put("contentDigest", "sha256:" + "c".repeat(64));

        root.putArray("secretCapabilities");

        ObjectNode network = root.putObject("networkPolicy");
        network.put("policyRevisionId", "88888888-8888-4888-8888-888888888888");
        network.put("type", "DENY_ALL");
        network.put("version", 1);
        network.put("digest", "sha256:" + "d".repeat(64));

        ObjectNode sandbox = root.putObject("sandboxSecurityProfile");
        sandbox.put("profileVersion", "kaas.sandbox.v1");
        sandbox.put("assessmentDigest", "sha256:" + "e".repeat(64));

        root.putObject("configurationSnapshot").put("baseUrl", "https://environment.example");
        root.putObject("selection").putArray("tags").add("@smoke");
        root.put("parallelism", 1);
        ObjectNode retry = root.putObject("scenarioRetry");
        retry.put("maxAttempts", 1);
        retry.put("delayMilliseconds", 0);
        root.put("executionTimeoutSeconds", 60);
        ObjectNode artifacts = root.putObject("artifactPolicy");
        artifacts.putArray("types").add("RAW_RESULT");
        artifacts.put("maxArtifactBytes", 1_000);
        artifacts.put("maxTotalBytes", 2_000);
        return root;
    }
}
