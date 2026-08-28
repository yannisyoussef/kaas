package com.kaas.api.controlplane.domain;

import java.util.UUID;

/** The minimum trusted identity the scheduler needs to attempt one compare-and-set scheduling transition. */
public record SchedulableRun(UUID organizationId, UUID projectId, UUID runId, long runVersion) {}
