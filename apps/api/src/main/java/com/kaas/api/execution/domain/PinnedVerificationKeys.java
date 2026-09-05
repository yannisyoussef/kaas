package com.kaas.api.execution.domain;

import java.security.PublicKey;
import java.util.Optional;

/**
 * The deployment's pinned attestation verification keys, as the verifier sees them.
 *
 * <p>An interface so the verifier stays pure domain logic with no configuration or framework behind it, and so
 * a test can pin exactly the keys it means to. There is deliberately no method that enumerates the keys or
 * that resolves a key by anything other than its id: a verifier able to iterate would eventually iterate, and
 * "some key we trust signed something" is a far weaker statement than "the key this document names signed
 * this document".
 */
public interface PinnedVerificationKeys {

    /** The one key this id names, or empty. Never a search. */
    Optional<PublicKey> keyFor(String keyId);

    /** Whether any key is pinned. None means no execution, never "verify nothing". */
    boolean available();
}
