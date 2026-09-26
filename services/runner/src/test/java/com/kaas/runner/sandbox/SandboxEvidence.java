package com.kaas.runner.sandbox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Where a mediated-runtime suite writes the evidence its CI gate reads back.
 *
 * <p>A directory the BUILD names (system property {@code kaas.evidence.dir}, set by the Gradle task that runs the
 * suite), not an environment variable the suite hopes is present. The previous arrangement returned silently when
 * {@code RUNNER_TEMP} was unset, so a suite run anywhere but CI produced no evidence and said nothing about it;
 * this one fails the test instead. Evidence is written BEFORE the assertions that check it, so a run that named the
 * wrong engine leaves the wrong name where a reader can see it.
 *
 * <p>Evidence lines are categories, booleans and identifiers. A secret value, or anything derived from one, is
 * never written here.
 */
public final class SandboxEvidence {

    private SandboxEvidence() {}

    public static Path directory() {
        String configured = System.getProperty("kaas.evidence.dir");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "kaas.evidence.dir is set by the build for every mediated-runtime suite; without it this "
                            + "suite would produce evidence nobody reads.");
        }
        return Path.of(configured);
    }

    public static void write(String file, String evidence) {
        try {
            Path directory = directory();
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(file), evidence, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException unwritable) {
            throw new UncheckedIOException(unwritable);
        }
    }

    public static void append(String file, String evidence) {
        try {
            Path directory = directory();
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(file), evidence, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException unwritable) {
            throw new UncheckedIOException(unwritable);
        }
    }
}
