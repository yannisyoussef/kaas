package com.kaas.api.execution.application;

import com.kaas.api.execution.domain.PinnedVerificationKeys;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.EdECPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The Ed25519 public keys this deployment will accept an attestation from, and nothing else.
 *
 * <h2>Pinned, and only pinned</h2>
 *
 * <p>The keys come from deployment configuration. They are never taken from the attestation, from a URL the
 * attestation names, from tenant configuration, from a worker request, or from a remote JWKS. Any of those
 * would let the document choose who is trusted to sign it, which is not a weaker version of this design — it
 * is the absence of one.
 *
 * <p>A key id <strong>selects</strong> one pinned key. It is not itself authority: an unknown id resolves to
 * nothing and the attestation is refused. The verifier never tries the other keys to see whether one happens to
 * work, because "some key we trust signed something" is a different and much weaker statement than "the key
 * this document names signed this document".
 *
 * <h2>Rotation without an outage</h2>
 *
 * <p>Several keys may be trusted at once, so an operator can add the next key, switch the producer over, and
 * remove the previous one, with no window in which no valid attestation exists. There is deliberately no
 * registration API: rotation is an operator action on configuration, not something a worker can do to itself.
 *
 * <h2>Unavailable is not fatal to the process</h2>
 *
 * <p>Absent or malformed key configuration makes the <em>execution security subsystem</em> unavailable — every
 * authorization fails closed — without preventing the application from starting. Read-only product endpoints
 * have nothing to do with sandbox attestation, and taking them down would turn a misconfigured signing key into
 * a total outage, which is the kind of consequence that gets a security control switched off.
 */
@Component
public class AttestationTrustStore implements PinnedVerificationKeys {

    private static final Logger LOGGER = LoggerFactory.getLogger(AttestationTrustStore.class);

    /** X.509 SubjectPublicKeyInfo for Ed25519 is exactly this long. A cheap, total shape check. */
    private static final int ED25519_SPKI_LENGTH = 44;

    private final Map<String, PublicKey> keys;

    private final String unavailableReason;

    /**
     * @param configured {@code keyId=base64Spki} entries, comma separated. Empty means no trust root, which
     *     means no execution. There is deliberately no default: a built-in key would be a key whose private
     *     half somebody else also has, and a default that trusted the repository's test key would make every
     *     vector in the tree a production signer.
     */
    public AttestationTrustStore(
            @Value("${kaas.execution.attestation-trusted-keys:}") String configured) {
        Map<String, PublicKey> parsed = new LinkedHashMap<>();
        String failure = null;
        if (configured == null || configured.isBlank()) {
            failure = "no attestation verification key is configured";
        } else {
            try {
                for (String entry : configured.split(",")) {
                    String trimmed = entry.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    int separator = trimmed.indexOf('=');
                    if (separator <= 0 || separator == trimmed.length() - 1) {
                        throw new IllegalArgumentException("keyId=base64");
                    }
                    String keyId = trimmed.substring(0, separator);
                    if (parsed.containsKey(keyId)) {
                        // Two keys under one id is a configuration whose meaning depends on parse order, and
                        // the failure mode is that an operator removes a compromised key and it keeps working.
                        throw new IllegalArgumentException("duplicate key id");
                    }
                    parsed.put(keyId, publicKeyOf(trimmed.substring(separator + 1)));
                }
                if (parsed.isEmpty()) {
                    throw new IllegalArgumentException("no entries");
                }
            } catch (RuntimeException malformed) {
                parsed.clear();
                // The message is not echoed. This is operator configuration and the exception may carry the
                // offending text, which would put whatever was in that property into a log line.
                failure = "the configured attestation verification keys could not be read";
            }
        }
        this.keys = Map.copyOf(parsed);
        this.unavailableReason = failure;
        if (failure != null) {
            LOGGER.atError()
                    .addKeyValue("event", "ATTESTATION_TRUST_ROOT_UNAVAILABLE")
                    .log("No usable attestation verification key; execution authorization is unavailable");
        } else {
            // Key IDS are not secret and an operator needs to see which are live during a rotation. Key
            // MATERIAL is never logged, even though a public key is not confidential: a log is not where
            // anybody should be reading cryptographic material from.
            LOGGER.atInfo()
                    .addKeyValue("event", "ATTESTATION_TRUST_ROOT_LOADED")
                    .addKeyValue("keyIds", String.join(",", new java.util.TreeSet<>(keys.keySet())))
                    .log("Attestation verification keys pinned");
        }
    }

    /**
     * Parses one X.509 SubjectPublicKeyInfo and proves it is Ed25519.
     *
     * <p>Three guards, and they are <strong>jointly covered</strong> rather than independently proven — stated
     * here because the alternative is a comment that overclaims. Standard Base64 only, so one key does not
     * have several spellings. A 44-byte length, which is Ed25519's SPKI length and which an Ed448 key (69
     * bytes) fails first. Then {@code KeyFactory("Ed25519")}, which refuses another curve, and finally the
     * curve name itself.
     *
     * <p>Only the first guard to fire can be observed, so a mutation removing any one of the later ones kills
     * nothing. They are kept because each rests on something different — an encoding rule, a fixed size, a
     * provider behaviour, and a property of the parsed key — and reordering them to make one observable would
     * simply move the problem to whichever ended up second.
     *
     * <p>An algorithm-name check would not be one of these: a JCA Ed25519 key reports its algorithm as
     * {@code EdDSA}, and so does an Ed448 one.
     */
    private static PublicKey publicKeyOf(String base64Spki) {
        byte[] spki = Base64.getDecoder().decode(base64Spki.trim());
        if (spki.length != ED25519_SPKI_LENGTH) {
            throw new IllegalArgumentException("not an Ed25519 SubjectPublicKeyInfo");
        }
        try {
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
            if (!(key instanceof EdECPublicKey edwards) || !"Ed25519".equals(edwards.getParams().getName())) {
                throw new IllegalArgumentException("not Ed25519");
            }
            return key;
        } catch (IllegalArgumentException wrongCurve) {
            throw wrongCurve;
        } catch (Exception malformed) {
            throw new IllegalArgumentException("unusable public key");
        }
    }

    /** The one key this id names, or empty. Never a search across the others. */
    @Override
    public Optional<PublicKey> keyFor(String keyId) {
        return keyId == null ? Optional.empty() : Optional.ofNullable(keys.get(keyId));
    }

    /** Whether any key is pinned at all. No keys means no execution, not "verify nothing". */
    @Override
    public boolean available() {
        return unavailableReason == null;
    }

    public Optional<String> unavailableReason() {
        return Optional.ofNullable(unavailableReason);
    }

    /** How many keys are pinned. For health reporting; never the keys themselves. */
    public int size() {
        return keys.size();
    }
}
