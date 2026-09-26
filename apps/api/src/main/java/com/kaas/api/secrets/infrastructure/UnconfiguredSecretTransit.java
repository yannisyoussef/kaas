package com.kaas.api.secrets.infrastructure;

import com.kaas.api.secrets.domain.SecretFailure;
import com.kaas.api.secrets.domain.SecretProviderException;
import com.kaas.api.secrets.domain.SecretTransit;
import com.kaas.api.secrets.domain.TransitContext;

/**
 * The provider a deployment has when it configured none.
 *
 * <p>Every operation fails closed with {@link SecretFailure#SECRET_PROVIDER_UNAVAILABLE}. A secret cannot be
 * written, and a secret-bearing run is refused at authorization; a run with no secrets is untouched, because
 * nothing on its path ever asks this for anything.
 */
public final class UnconfiguredSecretTransit implements SecretTransit {

    @Override
    public boolean configured() {
        return false;
    }

    @Override
    public EncryptedValue encrypt(TransitContext context, byte[] plaintext) throws SecretProviderException {
        throw new SecretProviderException(SecretFailure.SECRET_PROVIDER_UNAVAILABLE);
    }

    @Override
    public byte[] decrypt(TransitContext context, String ciphertext) throws SecretProviderException {
        throw new SecretProviderException(SecretFailure.SECRET_PROVIDER_UNAVAILABLE);
    }
}
