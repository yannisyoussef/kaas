package com.kaas.api.controlplane.domain;

import java.util.List;

public record RunSelection(List<String> tags) {
    public RunSelection {
        tags = List.copyOf(tags);
    }
}
