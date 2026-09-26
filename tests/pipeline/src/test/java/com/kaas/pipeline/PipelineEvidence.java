package com.kaas.pipeline;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Where a pipeline suite writes the evidence its CI gate reads back: a directory the BUILD names
 * ({@code kaas.evidence.dir}), so a suite that cannot write it fails instead of returning quietly.
 *
 * <p>Evidence is categories, booleans and identifiers. A secret value, or anything derived from one, is never
 * written here.
 */
final class PipelineEvidence {

    private PipelineEvidence() {}

    static void append(String file, String evidence) {
        String configured = System.getProperty("kaas.evidence.dir");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("kaas.evidence.dir is set by the build for this suite.");
        }
        try {
            Path directory = Path.of(configured);
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(file), evidence, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException unwritable) {
            throw new UncheckedIOException(unwritable);
        }
    }
}
