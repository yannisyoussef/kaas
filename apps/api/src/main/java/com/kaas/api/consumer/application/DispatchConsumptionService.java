package com.kaas.api.consumer.application;

import com.kaas.api.consumer.domain.InboxDisposition;
import com.kaas.api.consumer.domain.InboxRecord;
import com.kaas.api.controlplane.application.RunClaimService;
import com.kaas.api.controlplane.domain.ClaimDisposition;
import com.kaas.api.controlplane.domain.ExecutionDispatch;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes one delivered dispatch and decides, durably, what became of it.
 *
 * <p>The single rule this class exists to keep is that the database commits before the broker is acknowledged.
 * Acknowledging first would mean a process death between the two loses the work entirely, with the broker
 * believing it was handled. Committing first means a death between them causes a redelivery instead — which the
 * inbox turns into a decided no-op.
 *
 * <p>A message is never authority. Everything it says is either re-derived from its own bytes or checked against
 * PostgreSQL; the only thing it contributes is an identity to look things up by.
 */
@Service
public class DispatchConsumptionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DispatchConsumptionService.class);

    private final DispatchInboxRepository inbox;
    private final DispatchContractValidator validator;
    private final RunClaimService claims;
    private final Clock clock;
    private final MeterRegistry meters;
    private final String consumerName;
    private final String workerInstanceId;

    public DispatchConsumptionService(
            DispatchInboxRepository inbox,
            DispatchContractValidator validator,
            RunClaimService claims,
            Clock clock,
            MeterRegistry meters,
            @Value("${kaas.consumer.name}") String consumerName,
            @Value("${kaas.consumer.worker-instance-id}") String workerInstanceId) {
        if (consumerName == null || consumerName.isBlank() || consumerName.length() > 64) {
            throw new IllegalArgumentException("The consumer needs a bounded configured name.");
        }
        if (workerInstanceId == null || workerInstanceId.isBlank() || workerInstanceId.length() > 255) {
            throw new IllegalArgumentException("The consumer needs a bounded configured worker instance id.");
        }
        this.inbox = inbox;
        this.validator = validator;
        this.claims = claims;
        this.clock = clock;
        this.meters = meters;
        this.consumerName = consumerName;
        this.workerInstanceId = workerInstanceId;
    }

    /**
     * Decides one delivery and commits that decision.
     *
     * <p>Any exception escaping this method means nothing was decided and nothing was committed, which is exactly
     * when the caller should let the broker redeliver. A returned disposition means the opposite: a durable
     * decision exists, so the delivery must be acknowledged rather than requeued no matter what it says.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public InboxDisposition consume(DispatchMessage message) {
        Instant receivedAt = clock.instant();
        DispatchValidation validation = validator.validate(message);
        ExecutionDispatch dispatch =
                validation instanceof DispatchValidation.Accepted accepted ? accepted.dispatch() : null;

        // A message that does not parse has no trustworthy identity of its own, so it is deduplicated under the
        // identity the transport claimed. With none, there is nothing to key a decision on and nothing to
        // deduplicate against — inventing an identity for it would be writing a fact nothing supports.
        UUID messageId = dispatch != null ? dispatch.messageId() : message.transportMessageId();
        if (messageId == null) {
            String reason = ((DispatchValidation.Rejected) validation).reason();
            count("kaas.dispatch.rejected", reason);
            logDecision(InboxDisposition.REJECTED, reason, null, null);
            return InboxDisposition.REJECTED;
        }
        // Tagged by domain, because the two are not comparable. A parsed message contributes its *semantic*
        // digest; an unparsed one can only contribute a hash of its raw bytes. Storing both undistinguished lets
        // garbage published first under a chosen identity permanently poison the genuine message that follows —
        // its semantic digest can never equal a raw-body hash, so it is refused as an integrity conflict.
        String digest = dispatch != null
                ? "semantic:" + hex(dispatch.payloadDigest())
                : "raw:" + digestOf(message);

        // Serialised per message, so two copies arriving together cannot both decide it. Taken before the read
        // so the check and the decision are one critical section rather than two.
        //
        // Every path below this point goes through the same lookup, valid or not. An earlier version recorded
        // refusals without checking first, which meant a second delivery of a refused message — or a tampered
        // one reusing a known identity — hit the unique key and threw. The listener reads a throw as "we
        // failed", so it requeued: a message that could never succeed, redelivered forever.
        inbox.lockMessage(consumerName, messageId);
        Optional<InboxRecord> existing = inbox.find(consumerName, messageId);
        if (existing.isPresent()) {
            InboxRecord decided = existing.orElseThrow();
            inbox.countRedelivery(consumerName, messageId);
            if (!decided.matches(digest)) {
                // Same name, different bytes. That is not a duplicate — it is two different messages claiming to
                // be one, and the recorded decision is not overwritten to accommodate the newcomer.
                count("kaas.dispatch.conflict", "DIGEST_CONFLICT");
                LOGGER.atError()
                        .addKeyValue("event", "DISPATCH_IDENTITY_CONFLICT")
                        .addKeyValue("messageId", messageId)
                        .addKeyValue("consumer", consumerName)
                        .log("A delivered message reused a known identity with different content");
                return InboxDisposition.CONFLICT;
            }
            count("kaas.dispatch.duplicate", decided.disposition().name());
            return decided.disposition();
        }

        if (dispatch == null) {
            String reason = ((DispatchValidation.Rejected) validation).reason();
            return decide(messageId, digest, null, InboxDisposition.REJECTED, reason, receivedAt);
        }

        var outcome = claims.claim(dispatch, workerInstanceId);
        InboxDisposition disposition = dispositionOf(outcome.disposition());
        return decide(messageId, digest, dispatch, disposition, outcome.reason(), receivedAt);
    }

    private InboxDisposition decide(
            UUID messageId,
            String digest,
            ExecutionDispatch dispatch,
            InboxDisposition disposition,
            String reason,
            Instant receivedAt) {
        boolean corroborated = dispatch != null
                && (disposition == InboxDisposition.CLAIMED || disposition == InboxDisposition.STALE);
        inbox.record(new InboxRecord(
                UUID.randomUUID(),
                consumerName,
                messageId,
                digest,
                // Tenant identity only when it was corroborated. The NOT_CLAIMABLE reasons are precisely the
                // cases where the control plane just determined the body corresponds to nothing it published, so
                // writing its chosen organization into an immutable, unprunable evidence table — indexed by
                // organization — would let anyone who can publish attribute records to a tenant at will.
                corroborated ? dispatch.organizationId() : null,
                corroborated ? dispatch.projectId() : null,
                corroborated ? dispatch.runId() : null,
                disposition,
                reason,
                receivedAt,
                receivedAt,
                receivedAt,
                1));
        count(metricFor(disposition), reason);
        logDecision(disposition, reason, messageId, dispatch);
        return disposition;
    }

    private static InboxDisposition dispositionOf(ClaimDisposition claim) {
        return switch (claim) {
            case CLAIMED -> InboxDisposition.CLAIMED;
            // Already claimed is a duplicate that raced rather than repeated, and a stale run is expected: the
            // broker had no way to know what the control plane decided after it handed the message over.
            case ALREADY_CLAIMED, STALE -> InboxDisposition.STALE;
            // The message does not describe the run the control plane holds. Redelivering cannot fix that.
            case NOT_CLAIMABLE -> InboxDisposition.REJECTED;
        };
    }

    private static String metricFor(InboxDisposition disposition) {
        return switch (disposition) {
            case CLAIMED -> "kaas.dispatch.claimed";
            case STALE -> "kaas.dispatch.stale";
            case REJECTED -> "kaas.dispatch.rejected";
            case CONFLICT -> "kaas.dispatch.conflict";
        };
    }

    /**
     * The digest of bytes that failed validation, so a conflicting redelivery of them is still detectable. It is
     * a plain hash of the raw body rather than the semantic digest, because the semantic digest is only defined
     * for a document that parsed — and this one did not.
     */
    private static String digestOf(DispatchMessage message) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(message.body()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform.", impossible);
        }
    }

    private static String hex(String digest) {
        return digest.startsWith("sha256:") ? digest.substring("sha256:".length()) : digest;
    }

    /** Reason only. Run, attempt, message, worker, and tenant identity would all be unbounded label cardinality. */
    private void count(String name, String reason) {
        Counter.builder(name).tag("reason", reason).register(meters).increment();
    }

    private void logDecision(
            InboxDisposition disposition, String reason, UUID messageId, ExecutionDispatch dispatch) {
        // Safe identifiers only, and never the body: a rejected message is untrusted input, and putting it in a
        // log is how it reaches whoever reads the logs.
        LOGGER.atInfo()
                .addKeyValue("event", "DISPATCH_CONSUMED")
                .addKeyValue("consumer", consumerName)
                .addKeyValue("disposition", disposition.name())
                .addKeyValue("reason", reason)
                .addKeyValue("messageId", messageId)
                .addKeyValue("organizationId", dispatch == null ? null : dispatch.organizationId())
                .addKeyValue("runId", dispatch == null ? null : dispatch.runId())
                .log("Decided a delivered execution dispatch");
    }
}
