# ADR-035: Deployment readiness — runner claim intake, the production runner, and the broker-loss finding

**Status: ACCEPTED.** Makes the application deployable into the topology the Operations assessment chose, with
one exception it measures and does not close: a dispatch RabbitMQ loses after publication is not rebuilt.
The slice verdict is therefore **DEPLOYMENT READINESS BLOCKED BY MESSAGING RECOVERY GAP** (see
[KAAS_DEPLOYMENT_READINESS_REPORT.md](../../KAAS_DEPLOYMENT_READINESS_REPORT.md)). Does not revisit any
execution boundary decided by ADR-022 through ADR-034.

## Context

KAAS-22 ended with secret-bearing Karate executing under runsc, and with nothing a deployment could start:
`RunnerApplication.main` printed a banner, every composition of the execution loop lived in test code, the
API's dispatch consumer claimed every delivered run for one configured worker id, the application migrated its
own schema on startup, attestations arrived only as configuration, and the runner held one static bearer string.

The Operations assessment fixed the topology: control plane on the application host, a dedicated execution
host, WireGuard between them, existing Vault as Transit, dedicated PostgreSQL and RabbitMQ, runsc installed by
Operations, deployment through the existing GitLab poller. The user answered the two questions the interrupted
session asked:

- **A — how does the production runner obtain work?** Through the API. The runner claims.
- **B — what does this slice do about RabbitMQ message loss?** Measure it and report it as a blocker with a
  narrow follow-up. Do not implement redrive.

## Decision

### 1. The runner claims work from the internal API; the control plane stays the only broker consumer

```
RabbitMQ ─▶ API consumer ─▶ dispatch_inbox DELIVERED ─▶ POST /internal/v1/assignments ─▶ RunClaimService
                                                          ▲  (over WireGuard, authenticated)       │
                                                          └──────────── runner ◀─── assignment ◀───┘
```

The execution host needs **no** RabbitMQ route, credential, AMQP client, broker TLS or topology knowledge. The
runner's build fails if a broker client reaches its classpath (`verifyLauncherHasNoUserContentDependencies`).

- The consumer still corroborates every delivery against the durable dispatch, exactly as a claim would
  (`RunClaimService.admit`), and records `DELIVERED`. It **claims nothing**: it cannot name who will run the
  work. `CLAIMED` stays in the vocabulary so the previous release's decisions still read back (V14 is
  expand-only).
- A run is claimable only once its dispatch was **delivered**. RabbitMQ remains the delivery path; nothing lets
  a worker reach work the broker never handed over.
- `POST /internal/v1/assignments` claims the oldest delivered run for the caller, through the unchanged
  `RunClaimService.claim`: same corroboration, same locks, same compare-and-set, same lease and epoch. The
  candidate row is locked `FOR UPDATE ... SKIP LOCKED` on the run, so concurrent runners get different runs,
  and under contention one run has exactly one owner (proved with twelve concurrent claimants).
- **The worker is the token's subject.** Nothing in the body can choose it; a body carrying `workerId` — or any
  field — is refused `UNKNOWN_FIELD`, not ignored. Only subjects in `kaas.worker.*` may claim; the egress
  proxy's identity is refused by the security chain before the controller.
- The response carries `runId`, `attemptId`, `assignmentEpoch` and nothing else. Capabilities, secrets and
  configuration still arrive only through the execution authorization, which revalidates the assignment.

### 2. Waiting and owning are separate requests

`POST /internal/v1/assignments/waits` is the bounded long poll (≤ 20 s). It **claims nothing**. The interrupted
session's endpoint waited *and then claimed* in one request; a runner abandoning that request at SIGTERM could
not stop the server from creating ownership afterwards. Splitting them means shutdown may abandon a wait at any
instant, and a claim — short, sent only while the runner accepts work — is never interrupted: what it returns
is drained like any other assignment. No WebSocket, no SSE.

### 3. The production runner is one composition

`RunnerComposition.compose` is the only place the deployed runner is assembled, and `RunnerApplication.main`
calls it. The deployment pipeline suite calls the same method. Startup order — READY is reported only after all
of it:

```
configuration ─▶ Docker ─▶ configured runtime registered & measured, images present ─▶ attestation produced
  ─▶ startup orphan reconciliation ─▶ service identity ─▶ attestation submitted and accepted ─▶ READY
```

- **Readiness** is "may this runner take a new assignment": Docker, the configured runtime (never a fallback
  from runsc to runc), images, accepted current evidence, runtime digest still matching it, startup
  reconciliation done, a valid credential, the internal API reached recently, not draining. No broker condition.
- **Liveness** is process health only. It survives API, token-endpoint, Vault and broker outages and a failed
  refresh while old evidence is valid.
- **Shutdown:** NOT READY → intake stops (a wait is abandoned, an in-flight claim completes and is drained) →
  running assignments drain within `KAAS_RUNNER_SHUTDOWN_TIMEOUT`, then are interrupted → maintenance stops →
  this process's own labelled sandbox resources are removed → clients close → exit.
- **API outage:** NOT READY, exponential back-off with jitter, reconnect; no hot loop. Reconnection belongs to
  the intake loop alone. Running assignments keep ADR-029's authority semantics.
