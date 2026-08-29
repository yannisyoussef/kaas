package com.kaas.api.execution.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * An immutable, platform-owned egress policy that an execution would run under.
 *
 * <p>Platform-owned rather than tenant-owned, and that is a deliberate limitation rather than an oversight. A
 * tenant selecting its own egress policy needs an approval path and enforcement that has been demonstrated;
 * neither exists. Until they do, there is exactly one revision, the platform created it, and no request can
 * name a different one — a worker cannot choose a weaker policy because it never supplies a policy at all.
 */
public record NetworkPolicyRevision(
        UUID policyRevisionId,
        NetworkPolicyType policyType,
        int policyVersion,
        String canonicalDigest,
        String createdBy,
        Instant createdAt) {

    /** The canonicalization this digest is taken over. Versioned, so a later form is a different digest. */
    private static final String FORMAT = "kaas.network-policy.v1";

    /**
     * The only policy revision that exists.
     *
     * <p>Its identity and digest are fixed constants rather than values generated at startup: a policy whose
     * identity varied by deployment could not be compared across an audit trail, and one the application
     * created lazily would be one the application could create differently.
     */
    public static final UUID DENY_ALL_ID = UUID.fromString("00000000-0000-4000-8000-00000000d001");

    public static final String PLATFORM_ACTOR = "kaas.platform";

    public NetworkPolicyRevision {
        if (policyRevisionId == null || policyType == null || createdAt == null) {
            throw new IllegalArgumentException("A policy revision names its identity, type, and creation.");
        }
        if (policyVersion < 1) {
            throw new IllegalArgumentException("A policy revision is versioned from one.");
        }
        if (createdBy == null || !createdBy.startsWith("kaas.")) {
            throw new IllegalArgumentException("Network policy revisions are platform-authored.");
        }
    }

    /**
     * The digest of this revision's semantic content.
     *
     * <p>Covers the type and the version and nothing else — not the identity, which is arbitrary, and not the
     * creation instant, which is provenance rather than meaning. Two revisions with the same digest describe
     * the same policy, which is what a command binding this digest is asserting.
     *
     * <p>Every component is length-prefixed before hashing, so no combination of field values can produce the
     * same byte sequence as a different combination. Concatenation without that is how {@code ("ab", "c")} and
     * {@code ("a", "bc")} come to share a digest.
     */
    public static String digestOf(NetworkPolicyType policyType, int policyVersion) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, FORMAT);
            update(digest, "POLICY_TYPE");
            update(digest, policyType.name());
            update(digest, "POLICY_VERSION");
            update(digest, Integer.toString(policyVersion));
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /** Whether the digest recorded for this revision still matches its content. */
    public boolean digestMatchesContent() {
        return digestOf(policyType, policyVersion).equals(canonicalDigest);
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
