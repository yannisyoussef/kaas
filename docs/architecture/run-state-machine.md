# Run State Machine

**Status: PARTIALLY IMPLEMENTED.** Implemented: the pure transition oracle, exact `none → CREATED` persistence, `CREATED → QUEUED` scheduling, the three transitions that end a run no worker has taken (`CREATED → COMPLETED` and `QUEUED → COMPLETED` by cancellation, `QUEUED → COMPLETED` by queue deadline), `QUEUED → CLAIMED` by the dispatch consumer, and `CLAIMED → STOPPING → COMPLETED` by cancellation or lease loss — with the assignment epoch as a fencing token, a server-controlled lease, and heartbeats. `CLAIMED → PROVISIONING` and everything past it remain proposed, as do result acceptance, artifact handling, and every deadline other than the queue deadline and the claim lease. See [the consumer/claim/lease slice](consumer-claim-lease-slice.md) and [the early terminal lifecycle slice](early-terminal-lifecycle-slice.md).

## State flow

```mermaid
stateDiagram-v2
  [*] --> CREATED: create
  CREATED --> QUEUED: schedule
  QUEUED --> CLAIMED: claim
  CLAIMED --> PROVISIONING: provision
  PROVISIONING --> RUNNING: sandbox ready
  RUNNING --> COLLECTING_RESULTS: process exited
  RUNNING --> PROCESSING_RESULTS: fatal or complete result accepted
  COLLECTING_RESULTS --> PROCESSING_RESULTS: result accepted
  PROCESSING_RESULTS --> COMPLETED: canonicalize evidence
  CREATED --> COMPLETED: cancel
  QUEUED --> COMPLETED: cancel or queue timeout
  CLAIMED --> STOPPING: cancel / lease loss
  PROVISIONING --> STOPPING: cancel / timeout / lease loss
  RUNNING --> STOPPING: cancel / timeout / lease loss
  COLLECTING_RESULTS --> STOPPING: cancel / timeout / lease loss
  STOPPING --> COMPLETED: stop acknowledged or grace elapsed
```

`STOPPING` carries a `stopCause`: `USER_REQUESTED`, `PROVISIONING_TIMEOUT`, `EXECUTION_TIMEOUT`, or `RESULT_COLLECTION_TIMEOUT`. Queue timeout terminalizes directly because no worker owns the attempt. Result-processing timeout terminalizes from `PROCESSING_RESULTS` as infrastructure failure after quarantining incomplete derived data; it does not need to stop a sandbox.

## Transition table

Every row assumes authenticated/authorized internal actors and schema/size validation. “Atomic effects” means the aggregate mutation, audit record, idempotency/inbox effect where applicable, and outgoing outbox record are committed together.

