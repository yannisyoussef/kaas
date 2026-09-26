package com.kaas.karate;

import io.karatelabs.core.Runner;
import io.karatelabs.core.SuiteResult;
import java.io.DataInputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        // WHICH ENGINE IS ACTUALLY LOADED, printed before anything else runs.
        //
        // Every other line this adapter prints would be equally printable by an adapter that never loaded
        // Karate at all, which makes an execution test indistinguishable from a protocol test. This one
        // cannot: it resolves a resource through Karate's own class, so producing it requires the engine to
        // be on the classpath and requires that engine to say what version it is.
        emitEngineIdentity();

        // THE ENGINE FRAME, before any tenant code exists in this process.
        //
        // The bootstrap consumed exactly the source frame from standard input and handed over; what is left in
        // the pipe is this frame, written by the runner, carrying the run's secret values and -- under an
        // allowlist -- where the egress proxy is. It is read exactly, the pipe is required to be empty after
        // it, and standard input is then CLOSED, so the descriptor tenant code could have read leftover bytes
        // from no longer exists by the time Karate parses a single feature.
        EngineFrame frame;
        try {
            frame = EngineFrame.read(new FileInputStream(FileDescriptor.in));
        } catch (IOException | RuntimeException unreadable) {
            closeStandardInput();
            // A category only. The frame may carry secret values, and an exception message about it could
            // quote part of one.
            fail("SECRET_CHANNEL");
            return 2;
        }
        closeStandardInput();
        System.out.println("kaas.secrets=CONSUMED");

        List<String> features;
        try {
            features = authorizedFeatures(MANIFEST, FILES);
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
                    // THE ONLY WAY A SECRET REACHES TENANT CODE: a JS global the platform binds, named `kaas`,
                    // read as `kaas.secrets.<BINDING_KEY>`. Programmatic, so there is no source rewriting, no
                    // environment variable, no system property, no file. It is readable by every scenario, and
                    // that is intended: the run was authorized to use these values. It is not protected from
                    // tenant code by Java visibility or an unmodifiable map, and nothing here pretends it is.
                    .global("kaas", frame.globals())
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
     * Closes descriptor 0 and replaces {@link System#in} with an empty stream.
     *
     * <p>Closing the {@link FileInputStream} over {@link FileDescriptor#in} closes the process's descriptor 0.
     * After this there is nothing at {@code /proc/self/fd/0} or {@code /dev/stdin} for tenant code to open, and
     * {@code System.in} reads end-of-stream immediately. Done on every path, including a malformed frame, so a
     * refusal cannot leave secret bytes readable behind it.
     */
    private static void closeStandardInput() {
        try {
            new FileInputStream(FileDescriptor.in).close();
        } catch (IOException | RuntimeException ignored) {
            // Already closed is the state this wants.
        }
        System.setIn(InputStream.nullInputStream());
    }

    /**
     * The engine frame, as the runner wrote it after the source frame.
     *
     * <p>See the runner's {@code EngineInput} and {@code packages/api-contracts/engine-frame.md}. Read with exact
     * reads from an unbuffered stream, bounded before anything is allocated, and refused whole on any
     * deviation. Values are decoded into strings only because Karate variables are strings; the byte arrays
     * they came from are cleared.
     */
    record EngineFrame(Map<String, String> secrets, String proxyUri, String proxyToken) {
        private static final byte[] MAGIC = "KAASENG1".getBytes(StandardCharsets.US_ASCII);
        private static final byte[] TRAILER = "KAASEND1".getBytes(StandardCharsets.US_ASCII);

        static EngineFrame read(InputStream stdin) throws IOException {
            DataInputStream in = new DataInputStream(stdin);
            require(in, MAGIC);
            int count = in.readInt();
            if (count < 0 || count > 50) {
                throw new IOException("frame");
            }
            Map<String, String> secrets = new LinkedHashMap<>();
            long total = 0;
            for (int index = 0; index < count; index++) {
                int keyLength = in.readUnsignedShort();
                if (keyLength < 1 || keyLength > 128) {
                    throw new IOException("frame");
                }
                String key = new String(in.readNBytes(keyLength), StandardCharsets.US_ASCII);
                int valueLength = in.readInt();
                total += valueLength;
                if (valueLength < 1 || valueLength > 8192 || total > 65536) {
                    throw new IOException("frame");
                }
                byte[] value = in.readNBytes(valueLength);
                if (value.length != valueLength || secrets.containsKey(key)) {
                    throw new IOException("frame");
                }
                secrets.put(key, new String(value, StandardCharsets.UTF_8));
                Arrays.fill(value, (byte) 0);
            }
            String proxyUri = null;
            String proxyToken = null;
            int egress = in.readUnsignedByte();
            if (egress == 1) {
                String host = new String(in.readNBytes(in.readUnsignedShort()), StandardCharsets.US_ASCII);
                int port = in.readUnsignedShort();
                proxyToken = new String(in.readNBytes(in.readUnsignedShort()), StandardCharsets.US_ASCII);
                proxyUri = "http://" + host + ":" + port;
            } else if (egress != 0) {
                throw new IOException("frame");
            }
            require(in, TRAILER);
            // Nothing may follow. The runner writes exactly the source frame and this one; a byte after the
            // trailer is a stream that is not what the platform wrote.
            if (stdin.available() != 0) {
                throw new IOException("frame");
            }
            return new EngineFrame(Map.copyOf(secrets), proxyUri, proxyToken);
        }

        private static void require(DataInputStream in, byte[] expected) throws IOException {
            byte[] actual = in.readNBytes(expected.length);
            if (!Arrays.equals(actual, expected)) {
                throw new IOException("frame");
            }
        }

        /**
         * The `kaas` global: {@code secrets} always (empty for a secret-free run), and {@code egress} only under
         * an allowlist. The platform's own karate-config.js reads {@code kaas.egress} to point Karate's HTTP
         * client at the proxy.
         */
        Map<String, Object> globals() {
            Map<String, Object> globals = new LinkedHashMap<>();
            globals.put("secrets", secrets);
            if (proxyUri != null) {
                globals.put("egress", Map.of("uri", proxyUri, "token", proxyToken));
            }
            return globals;
        }

        @Override
        public String toString() {
            return "EngineFrame[secrets=" + secrets.size() + ", egress=" + (proxyUri != null) + "]";
        }
    }

    /**
     * The authorized features, read from the platform's manifest.
     *
     * <p>The two roots are parameters rather than the constants they are called with in production. Not for
     * flexibility — nothing configures them — but because the escape check below is a security control, and a
     * control that can only run against {@code /kaas/source} on a live sandbox is a control no test can drive
     * a hostile manifest through. The production call site passes the constants and nothing else can.
     *
     * <p>Every entry is a FeatureRevision the run's sealed snapshot selected, so every entry is a top-level
     * entrypoint. A bundle entry that is not meant to be run on its own would have to not be selected, which
     * is a control-plane decision rather than something inferred here from a filename.
     */
    static List<String> authorizedFeatures(Path manifest, Path files) throws IOException {
        List<String> paths = new ArrayList<>();
        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        for (int line = 1; line < lines.size(); line++) {
            // Header first, then one tab-separated entry per line: logical path, digest, byte length.
            String[] fields = lines.get(line).split("\t");
            if (fields.length < 3 || fields[0].isBlank()) {
                continue;
            }
            Path resolved = files.resolve(fields[0]).normalize();
            if (!resolved.startsWith(files)) {
                // The bootstrap already refuses a path that escapes, and the control plane before it. This is
                // the third check, at the last place before a path becomes something Karate opens.
                throw new IllegalStateException("A manifest entry resolved outside the source root.");
            }
            paths.add(resolved.toString());
        }
        return List.copyOf(paths);
    }

    /**
     * Reports the loaded engine's identity from the engine's own metadata.
     *
     * <p>Not a constant, and not the adapter's opinion. {@code karate-meta.properties} ships inside
     * karate-core and is read through {@link Runner}'s classloader, so this line exists only if the class
     * loaded and only if its jar carried the version it claims.
     *
     * <p>Tenant code could print this line too, and could print a different version. That does not weaken it:
     * the runner refuses a stream carrying a duplicated key, so a second claim removes the evidence rather
     * than replacing it — the same rule that governs the result itself.
     */
    private static void emitEngineIdentity() {
        String version = "unknown";
        try (java.io.InputStream meta = Runner.class.getClassLoader().getResourceAsStream("karate-meta.properties")) {
            if (meta != null) {
                java.util.Properties properties = new java.util.Properties();
                properties.load(meta);
                version = properties.getProperty("karate.version", "unknown");
            }
        } catch (IOException | RuntimeException unreadable) {
            // Left as "unknown". An engine that cannot say what it is must not be reported as a version it
            // might not be, and the suites that require an identity will fail on the word rather than on an
            // absence nobody notices.
            version = "unknown";
        }
        System.out.println("kaas.engine=karate " + version);
    }

    /**
     * The verdict, on a line of its own whatever tenant code left on stdout.
     *
     * <p>The leading newline is defence in depth. Tenant code shares this stream, and a tenant that printed a
     * forged {@code PASSED} on stderr and then left stdout mid-line would, without it, have this line glued onto
     * its own unterminated one, where the runner would never count it. Under Karate 2.1.2 that does not happen
     * today -- Karate's console summary ends any open line first (measured) -- but this line should not depend
     * on what the engine happens to print before it. A tenant that forges and then exits, or that replaces
     * {@code System.out}, is not stopped by anything the adapter prints; that is the tenant-owned outcome
     * recorded in ADR-034.
     */
    private static void emit(String outcome) {
        System.out.print("\n" + PROTOCOL + "=" + outcome + "\n");
        System.out.flush();
    }

    private static void fail(String category) {
        // The engine could not produce a verdict. Reported as its own protocol value rather than as FAILED,
        // so the runner can tell "the suite failed" from "the engine never ran the suite" -- one of those is
        // a tenant's result and the other is the platform's problem.
        System.out.print("\n" + PROTOCOL + "=ENGINE_ERROR\n");
        System.out.println("engine_error=" + category);
    }
}
