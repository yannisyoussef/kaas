package com.kaas.api.controlplane.domain;

import java.util.UUID;

/**
 * One secret binding as a run pinned it: the key, the reference, and the exact version.
 *
 * <p>Distinct from {@link SecretBinding}, which is what an environment holds and deliberately names no version
 * — an environment means "this key uses this secret", and a run means "this key uses version N of this
 * secret, for as long as this run exists". Rotation creates version N+1 and leaves every already-pinned run on
 * N; revocation of N makes those runs fail rather than advance.
 *
 * <p>{@code version} is null only for a snapshot written by a release before KAAS-22, which pinned references
 * without versions. Such a run is refused at authorization rather than executed against a guessed version.
 */
public record PinnedSecretBinding(String key, UUID secretReferenceId, Integer version) {}
