package com.kaas.karate;

import io.karatelabs.core.Runner;
import io.karatelabs.core.SuiteResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The platform's engine adapter: the only thing that decides what Karate is asked to run.
 *
 * <h2>What this is, and what it deliberately is not</h2>
 *
 * <p>It runs in the same JVM as tenant code, and it must, because obtaining a result means calling Karate's
 * API. So it is written on the assumption that tenant code can read every field it holds and call every
 * method it exposes: it carries no credential, no privileged handle, no control-plane client and no host
 * authority. Private fields are not a security boundary against reflection and are not used as one.
 *
 * <p>The trusted orchestration lives outside this process entirely — in the runner, on the other side of the
 * sandbox. That is the boundary. This is a fixed program inside the blast radius, not a guard at its edge.
 *
 * <h2>What decides which features run</h2>
 *
 * <p>The manifest the source bootstrap wrote, and nothing else. That file is generated from the platform's
 * own frame, which the runner built from the command's authorized feature list — so reading it is reading the
 * platform's decision, not discovering files. There is no directory walk, no glob, no default scan and no
 * argument that names a path.
 *
 * <p>The distinction matters because Karate's own default is to scan a directory. If this walked
 * {@code /kaas/source} instead, anything that appeared there would run, and the authorized set would be
 * whatever the filesystem happened to contain.
 *
 * <h2>What it reports</h2>
 *
 * <p>Two lines: a protocol marker and {@code PASSED} or {@code FAILED}. Nothing about the run's identity,
 * timing, provenance or infrastructure — the runner reconstructs all of that from trusted state, because
 * anything printed here is printed inside a hostile process.
 *
 * <p>Tenant code can print these lines too. It cannot be prevented from doing so, and the platform does not
 * pretend otherwise: the runner refuses a stream carrying more than one result, so forging one turns a run
 * into an infrastructure failure rather than into a forged pass. What tenant code can genuinely influence is
 * its own test outcome, which is a property of running arbitrary code and is documented as accepted.
 */
public final class KaasKarateAdapter {

    /** Where the platform froze the authorized source. A constant here, as everywhere else. */
    private static final Path SOURCE_ROOT = Path.of("/kaas/source");

    private static final Path MANIFEST = SOURCE_ROOT.resolve("manifest.tsv");

    private static final Path FILES = SOURCE_ROOT.resolve("files");

    /**
     * Where Karate may write.
     *
     * <p>Under the bounded, non-executable scratch filesystem, never under the frozen source and never
     * anywhere that survives the container. Karate writes logs and would write reports; all of it dies with
     * the sandbox.
     */
    private static final Path WORK = Path.of("/tmp/kaas-engine");

    /** The result protocol. Versioned so a runner that does not understand a future shape refuses it. */
    static final String PROTOCOL = "kaas.karate-result.v1";

    private KaasKarateAdapter() {}

    public static void main(String[] args) {
        // Nothing from argv reaches Karate. The adapter takes no options, and there is nowhere to put one.
        int exit = run();
        System.out.flush();
        System.exit(exit);
    }

    static int run() {
        List<String> features;
        try {
            features = authorizedFeatures();
        } catch (IOException | RuntimeException unreadable) {
            // A category, never the exception's message: it would carry paths, and paths here are derived
            // from tenant-authored logical names.
            fail("SOURCE_UNREADABLE");
            return 2;
        }
        if (features.isEmpty()) {
            // A bundle with nothing to run is a platform error, not a passing test. Reporting PASSED here
            // would make an empty delivery indistinguishable from a successful suite.
            fail("NO_AUTHORIZED_FEATURES");
            return 2;
        }

        SuiteResult result;
        try {
            Files.createDirectories(WORK);
            result = Runner.path(features)
                    // EVERY REPORT FORM OFF. Karate's default is to write an HTML report, and rich
                    // tenant-controlled output is a separate decision this slice has not made. The files
                    // would also outlive nothing -- the container takes them -- but "it gets deleted" is a
                    // weaker statement than "it was never produced".
                    .outputHtmlReport(false)
                    .outputJsonLines(false)
                    .outputJunitXml(false)
                    .outputCucumberJson(false)
                    .backupOutputDir(false)
                    // Working and output directories are platform-chosen and outside the frozen source, so
                    // an engine that tried to write beside a feature would fail on a read-only filesystem
                    // rather than succeed somewhere unexpected.
                    .workingDir(WORK)
                    .outputDir(WORK.resolve("out"))
                    // One at a time. Parallelism changes resource behaviour, log interleaving and result
                    // semantics all at once, and none of that belongs in the first execution slice.
                    .parallel(1);
        } catch (Throwable engineFailed) {
            // Karate itself failing to run is an ENGINE failure, not a test failure. The distinction is the
            // whole point of the two-outcome model: a broken engine must not be reported as a tenant's
            // assertion failing.
            fail("ENGINE_FAILURE");
            return 3;
        }

        // isFailed() covers a failed scenario and an errored feature alike, which is the question being
        // asked: did the authorized suite pass. Counts are deliberately not reported -- see the result
        // contract; a number the platform cannot corroborate is a number tenant code chooses.
        emit(result.isFailed() ? "FAILED" : "PASSED");
        return 0;
    }

    /**
     * The authorized features, read from the platform's manifest.
     *
     * <p>Every entry is a FeatureRevision the run's sealed snapshot selected, so every entry is a top-level
     * entrypoint. A bundle entry that is not meant to be run on its own would have to not be selected, which
     * is a control-plane decision rather than something inferred here from a filename.
     */
    private static List<String> authorizedFeatures() throws IOException {
        List<String> paths = new ArrayList<>();
        List<String> lines = Files.readAllLines(MANIFEST, StandardCharsets.UTF_8);
        for (int line = 1; line < lines.size(); line++) {
            // Header first, then one tab-separated entry per line: logical path, digest, byte length.
            String[] fields = lines.get(line).split("\t");
            if (fields.length < 3 || fields[0].isBlank()) {
                continue;
            }
            Path resolved = FILES.resolve(fields[0]).normalize();
            if (!resolved.startsWith(FILES)) {
                // The bootstrap already refuses a path that escapes, and the control plane before it. This is
                // the third check, at the last place before a path becomes something Karate opens.
                throw new IllegalStateException("A manifest entry resolved outside the source root.");
            }
            paths.add(resolved.toString());
        }
        return List.copyOf(paths);
    }

    private static void emit(String outcome) {
        System.out.println(PROTOCOL + "=" + outcome);
    }

    private static void fail(String category) {
        // The engine could not produce a verdict. Reported as its own protocol value rather than as FAILED,
        // so the runner can tell "the suite failed" from "the engine never ran the suite" -- one of those is
        // a tenant's result and the other is the platform's problem.
        System.out.println(PROTOCOL + "=ENGINE_ERROR");
        System.out.println("engine_error=" + category);
    }
}
