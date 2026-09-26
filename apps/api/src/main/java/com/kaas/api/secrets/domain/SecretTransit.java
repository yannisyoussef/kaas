package com.kaas.api.secrets.domain;

/**
 * Envelope encryption for tenant secret values, through a key service the platform does not hold keys for.
 *
 * <p>Vault is used as a KMS and nothing more (ADR-034): it holds the key, performs the cryptography, and stores
 * nothing. The ciphertext it returns is persisted by KaaS in PostgreSQL. So this interface is two operations on
 * bytes, and every call names the tenant context the key is derived from.
 *
 * <p>Implementations throw {@link SecretProviderException} and nothing else for provider-side failures, and
 * never attach a cause: see that type for why.
 */
public interface SecretTransit {

    /**
     * Whether this deployment configured a provider at all.
     *
     * <p>Configuration, not health. Asking it never contacts the provider, which is what lets authorization
     * decide about a secret-free run without depending on anything a secret-bearing run needs.
     */
    boolean configured();

    /**
     * Encrypts one value under the tenant's derived key.
     *
     * <p>The caller owns {@code plaintext} and clears it; this method does not retain it.
     */
    EncryptedValue encrypt(TransitContext context, byte[] plaintext) throws SecretProviderException;

    /**
     * Decrypts one ciphertext under the tenant's derived key.
     *
     * @return a new array the caller owns and must clear when finished with it
     */
    byte[] decrypt(TransitContext context, String ciphertext) throws SecretProviderException;

    /** A ciphertext and the key version that produced it. */
    record EncryptedValue(String ciphertext, String keyName, int keyVersion) {
        public EncryptedValue {
            if (!TransitCiphertext.isWellFormed(ciphertext)) {
                // Refused rather than stored: the column's CHECK would refuse it too, but a value that is not a
                // Transit ciphertext may be a plaintext that took a wrong turn, and it should not travel as far
                // as an INSERT to be noticed.
                throw new IllegalArgumentException("Not a Transit ciphertext.");
            }
        }

        /** Never the ciphertext. It is sensitive platform material even though it is encrypted. */
        @Override
        public String toString() {
            return "EncryptedValue[keyName=" + keyName + ", keyVersion=" + keyVersion + "]";
        }
    }
}
