package com.kaas.api.secrets.domain;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * The Transit key-derivation context for one tenant: {@code org:<organizationUuid>/project:<projectUuid>}.
 *
 * <h2>Why a derived key, and why this string</h2>
 *
 * <p>The Transit key is shared by every tenant, and it is created with {@code derived=true}, so Vault derives a
 * distinct encryption key from it for each context. A ciphertext produced under one project's context does not
 * decrypt under another's: the authentication tag fails. That makes tenant isolation a property of the
 * cryptography rather than only of the SQL that looks the ciphertext up — a row copied into another project, or
 * a query that joined on the wrong key, yields a decryption failure instead of another tenant's value.
 *
 * <h2>Why it is constructed only from ownership</h2>
 *
 * <p>The two UUIDs come from the columns that own the secret, never from a request. There is no constructor that
 * accepts a string, so no tenant input — a name, a path, a header — can become part of a context, and a caller
 * cannot ask Vault to derive a key for a tenant it named itself.
 *
 * <p>The encoding is fixed: lowercase canonical UUIDs, ASCII, no whitespace, exactly the shape above. It is
 * deterministic by construction ({@link UUID#toString()} is specified to produce the canonical lowercase form)
 * and pinned by a golden test, because a context that changed encoding between releases would make every
 * existing ciphertext undecryptable.
 */
public final class TransitContext {
    private final UUID organizationId;
    private final UUID projectId;

    private TransitContext(UUID organizationId, UUID projectId) {
        this.organizationId = Objects.requireNonNull(organizationId, "organization");
        this.projectId = Objects.requireNonNull(projectId, "project");
    }

    public static TransitContext of(UUID organizationId, UUID projectId) {
        return new TransitContext(organizationId, projectId);
    }

    /** The exact bytes Vault derives the key from. */
    public byte[] bytes() {
        return ("org:" + organizationId + "/project:" + projectId).getBytes(StandardCharsets.US_ASCII);
    }

    /** The encoding Vault's API requires for a context. */
    public String base64() {
        return Base64.getEncoder().encodeToString(bytes());
    }

    public UUID organizationId() {
        return organizationId;
    }

    public UUID projectId() {
        return projectId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TransitContext that
                && organizationId.equals(that.organizationId)
                && projectId.equals(that.projectId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(organizationId, projectId);
    }

    /** Ownership only. A context is not secret, but printing it is still never necessary. */
    @Override
    public String toString() {
        return "TransitContext[organization=" + organizationId + ", project=" + projectId + "]";
    }
}
