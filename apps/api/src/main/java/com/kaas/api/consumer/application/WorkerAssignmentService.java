package com.kaas.api.consumer.application;

import com.kaas.api.controlplane.application.RunClaimService;
import com.kaas.api.controlplane.domain.ClaimDisposition;
import com.kaas.api.controlplane.domain.ExecutionDispatch;
import com.kaas.api.controlplane.domain.WorkerAssignment;
import com.kaas.api.shared.WorkerIdentity;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hands a delivered run to the worker that asks for it, and to nobody else.
 *
 * <h2>Why workers claim, rather than being assigned</h2>
 *
 * <p>The broker's consumer used to claim every delivered run for one configured worker id. That id named nobody:
 * with more than one runner the assignment could not say which one would execute, and with none it said a run
 * was owned that nobody held. The consumer now only corroborates and records a delivery; the claim happens here,
 * for the authenticated caller, when that caller has capacity to run it. Execution hosts therefore never touch
 * the broker -- they reach the control plane over its internal API and nothing else.
 *
 * <p>The claim itself is {@link RunClaimService#claim}, unchanged: the same corroboration against the durable
 * dispatch, the same locks, the same compare-and-set, the same lease. What is new is only where the candidate
 * comes from and whose id is written.
 */
@Service
public class WorkerAssignmentService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorkerAssignmentService.class);

    /**
     * How many delivered candidates one call considers before answering "nothing". A candidate can be refused
     * after it was selected (a cancellation committed in between); the bound keeps a refused row that is somehow
     * still selectable from turning one request into a loop.
     */
    private static final int MAX_CANDIDATES = 3;

    private final WorkerAssignmentRepository repository;
    private final RunClaimService claims;
    private final DispatchContractValidator validator;
    private final String consumer;
    private final MeterRegistry meters;

    public WorkerAssignmentService(
            WorkerAssignmentRepository repository,
            RunClaimService claims,
            DispatchContractValidator validator,
            DispatchConsumptionService consumption,
            MeterRegistry meters) {
        this.repository = repository;
        this.claims = claims;
        this.validator = validator;
        this.consumer = consumption.consumerName();
        this.meters = meters;
    }

    /** Records that the worker asked for work. Its own transaction, once per request, not once per poll. */
    @Transactional
    public void recordPresence(String workerId) {
        requireWorker(workerId);
        repository.recordPresence(workerId);
    }

    /**
     * Whether delivered work is waiting right now. Reads, locks nothing and claims nothing: the answer a long poll
     * waits on, and one that may already be stale by the time the worker acts on it -- which is harmless, because
     * the claim that follows re-decides everything under a lock.
     */
    @Transactional(readOnly = true)
    public boolean workAvailable(String workerId) {
        requireWorker(workerId);
        return repository.deliveredWaiting(consumer);
    }

    /**
     * Claims the oldest delivered run for this worker, or answers that there is none right now.
     *
     * <p>One transaction: the candidate's run row is locked with SKIP LOCKED and the claim re-locks and
     * re-checks it inside the same transaction, so the row cannot change between being chosen and being claimed.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<Assignment> claimNext(String workerId) {
        requireWorker(workerId);
        for (int candidate = 0; candidate < MAX_CANDIDATES; candidate++) {
            var delivered = repository.lockNextDelivered(consumer);
            if (delivered.isEmpty()) {
                return Optional.empty();
            }
            ExecutionDispatch dispatch = revalidated(delivered.orElseThrow());
            if (dispatch == null) {
                return Optional.empty();
            }
            var outcome = claims.claim(dispatch, workerId);
            if (outcome.disposition() == ClaimDisposition.CLAIMED) {
                count("CLAIMED");
                return Optional.of(new Assignment(
                        dispatch.runId(), dispatch.attemptId(), WorkerAssignment.FIRST_EPOCH));
            }
            count(outcome.reason());
        }
        return Optional.empty();
    }

    /**
     * The stored dispatch, through the same strict validator a delivered message passes: unknown fields fatal,
     * digest re-derived. The stored row is the control plane's own, but "our own database said so" is not a
     * reason to skip the check every other reader of this document makes.
     */
    private ExecutionDispatch revalidated(WorkerAssignmentRepository.DeliveredDispatch delivered) {
        DispatchValidation validation = validator.validate(new DispatchMessage(
                delivered.messageId(), null, null, delivered.payload().getBytes(StandardCharsets.UTF_8)));
        if (validation instanceof DispatchValidation.Accepted accepted) {
            return accepted.dispatch();
        }
        // Unreachable while the scheduler writes only digest-bound payloads. Refused and reported rather than
        // claimed on a document that no longer verifies.
        LOGGER.atError()
                .addKeyValue("event", "DELIVERED_DISPATCH_INVALID")
                .addKeyValue("organizationId", delivered.organizationId())
                .addKeyValue("messageId", delivered.messageId())
                .log("A delivered dispatch no longer validates; it was not claimed");
        count("DISPATCH_INVALID");
        return null;
    }

    private static void requireWorker(String workerId) {
        if (!WorkerIdentity.isWorker(workerId)) {
            throw new NotAWorker();
        }
    }

    private void count(String outcome) {
        Counter.builder("kaas.assignment.claim").tag("outcome", outcome).register(meters).increment();
    }

    /** What a worker needs to start: the run, the attempt it holds, and the fencing token it holds it under. */
    public record Assignment(UUID runId, UUID attemptId, int assignmentEpoch) {}

    /** The caller authenticated as a platform service that is not a worker. */
    public static final class NotAWorker extends RuntimeException {
        NotAWorker() {
            super("Only a worker identity may claim work.", null, false, false);
        }
    }
}
