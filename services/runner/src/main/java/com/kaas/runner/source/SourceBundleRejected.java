package com.kaas.runner.source;

/**
 * Why a redeemed bundle was refused before anything was written or mounted.
 *
 * <p>Carries a category and never the offending value. A hostile logical path or a fragment of tenant source
 * in an exception message is tenant content in a log, which is the leak this slice exists to avoid — and the
 * category is what an operator can act on anyway.
 */
public final class SourceBundleRejected extends RuntimeException {

    /** What was wrong. Deliberately coarse, and safe to log. */
    public enum Reason {
        /** The response was not a bundle this build can read at all. */
        MALFORMED,
        /** An entry named a path the contract forbids, or two entries collided. */
        UNSAFE_PATH,
        /** The bundle did not carry exactly the features the command authorized. */
        WRONG_FEATURE_SET,
        /** An entry's bytes did not match the digest the command recorded for it. */
        DIGEST_MISMATCH,
        /** The bundle's aggregate digest did not match the one the command authorized. */
        BUNDLE_DIGEST_MISMATCH,
        /** An entry, or the bundle, exceeded a platform ceiling. */
        TOO_LARGE,
        /** The control plane refused the redemption, or could not be reached. */
        NOT_REDEEMABLE,
        /**
         * This worker's runtime cannot enforce the source filesystem's boundary.
         *
         * <p>Refused before a container exists rather than attempted and failed. The hardened source
         * filesystem is built by remounting a tmpfs read-only from inside the sandbox, and that is permitted
         * by the mediating runtime and refused by the baseline one — measured, both ways. A worker that tried
         * anyway would produce a sandbox with tenant bytes on a filesystem whose flags nobody could
         * establish, which is the state this whole slice exists to make unreachable.
         */
        RUNTIME_CANNOT_ENFORCE,
        /** Authority ended while the bundle was being obtained or staged. */
        AUTHORITY_LOST,
        /** The bundle could not be staged on this host. */
        STAGING_FAILED
    }

    private final Reason reason;

    public SourceBundleRejected(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