- **Reconciliation** runs at startup (required before READY) and on `KAAS_RUNNER_RECONCILE_INTERVAL`, with
  ADR-022's age-and-label rule, which cannot reap a live sandbox.

### 4. Evidence refreshes without a restart, and every refresh is a new measurement

The runner re-runs the real gates, re-measures the daemon-registered runsc, signs with the key at
`KAAS_RUNNER_ATTESTATION_KEY_FILE` (default `/run/kaas/attestation.key`, never generated) and submits to
`POST /internal/v1/sandbox-attestations`, on `KAAS_RUNNER_ATTESTATION_REFRESH_INTERVAL` (default 20 h;
validated shorter than the 24 h maximum age). The **signature, not the submitter, is the authority**: the
control plane stores a document only if it verifies against an operator-pinned key and could authorize now, and
re-verifies it on every use. An authorization for a worker uses that worker's own latest evidence and never
another's. A runtime replaced between refreshes is noticed at the next maintenance tick (15 s): the runner
becomes NOT READY at once and re-measures; the control plane refuses the new binary unless an operator accepted
it.

### 5. Refreshable service identity from the existing issuer

The runner obtains short-lived JWTs from the platform's existing issuer — OAuth 2.0 client credentials with the
secret in a file, or a token file a host agent maintains — and replaces each at 70 % of its life or 45 s before expiry, whichever
comes first, so it is never without a presentable one (a gap for short-lived tokens was found by the gate). A token for
another subject, an expired one, or none at all makes the runner NOT READY; nothing is ever sent
unauthenticated. The proxy has its own identity (`kaas.egress-proxy`) and the runner's credential never enters a
proxy container. No new identity provider. No token in a sandbox, database, queue or log.

### 6. The application does not migrate in production

`kaas-api migrate` (`DatabaseMigrator`, a plain `main`) runs Flyway only, with the migrator's credential, and
exits 0/1/2. The production profile sets `spring.flyway.enabled=false` as a literal; `SchemaGuard`, ordered
before JPA, refuses to start against a schema that is behind (`SCHEMA_NOT_CURRENT`) or whose history differs.
`infrastructure/database/roles.sql` creates `kaas_migrator` (owns the schema, only DDL authority) and `kaas_app`
(rows only; no DDL, no TRUNCATE, cannot write Flyway history) — proved against PostgreSQL.

### 7. Five images, one revision, handed off by digest

Repository reality is **five** deployable images, not the four the Ops assessment listed: `api` (also the
migrator and deploy check), `runner`, `karate-engine`, `egress-proxy`, and `security-probe` — the attestation
refresh runs the probe on the execution host. Each carries `org.opencontainers.image.revision`. The release
manifest (`packages/api-contracts/release-manifest.schema.json`) names the revision and every image by registry
digest; tags are refused, as are missing, unknown or duplicated components; the verifier pulls each image by
digest and requires its label to equal the revision. GitHub builds, tests and publishes; GitLab orchestrates
deployment and is not called from this repository.

### 8. Broker loss is measured, not recovered — and redrive is deliberately NOT implemented here

Once the relay records a dispatch as published, that publication is final. If RabbitMQ loses the message:

```
published ─▶ lost ─▶ not rebuilt ─▶ run stays QUEUED ─▶ QUEUE_DEADLINE ─▶ TIMED_OUT / QUEUE_DEADLINE
```

The property proved (`BrokerLossMeasurementTests`) is **loss is detected through the queue deadline and fails
closed**. It is **not** recovery: the tenant's run is lost. The gate requires `rabbitmq_loss_recovery=false`.

Redrive is not implemented because a correct one touches outbox uniqueness, dispatch identity, the schema, its
triggers, the messaging contract, reconciliation, duplicate handling, claim semantics and the queue deadline —
too much to hide inside this slice. **Follow-up KAAS-MSG-001 — durable dispatch reconstruction after broker
loss:** decide how PostgreSQL's authoritative state can safely reconstruct *unclaimed* lost dispatches.

**Operations consequence:** the assessment's "RabbitMQ needs no backup if application state can reconstruct
lost broker work" is currently **false**. Until KAAS-MSG-001 closes, do not treat broker state as disposable.
This ADR does not choose a backup strategy.

## Consequences

- Deployment is blocked by the messaging gap and by nothing else the application owns.
- The execution host's firewall needs WireGuard to the internal API and nothing towards RabbitMQ.
- **The internal API must be served over TLS on the WireGuard path.** ADR-034 recorded Operations' plan of
  `http://10.77.0.1:8443`; KAAS-22 made the runner refuse secret redemption over anything but https or
  loopback, and the runner now refuses such a URL at startup. WireGuard is not treated as a substitute. This is
  an infrastructure prerequisite, not an application change.
- A worker's claim writes the same `kaas.dispatch-consumer` actor on the lifecycle event it always did; the
  worker is recorded in `assigned_worker_id`. Worker presence (`worker_presence`) is operational only and
  authorizes nothing.
- The proxy holds the token it started with for its execution; the proxy identity's token lifetime must exceed
  the longest execution plus revalidation, or revalidation fails closed.
- Branch protection is not asserted: CI's eleven jobs exist and are non-skippable; whether merging requires
  them is repository administration this slice could not inspect.
