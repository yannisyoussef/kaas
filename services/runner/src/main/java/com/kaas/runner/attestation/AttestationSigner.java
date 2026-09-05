package com.kaas.runner.attestation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.EdECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Holds the producer's Ed25519 private key and signs canonical preimages with it.
 *
 * <h2>What this class refuses to do</h2>
 *
 * <p>It never generates a key. An automatically generated signer destroys pinning continuity: the control
 * plane's trust map would not contain the new key, so every attestation would be refused — and the obvious
 * repair for that is to make the control plane trust whatever turned up, which is the whole property gone.
 * A missing, malformed, unreadable, or wrong-curve key fails the producer instead.
 *
 * <p>It reads the key from a <strong>file</strong> rather than a system property or an environment variable.
 * A process argument is visible in {@code ps} to every user on the host; an environment variable is visible in
 * {@code /proc/<pid>/environ} and is inherited by children. A file has an owner and a mode.
 */
public final class AttestationSigner {

    private final PrivateKey privateKey;

    private final String keyId;

    private AttestationSigner(String keyId, PrivateKey privateKey) {
        this.keyId = keyId;
        this.privateKey = privateKey;
    }

    public String keyId() {
        return keyId;
    }

    /**
     * Loads a signing key from a PKCS#8 file, Base64 or DER.
     *
     * @throws AttestationProductionFailed for every failure mode, with a category and without the key material
     *     or the file's contents in the message
     */
    public static AttestationSigner fromFile(String keyId, Path privateKeyFile) {
        if (keyId == null || keyId.isBlank()) {
            throw new AttestationProductionFailed(
                    AttestationFailure.SIGNING_KEY_UNUSABLE, "A signing key is identified by a key id.");
        }
        byte[] pkcs8;
        try {
            byte[] raw = Files.readAllBytes(privateKeyFile);
            pkcs8 = decode(raw);
        } catch (Exception unreadable) {
            // The path is named because an operator needs it; the contents never are.
            throw new AttestationProductionFailed(
                    AttestationFailure.SIGNING_KEY_UNUSABLE,
                    "The signing key at " + privateKeyFile + " could not be read.");
        }
        try {
            PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            // TWO GUARDS AGAINST THE SAME THING, AND THEY ARE JOINTLY COVERED. Measured on this JDK: the
            // algorithm-specific KeyFactory refuses an Ed448 PKCS#8 outright, so this explicit check cannot
            // fire for that input and a mutation removing it kills no test. It is recorded as joint coverage
            // rather than claimed as independently proven.
            //
            // It is kept anyway, and the reason is not thoroughness. The factory's strictness is a property of
            // the JCA provider; this is a property of this code. Deleting it would make the whole guarantee
            // depend on a provider behaviour nothing here asserts, and using the generic "EdDSA" factory to
            // make this line observable would mean weakening the first guard to get a greener mutation
            // report — optimising for the measurement rather than for the boundary.
            //
            // Note that an algorithm-NAME check would not do: a JCA Ed25519 key reports getAlgorithm() as
            // "EdDSA", and so does an Ed448 one.
            if (!(key instanceof EdECPrivateKey edwards)
                    || !"Ed25519".equals(edwards.getParams().getName())) {
                throw new AttestationProductionFailed(
                        AttestationFailure.SIGNING_KEY_UNUSABLE, "The signing key is not Ed25519.");
            }
            return new AttestationSigner(keyId, key);
        } catch (AttestationProductionFailed wrongCurve) {
            throw wrongCurve;
        } catch (Exception malformed) {
            throw new AttestationProductionFailed(
                    AttestationFailure.SIGNING_KEY_UNUSABLE, "The signing key is not a usable Ed25519 key.");
        }
    }

    /**
     * Accepts DER or Base64, and nothing in between.
     *
     * <p>A PKCS#8 DER file begins with a SEQUENCE tag (0x30); anything else is treated as Base64 text. PEM
     * armour is stripped when present. This is deliberately narrow: the point is to read the key an operator
     * exported, not to accept every encoding anybody might produce.
     */
    private static byte[] decode(byte[] raw) {
        if (raw.length > 0 && raw[0] == 0x30) {
            return raw;
        }
        String text = new String(raw, java.nio.charset.StandardCharsets.UTF_8)
                .replaceAll("-----[A-Z ]+-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(text);
    }

    /** Signs the canonical preimage. Ed25519 is deterministic, so the same payload always signs identically. */
    public byte[] sign(byte[] canonicalBytes) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(privateKey);
            signature.update(canonicalBytes);
            return signature.sign();
        } catch (Exception failed) {
            throw new AttestationProductionFailed(
                    AttestationFailure.SIGNING_FAILED, "The attestation could not be signed.");
        }
    }

    /**
     * Redacted.
     *
     * <p>A record or a default {@code toString} on a key holder is how private material reaches a log line
     * about something else entirely. The key id is not secret; the key is.
     */
    @Override
    public String toString() {
        return "AttestationSigner[keyId=" + keyId + ", privateKey=<redacted>]";
    }
}
