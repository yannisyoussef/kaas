package com.kaas.api.secrets.domain;

/**
 * A secret operation failed, and this says which way.
 *
 * <p>Deliberately carries NO cause and no message beyond the category. A cause chain is how a provider's
 * response body, a URL, or an I/O exception quoting a request reaches a log formatter that prints it: the
 * unexpected-exception handler logs the whole chain, and so would any future caller that forgot not to. There is
 * nothing to forget if there is nothing attached. Stack traces are also suppressed, because this type is thrown
 * across the one boundary where a trace could show frames holding plaintext in local variables to a debugger —
 * and because it is an ordinary outcome, not a programming error.
 */
public final class SecretProviderException extends Exception {
    private final SecretFailure failure;

    public SecretProviderException(SecretFailure failure) {
        super(failure.name(), null, false, false);
        this.failure = failure;
    }

    public SecretFailure failure() {
        return failure;
    }
}
