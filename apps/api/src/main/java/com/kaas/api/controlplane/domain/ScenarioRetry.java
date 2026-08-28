package com.kaas.api.controlplane.domain;

public record ScenarioRetry(int maxAttempts, int delayMilliseconds) {}
