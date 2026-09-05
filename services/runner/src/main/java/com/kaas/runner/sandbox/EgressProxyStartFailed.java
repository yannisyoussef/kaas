package com.kaas.runner.sandbox;

/** The egress path could not be established, with the category that says which part of it failed. */
public class EgressProxyStartFailed extends RuntimeException {

    private final EgressFailure failure;

    public EgressProxyStartFailed(EgressFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public EgressProxyStartFailed(EgressFailure failure, String message, Throwable cause) {
        // The cause is kept for local diagnosis but never reaches a caller-visible surface: a daemon error
        // carries socket paths, host directories, and image references.
        super(message, cause);
        this.failure = failure;
    }

    public EgressFailure failure() {
        return failure;
    }
}
