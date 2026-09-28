# KAAS-DEPLOY-001 — Deployment readiness: slice report

Production runner composition, deployment-ready application entrypoints, runner health and reconciliation,
runtime attestation refresh, refreshable service identity, the migrate-only entrypoint, deployment metrics, and
the immutable image/release handoff. Decided by
[ADR-035](docs/adr/035-deployment-readiness-runner-claim-intake.md).

## FINAL VERDICT

**DEPLOYMENT READINESS BLOCKED BY MESSAGING RECOVERY GAP**

Everything the application owns for deployment exists and is gated in CI, except one thing that was
deliberately measured instead of built: a dispatch RabbitMQ loses after publication is **not rebuilt**. The run
ends `TIMED_OUT / QUEUE_DEADLINE` — fail-closed and visible, and lost. That is the expected, honest verdict
under the user's Decision B, and it is not downgraded because every CI job is green: the deployment-readiness
gate passes *because* it proves the blocker exactly (`rabbitmq_loss_recovery=false`).

STOP here. The next decision — (A) application-side durable dispatch reconstruction, KAAS-MSG-001, or (B) a
change to the Operations durability/backup assumption — is to be reviewed separately. KAAS-23 is not started.

## 1. Recovery of the interrupted session

| | |
|---|---|
| Branch | `codex/project-feature-control-plane` |
| HEAD at takeover | `52d4599b7d88d93205574618d3117210ca9d300c` = upstream; 0 ahead / 0 behind |
| Stash, worktrees, rebase/merge state, locks | none |
| Crashed session's work | uncommitted: 9 modified and 19 untracked files, **all on the API side** |
| Runner side | untouched: `RunnerApplication.main` still printed a banner |

What the previous session had done, and what became of it:

| Previous work | Kept / corrected |
|---|---|
| Consumer admits (`RunClaimService.admit`, `DELIVERED`) instead of claiming for one fixed worker id | **Kept.** Matches Decision A. |
| `WorkerAssignmentService` + repository in `controlplane` | **Moved to `consumer`**: the control plane depended on the consumer package, which the architecture rule forbids (the rule caught it). Worker namespace moved to `shared.WorkerIdentity`. |
| One endpoint that long-polled **and then claimed** | **Split** into `…/assignments/waits` (owns nothing) and `…/assignments` (claims at once). A runner abandoning a combined request at SIGTERM could not stop the server creating ownership afterwards (requirement 34). Body fields other than those named are refused, so `workerId` cannot be smuggled. |
| Runner-submitted attestations, per-worker evidence, V14 | **Kept**; tests added. |
| `DatabaseMigrator`, `SchemaGuard`, `roles.sql`, `afterMigrate.sql`, production profile | **Kept**, with two defects fixed: JPA validated the schema **before** `SchemaGuard` (a behind schema failed as "missing table" rather than `SCHEMA_NOT_CURRENT`) — now ordered; and the production management port served `/actuator/deployment` and `/actuator/prometheus` as **401** because the tenant chain's `denyAll` applied — a management-port chain now permits exactly health, deployment and prometheus there. |
| `DeploymentStatusService`, `DeploymentCheck`, secret-provider health | **Kept**; tests added. |
| Stale tests (six inbox tests, one architecture rule) | Updated to the delivered-then-claimed contract. |

No RabbitMQ path had been added to the runner, so none had to be removed.

## 2. Decisions applied (not re-asked)

- **A — work intake:** the runner claims from the API. RabbitMQ stays on the control plane; the execution host
  has no broker route, credential, client, TLS material or topology.
- **B — broker loss:** measured and reported as the blocker; no redrive, no schema change, no second outbox
  dispatch, no republish reconciler, no generation counter, no new broker contract.

## 3. What was built

### Control plane
- `POST /internal/v1/assignments` (claim) and `/assignments/waits` (bounded long poll ≤ 20 s), through the
  unchanged `RunClaimService`: QUEUED→CLAIMED, attempt, epoch, lease, CAS, stale-dispatch rejection,
  queue-deadline refusal. Worker = token subject (`kaas.worker.*`). Response: `runId`, `attemptId`,
  `assignmentEpoch` only. Not in the public OpenAPI.
