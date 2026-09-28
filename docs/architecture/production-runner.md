# The production runner

KAAS-DEPLOY-001 · [ADR-035](../adr/035-deployment-readiness-runner-claim-intake.md) ·
Ops contract: [application-contract.md](../deployment/application-contract.md)

## Where work comes from

```
  APPLICATION HOST                                                  EXECUTION HOST
  ┌───────────────────────────────────────────────┐                ┌──────────────────────────────────┐
  │  PostgreSQL ── outbox ── relay ──▶ RabbitMQ   │                │                                  │
  │                                       │       │                │                                  │
  │                                       ▼       │                │                                  │
  │                           API dispatch consumer                │                                  │
  │                           (corroborates, records DELIVERED;    │                                  │
  │                            claims nothing)                     │                                  │
  │                                       │       │                │                                  │
  │                                       ▼       │   WireGuard    │                                  │
  │                      durable state: dispatch_inbox ◀═══════════╪══ runner                         │
  │                      POST /internal/v1/assignments/waits  (wait; owns nothing)                   │
  │                      POST /internal/v1/assignments        (claim, in the token's name)           │
  │                                       │       │   ═══════════▶ │   │                              │
  │                            RunClaimService    │   assignment   │   ▼                              │
  │                            (attempt, epoch,   │   (run,        │ ExecutionLoop                    │
  │                             lease, fencing)   │    attempt,    │   │ authorization, lease renewal,│
  │                                               │    epoch)      │   │ source, secrets, egress      │
  │                                               │                │   ▼                              │
  │                                               │                │ runsc sandbox ── Karate 2.1.2    │
  └───────────────────────────────────────────────┘                └──────────────────────────────────┘
```

**RabbitMQ never reaches the runner.** The execution host holds no broker address, credential, AMQP client or
TLS configuration, and the runner's build fails if a broker client appears on its classpath. The only thing the
execution host talks to is the internal API, over WireGuard, authenticated.

## One composition

[`RunnerComposition.compose`](../../services/runner/src/main/java/com/kaas/runner/daemon/RunnerComposition.java)
builds the deployed runner; [`RunnerApplication.main`](../../services/runner/src/main/java/com/kaas/runner/RunnerApplication.java)
validates configuration and runs it until SIGTERM. `DeploymentPipelineTests` builds the runner through the same
method — only the Docker and HTTP clients are supplied — and drives a real run through it.

| Part | Production type |
|---|---|
| configuration | `RunnerConfiguration` — typed, validated at startup, every problem reported by variable name |
| Docker | docker-java against `KAAS_RUNNER_DOCKER_HOST` |
| runtime preflight | `RuntimePreflight` — configured runtime registered and measured; images present |
| attestation | `AttestationRefresher` over the real gates (`HostileExecutionSecurityGate`, `EgressEnforcementGate`) |
| service identity | `ServiceIdentity` — runner, and separately the egress proxy |
| internal API | `ControlPlaneClient` with a per-request credential |
| work intake | `RunnerDaemon` intake loop: claim, wait, back off |
| execution | `ExecutionLoop` (Karate engine, configured runtime, source delivery on) |
| authority | `ExecutionAuthorityMonitor`, inside the loop (ADR-029) |
| source / secrets / egress | the loop's existing redemption paths; `RefreshingEgressExecutions` for allowlists |
| reconciliation | `OrphanSandboxReconciler`, startup + periodic |
| health / metrics | `HealthServer` — `/health/liveness`, `/health/readiness`, `/metrics` |

## Lifecycle

```
startup ─▶ Docker ─▶ runtime (configured one only) + images ─▶ attestation produced ─▶ startup reconciliation
        ─▶ service identity ─▶ attestation submitted & accepted ─▶ READY
READY   ─▶ claim ─▶ (none) ─▶ wait ≤ KAAS_RUNNER_CLAIM_WAIT ─▶ claim …
        ─▶ (assignment) ─▶ slot taken ─▶ ExecutionLoop ─▶ result ─▶ slot freed
SIGTERM ─▶ NOT READY ─▶ wait abandoned / in-flight claim completed ─▶ drain ≤ KAAS_RUNNER_SHUTDOWN_TIMEOUT
        ─▶ interrupt remaining ─▶ maintenance stops ─▶ own-generation resources removed ─▶ clients close ─▶ exit
```

A runner asks only when it has a free slot (`KAAS_RUNNER_MAX_CONCURRENCY`), so the claim is the backpressure.

### Why waiting and claiming are separate requests

The server cannot reliably tell that a client went away. A long poll that ended in a claim could therefore
create ownership for a runner that had already stopped taking work. The wait owns nothing, so it is abandoned
freely; the claim answers at once and is sent only while the runner accepts work. A claim in flight when
shutdown begins is allowed to complete, and its assignment is drained — never dropped.

