package com.kaas.api.execution.infrastructure;

import com.kaas.api.controlplane.domain.InfrastructureOutcome;
import com.kaas.api.controlplane.domain.TestOutcome;
import com.kaas.api.execution.application.ResultDocumentReader;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads a result document strictly.
 *
 * <p>Strict in the sense that matters here: every field this reads must be present and well typed, and a
 * missing one is an exception rather than a null that flows onward. A reader that quietly produced nulls for
 * absent identity fields would turn "the document says nothing about which run it belongs to" into "the
 * document agrees with whatever we compare it to", which is the exact inversion of what provenance is for.
 */
@Component
public class JacksonResultDocumentReader implements ResultDocumentReader {

    private final ObjectMapper mapper;

    public JacksonResultDocumentReader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ParsedResult read(String document) {
        JsonNode root = mapper.readTree(document);
        if (!root.isObject()) {
            throw new IllegalArgumentException("A result document is an object.");
        }
        return new ParsedResult(
                uuid(root, "organizationId"),
                uuid(root, "projectId"),
                uuid(root, "runId"),
                integral(root, "runVersion"),
                uuid(root, "attemptId"),
                (int) integral(root, "assignmentEpoch"),
                uuid(root, "commandId"),
                instant(root, "startedAt"),
                instant(root, "finishedAt"),
                TestOutcome.valueOf(text(root, "testOutcome")),
                InfrastructureOutcome.valueOf(text(root, "infrastructureOutcome")));
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isString()) {
            throw new IllegalArgumentException("A result document carries a textual " + field + ".");
        }
        return node.asString();
    }

    private static UUID uuid(JsonNode root, String field) {
        return UUID.fromString(text(root, field));
    }

    private static Instant instant(JsonNode root, String field) {
        return Instant.parse(text(root, field));
    }

    private static long integral(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || !node.isIntegralNumber()) {
            throw new IllegalArgumentException("A result document carries an integral " + field + ".");
        }
        return node.asLong();
    }
}
