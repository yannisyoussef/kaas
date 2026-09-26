package com.kaas.api.secrets.domain;

/**
 * Why a secret could not be written, resolved or delivered — as a category and nothing else.
 *
 * <p>These are the only words that leave the secret path. A provider's own error body can quote the request it
 * refused, and a request to decrypt carries ciphertext and context; a Java exception message built from either
 * would put material the platform promised not to log into whatever renders it. So every failure on this path
 * is collapsed to one of these values at the point it happens, and the value is what a log line, a metric tag,
 * a response body or an infrastructure-failure detail may carry.
 */
public enum SecretFailure {
    /** The reference does not exist in this project. */
    SECRET_NOT_FOUND,
    /** The pinned version does not exist, or has no ciphertext left to decrypt. */
    SECRET_VERSION_NOT_FOUND,
    /** The pinned version was revoked. There is no fallback to another version. */
    SECRET_VERSION_REVOKED,
    /** The provider refused the operation for this context: a ciphertext presented under the wrong tenant. */
    SECRET_ACCESS_DENIED,
    /** The provider could not be reached, answered too slowly, or is sealed. */
    SECRET_PROVIDER_UNAVAILABLE,
    /** The platform could not authenticate to the provider. */
    SECRET_PROVIDER_AUTH_FAILED,
    /** The value is empty, not UTF-8, or the provider returned something that is not a value. */
    SECRET_VALUE_INVALID,
    /** A value, or a run's set of values, exceeds the platform's bound. */
    SECRET_VALUE_TOO_LARGE,
    /** The capability presented does not authorize this delivery. */
    SECRET_CAPABILITY_DENIED,
    /** The values were resolved and could not be handed over. */
    SECRET_DELIVERY_FAILED
}
