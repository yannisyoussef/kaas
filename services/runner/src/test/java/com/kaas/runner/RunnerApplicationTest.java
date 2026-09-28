package com.kaas.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RunnerApplicationTest {
    @Test
    void anInvalidConfigurationStopsTheRunnerBeforeAnythingStartsAndNamesEveryProblemButNoValue() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        String secretLooking = "kaas-canary-value-that-must-not-be-echoed";

        int exit = RunnerApplication.run(
                Map.of("KAAS_RUNNER_API_URL", "http://10.0.0.5:8080?token=" + secretLooking,
                        "KAAS_RUNNER_ENGINE_IMAGE", "registry.example/kaas-engine:latest"),
                new PrintStream(output, true, StandardCharsets.UTF_8));

        String printed = output.toString(StandardCharsets.UTF_8);
        assertThat(exit).isEqualTo(2);
        assertThat(printed).startsWith("runner=CONFIGURATION_INVALID")
                .contains("KAAS_RUNNER_WORKER_ID is required")
                .contains("KAAS_RUNNER_API_URL")
                .contains("KAAS_RUNNER_ENGINE_IMAGE must be pinned by digest")
                .contains("KAAS_RUNNER_TOKEN_ENDPOINT")
                .doesNotContain(secretLooking)
                .doesNotContain("registry.example");
    }

    @Test
    void thereIsNoGeneralCommandApi() {
        // The value of the boundary comes from there being exactly one thing the launcher will run. A method
        // taking a command, an image, or an argument list would end that, so this fails if one appears.
        assertTrue(
                java.util.Arrays.stream(RunnerApplication.class.getDeclaredMethods())
                        .noneMatch(method -> method.getName().toLowerCase(java.util.Locale.ROOT).contains("exec")
                                || method.getName().toLowerCase(java.util.Locale.ROOT).contains("command")),
                "the runner must not expose a general execution entry point");
    }
}
