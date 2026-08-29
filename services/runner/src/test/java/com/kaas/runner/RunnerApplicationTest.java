package com.kaas.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RunnerApplicationTest {
    @Test
    void bootstrapReportsThatExecutionIsDisabled() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        RunnerApplication.run(new PrintStream(output, true, StandardCharsets.UTF_8));

        // The original contract is unchanged: this module executes nothing arbitrary. It now also says what the
        // sandbox is for, because a module that has acquired a container launcher should say out loud that the
        // launcher runs one probe rather than anything a caller names.
        assertEquals(
                RunnerApplication.BOOTSTRAP_MESSAGE
                        + System.lineSeparator()
                        + RunnerApplication.SECURITY_PROBE_MESSAGE
                        + System.lineSeparator(),
                output.toString(StandardCharsets.UTF_8));
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
