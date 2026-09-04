package com.kaas.api.execution.application;

import com.kaas.api.controlplane.domain.ExecutionAttempt;
import com.kaas.api.controlplane.domain.InfrastructureOutcome;
import com.kaas.api.controlplane.domain.RunLifecycle;
import com.kaas.api.controlplane.domain.TestOutcome;
import com.kaas.api.controlplane.domain.TestRun;
import com.kaas.api.execution.domain.ExecutionDenial;
import com.kaas.api.execution.domain.ExecutionResult;
import com.kaas.api.execution.domain.ExecutionResultPolicy;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accepts the evidence one execution produced, and completes the run on it.
 *
 * <p>A submitted document is a claim until the control plane has established where it came from. This service
 * is the boundary between the two. It never reads identity out of the document: run, attempt, epoch, command,
 * and snapshot all come from authoritative state, and the document's own copies of those fields are compared
 * against them so a worker describing a different execution is refused rather than believed.
 *
 * <p>Acceptance and completion are one transaction because either alone is a lie. Evidence without completion
 * describes something the platform still believes is running; completion without evidence is what the
 * database's own trigger refuses, and this is the path that would otherwise attempt it.
 */
@Service
public class ExecutionResultService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExecutionResultService.class);

    private static final String SHA256_PREFIX = "sha256:";

    private final ExecutionLifecycleRepository lifecycle;
    private final ResultDocumentReader documents;

    public ExecutionResultService(ExecutionLifecycleRepository lifecycle, ResultDocumentReader documents) {
        this.lifecycle = lifecycle;
        this.documents = documents;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ResultDecision submit(
            String workerId, UUID runId, UUID attemptId, int assignmentEpoch,
            UUID commandId, String document) {

        // Refused before parsing, not after. A body this large is already outside what the database will store,
        // and digesting it first would mean doing the expensive work for a request whose answer is fixed.
        if (document == null
                || document.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        > ExecutionResultPolicy.MAX_DOCUMENT_BYTES) {
            // OCTETS, not characters. The database bound is octet_length, and String.length() counts UTF-16
            // units — so 262,144 three-byte characters passed every application check at 786 KB and was then
            // refused by the CHECK at INSERT, surfacing as an opaque 409 CONFLICT with no denial code and a run
            // stranded in PROCESSING_RESULTS. The comment claiming this check "matches the database's own
            // bound" was true only for ASCII.
            return ResultDecision.refused(ExecutionDenial.RESULT_TOO_LARGE);
        }

        Optional<ExecutionLifecycleRepository.AssignedRun> locked = lifecycle.lockAssignedRun(runId);
        if (locked.isEmpty()) {
            return ResultDecision.refused(ExecutionDenial.ASSIGNMENT_STALE);
        }
        UUID organizationId = locked.orElseThrow().organizationId();
        TestRun run = locked.orElseThrow().run();
        ExecutionAttempt attempt = locked.orElseThrow().attempt();

        Instant now = lifecycle.currentDatabaseTime();

        Optional<ExecutionDenial> notThisAssignment = ExecutionPhaseService.proveAssignment(
                attempt, workerId, attemptId, assignmentEpoch);
        if (notThisAssignment.isPresent()) {
            return ResultDecision.refused(notThisAssignment.orElseThrow());
        }

        // IDEMPOTENCY BEFORE LIVENESS, and the order is the whole point.
        //
        // Accepting a result completes the run, and completing it fences the assignment. So a worker that
        // submitted successfully and lost the response comes back holding an assignment that is, by then,
        // legitimately fenced — and a liveness check first tells it ASSIGNMENT_STALE. That is the worst
        // possible answer: the worker concludes it lost the run, when in fact its result was accepted and the
        // run finished. It would report a failure that did not happen.
        //
        // Checking here is safe because identity and epoch are already proved above: this answer is being given
        // to the assignment that produced the result, not to anybody who can name an attempt.
        Optional<ExecutionResult> existing = lifecycle.findResult(organizationId, attemptId, assignmentEpoch);
        if (existing.isPresent()) {
            return ResultDecision.refused(ExecutionDenial.RESULT_ALREADY_SUBMITTED);
        }

        Optional<ExecutionDenial> cannotAct = ExecutionPhaseService.checkStillLive(run, attempt, now);
        if (cannotAct.isPresent()) {
            return ResultDecision.refused(cannotAct.orElseThrow());
        }

        if (run.lifecycleState() != RunLifecycle.PROCESSING_RESULTS) {
            return ResultDecision.refused(ExecutionDenial.PHASE_NOT_ENTERABLE);
        }

        // The command identifier must be the one this assignment was actually issued. Comparing it only
        // against the document's own copy would compare two fields the same caller supplies — they agree with
        // each other whatever value was chosen, so the check would pass for any invented command.
        Optional<ExecutionLifecycleRepository.AuthorizedCommand> issued =
                lifecycle.findAuthorizedCommand(organizationId, attemptId, assignmentEpoch);
        if (issued.isEmpty() || !issued.orElseThrow().commandId().equals(commandId)) {
            LOGGER.warn("run {} submitted a result answering a command it was not issued", runId);
            return ResultDecision.refused(ExecutionDenial.RESULT_PROVENANCE_MISMATCH);
        }

        ResultDocumentReader.ParsedResult parsed;
        try {
            parsed = documents.read(document);
        } catch (RuntimeException malformed) {
            // Deliberately not distinguished from a provenance mismatch on the wire. A document the control
            // plane cannot parse and one that describes another execution are both "this is not the evidence
            // that was asked for", and separating them tells a caller which of its lies was detected first.
            // The EXCEPTION TYPE ONLY, never its message. UUID.fromString throws "Invalid UUID string: <value>"
            // and Enum.valueOf throws "No enum constant ...<value>", both untruncated — so logging the message
            // writes a worker's entire submitted field, up to the document ceiling, into the platform log. That
            // is the same leak ApiExceptionHandler was rewritten to prevent in this slice, forty lines away.
            LOGGER.atWarn()
                    .addKeyValue("event", "RESULT_DOCUMENT_UNREADABLE")
                    .addKeyValue("runId", runId)
                    .addKeyValue("exceptionType", malformed.getClass().getName())
                    .log("A submitted result could not be read");
            return ResultDecision.refused(ExecutionDenial.RESULT_PROVENANCE_MISMATCH);
        }

        // Provenance. Every one of these is the document agreeing with state it did not supply.
        boolean bound = parsed.runId().equals(runId)
                && parsed.attemptId().equals(attemptId)
                && parsed.assignmentEpoch() == assignmentEpoch
                && parsed.commandId().equals(commandId)
                && parsed.organizationId().equals(organizationId)
                && parsed.projectId().equals(run.projectId())
                // The version the COMMAND was authorized against, not the run's current one. The run has
                // advanced through four phases since the command was issued, and the runner has no way to know
                // the version it reached — comparing against the live value would refuse every honest result.
                && parsed.runVersion() == issued.orElseThrow().runVersion();
        if (!bound) {
            LOGGER.warn("run {} submitted a result bound to a different execution", runId);
            return ResultDecision.refused(ExecutionDenial.RESULT_PROVENANCE_MISMATCH);
        }

        // The run knows when execution actually started, because it stamped that instant itself when the worker
        // entered RUNNING. A result claiming to have started at some other time is describing a different
        // execution, or the same one dishonestly.
        if (run.executionStartedAt() == null || !parsed.startedAt().equals(run.executionStartedAt())) {
            LOGGER.warn("run {} submitted a result whose start instant is not the run's own", runId);
            return ResultDecision.refused(ExecutionDenial.RESULT_PROVENANCE_MISMATCH);
        }
        if (parsed.finishedAt().isBefore(parsed.startedAt())) {
            return ResultDecision.refused(ExecutionDenial.RESULT_PROVENANCE_MISMATCH);
        }

        // Only a successful execution may complete a run through this path. An infrastructure failure has its
        // own route — the worker reports it and the run stops — and accepting it here would produce a COMPLETED
        // run whose termination reason says the execution finished normally.
        if (parsed.infrastructureOutcome() != InfrastructureOutcome.SUCCEEDED
                || !(parsed.testOutcome() == TestOutcome.PASSED || parsed.testOutcome() == TestOutcome.FAILED)) {
            return ResultDecision.refused(ExecutionDenial.RESULT_PROVENANCE_MISMATCH);
        }

        UUID resultId = UUID.randomUUID();
        // Bare hex, not the prefixed form the aggregate carries. TestRun.snapshotDigest() is "sha256:"-prefixed
        // for display and comparison against other prefixed digests, while execution_results stores the bare
        // value so it compares directly against test_runs.snapshot_sha256 without either side normalising.
        String snapshotSha256 = run.snapshotDigest().startsWith(SHA256_PREFIX)
                ? run.snapshotDigest().substring(SHA256_PREFIX.length())
                : run.snapshotDigest();
        String digest = ExecutionResultPolicy.digest(
                resultId, organizationId, run.projectId(), runId, attemptId, assignmentEpoch, commandId,
                snapshotSha256, document);

        ExecutionResult result = new ExecutionResult(
                resultId, organizationId, run.projectId(), runId, attemptId, assignmentEpoch, commandId,
                snapshotSha256, digest, parsed.testOutcome(), parsed.infrastructureOutcome(), document, now);

        TestRun completed = run.completedWithResult(parsed.testOutcome(), now);
        lifecycle.persistResultAndComplete(
                organizationId, run, completed, attempt, result, UUID.randomUUID());

        LOGGER.info(
                "run {} completed with test outcome {} on evidence {}", runId, parsed.testOutcome(), digest);
        return ResultDecision.accepted(completed, result);
    }

    /** Accepted with the evidence recorded, or refused with the reason. */
    public record ResultDecision(
            Optional<TestRun> run, Optional<ExecutionResult> result, Optional<ExecutionDenial> denial) {

        public static ResultDecision accepted(TestRun run, ExecutionResult result) {
            return new ResultDecision(Optional.of(run), Optional.of(result), Optional.empty());
        }

        public static ResultDecision refused(ExecutionDenial denial) {
            return new ResultDecision(Optional.empty(), Optional.empty(), Optional.of(denial));
        }
    }
}
