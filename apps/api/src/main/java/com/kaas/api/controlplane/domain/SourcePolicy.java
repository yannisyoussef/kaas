package com.kaas.api.controlplane.domain;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class SourcePolicy {
    public static final int MAX_SOURCE_BYTES = 512 * 1024;

    private SourcePolicy() {}

    public static String validateAndDigest(String source) {
        if (source == null || source.isEmpty()) {
            throw new IllegalArgumentException("source is required");
        }
        if (source.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("source must not contain NUL characters");
        }
        source.codePoints()
                .filter(codePoint -> codePoint < 0x20 && codePoint != '\t' && codePoint != '\n' && codePoint != '\r')
                .findFirst()
                .ifPresent(ignored -> {
                    throw new IllegalArgumentException("source contains a forbidden control character");
                });
        byte[] bytes = encodeUtf8(source);
        if (bytes.length > MAX_SOURCE_BYTES) {
            throw new SourceTooLargeException();
        }
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static byte[] encodeUtf8(String source) {
        try {
            ByteBuffer encoded = StandardCharsets.UTF_8
                    .newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(source));
            byte[] bytes = new byte[encoded.remaining()];
            encoded.get(bytes);
            return bytes;
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("source is not valid Unicode", exception);
        }
    }

    public static final class SourceTooLargeException extends RuntimeException {
        public SourceTooLargeException() {
            super("source exceeds the UTF-8 byte limit");
        }
    }
}
