package com.kaas.api.controlplane.domain;

public enum ScheduleDisposition {
    SCHEDULED,
    ALREADY_SCHEDULED,
    STALE_VERSION,
    INVALID_STATE
}
