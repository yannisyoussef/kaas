package com.kaas.runner.source;

/**
 * The bounds an inert tenant source bundle must satisfy, as this module understands them.
 *
 * <p>Duplicated from {@code packages/api-contracts/source-bundle.json} rather than imported, because the
 * control plane assembles bundles and this module is build-guarded against depending on it. A contract test
 * on each side asserts its own constants equal that file, so a limit relaxed on one side alone fails the
 * build instead of silently admitting a bundle the other would refuse.
 *
 * <h2>Why the runner re-checks what the control plane already checked</h2>
 *
 * <p>The control plane validates every one of these before it assembles a bundle. That is not sufficient. The
 * runner is the last component before tenant bytes reach a filesystem, and "the server would not send that" is
 * an assumption rather than a check — a control-plane defect must not become source substitution or
 * unbounded materialisation.
 */
public final class SourceBundleContract {

    private SourceBundleContract() {}

    /** The bundle format this build accepts. An unknown version is refused, never interpreted. */
    public static final String FORMAT_VERSION = "kaas.source-bundle.v1";

    /** The sealed snapshot's selection ceiling. */
    public static final int MAX_ENTRIES = 1000;

    /** The whole bundle. A per-feature bound alone does not stop aggregate exhaustion across many features. */
    public static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;

    /** One entry, corroborating the per-FeatureRevision ceiling independently of the database. */
    public static final long MAX_ENTRY_BYTES = 1024L * 1024;

    /** A logical path. */
    public static final int MAX_PATH_LENGTH = 512;

    /** Where the bundle appears inside the sandbox. Platform-owned and fixed; never derived from tenant input. */
    public static final String CONTAINER_PATH = "/kaas/source";

    /** The platform-generated manifest, at the mount root and outside the tenant-controlled subtree. */
    public static final String MANIFEST_NAME = "manifest.tsv";

    /** The subdirectory every tenant file lives under, so a logical path cannot collide with the manifest. */
    public static final String FILES_DIRECTORY = "files";

    /**
     * The size of the sandbox-private filesystem the source is written into.
     *
     * <p>The aggregate ceiling plus a bounded allowance for directory entries, the manifest and the
     * filesystem's own overhead. It is a real limit rather than a formality: the filesystem is memory inside
     * the sandbox, and a bundle that does not fit fails the bootstrap's write instead of growing without end.
     *
     * <p>It does not replace the transport and aggregate bounds. Those refuse an oversized bundle before a
     * container exists, and they are what should actually fire; this is the last layer.
     */
    public static final long SOURCE_FILESYSTEM_BYTES = MAX_TOTAL_BYTES + (4L * 1024 * 1024);
}
