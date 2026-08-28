package com.kaas.api.outbox.domain;

public record PublishOutcome(PublishStatus status, String failureCode) {
    public PublishOutcome {
        if (status == null || (status == PublishStatus.CONFIRMED) != (failureCode == null)) {
            throw new IllegalArgumentException("A failed publication must carry exactly one bounded failure code.");
        }
    }

    public static PublishOutcome confirmed() {
        return new PublishOutcome(PublishStatus.CONFIRMED, null);
    }

    public static PublishOutcome transientFailure(String failureCode) {
        return new PublishOutcome(PublishStatus.TRANSIENT_FAILURE, failureCode);
    }

    public static PublishOutcome permanentFailure(String failureCode) {
        return new PublishOutcome(PublishStatus.PERMANENT_FAILURE, failureCode);
    }
}