## Readiness and liveness

| Condition | Holds when | Typical reason codes when not |
|---|---|---|
| `DOCKER` | the daemon answers | `DOCKER_UNREACHABLE` |
| `RUNTIME` | the configured runtime is registered, at `KAAS_RUNNER_RUNSC_PATH` if set, and measurable | `RUNTIME_NOT_REGISTERED`, `RUNTIME_PATH_MISMATCH`, `RUNTIME_UNMEASURABLE` |
| `IMAGES` | probe, engine (and proxy) images are present by digest | `IMAGE_ABSENT` |
| `ATTESTATION` | accepted evidence exists and is not within 5 minutes of expiry | `ASSESSMENT_FAILED_*`, `REFUSED_*`, `SUBMISSION_FAILED`, `EXPIRED` |
| `RUNTIME_DIGEST_MATCH` | the runtime measured now is the one the evidence describes | `RUNTIME_CHANGED`, `RUNTIME_NOT_ACCEPTED` |
| `STARTUP_RECONCILIATION` | startup reconciliation completed | `NOT_YET_ESTABLISHED` |
| `SERVICE_IDENTITY` | a valid credential for this worker can be had | `TOKEN_ENDPOINT_UNREACHABLE`, `TOKEN_REFUSED`, `SUBJECT_MISMATCH`, … |
| `CONTROL_PLANE` | the internal API answered recently | `CONTROL_PLANE_UNAVAILABLE` |
| `NOT_DRAINING` | shutdown has not begun (one-way) | `DRAINING` |

Liveness is 200 while the process is healthy — through API, issuer, Vault and broker outages, and through a
failed refresh while old evidence lasts. It is 503 only after shutdown or if the intake thread died.

## Attestation refresh

Every refresh runs the gates again, measures the runsc the daemon has registered **now**, signs with the key
read from its file **now**, and submits. The control plane stores it only if a pinned key verifies it and it
could authorize an execution now; it re-verifies it on every use and uses a worker's own evidence only. A
refused or failed refresh retries every `KAAS_RUNNER_ATTESTATION_RETRY_INTERVAL`; the previous evidence keeps
the runner ready until it expires. A runtime binary replaced between refreshes is caught by the 15-second
maintenance tick: NOT READY at once, and an immediate re-measurement.

## Service identity

A JWT from the existing issuer, replaced at 70 % of its life; `sub` must equal `KAAS_RUNNER_WORKER_ID`. No
token is sent past 30 s before expiry, none is sent unauthenticated, none enters a sandbox, and no `toString`
prints one. The egress proxy's identity is separate and is injected into each proxy container when that proxy
starts.

## Metrics

Prometheus text at `/metrics`. Closed label vocabularies only — never a run, tenant, worker, URL or image.

| Metric | |
|---|---|
| `kaas_runner_ready`, `kaas_runner_live` | gauges |
| `kaas_runner_claim_api_available` | internal claim API answered recently |
| `kaas_runner_claim_poll_total`, `_attempt_total`, `_success_total`, `_empty_total`, `_error_total` | intake |
| `kaas_runner_active_assignments`, `kaas_runner_max_concurrency` | slots |
| `kaas_runner_execution_started_total`, `kaas_runner_execution_completed_total{status}` | status ∈ COMPLETED, REFUSED, REJECTED, INFRASTRUCTURE_FAILED, AUTHORITY_LOST, CRASHED |
| `kaas_runner_reconcile_total`, `_reconcile_failure_total`, `kaas_runner_orphans_reclaimed_total` | reconciliation |
| `kaas_egress_*` | the egress components' own closed-vocabulary counters (proxy launches/failures, reclaimed proxies and networks) |
| `kaas_runner_attestation_age_seconds`, `_valid_seconds`, `kaas_runner_attestation_refresh_total{result}` | evidence |
| `kaas_runner_runtime_digest_match` | 1/0 |
| `kaas_runner_service_auth_refresh_total{identity}`, `_failure_total{identity}`, `kaas_runner_service_credential_valid_seconds` | identity ∈ runner, egress_proxy |

The runner has no broker metric, because it has no broker. The control plane's own transport metrics
(`kaas.outbox.*`, `kaas.dispatch.*`, including `kaas.dispatch.delivered`) and its intake metric
(`kaas.assignment.claim{outcome}`) are separate dimensions on the API's `/actuator/prometheus`.

## What this does not do

- It does not recover a dispatch RabbitMQ lost after publication; the run ends `TIMED_OUT / QUEUE_DEADLINE`.
  KAAS-MSG-001. See [ADR-035 §8](../adr/035-deployment-readiness-runner-claim-intake.md).
- It does not pull images: they arrive with the release, placed by the host deployment.
