package com.kaas.api.secrets.domain;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * What a secret value is: an opaque, bounded, non-empty sequence of UTF-8 bytes, preserved exactly.
 *
 * <p>Nothing is trimmed, normalised, re-encoded or line-ending-converted. A PEM block with CRLF endings and a
 * trailing newline arrives at the engine as exactly those bytes, because the platform has no way to know which
 * of them the far end checks and every "harmless" normalisation is a value that no longer authenticates.
 *
 * <p>UTF-8 because the only consumer is a Karate variable, which is a Java {@code String}. Arbitrary binary is
 * deliberately NOT supported rather than accidentally supported: an invalid sequence decoded leniently becomes
 * U+FFFD, which is a different value, and the platform would then deliver something other than what was stored
 * while reporting success. So invalid UTF-8 is refused at the door instead.
 */
public final class SecretValuePolicy {

    private SecretValuePolicy() {}

    /**
     * Refuses a value that is empty, too large, or not strictly valid UTF-8.
     *
     * <p>Validates without constructing a {@code String}: the decoder writes into a buffer this method clears
     * before returning, so validation does not leave an immutable copy of the plaintext on the heap.
     */
    public static void requireValid(byte[] value) throws SecretProviderException {
        if (value == null || value.length == 0) {
            throw new SecretProviderException(SecretFailure.SECRET_VALUE_INVALID);
        }
        if (value.length > SecretLimits.MAX_VALUE_BYTES) {
            throw new SecretProviderException(SecretFailure.SECRET_VALUE_TOO_LARGE);
        }
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        java.nio.CharBuffer scratch = java.nio.CharBuffer.allocate(value.length);
        try {
            var result = decoder.decode(ByteBuffer.wrap(value), scratch, true);
            if (result.isError()) {
                throw new SecretProviderException(SecretFailure.SECRET_VALUE_INVALID);
            }
            result = decoder.flush(scratch);
            if (result.isError()) {
                throw new SecretProviderException(SecretFailure.SECRET_VALUE_INVALID);
            }
        } finally {
            scratch.clear();
            while (scratch.hasRemaining()) {
                scratch.put('\0');
            }
        }
    }

    /** Whether the bytes are valid UTF-8, for callers that report rather than throw. */
    public static boolean isValid(byte[] value) {
        try {
            requireValid(value);
            return true;
        } catch (SecretProviderException invalid) {
            return false;
        }
    }
}
