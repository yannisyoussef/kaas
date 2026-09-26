package com.kaas.api.controlplane.domain;

/**
 * A bound secret has no version a new run could pin: none was ever written, or every one was revoked.
 *
 * <p>Refused at run creation, because a snapshot is immutable and a snapshot without a version for one of its
 * bindings would be a run that can never execute. Carries no key, reference or value.
 */
public final class SecretVersionUnavailableException extends RuntimeException {
    public SecretVersionUnavailableException() {
        super("A bound secret has no usable version.");
    }
}
