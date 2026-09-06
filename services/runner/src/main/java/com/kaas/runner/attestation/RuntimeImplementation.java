package com.kaas.runner.attestation;

import com.github.dockerjava.api.DockerClient;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Which program will actually confine tenant code, measured rather than declared.
 *
 * <h2>The gap this closes</h2>
 *
 * <p>Until now the signed attestation said which runtime <em>family</em> served the probes — {@code GVISOR},
 * a profile version, an operator-assigned subject, a hash of the daemon's instance id. Every one of those
 * survives replacing the {@code runsc} binary with a different build. A deployment could gather evidence
 * against one implementation and execute against another, and nothing in the signature would notice.
 *
 * <p>That was acceptable while the sandbox ran a repository-controlled probe over inert bytes. It is not
 * acceptable for tenant code, because the runtime implementation IS the boundary — the sentry is the kernel
 * the tenant's syscalls meet.
 *
 * <h2>Measured from what the daemon will invoke</h2>
 *
 * <p>The path comes from the daemon's own runtime registration, not from {@code PATH} and not from an
 * operator-supplied string. Hashing whichever {@code runsc} happens to be first on a {@code PATH} would
 * measure a binary the daemon may never run, which is a worse failure than not measuring at all: it produces
 * confident evidence about the wrong program.
 *
 * <p>Symlinks are resolved and both the link and its target are accounted for: the digest is of the real
 * file, and {@link #pathIdentity()} covers the configured path so that repointing a symlink at a different
 * binary changes the evidence even if some other copy of the old one still exists.
 *
 * <h2>What it cannot promise</h2>
 *
 * <p>There is a window between hashing the file and the daemon executing it. Nothing here closes that — the
 * daemon opens the file itself, later, and no measurement from this process can bind that open. What the
 * measurement does bind is the implementation an attestation describes, so evidence gathered against build A
 * cannot authorize a deployment now running build B. Narrowing the window further is a deployment-integrity
 * problem (immutable images, read-only host paths) and is documented as such rather than claimed here.
 */
public record RuntimeImplementation(String name, String version, String digest, String pathIdentity) {

    /** How long the runtime is given to answer what version it is. It prints a line and exits. */
    private static final java.time.Duration VERSION_TIMEOUT = java.time.Duration.ofSeconds(10);

    /** Bounds what is read from the runtime's own output, which is a program's stdout and therefore untrusted. */
    private static final int MAX_VERSION_BYTES = 4096;

    private static final String PATH_DOMAIN = "KAAS_RUNTIME_PATH_IDENTITY_V1";

    /** Emitted when the deployment's runtime is the default one, which has no separately measurable binary. */
    public static final String NOT_MEASURED = "not-measured";

    public RuntimeImplementation {
        Objects.requireNonNull(name, "Evidence names the runtime it measured.");
        Objects.requireNonNull(version, "Evidence names the runtime version it measured.");
        Objects.requireNonNull(digest, "Evidence names the runtime binary it measured.");
        Objects.requireNonNull(pathIdentity, "Evidence names where that binary was registered.");
    }

    /**
     * The implementation registered under a runtime name, measured through the daemon's own configuration.
     *
     * @param runtimeName the daemon runtime name the profile requires, e.g. {@code runsc}
     */
    public static RuntimeImplementation measure(DockerClient docker, String runtimeName) {
        Path configured = registeredPath(docker, runtimeName);
        Path real;
        try {
            // toRealPath resolves every symlink in the chain, so the digest below is of the file the daemon
            // will actually execute rather than of a link that could be repointed afterwards.
            real = configured.toRealPath();
        } catch (IOException unreadable) {
            throw new AttestationProductionFailed(
                    AttestationFailure.RUNTIME_UNIDENTIFIED,
                    "The configured sandbox runtime could not be resolved to a file.");
        }
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new AttestationProductionFailed(
                    AttestationFailure.RUNTIME_UNIDENTIFIED,
                    "The configured sandbox runtime is not a regular file.");
        }
        return new RuntimeImplementation(
                runtimeName, versionOf(real), digestOf(real), pathIdentityOf(configured, real));
    }

    /**
     * Asks the daemon where the runtime lives.
     *
     * <p>The authoritative answer, and the only one worth having. An operator-typed digest measures a claim;
     * {@code which runsc} measures whatever this process's environment happens to find. This measures the
     * registration the daemon will resolve when it starts a mediated container.
     */
    private static Path registeredPath(DockerClient docker, String runtimeName) {
        Object runtimes;
        try {
            runtimes = docker.infoCmd().exec().getRuntimes();
        } catch (RuntimeException unreachable) {
            throw new AttestationProductionFailed(
                    AttestationFailure.RUNTIME_UNIDENTIFIED,
                    "The container runtime could not be asked how it is configured.");
        }
        if (!(runtimes instanceof java.util.Map<?, ?> registered)) {
            throw new AttestationProductionFailed(
                    AttestationFailure.RUNTIME_UNIDENTIFIED, "The daemon reported no runtime configuration.");
        }
        Object entry = registered.get(runtimeName);
        if (entry == null) {
            throw new AttestationProductionFailed(
                    AttestationFailure.RUNTIME_UNIDENTIFIED,
                    "The daemon has no runtime registered under the name this profile requires.");
        }
        String path = pathOf(entry);
        if (path == null || path.isBlank()) {
            throw new AttestationProductionFailed(
                    AttestationFailure.RUNTIME_UNIDENTIFIED,
                    "The daemon registered the runtime without a path to measure.");
        }
        return Path.of(path);
    }

    /**
     * Reads the path out of whatever shape the client library models a runtime as.
     *
     * <p>Reflective because the field has moved between client versions and this must not fail closed on a
     * library upgrade in a way that looks like a security finding. A missing path is still refused above.
     */
    private static String pathOf(Object runtime) {
        for (String accessor : List.of("getPath", "path")) {
            try {
                Object value = runtime.getClass().getMethod(accessor).invoke(runtime);
                if (value instanceof String path && !path.isBlank()) {
                    return path;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Try the next shape; an unmeasurable runtime is refused by the caller.
            }
        }
        return null;
    }

    /**
     * The runtime's own version string, bounded and normalised to one line.
     *
     * <p>Executed rather than parsed out of a filename, because a filename is not a version. The output is a
     * program's stdout and is treated as such: bounded, single-line, and stripped of anything that is not
     * printable ASCII, so a runtime that printed control characters could not put them into signed evidence.
     */
    private static String versionOf(Path binary) {
        Process process;
        try {
            process = new ProcessBuilder(binary.toString(), "--version")
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException cannotRun) {
            throw new AttestationProductionFailed(
                    AttestationFailure.RUNTIME_UNIDENTIFIED, "The sandbox runtime could not be asked its version.");
        }
        String output;
        try (InputStream stdout = process.getInputStream()) {
            byte[] bytes = stdout.readNBytes(MAX_VERSION_BYTES);
            output = new String(bytes, StandardCharsets.UTF_8);
            if (!process.waitFor(VERSION_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new AttestationProductionFailed(
                        AttestationFailure.RUNTIME_UNIDENTIFIED, "The sandbox runtime did not report a version.");
            }
        } catch (IOException unreadable) {
            throw new AttestationProductionFailed(
                    AttestationFailure.RUNTIME_UNIDENTIFIED, "The sandbox runtime's version could not be read.");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AttestationProductionFailed(
                    AttestationFailure.RUNTIME_UNIDENTIFIED, "Interrupted while reading the runtime version.");
        }
        String first = output.lines().findFirst().orElse("").trim();
        String cleaned = first.replaceAll("[^\\x20-\\x7E]", "");
        if (cleaned.isBlank()) {
            throw new AttestationProductionFailed(
                    AttestationFailure.RUNTIME_UNIDENTIFIED, "The sandbox runtime reported no version.");
        }
        return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
    }

    /** SHA-256 of the executable itself. The whole point of the field. */
    private static String digestOf(Path binary) {
        try (InputStream bytes = Files.newInputStream(binary)) {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = bytes.read(buffer)) > 0) {
                sha.update(buffer, 0, read);
            }
            return "sha256:" + HexFormat.of().formatHex(sha.digest());
        } catch (IOException | NoSuchAlgorithmException unreadable) {
            throw new AttestationProductionFailed(
                    AttestationFailure.RUNTIME_UNIDENTIFIED, "The sandbox runtime binary could not be measured.");
        }
    }

    /**
     * An opaque identity for where the runtime was registered.
     *
     * <p>Hashed rather than published, because a host path is host-descriptive and the artifact travels — the
     * same reason the runtime subject is an operator label rather than a hostname. It covers the configured
     * path and the resolved one together, so repointing a symlink changes the evidence even when the digest of
     * some binary somewhere is unchanged.
     */
    private static String pathIdentityOf(Path configured, Path real) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            update(sha, PATH_DOMAIN);
            update(sha, configured.toString());
            update(sha, real.toString());
            return "path:" + HexFormat.of().formatHex(sha.digest()).substring(0, 32);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
