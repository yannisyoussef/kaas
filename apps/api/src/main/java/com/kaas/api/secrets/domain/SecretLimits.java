package com.kaas.api.secrets.domain;

/**
 * The platform's bounds on secret material, enforced at every boundary that handles it.
 *
 * <p>Enforced at creation, after decryption, when a bundle is built, and again by the runner's parser — and not
 * merely at creation. A value that was small when it was written is not guaranteed to be small when it comes
 * back: the database and the provider are both outside this process, and a bound that is checked once is a
 * bound that holds only as long as nothing else ever writes the row.
 *
 * <p>The numbers are chosen for what secrets actually are — tokens, passwords, and PEM-encoded keys, the largest
 * common case being a 4096-bit RSA private key at a little over three kilobytes — and for what the output
 * redactor has to hold back. The redactor keeps one byte less than the longest value in memory per stream, so
 * these also bound its buffering.
 */
public final class SecretLimits {

    /** One value, in bytes of UTF-8. */
    public static final int MAX_VALUE_BYTES = 8 * 1024;

    /**
     * Secrets one run may bind. The environment already refuses more than this many bindings, so this restates
     * a configuration bound at the execution boundary rather than inventing a new one.
     */
    public static final int MAX_SECRETS_PER_RUN = 50;

    /** Every value one run receives, together. */
    public static final int MAX_TOTAL_BYTES = 64 * 1024;

    private SecretLimits() {}
}
