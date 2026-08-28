package com.kaas.api.controlplane.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyRepository {
    void lock(Scope scope);

    Optional<Record> find(Scope scope);

    void insert(Scope scope, String requestSha256, UUID resourceId, int status, String location, Instant now);

    record Scope(UUID organizationId, String principalId, String operation, String scopePath, String key) {}

    record Record(String requestSha256, UUID resourceId, int status, String location) {}
}
