package com.kaas.api.outbox.infrastructure;

import com.kaas.api.controlplane.domain.ExecutionDispatch;
import com.kaas.api.controlplane.domain.ExecutionDispatchPolicy;
import com.kaas.api.outbox.application.OutboxMessageVerifier;
import com.kaas.api.outbox.domain.FailureCode;
import com.kaas.api.outbox.domain.MessageType;
import com.kaas.api.outbox.domain.OutboxMessage;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Re-derives the semantic digest from the persisted payload before anything is published. Same message identity
 * with a different digest means the durable record was tampered with or corrupted; that is a permanent security
 * failure and must never be retried into the broker.
 */
@Component
class DispatchMessageVerifier implements OutboxMessageVerifier {
    private static final String SUPPORTED_SCHEMA_VERSION = "1.0";

    /** Mirrors the scheduler's private mapper so that HTTP-shaping Jackson properties cannot alter verification. */
    private static final ObjectMapper DISPATCH_MAPPER = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            // The relay publishes the stored bytes verbatim while the digest covers only the fields this record
            // declares. Without this, an extra key in the payload would ride to the broker unverified under a
            // digest header that claims to cover it. Jackson defaults this to false.
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    @Override
    public Optional<String> verify(OutboxMessage message) {
        if (!MessageType.isPublishable(message.messageType())) {
            return Optional.of(FailureCode.UNSUPPORTED_MESSAGE_TYPE);
        }
        if (!SUPPORTED_SCHEMA_VERSION.equals(message.schemaVersion())) {
            return Optional.of(FailureCode.UNSUPPORTED_SCHEMA_VERSION);
        }
        ExecutionDispatch dispatch;
        try {
            dispatch = DISPATCH_MAPPER.readValue(message.payload(), ExecutionDispatch.class);
        } catch (RuntimeException malformed) {
            return Optional.of(FailureCode.MALFORMED_PAYLOAD);
        }
        // The digest binds every semantic field, so this also proves identity, tenancy, and snapshot binding.
        if (!message.payloadDigest().equals(dispatch.payloadDigest())
                || !message.payloadDigest().equals(ExecutionDispatchPolicy.digest(dispatch))
                || !message.messageId().equals(dispatch.messageId())
                || !message.organizationId().equals(dispatch.organizationId())
                || !message.projectId().equals(dispatch.projectId())
                || !message.runId().equals(dispatch.runId())
                || (message.dispatchId() != null && !message.dispatchId().equals(dispatch.dispatchId()))) {
            return Optional.of(FailureCode.INTEGRITY_DIGEST_MISMATCH);
        }
        return Optional.empty();
    }
}
