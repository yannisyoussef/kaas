package com.kaas.runner.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * What a sandbox says cannot become an instruction to whoever reads it.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The collector strips control and Unicode format characters, and the code says so in a comment about
 * terminal escape sequences being an attack on whoever reads the logs. Nothing verified it: removing the
 * filter broke no test, which was measured rather than assumed.
 *
 * <p>Under a workload this repository writes, that gap costs little. The next slice puts tenant-authored
 * bytes in the sandbox, and although they will not execute, they can influence what the sandbox prints —
 * which is the moment output sanitisation stops being hygiene and becomes a boundary.
 *
 * <p>ADR-022's fifth prerequisite is "artifact and output handling for genuinely untrusted content". These
 * are the tests behind the half of it that concerns what leaves the sandbox.
 */
@DisplayName("Untrusted output")
class UntrustedOutputTests {

    private final String generation = "hostile-output-" + UUID.randomUUID();

    @Test
    @Timeout(180)
    @DisplayName("terminal escapes and Unicode reordering characters never reach an observation")
    void hostileOutputIsSanitisedAtTheCollector() {
        Map<String, String> observations = hostileObservations();

        // Nothing the workload printed survives as a control or format character, in a key or a value. Both
        // halves matter: sanitising only values would leave the map's own keys attacker-shaped.
        assertThat(observations).isNotEmpty();
        observations.forEach((key, value) -> {
            assertThat(containsControlOrFormat(key)).as("key %s", debug(key)).isFalse();
            assertThat(containsControlOrFormat(value)).as("value %s", debug(value)).isFalse();
        });

        // And specifically the shapes the probe emitted, so a sanitiser that stripped everything into empty
        // strings would not pass either.
        assertThat(observations.get("hostile_escape")).isEqualTo("[31mred[0m");
        assertThat(observations.get("hostile_rtlo")).isEqualTo("reversed");
        assertThat(observations.get("hostile_zwj")).isEqualTo("ab");
    }

    @Test
    @Timeout(180)
    @DisplayName("path-like output stays an inert string and never becomes a path")
    void pathLikeOutputIsData() {
        Map<String, String> observations = hostileObservations();

        // Retained exactly as written. The property is NOT that traversal is scrubbed -- it is that an
        // observation is a string and nothing in the runner ever resolves one against a filesystem. Scrubbing
        // it would hide the evidence and imply the opposite: that something might have used it as a path.
        assertThat(observations.get("hostile_traversal")).isEqualTo("../../etc/passwd");
        assertThat(observations.get("hostile_absolute")).isEqualTo("/etc/shadow");

        // The claim above, made structural: nothing in the runner turns an observation into a file, a path,
        // or a command. If that ever changes, this is the test that has to be argued with.
        assertThat(sourceMentioningObservationsAsPaths())
                .as("no runner source resolves an observation against the filesystem")
                .isEmpty();
    }

    private Map<String, String> hostileObservations() {
        var launcher = SandboxTestSupport.launcher(SandboxTestSupport.profile(), generation);
        SandboxOutcome outcome = launcher.run(new SandboxLaunchRequest(
                SyntheticProbe.HOSTILE_OUTPUT, launcher.profile().version(), UUID.randomUUID()));
        assertThat(outcome.failure()).as("%s", outcome).isEmpty();
        assertThat(outcome.observations()).containsKey("hostile_output_completed");
        return outcome.observations();
    }

    /** Renders a value's code points, so a failure message about invisible characters is readable. */
    private static String debug(String value) {
        return value.codePoints()
                .mapToObj(codePoint -> String.format("U+%04X", codePoint))
                .collect(java.util.stream.Collectors.joining(" "));
    }

    private static boolean containsControlOrFormat(String value) {
        return value.codePoints()
                .anyMatch(codePoint -> Character.isISOControl(codePoint)
                        || Character.getType(codePoint) == Character.FORMAT
                        || Character.getType(codePoint) == Character.LINE_SEPARATOR
                        || Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR);
    }

    /** Any runner source that passes an observation to a filesystem or process API. */
    private static java.util.List<String> sourceMentioningObservationsAsPaths() throws AssertionError {
        try {
            java.nio.file.Path main = java.nio.file.Path.of("src", "main", "java").toFile().isDirectory()
                    ? java.nio.file.Path.of("src", "main", "java")
                    : java.nio.file.Path.of("services", "runner", "src", "main", "java");
            try (var files = java.nio.file.Files.walk(main)) {
                return files.filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> {
                            try {
                                String text = java.nio.file.Files.readString(path);
                                return java.util.stream.Stream.of(
                                                "Path.of(observation", "new File(observation",
                                                "Files.readString(observation", "ProcessBuilder(observation")
                                        .anyMatch(text::contains);
                            } catch (java.io.IOException unreadable) {
                                return false;
                            }
                        })
                        .map(java.nio.file.Path::toString)
                        .toList();
            }
        } catch (java.io.IOException unreadable) {
            throw new AssertionError("the runner's sources must be readable", unreadable);
        }
    }
}
