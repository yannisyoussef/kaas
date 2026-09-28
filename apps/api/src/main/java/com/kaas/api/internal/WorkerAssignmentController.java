package com.kaas.api.internal;

import com.kaas.api.consumer.application.WorkerAssignmentService;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where a worker asks for work.
 *
 * <h2>Two operations, because waiting and owning must not share a request</h2>
 *
 * <p>{@code POST /internal/v1/assignments/waits} is the long poll. It holds the request for up to
 * {@code waitMillis} until delivered work exists and answers 200 {@code {"available":true}}, or 204 when none
 * appeared. It CLAIMS NOTHING, so a worker that is shutting down may abandon it at any instant: the server cannot
 * reliably tell that its client went away, and a wait that ended in a claim would then create ownership for a
 * process that had already stopped taking work.
 *
 * <p>{@code POST /internal/v1/assignments} claims. It answers at once -- 200 with the assignment, or 204 -- and a
 * worker sends it only while it is accepting work, so a claim is never left in flight across the start of a
 * shutdown for longer than one short request the worker waits for and then drains like any other assignment.
 *
 * <p>In both, the worker's identity is the service token's subject and nothing else. The body may carry only the
 * fields named here; a {@code workerId} or anything else is refused rather than ignored, because a field that
 * is silently dropped today is one somebody wires up tomorrow.
 *
 * <p>A worker asks only when it has a free execution slot, so the poll IS the backpressure: nothing is ever
 * claimed for a worker that has not said it can run it.
 */
@RestController
@RequestMapping("/internal/v1")
class WorkerAssignmentController {
    /** The longest a wait may hold a server thread. Long enough to make idle polling cheap. */
    static final Duration MAX_WAIT = Duration.ofSeconds(20);

    private static final Duration DEFAULT_WAIT = Duration.ofSeconds(10);

    /** Between checks while waiting. */
    private static final Duration PACE = Duration.ofMillis(250);

    private final WorkerAssignmentService assignments;

    WorkerAssignmentController(WorkerAssignmentService assignments) {
        this.assignments = assignments;
    }

    @PostMapping("/assignments")
    ResponseEntity<Map<String, Object>> claim(
            Authentication authentication, @RequestBody(required = false) Map<String, Object> request) {
        if (!onlyFields(request, Set.of())) {
            return unknownField();
        }
        String worker = authentication.getName();
        assignments.recordPresence(worker);
        Optional<WorkerAssignmentService.Assignment> claimed = assignments.claimNext(worker);
        if (claimed.isEmpty()) {
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        }
        var assignment = claimed.orElseThrow();
        // Only what the worker needs to begin, all of it the control plane's own: no capability, no secret, no
        // image, no tenant-selected option. Everything else arrives through the execution authorization, which
        // revalidates this assignment before it grants anything.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", assignment.runId().toString());
        body.put("attemptId", assignment.attemptId().toString());
        body.put("assignmentEpoch", assignment.assignmentEpoch());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @PostMapping("/assignments/waits")
    ResponseEntity<Map<String, Object>> await(
            Authentication authentication, @RequestBody(required = false) Map<String, Object> request)
            throws InterruptedException {
        if (!onlyFields(request, Set.of("waitMillis"))) {
            return unknownField();
        }
        Duration wait = DEFAULT_WAIT;
        if (request != null && request.containsKey("waitMillis")) {
            if (!(request.get("waitMillis") instanceof Number number)
                    || number.longValue() < 0
                    || number.longValue() > MAX_WAIT.toMillis()
                    || number.doubleValue() != number.longValue()) {
                return ResponseEntity.badRequest()
                        .cacheControl(CacheControl.noStore())
                        .body(Map.of("code", "INVALID_WAIT"));
            }
            wait = Duration.ofMillis(number.longValue());
        }
        String worker = authentication.getName();
        assignments.recordPresence(worker);
        long deadline = System.nanoTime() + wait.toNanos();
        while (true) {
            if (assignments.workAvailable(worker)) {
                return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("available", true));
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
            }
            Thread.sleep(Math.min(PACE.toMillis(), Math.max(1, remaining / 1_000_000)));
        }
    }

    private static boolean onlyFields(Map<String, Object> request, Set<String> allowed) {
        return request == null || allowed.containsAll(request.keySet());
    }

    private static ResponseEntity<Map<String, Object>> unknownField() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of("code", "UNKNOWN_FIELD"));
    }

    @ExceptionHandler(WorkerAssignmentService.NotAWorker.class)
    ResponseEntity<Map<String, Object>> notAWorker() {
        // A platform service that is not a worker -- the egress proxy's identity, say -- has no business
        // claiming work. Forbidden, not "nothing available": the answer must not look like an empty queue.
        return ResponseEntity.status(403).cacheControl(CacheControl.noStore()).body(Map.of("code", "NOT_A_WORKER"));
    }
}
