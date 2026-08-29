package com.kaas.api.execution.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generation and verification of capability bearer tokens.
 *
 * <p>The plaintext exists exactly once, in the response that issues it. What is stored is a SHA-256 of it, so a
 * database backup, a replica, or an accidental dump of the capability table grants nobody anything.
 *
 * <p>A plain SHA-256 rather than a password hash, deliberately. Password hashing is slow on purpose because a
 * human-chosen password has little entropy and must be made expensive to guess. These tokens are 256 bits of
 * {@link SecureRandom}; there is nothing to guess, and an expensive hash would only make every legitimate
 * redemption slower while adding no resistance an attacker would notice.
 */
public final class CapabilityToken {
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 32 bytes. Well beyond the point where offline search is the attacker's problem rather than ours. */
    private static final int ENTROPY_BYTES = 32;

    private CapabilityToken() {}

    /** A fresh token of the given type. The caller must return it to exactly one recipient and then forget it. */
    public static String issue(CapabilityType type) {
        byte[] entropy = new byte[ENTROPY_BYTES];
        RANDOM.nextBytes(entropy);
        return type.tokenPrefix() + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }

    /** The stored form of a token. Bare lowercase hex, matching the column's own constraint. */
    public static String hash(String token) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * Whether a presented token is well-formed for the type it is being presented as.
     *
     * <p>Checked before the hash is looked up, so a token of the wrong type is refused on its shape rather than
     * being searched for in a table where it might, in principle, collide with something.
     */
    public static boolean hasShapeOf(String token, CapabilityType type) {
        if (token == null || !token.startsWith(type.tokenPrefix())) {
            return false;
        }
        String body = token.substring(type.tokenPrefix().length());
        return body.length() == 43 && body.chars().allMatch(CapabilityToken::isUrlBase64);
    }

    private static boolean isUrlBase64(int character) {
        return (character >= 'A' && character <= 'Z')
                || (character >= 'a' && character <= 'z')
                || (character >= '0' && character <= '9')
                || character == '-'
                || character == '_';
    }

    /**
     * Constant-time comparison of two stored hashes.
     *
     * <p>The lookup itself is by unique index on the hash, so the practical exposure is small, but a comparison
     * that returns early on the first differing byte is a habit worth not having in a credential path.
     */
    public static boolean hashesMatch(String left, String right) {
        return left != null
                && right != null
                && MessageDigest.isEqual(
                        left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }
}