- `POST /internal/v1/sandbox-attestations`: stored only if a pinned key verifies it and it could authorize now;
  re-verified on every use; a worker is judged by its own evidence only.
- `kaas-api migrate` / production never migrates / `SchemaGuard` before JPA / `kaas_migrator` vs `kaas_app`.
- `/actuator/deployment`, `/actuator/prometheus`, `secrets` health group, on a private management port.

### Runner
- `RunnerComposition` (the only composition) and `RunnerApplication.main`.
- Typed, validated configuration (`RunnerConfiguration`), every problem by name, never a value.
- `RuntimePreflight`: Docker, the **configured** runtime (no fallback), path cross-check, measurement, images.
- `AttestationRefresher`: real gates, fresh runsc measurement, key read per refresh, submit; 20 h / 24 h.
- `ServiceIdentity`: client credentials or token file from the existing issuer; refresh at 70 % or 45 s before
  expiry, whichever is sooner; subject bound.
- `RunnerDaemon`: startup order, readiness vs liveness, intake loop (claim → wait → claim), slots, back-off,
  periodic reconciliation, runtime-drift tick, drain-safe shutdown, own-generation cleanup.
- `HealthServer`: `/health/liveness`, `/health/readiness`, `/metrics` (closed-vocabulary labels).
- Build guard: no RabbitMQ/AMQP client may reach the runner classpath.

### Release
- API and runner Dockerfiles; five images; OCI revision label; digest-only manifest schema + verifier;
  `infrastructure/release/build-release.sh`; manual `release-images.yml`.

## 4. Evidence

Local verification (macOS, Docker Desktop, no runsc):

| Suite | Result |
|---|---|
| `./gradlew clean check` (the backend job) | BUILD SUCCESSFUL |
| `:apps:api:deploymentReadinessTest` | WorkerClaimEndpointTests 12, BrokerLossMeasurementTests 2, AttestationSubmissionTests 8, MigrationModeTests 8, DeploymentStatusTests 5 — all pass |
| runner `com.kaas.runner.daemon.*` + `RunnerApplicationTest` | 18 + 3 + 4 + 5 + 4 + 2 — all pass |
| `npm run validate:schemas`, `verify:release-manifest` | pass |
| API and runner images | built; entrypoints and exit codes checked; `jdk.httpserver` present; no broker jar |

**CI-only** (needs runsc, which Docker Desktop cannot host — not faked): `DeploymentPipelineTests` (the
production composition claiming through the API and running Karate under runsc), the release rehearsal against a
registry, the shipped-migrator run, and the leak check. See §6.

### Broker loss (measured against a real RabbitMQ)

```
published_before_loss=true
broker_depth_before_loss=1
broker_depth_after_loss=0
republished_after_loss=0
claims_after_loss=0
terminal_outcome=TIMED_OUT/QUEUE_DEADLINE
queue_deadline_fail_closed=true
rabbitmq_loss_recovery=false
follow_up=KAAS-MSG-001
```

Anti-vacuity: the dispatch is confirmed and recorded as published, the broker is observed holding it, and only
then is it destroyed; a control run through the identical path with the message intact is delivered and claimed.

### Claim anti-vacuity
Runner A claims run X; runner B then gets 204 and X stays A's. Twelve concurrent claimants on one run: exactly one
200, eleven 204, one version bump, one event. B asking in A's name (`{"workerId": A}`) is refused 400 and the run
is untouched; tenant tokens, `kaas.scheduler`, `kaas.egress-proxy`, hybrid and expired tokens are refused.

