package com.kaas.runner.attestation;

/** Production failed, with the category that says which part. Never carries key material or file contents. */
public class AttestationProductionFailed extends RuntimeException {

    private final AttestationFailure failure;

    public AttestationProductionFailed(AttestationFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public AttestationFailure failure() {
        return failure;
    }
}
