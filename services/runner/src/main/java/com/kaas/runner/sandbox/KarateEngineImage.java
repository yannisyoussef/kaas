package com.kaas.runner.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.kaas.runner.command.CommandValidator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Builds the Karate engine image, and refuses to build one carrying an engine the runner will not accept.
 *
 * <h2>Why the version is checked here rather than trusted</h2>
 *
 * <p>The runner refuses a command that names an engine version other than {@link CommandValidator#KARATE_VERSION}.
 * That refusal is worth exactly as much as the claim that the image actually contains that version — and
 * nothing in a Docker build enforces the connection. The Gradle dependency could be bumped, the image rebuilt,
 * and every command still authorized against a version string that no longer describes what runs.
 *
 * <p>So the two are tied together at the point where the image is produced: the context is inspected for the
 * engine jar it will ship, and a mismatch is a build failure rather than a silently different engine. This is
 * the same argument the profile makes about image digests — an identity nobody verified is an identity nobody
 * has.
 *
 * <h2>Why the check is on the context</h2>
 *
 * <p>The context is what {@code docker build} copies; checking it is checking the bytes that become the image.
 * Asking the built image instead would mean running it, and the only program in it is the adapter, which
 * shares its JVM with tenant code in every other use — a measurement taken by running the engine is a
 * measurement of a process the platform does not fully own. This one needs neither.
 */
public final class KarateEngineImage {

    /** Where the image context puts the engine's classpath, matching the Dockerfile and the entrypoint. */
    private static final String LIB = "lib";

    private KarateEngineImage() {}

    /**
     * @param contextDirectory the repository-controlled, Gradle-assembled context; never a caller-supplied path
     * @return the built image's full content-derived identity
     */
    public static String build(DockerClient docker, Path contextDirectory) {
        verifyEngineVersion(contextDirectory, CommandValidator.KARATE_VERSION);
        return ProbeImage.build(docker, contextDirectory);
    }

    /**
     * Asserts the context ships exactly one Karate engine, at exactly the expected version.
     *
     * <p>Both halves matter. A missing jar means the image cannot run anything and would fail late, inside a
     * sandbox, as an engine error. TWO jars is worse and quieter: the JVM would pick whichever the classpath
     * glob ordered first, so the running engine would be decided by directory iteration order rather than by
     * anything the platform chose.
     */
    static void verifyEngineVersion(Path contextDirectory, String expectedVersion) {
        Path lib = contextDirectory.resolve(LIB);
        List<String> engines;
        try (Stream<Path> entries = Files.list(lib)) {
            engines = entries.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith("karate-core-") && name.endsWith(".jar"))
                    .sorted()
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(
                    "The engine image context must carry a resolved classpath at " + lib + ".", unreadable);
        }
        String expected = "karate-core-" + expectedVersion + ".jar";
        if (!engines.equals(List.of(expected))) {
            throw new IllegalStateException("The engine image must ship exactly " + expected
                    + ", because that is the only version this runner authorizes a command against. It ships "
                    + engines + ".");
        }
    }
}
