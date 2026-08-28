package com.kaas.api.controlplane.domain;

import java.util.regex.Pattern;

public record EngineDescriptor(String engine, String version) {
    private static final Pattern VERSION =
            Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?");

    public EngineDescriptor {
        if (!"KARATE".equals(engine) || version == null || !VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("The configured engine descriptor is invalid.");
        }
    }
}
