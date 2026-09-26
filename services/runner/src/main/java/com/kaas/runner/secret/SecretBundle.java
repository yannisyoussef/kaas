package com.kaas.runner.secret;

import com.kaas.runner.sandbox.EngineInput;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses a secret bundle received from the control plane, and refuses anything but the exact authorized set.
 *
 * <p>The format is in {@code packages/api-contracts/secret-bundle.md}. This is an independent implementation of
 * it, written against the contract rather than against the control plane's encoder, and it trusts nothing about
 * the bytes: every length is bounded before anything is allocated for it, every key is checked, and the set of
 * keys must equal the command's authorized bindings exactly — none extra, none missing, none twice. A bundle
 * that carries one secret more than the command authorized is not "the authorized set plus noise"; it is a
 * bundle for some other run, and nothing of it is used.
 *
 * <p>Values are validated as strict UTF-8 without being decoded into a {@code String}, because the engine
 * receives them as strings and a lenient decode there would silently deliver a different value.
 *
 * <p>Failures are categories. No key, length or value appears in a refusal.
 */
public final class SecretBundle {
    private static final byte[] MAGIC = "KAASSEC1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TRAILER = "KAASEND1".getBytes(StandardCharsets.US_ASCII);
    private static final Pattern KEY = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.-]{0,127}$");

    /** The largest a well-formed bundle can be: the value bound plus framing for every entry. */
    public static final int MAX_FRAME_BYTES =
            EngineInput.MAX_TOTAL_BYTES + EngineInput.MAX_SECRETS * (2 + 128 + 4) + 32;

    private SecretBundle() {}

    /** Why a bundle was refused. */
    public enum Rejection { MALFORMED, UNEXPECTED_SET, TOO_LARGE, NOT_UTF8 }

    public static final class Rejected extends Exception {
        private final Rejection reason;

        Rejected(Rejection reason) {
            super(reason.name(), null, false, false);
            this.reason = reason;
        }

        public Rejection reason() {
            return reason;
        }
    }

    /**
     * Parses the frame against the command's authorized keys. Clears the frame on every path.
     *
     * @return the secrets, each with a value array the caller now owns and must clear
     */
    public static List<EngineInput.Secret> parse(byte[] frame, Set<String> authorizedKeys) throws Rejected {
        List<EngineInput.Secret> secrets = new ArrayList<>();
        try {
            if (frame.length > MAX_FRAME_BYTES) {
                throw new Rejected(Rejection.TOO_LARGE);
            }
            ByteBuffer in = ByteBuffer.wrap(frame);
            require(in, MAGIC);
            int count = in.getInt();
            if (count < 1 || count > EngineInput.MAX_SECRETS) {
                throw new Rejected(count < 1 ? Rejection.MALFORMED : Rejection.TOO_LARGE);
            }
            Set<String> keys = new HashSet<>();
            String previous = null;
            long total = 0;
            for (int index = 0; index < count; index++) {
                int keyLength = Short.toUnsignedInt(in.getShort());
                if (keyLength < 1 || keyLength > 128 || keyLength > in.remaining()) {
                    throw new Rejected(Rejection.MALFORMED);
                }
                byte[] keyBytes = new byte[keyLength];
                in.get(keyBytes);
                String key = new String(keyBytes, StandardCharsets.US_ASCII);
                if (!KEY.matcher(key).matches() || (previous != null && key.compareTo(previous) <= 0)) {
                    throw new Rejected(Rejection.MALFORMED);
                }
                previous = key;
                keys.add(key);
                int valueLength = in.getInt();
                if (valueLength < 1 || valueLength > EngineInput.MAX_VALUE_BYTES) {
                    throw new Rejected(valueLength < 1 ? Rejection.MALFORMED : Rejection.TOO_LARGE);
                }
                if (valueLength > in.remaining()) {
                    throw new Rejected(Rejection.MALFORMED);
                }
                total += valueLength;
                if (total > EngineInput.MAX_TOTAL_BYTES) {
                    throw new Rejected(Rejection.TOO_LARGE);
                }
                byte[] value = new byte[valueLength];
                in.get(value);
                secrets.add(new EngineInput.Secret(key, value));
                if (!strictUtf8(value)) {
                    throw new Rejected(Rejection.NOT_UTF8);
                }
            }
            require(in, TRAILER);
            if (in.hasRemaining()) {
                throw new Rejected(Rejection.MALFORMED);
            }
            if (!keys.equals(authorizedKeys)) {
                // Extra, missing, or different: all the same refusal. Which key was wrong is not said.
                throw new Rejected(Rejection.UNEXPECTED_SET);
            }
            return secrets;
        } catch (java.nio.BufferUnderflowException truncated) {
            clear(secrets);
            throw new Rejected(Rejection.MALFORMED);
        } catch (Rejected refused) {
            clear(secrets);
            throw refused;
        } finally {
            Arrays.fill(frame, (byte) 0);
        }
    }

    public static void clear(List<EngineInput.Secret> secrets) {
        secrets.forEach(secret -> Arrays.fill(secret.value(), (byte) 0));
    }

    private static void require(ByteBuffer in, byte[] expected) throws Rejected {
        if (in.remaining() < expected.length) {
            throw new Rejected(Rejection.MALFORMED);
        }
        byte[] actual = new byte[expected.length];
        in.get(actual);
        if (!Arrays.equals(actual, expected)) {
            throw new Rejected(Rejection.MALFORMED);
        }
    }

    private static boolean strictUtf8(byte[] value) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        CharBuffer scratch = CharBuffer.allocate(value.length);
        try {
            if (decoder.decode(ByteBuffer.wrap(value), scratch, true).isError()) {
                return false;
            }
            return !decoder.flush(scratch).isError();
        } finally {
            scratch.clear();
            while (scratch.hasRemaining()) {
                scratch.put('\0');
            }
        }
    }
}