| Current | Event → result | Actor | Preconditions and expected version | Durable atomic effects | Emitted durable event | Retry / idempotency | Timeout / terminal | Invalid or race behavior |
|---|---|---|---|---|---|---|---|---|
| none | `CREATE_RUN` → `CREATED` | API | Authorized project; exact owned revisions; valid scoped idempotency key | TestRun + sealed snapshot + key/fingerprint/response metadata, `runVersion=1` | none in this bounded slice | Same key+fingerprint replays original; same key+different fingerprint is 409 | No queue deadline; nonterminal | Advisory transaction lock serializes concurrent same-key creation; loser reads original |
| `CREATED` | `SCHEDULE` → `QUEUED` | Scheduler (internal) | **IMPLEMENTED:** `expectedRunVersion` compare-and-set; no cancellation | Create attempt 1 awaiting claim, immutable queue-time DispatchIntent + digest, `queuedAt`, queue deadline, lifecycle event, unpublished outbox row; increment version | `RUN_STATE_CHANGED` persisted; nothing published | Repeat scheduling observes already QUEUED and writes nothing; concurrent schedulers yield one winner | Queue-wait timer starts here; nonterminal | Cancellation blocks scheduling; no production trigger invokes the scheduler yet |
| `CREATED`,`QUEUED` | `REQUEST_CANCEL` → `COMPLETED` | API | **IMPLEMENTED:** locked compare-and-set on state and version; not already asked to stop | Record request+ack times; `NOT_AVAILABLE/CANCELLED`; reason `USER_REQUESTED` phase `CANCELLATION`; suppress any unclaimed pending dispatch; delete scheduling control state; increment version | `RUN_STATE_CHANGED` persisted; `EXECUTION_COMPLETED` proposed | Idempotent by state: repeat returns the same terminal run and writes nothing; no idempotency key needed | Immediate terminal; no `STOPPING`, because no worker owns the run | Schedule after winning cancel is refused; a relay-claimed dispatch publishes and is stale |
| `QUEUED` | `CLAIM` → `CLAIMED` | Dispatch consumer | **IMPLEMENTED:** message corroborated against the persisted dispatch, then locked compare-and-set on state, version, and current attempt; queue deadline open; assignment epoch 1 | Store lease owner audit, epoch, expiry, last-seen; increment version | `RUN_STATE_CHANGED` | Duplicate delivery observes the recorded inbox decision and writes nothing; a competing claim is rejected | 30s lease; nonterminal; grants no execution, source, or secret authority | Cancel/queue-timeout CAS first makes the delivery stale |
| `CLAIMED` | `HEARTBEAT` → `CLAIMED` | Worker, via the internal service surface | **IMPLEMENTED:** exact attempt, epoch, and worker identity; lease live and not fenced | Update `lastHeartbeatAt` and `leaseExpiresAt` only | none | Repeated heartbeat is safe | Extends lease; nonterminal | An old epoch, another worker, an expired lease, or a fenced or terminal run is refused; no version change |
| `CLAIMED` | `PROVISIONING_STARTED` → `PROVISIONING` | Worker gateway | Exact attempt+epoch; live lease; `expectedRunVersion` | Record provisioning start/deadline; increment version | `RUN_STATE_CHANGED` | Exact duplicate at resulting state is no-op | 2m provisioning deadline; nonterminal | Cancel/lease fencing CAS first makes event stale |
| `PROVISIONING` | `SANDBOX_READY` → `RUNNING` | Worker gateway | Exact attempt+epoch; live lease; provisioning deadline open; `expectedRunVersion` | Record execution start/absolute execution deadline; increment version | `RUN_STATE_CHANGED`, `EXECUTION_STARTED` | Exact duplicate is no-op | Command timeout, default 5m/max 1h; nonterminal | Timeout/cancel CAS first fences late ready event |
| `RUNNING` | `PROCESS_EXITED` → `COLLECTING_RESULTS` | Worker gateway | Exact attempt+epoch; `expectedRunVersion`; no stop won | Record exit observation/result-collection deadline; increment version | `RUN_STATE_CHANGED`, coalesced `PROGRESS` | Duplicate is no-op | 2m collection/upload deadline; nonterminal | Accepted result may race and skip directly to processing; CAS decides |
| `RUNNING`,`COLLECTING_RESULTS` | `RESULT_ACCEPTED` → `PROCESSING_RESULTS` | Result processor | Schema+semantic limits; full trusted identity, command, epoch, digest; `expectedRunVersion`; no stop won | Inbox/result ID+digest, immutable raw evidence reference, processing deadline; increment version | `RUN_STATE_CHANGED` | Same result ID+digest no-op; conflicting digest quarantined/security audit | 2m processing deadline; cancellation now too late | Stop CAS first makes result stale; accepted-result CAS first makes cancellation 409 |
| `PROCESSING_RESULTS` | `RESULT_CANONICALIZED` → `COMPLETED` | Result processor | Accepted result; summaries recomputed; manifest verified or policy permits empty; `expectedRunVersion` | Canonical result/outcomes, artifact registrations, terminal timestamps; increment version | `RUN_STATE_CHANGED`, `ARTIFACT_AVAILABLE` as verified, `EXECUTION_COMPLETED` | Duplicate finalization reads immutable terminal record | Terminal | Different result/manifest cannot replace evidence |
| `CLAIMED`,`PROVISIONING`,`RUNNING`,`COLLECTING_RESULTS` | `REQUEST_CANCEL` → `STOPPING` | API | **IMPLEMENTED for `CLAIMED`:** locked compare-and-set; the assignment is fenced in the same transaction | Record cancellation request and stop reason; fence the assignment; increment version. No stop command outbox exists yet, because there is no sandbox to send one to | `RUN_STATE_CHANGED` | Natural run cancellation, idempotent by state | Answered 202: the request is durable and termination is pending | Cancel CAS first makes a later claim or heartbeat stale |
| `PROVISIONING`,`RUNNING`,`COLLECTING_RESULTS` | `PHASE_DEADLINE` → `STOPPING` | Reconciler | Server clock past stored absolute deadline; `expectedRunVersion` | Record timeout cause, fence epoch, stop command outbox; increment version | `RUN_STATE_CHANGED` | Repeated reconciliation sees STOPPING/no-op | 30s stop grace; nonterminal | Late worker success cannot override timeout commit |
| `STOPPING` | `STOP_ACKNOWLEDGED` or `STOP_GRACE_EXPIRED` → `COMPLETED` | Worker gateway or reconciler | **IMPLEMENTED for the reconciler:** locked compare-and-set on state and version. The grace is deliberately not waited today — nothing can acknowledge a stop, so the reconciler settles on its next pass; see the slice document | Ack cancellation if user-caused; set `NOT_AVAILABLE` and `CANCELLED` (user-caused) or `FAILED` (lease lost), and — once phase deadlines land — `TIMED_OUT`. A `TIMED_OUT` run here may legitimately carry an outstanding cancellation request, which is why `ck_test_runs_timeout_not_cancelled` is scoped to the queue deadline rather than to `TIMED_OUT` in general; cleanup-needed audit; increment version | `RUN_STATE_CHANGED`, `EXECUTION_COMPLETED` | Duplicate stop ack is terminal no-op | Terminal | Late result/heartbeat/manifest is stale and quarantined if conflicting |
| `QUEUED` | `QUEUE_DEADLINE` → `COMPLETED` | Reconciler | **IMPLEMENTED:** database clock past queue deadline; locked compare-and-set on state and version | Set `NOT_AVAILABLE/TIMED_OUT` with reason `QUEUE_DEADLINE` phase `QUEUE`; never a cancellation; suppress any unclaimed pending dispatch; delete scheduling control state; increment version | `RUN_STATE_CHANGED` persisted; `EXECUTION_COMPLETED` proposed | Repeated reaping is a terminal no-op; a cancellation that wins the race leaves nothing to reap | Default 5m; terminal | Cancel CAS first wins and the queue timeout becomes stale |
| `CLAIMED`,`PROVISIONING`,`RUNNING`,`COLLECTING_RESULTS` | `LEASE_EXPIRED` → `STOPPING` | Reconciler | **IMPLEMENTED for `CLAIMED`:** expiry plus 30s recovery window; exact epoch; locked compare-and-set | Fence the epoch and record `LEASE_LOST`; increment version. No cleanup intent is emitted, because no sandbox exists to clean up | `RUN_STATE_CHANGED` | Reconciliation is idempotent once the epoch is fenced | Then `FAILED` with phase `CLAIM`; nonterminal now | A late heartbeat cannot renew a fenced epoch; no automatic retry |
| `PROCESSING_RESULTS` | `PROCESSING_DEADLINE` → `COMPLETED` | Reconciler | Server clock past deadline; `expectedRunVersion` | Quarantine incomplete derived data; set `NOT_AVAILABLE/FAILED` with `RESULT_PROCESSING`; increment version | `RUN_STATE_CHANGED`, `EXECUTION_COMPLETED` | Repeated reconciliation is terminal no-op | Default 2m; terminal | Late processing cannot publish canonical evidence |
| `PROCESSING_RESULTS`,`COMPLETED`,`STOPPING` | `REQUEST_CANCEL` → unchanged | API | **IMPLEMENTED for `COMPLETED` and `STOPPING`:** current state re-read after the lock predicate fails | No lifecycle mutation | none | Idempotent by state: a run already cancelled replays its representation (200 terminal, 202 stopping); one that is ending for any other reason is a conflict | Terminal or point-of-no-return | Returns 409 `RUN_ALREADY_TERMINAL` for a run that completed for another reason, and 409 `RUN_NOT_CANCELLABLE` both for a phase early cancellation cannot end and for a run already stopping because its lease was lost — answering 202 there would promise a cancellation that will never be recorded |
| any | duplicate/stale/unsupported transition → unchanged | Any | Message ID, run version, attempt, epoch, state, deadline checked | Inbox/audit/quarantine only as appropriate | none | Exact duplicate ACK; stale ACK+audit; tampering reject/quarantine | Terminal remains immutable | Never retry permanent validation/version/tampering errors |

