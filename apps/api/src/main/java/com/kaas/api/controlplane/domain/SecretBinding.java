package com.kaas.api.controlplane.domain;

import java.util.UUID;

public record SecretBinding(String key, UUID secretReferenceId) {}
