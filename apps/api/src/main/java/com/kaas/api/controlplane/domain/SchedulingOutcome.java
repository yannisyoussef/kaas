package com.kaas.api.controlplane.domain;

/**
 * What the database actually recorded for one scheduling attempt. Both values come from the statement that wrote
 * them, so the log and the metric can never disagree with the row.
 */
public record SchedulingOutcome(int failureCount, boolean quarantined) {}