## Cancellation race policy

Cancellation is idempotent at the API but not retroactive. The first valid compare-and-set determines the branch:

- Result accepted first: lifecycle enters `PROCESSING_RESULTS`; cancellation returns 409 and evidence completes normally.
- Cancellation committed first: lifecycle enters `STOPPING`; later ordinary success or assertion result is stale and cannot override cancellation.
- Timeout committed first: late success is stale; infrastructure outcome remains `TIMED_OUT`.
- Early cancellation before claim completes immediately and prevents a claim from satisfying its version precondition.

Control-plane commit order, not runner timestamps or broker arrival order, decides races.

## Timeout and lease profile

| Concern | MVP default | Behavior |
|---|---:|---|
| Queue wait | 5 minutes | `QUEUED → COMPLETED`, `TIMED_OUT`, phase `QUEUE` |
| Claim lease | 30 seconds | Heartbeat every 10 seconds; expiry starts a 30-second recovery window before fencing |
| Provisioning | 2 minutes | `PROVISIONING → STOPPING`, timeout cause |
| Execution | 5 minutes, request range 1 second–1 hour | Absolute command deadline; `RUNNING → STOPPING` |
| Result collection/upload | 2 minutes | `COLLECTING_RESULTS → STOPPING` |
| Result processing | 2 minutes | `PROCESSING_RESULTS → COMPLETED`, infrastructure failure |
| Stop/cancel grace | 30 seconds | Terminalize after acknowledgment or grace; external cleanup remains required |

Durations are initial contract defaults, not measured SLOs. Implementations must use server-controlled absolute deadlines and define clock-skew tolerance. A database terminal state does not prove a partitioned sandbox stopped; future launcher reconciliation must verify cleanup.

## Ordering requirement

Broker ordering is never required for correctness. The only ordered public sequence is a per-run SSE sequence allocated durably with its event. Lifecycle correctness uses current state, `runVersion`, attempt identity, and `assignmentEpoch`. Scenario steps and test retry attempts preserve their source execution order inside one immutable result document; that is evidence ordering, not broker ordering.