### Production-composition anti-vacuity
`ProductionRunnerCompositionTests` builds the real parts and shows the composed executor is the execution loop
(its first act is an execution-authorization request under the runner's credential); replacing it with a no-op
fails there (mutant D01). Replacing the claim poller with nothing fails the lifecycle suite (D02).
`DeploymentPipelineTests` runs a tenant's run to COMPLETED/PASSED through RabbitMQ, the API consumer, the
runner's claim and Karate under runsc with no test code between stages.

## 5. Mutation battery

`tests/mutation/deployment_mutants.py`: each mutant is one exact change to production code, build or configuration,
run against only the tests that must kill it, and required to fail a **named** test (read from JUnit XML)
before the file is restored byte for byte. 34 run locally; 2 need a registry and are killed by the
deployment-readiness job. **All 36 die.** Two exposed real gaps first: D24 survived the first run (nothing
refused a manifest image with neither tag nor digest), and D19b was added after CI found the credential gap it
reinstates (§6).

| ID | Mutant | Result | Killed by |
|---|---|---|---|
| D01 | production composition omits the ExecutionLoop | KILLED | ProductionRunnerCompositionTests.theComposedExecutorIsTheExecutionLoopAndItRevalidatesAuthorityFirst |
| D02 | claim poller never starts | KILLED | RunnerDaemonLifecycleTests.withoutAValidCredentialTheRunnerIsNotReadyClaimsNothingAndSendsNothingUnauthenticated |
| D03 | runner reports READY without runsc | KILLED | RuntimePreflightTests.aRegistrationAtAPathOtherThanTheConfiguredOneIsNotReady |
| D04 | runner falls back to runc when runsc is absent | KILLED | RuntimePreflightTests.aRunnerConfiguredForGvisorIsReadyOnlyUnderRunscAndNeverFallsBackToRunc |
| D05 | startup reconciliation removed | KILLED | RunnerDaemonLifecycleTests.readyOnlyAfterEveryStartupStepAndInTheContractedOrder |
| D06 | reconciler deletes resources it does not own | KILLED | OrphanReconciliationTests.aSandboxPastItsDeadlineIsReclaimedWhicheverLauncherLeftIt |
| D07 | periodic reconciliation removed | KILLED | RunnerDaemonLifecycleTests.reconciliationKeepsRunningOnItsIntervalAfterStartup |
| D08 | shutdown still performs new claims | KILLED | RunnerDaemonLifecycleTests.shutdownAbandonsALongPollAtOnceAndClaimsNothingAfterItBegins |
| D09 | worker id accepted from the request body | KILLED | WorkerClaimEndpointTests.aWorkerIdInTheBodyIsRefusedAndCannotChooseWhoseNameTheClaimIsWrittenIn |
| D10 | a claim reports success for a run somebody already owns | KILLED | WorkerClaimEndpointTests.underContentionOneRunHasExactlyOneOwner |
| D11 | claim bypasses RunClaimService | KILLED | WorkerClaimEndpointTests.underContentionOneRunHasExactlyOneOwner |
| D12 | claim loses the assignment epoch | KILLED | WorkerClaimEndpointTests.aWorkerClaimsDeliveredWorkInItsOwnNameAndLearnsOnlyWhatItNeedsToBegin |
| D13 | runner acquires a RabbitMQ client | KILLED | > Forbidden launcher dependencies found in services/runner: [com.rabbitmq:amqp-client] |
| D14 | migrator starts the full API | KILLED | MigrationModeTests.theMigratorIsFlywayAndNothingElse |
| D15 | API production startup auto-runs Flyway | KILLED | MigrationModeTests.inProductionTheApplicationDoesNotMigrateAndRefusesASchemaThatIsBehind |
| D16 | attestation refresh removed | KILLED | RunnerDaemonLifecycleTests.evidenceIsReMeasuredAndResubmittedOnItsIntervalWithoutARestart |
| D17 | expired attestation accepted | KILLED | AttestationSubmissionTests.expiredEvidenceIsRefusedRatherThanStored |
| D18 | runtime change not re-measured | KILLED | RunnerDaemonLifecycleTests.aRuntimeReplacedUnderAcceptedEvidenceIsNoticedAtTheNextTickAndReMeasured |
| D19 | service token never refreshes | KILLED | RunnerDaemonLifecycleTests.anExpiredCredentialIsNeverPresentedAndAnExpiringOneIsReplacedBeforeItLapses |
| D19b | a short-lived token is replaced only after it stops being presentable | KILLED | RunnerDaemonLifecycleTests.aShortLivedCredentialIsReplacedBeforeItStopsBeingPresentableLeavingNoGap — fails at t=30s against the old schedule |
| D20 | expired service token accepted | KILLED | RunnerDaemonLifecycleTests.anExpiredCredentialIsNeverPresentedAndAnExpiringOneIsReplacedBeforeItLapses |
| D21 | engine image tag accepted | KILLED | RunnerConfigurationTests.everyUnsafeOrMalformedValueIsRefusedByName |
| D22 | proxy image tag accepted | KILLED | RunnerConfigurationTests.allowlistEgressNeedsItsOwnPinnedProxyAndItsOwnIdentity |
| D23 | revision label missing | KILLED in CI | deployment-readiness *Build, push and verify a release manifest* — `--check-labels` refuses an image whose label is absent (rehearsed locally: `release_manifest=VALID` only with labels) |
| D24 | malformed release manifest accepted | KILLED (after fix) | validate-schemas: `invalid-digestless-image.json` — **survived the first run**; the fixture was missing and now exists |
| D25 | runner ready before reconciliation | KILLED | RunnerDaemonLifecycleTests.readyOnlyAfterEveryStartupStepAndInTheContractedOrder |
| D26 | broker loss reported as recovered | KILLED | rabbitmq_loss_recovery: ['true'] (expected exactly false) |
| D27 | queue-deadline terminal transition removed | KILLED | BrokerLossMeasurementTests.aPublishedDispatchTheBrokerLosesIsNeverRebuiltAndTheRunEndsAtItsQueueDeadline |
| D28 | active resource reaped | KILLED | OrphanReconciliationTests.aRunningSandboxFromAnotherLiveLauncherIsNeverReclaimed |
| D29 | startup orphan survives | KILLED | OrphanReconciliationTests.aSandboxPastItsDeadlineIsReclaimedWhicheverLauncherLeftIt |
| D30 | a run identity becomes a metric label | KILLED | RunnerDaemonLifecycleTests.aClaimedAssignmentIsExecutedInTheRunnersOwnName |
| D31 | deployment check passes without a runner | KILLED | DeploymentStatusTests.withNoRunnerThePlatformIsNotReadyEvenThoughEverythingElseIsUp |
| D32 | image revision mismatch accepted | KILLED in CI | deployment-readiness *Confirm the verifier refuses what it must* — relabelled image refused (rehearsed locally against a registry) |
| D33 | runner background work survives shutdown | KILLED | RunnerDaemonLifecycleTests.nothingTheRunnerStartedOutlivesItsShutdown |
| D34 | long poll prevents timely shutdown | KILLED | RunnerDaemonLifecycleTests.shutdownAbandonsALongPollAtOnceAndClaimsNothingAfterItBegins |
| D35 | runner requires a RabbitMQ credential | KILLED | RunnerConfigurationTests.aMinimalProductionConfigurationHasSafeDefaultsAndNoTopology |

Mutants needing runsc beyond these (the composition claiming and executing under runsc) are covered by
`DeploymentPipelineTests` in CI; D01 and D02 are additionally killed locally, so the gate does not depend on the
runsc job alone to notice a missing execution loop or intake.

## 6. CI

Final run: [36456190987](https://github.com/yannisyoussef/kaas/actions/runs/36456190987) on `cdac6aa6680ff5e2c6ded3301a8c73676629605e` — **11/11 jobs success**.

| Job | Result |
|---|---|
| backend | success |
| hostile-execution-gate | success |
| synthetic-execution-pipeline | success |
| execution-egress-gate | success |
| karate-execution-gate | success |
| secret-execution-gate | success |
| strong-runtime-gate | success |
| **deployment-readiness** (new) | success |
| web | success |
| contracts | success |
| infrastructure | success |

No existing gate was weakened: none of the ten existing jobs' steps, suites or counts changed; `contracts` and
`infrastructure` each gained one step.

**deployment-readiness evidence (run 36456190987):**

| Evidence | Result |
|---|---|
| Control-plane suites | WorkerClaimEndpointTests 12, BrokerLossMeasurementTests 2, AttestationSubmissionTests 8, MigrationModeTests 8, DeploymentStatusTests 5 — executed, 0 skipped/failed |
| Runner suites | RunnerDaemonLifecycleTests 18, ProductionRunnerCompositionTests 3, RuntimePreflightTests 4 (runsc branch), RunnerConfigurationTests 5, ServiceIdentityClientCredentialsTests 4, RunnerApplicationTest 2; broker-dependency guard passed |
| Production composition under runsc | DeploymentPipelineTests 5/5: `deploy_check_without_runner=NOT_READY`, `runner_composition=RunnerComposition.compose`, `runner_ready=true`, `attestation_submitted_by_runner=true`, `attestation_runtime_implementation=sha256:048b89aa…074c` (the pinned runsc), `deploy_check_with_runner=READY`, `claim_path=RABBITMQ_TO_API_CONSUMER_TO_RUNNER_CLAIM`, `inbox_disposition=DELIVERED`, `assigned_worker=kaas.worker.deployment-pipeline`, `run_outcome=COMPLETED/PASSED`, `sandbox_runtime=runsc`, `service_tokens_issued=2` (60 s tokens), `runner_containers_after_shutdown=0` |
| Claim evidence | one owner under 12 concurrent claimants; B cannot claim A's run; `workerId` in body refused; non-worker, tenant, hybrid, expired tokens refused |
| Migrator evidence | shipped image: `migration=CURRENT applied=14 version=14`, then `applied=0`; one output line, no URL or password; application role `migration=FAILED error=FlywaySqlScriptException sqlState=42501` |
| Attestation-refresh evidence | runner-produced evidence accepted and naming this host's runsc; periodic re-measure/re-submit and runtime-drift NOT READY proven in the lifecycle suite |
| Token-refresh evidence | 60 s tokens replaced without a gap while READY throughout (`service_tokens_issued=2`) |
| Image / manifest evidence | five images pushed to a job-local registry and re-verified by digest: `release_manifest=VALID`; refused: relabelled, tagged, missing |
| Broker-loss evidence | `rabbitmq_loss_recovery=false`, `queue_deadline_fail_closed=true`, `terminal_outcome=TIMED_OUT/QUEUE_DEADLINE`, `republished_after_loss=0`, `claims_after_loss=0` |
| Leak evidence | `containers=0 networks=0 runsc_processes=0` |
| Verdict printed | `verdict=DEPLOYMENT READINESS BLOCKED BY MESSAGING RECOVERY GAP` |

Release manifest from that run (job-local registry, so the repository prefix is `localhost:5000/kaas`):

```json
{
  "schemaVersion": "kaas.release-manifest.v1",
  "revision": "cdac6aa6680ff5e2c6ded3301a8c73676629605e",
  "images": {
    "api": "localhost:5000/kaas/kaas-api@sha256:c43cbc7225a9d7f2304963e1bda7bceaa25f31e92dc689494e6b0af300e46e6f",
    "runner": "localhost:5000/kaas/kaas-runner@sha256:ff7f5e0b76894f4e40c61c5b4a433c0add4c0b46acd465458a63bd4163ccf5d8",
    "karate-engine": "localhost:5000/kaas/kaas-karate-engine@sha256:5e76c8fb96cfee77e10aca3b6ccb3167516c99e11aacda66ac181189422d646b",
    "egress-proxy": "localhost:5000/kaas/kaas-egress-proxy@sha256:55998fb30d739cdbb7dbff890260d9da436cf6c7b33c2ba1602f9b45d5512718",
    "security-probe": "localhost:5000/kaas/kaas-security-probe@sha256:25ab4a044d2a345490e1ac5191aa3790e1f45232438d67c4b6aafc9c46709a7e"
  }
}
```

These digests are CI rehearsal evidence, not a release: nothing was published to GHCR.

### What the CI iterations found

| Run | Result | Finding |
|---|---|---|
| [36452171650](https://github.com/yannisyoussef/kaas/actions/runs/36452171650) | 10/11 | The deployed path passed end to end; a test assumed a token refresh had already been due. Test fixed to wait for one. |
| [36453475171](https://github.com/yannisyoussef/kaas/actions/runs/36453475171) | 10/11 | **A real defect**: waiting for that refresh, the runner went NOT READY — a 60 s token stopped being presentable at 30 s and was replaced only at 42 s. Fixed in `ServiceIdentity.refreshPoint`; unit test walks every second; mutant D19b. |
| [36454998398](https://github.com/yannisyoussef/kaas/actions/runs/36454998398) | 10/11 | Everything through the release rehearsal passed; the migrator step raced PostgreSQL's init-time restart. CI wait fixed. |
| [36456190987](https://github.com/yannisyoussef/kaas/actions/runs/36456190987) | **11/11** | — |

Defects found and fixed locally before CI, beyond those in §1: an API-outage reconnect that probed on every
maintenance tick as well as on back-off (a request stream from every runner during an outage); the migrator
printing Flyway's narration, including the JDBC URL, from the shipped image; and the manifest fixture gap D24.

**Branch protection** is not asserted: the eleven jobs exist and none is skippable; whether merging requires
them is repository administration that was not inspected.

## 7. Ops handoff

| | |
|---|---|
| Application SHA | `cdac6aa6680ff5e2c6ded3301a8c73676629605e` (implementation; this report is a documentation commit on top) |
| CI run | [36456190987](https://github.com/yannisyoussef/kaas/actions/runs/36456190987) — all 11 jobs success |
| Images / digests | contract in [image-promotion.md](docs/deployment/image-promotion.md); rehearsed against a job-local registry in CI; **not published to GHCR** by this slice (the release workflow is manual and was not dispatched) |
| Runner config contract | [application-contract.md § Runner](docs/deployment/application-contract.md#runner) |
| Internal claim API | [application-contract.md § Internal claim API](docs/deployment/application-contract.md#internal-claim-api-runner-facing) |
| Health | API `:8081/actuator/health{,/liveness,/readiness}`; runner `:9090/health/{liveness,readiness}` |
| Metrics | API `:8081/actuator/prometheus`; runner `:9090/metrics` |
| Migrator | `kaas-api migrate` with `KAAS_MIGRATOR_DATABASE_*`; roles from `infrastructure/database/roles.sql` |
| runsc | registered as `runsc` at an absolute path, bind-mounted read-only at the same path into the runner; its digest in `KAAS_EXECUTION_ATTESTATION_RUNTIME_IMPLEMENTATIONS` |
| Attestation key | `/run/kaas/attestation.key` (read-only); public key pinned in `KAAS_EXECUTION_ATTESTATION_TRUSTED_KEYS` |
| Service identity | a `kaas.worker.<name>` client (or token file) at the existing issuer; the proxy's own `kaas.egress-proxy` client if allowlisting |
| RabbitMQ on the execution host | **none needed — no route, no credential** |
| Broker-loss caveat | **no recovery guarantee; RabbitMQ state is not disposable** until KAAS-MSG-001 |
| Synthetic check | `kaas-api deploy-check --api … --runner …` → exit 0 |
| Remaining infrastructure prerequisites | TLS on the internal API over WireGuard (the runner refuses plain http off-loopback — secrets cross it; ADR-034 had recorded `http://10.77.0.1:8443`); runsc install and accept-list; attestation key provisioning and pinning; issuer clients; Docker socket group; stop grace ≥ `KAAS_RUNNER_SHUTDOWN_TIMEOUT` + 60 s; image placement on the execution host; private binding of both management ports; GitLab consuming the manifest |

## 8. Findings for Operations beyond the blocker

1. **Five images, not four** — the attestation refresh runs the security probe on the execution host.
2. **TLS on the internal path** — see §7.
3. **runsc upgrades are accept-list changes** — until the new digest is accepted the runner is NOT READY.
4. **Proxy token lifetime** must exceed the longest execution plus revalidation.
5. **Branch protection** is not asserted: CI's eleven jobs exist and are non-skippable; whether merges require
   them was not inspectable.

## 9. Residual risks

- Broker loss (the verdict).
- The long poll holds a servlet thread per waiting runner (≤ 20 s); fine for a runner fleet, not a tenant surface.
- A claim whose response is lost in transit leaves a run leased to a runner that never heard of it; it is fenced
  at lease expiry and fails `LEASE_LOST`. Retrying blindly would risk a second claim instead.
- Worker presence and the deployment status count evidence rows by assessment time; they do not re-verify the
  documents (authorization does).
