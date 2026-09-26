package com.kaas.api.secrets.domain;

import java.util.regex.Pattern;

/** The shape of a Transit ciphertext, {@code vault:v<keyVersion>:<base64>}, matching the database CHECK. */
public final class TransitCiphertext {
    private static final Pattern SHAPE = Pattern.compile("^vault:v([1-9][0-9]{0,6}):[A-Za-z0-9+/]+={0,2}$");

    /** The column bound. The largest permitted value encrypts to well under it. */
    public static final int MAX_LENGTH = 16384;

    private TransitCiphertext() {}

    public static boolean isWellFormed(String ciphertext) {
        return ciphertext != null && ciphertext.length() <= MAX_LENGTH && SHAPE.matcher(ciphertext).matches();
    }

    /** The key version a well-formed ciphertext was produced with. */
    public static int keyVersionOf(String ciphertext) {
        var matcher = SHAPE.matcher(ciphertext);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not a Transit ciphertext.");
        }
        return Integer.parseInt(matcher.group(1));
    }
}
