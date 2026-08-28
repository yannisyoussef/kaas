package com.kaas.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RunnerApplicationTest {
    @Test
    void bootstrapReportsThatExecutionIsDisabled() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        RunnerApplication.run(new PrintStream(output, true, StandardCharsets.UTF_8));

        assertEquals(
                RunnerApplication.BOOTSTRAP_MESSAGE + System.lineSeparator(),
                output.toString(StandardCharsets.UTF_8));
    }
}
