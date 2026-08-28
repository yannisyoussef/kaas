package com.kaas.api.controlplane.domain;

public record ConfigurationVariable(String key, ConfigurationValueType type, Object value) {}
