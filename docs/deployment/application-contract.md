# KaaS application deployment contract

What the application needs from Operations, and what it provides back (KAAS-DEPLOY-001,
[ADR-035](../adr/035-deployment-readiness-runner-claim-intake.md)). The application provisions none of it.

> **RabbitMQ state is not authoritative (KAAS-MSG-001).** A dispatch the broker loses after publication, before
> the API consumer records it, is reconstructed from PostgreSQL and republished — the same dispatch — within the
> bounds in [§ RabbitMQ loss](#rabbitmq-loss). (KAAS-DEPLOY-001 measured this as a blocker; ADR-036 closed it.)

## Hosts

### Application host (control plane)

Needs: the API image; PostgreSQL (dedicated); RabbitMQ (dedicated); Vault Transit (existing); the OIDC issuer
(existing); the internal API reachable from the execution host over WireGuard, **served over TLS**
(see [§ TLS on the internal path](#tls-on-the-internal-path)).

### Execution host (runner)

**NEEDS:**

- WireGuard route to the **internal API** — and to the **token endpoint** of the issuer, if the runner uses
  client credentials (or a host agent that writes the token file);
- Docker, with **runsc registered** under the runtime name `runsc` at an **absolute path**;
- that runsc binary **bind-mounted read-only into the runner container at the same absolute path**, so the
  runner measures the binary the daemon runs;
- the Docker socket mounted into the runner container, with the socket's group added (`--group-add`);
- the release images present locally by digest: `runner`, `karate-engine`, `security-probe`, and
  `egress-proxy` if allowlist egress is enabled (the runner does not pull);
- the attestation signing key at `/run/kaas/attestation.key` (read-only mount), its public key pinned in the
  control plane;
- the runner's service credential (client secret file or token file) and its configuration below.

**DOES NOT NEED:**

- **any route to RabbitMQ;**
- **any RabbitMQ credential, AMQP client, broker TLS material or topology;**
- any route or credential for Vault or PostgreSQL.

Firewall accordingly: the execution host's egress is WireGuard → internal API (and token endpoint), plus
whatever allowlisted tenant destinations the egress networks carry.

## Deploy order

```
1. kaas-api migrate            (migrator credential)          exit 0 required
2. kaas-api serve              (application credential)       refuses to start if a migration is pending
3. runner                      (execution host)
4. kaas-api deploy-check --api http://<api-mgmt>:8081 --runner http://<runner-health>:9090   exit 0 required
```

## Database

Run once per database, as its owner, before the first migration:

```
psql "<admin connection>" -v ON_ERROR_STOP=1 \
     -v migrator_password="$KAAS_MIGRATOR_DATABASE_PASSWORD" -v app_password="$KAAS_DATABASE_PASSWORD" \
     -f infrastructure/database/roles.sql
```

| Role | May | May not |
|---|---|---|
| `kaas_migrator` | own the schema; run every migration | — |
| `kaas_app` | read and write rows; read Flyway history | create/alter/drop anything, truncate, write Flyway history |

## API (`kaas-api`)

| Mode | Command | Exit |
|---|---|---|
| serve | `kaas-api serve` (default) | runs until stopped |
| migrate | `kaas-api migrate` | 0 schema current (prints `migration=CURRENT applied=<n> version=<v>`); 1 failed; 2 configuration invalid |
| deploy check | `kaas-api deploy-check --api URL [--runner URL ...] [--timeout PT120S]` | 0 ready; 1 not ready by the timeout; 2 usage |

`SPRING_PROFILES_ACTIVE=production` is required in production: startup migration is **off and not
re-enablable**, the dispatch consumer is on, management moves to its own port.

| Variable | Used by | |
|---|---|---|
| `KAAS_DATABASE_URL`, `KAAS_DATABASE_USERNAME`, `KAAS_DATABASE_PASSWORD` | serve | the `kaas_app` credential |
| `KAAS_MIGRATOR_DATABASE_URL` (falls back to `KAAS_DATABASE_URL`), `KAAS_MIGRATOR_DATABASE_USERNAME`, `KAAS_MIGRATOR_DATABASE_PASSWORD` or `KAAS_MIGRATOR_DATABASE_PASSWORD_FILE` | migrate | the `kaas_migrator` credential |
| `KAAS_OIDC_ISSUER_URI`, `KAAS_OIDC_JWK_SET_URI`, `KAAS_OIDC_AUDIENCE` | serve | the existing issuer; also verifies runner and proxy tokens |
| `KAAS_RABBIT_HOST`, `_PORT`, `_VHOST`, `_USERNAME`, `_PASSWORD`, `_SSL_ENABLED` | serve | the dedicated broker — application host only |
| `KAAS_VAULT_*` (five) | serve | ADR-034 |
| `KAAS_EXECUTION_ATTESTATION_TRUSTED_KEYS` | serve | `keyId=<SPKI base64>` of each runner's attestation key |
| `KAAS_EXECUTION_ATTESTATION_RUNTIME_SUBJECTS` | serve | each execution host's `KAAS_RUNNER_RUNTIME_SUBJECT` |
| `KAAS_EXECUTION_ATTESTATION_RUNTIME_IMPLEMENTATIONS` | serve | the sha256 of the runsc binary Operations installed |
| `KAAS_EXECUTION_SECURITY_PROFILE_VERSION` | serve | `kaas.sandbox.gvisor.v1` |
| `KAAS_EXECUTION_ATTESTATION_MAX_AGE` | serve | default `PT24H`; must equal the runner's |
| `KAAS_MANAGEMENT_PORT`, `KAAS_MANAGEMENT_ADDRESS` | serve | default `8081`, `0.0.0.0` — **bind privately** |

### API endpoints

| Port | Path | Auth | |
|---|---|---|---|
| 8080 | `/api/**` | tenant JWT | public API |
| 8080 | `/internal/v1/**` | service JWT | **never route from the public edge** |
| 8081 | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | none | probes; no details |
| 8081 | `/actuator/deployment` | none | `{"status":"READY"|"NOT_READY","database","schema","broker","runnersPolling","runnersWithCurrentEvidence","secretProvider"}` |
| 8081 | `/actuator/prometheus` | none | metrics |
| 8081 | anything else | — | refused |

The secret provider is reported in `/actuator/deployment` and the `secrets` health group, and is deliberately
**not** part of readiness or of `status`: a sealed Vault blocks only secret-bearing runs (ADR-034).

### Internal claim API (runner-facing)

| | |
|---|---|
| `POST /internal/v1/assignments/waits` body `{"waitMillis": 0..20000}` | 200 `{"available":true}` or 204. Claims nothing. |
| `POST /internal/v1/assignments` body `{}` | 200 `{"runId","attemptId","assignmentEpoch"}` or 204. Claims one delivered run for the caller. |
| `POST /internal/v1/sandbox-attestations` body = signed document | 201/200 `{"code":"ACCEPTED","attestationId","assessedAt","usableUntil"}`; 422 `{"code":<refusal>}` |

The caller is the JWT subject; it must be `kaas.worker.<name>`. Any body field other than those listed is
refused (`400 UNKNOWN_FIELD`). None of these is in the public OpenAPI.

## Runner

| Variable | Default | |
|---|---|---|
| `KAAS_RUNNER_WORKER_ID` | — | `kaas.worker.<name>`; must equal the credential's `sub` |
| `KAAS_RUNNER_API_URL` | — | internal API; **https** (or http to loopback only) |
| `KAAS_RUNNER_TOKEN_ENDPOINT`, `KAAS_RUNNER_CLIENT_ID`, `KAAS_RUNNER_CLIENT_SECRET_FILE`, `KAAS_RUNNER_TOKEN_AUDIENCE`, `KAAS_RUNNER_TOKEN_SCOPE` | — | client credentials against the existing issuer, **or** |
| `KAAS_RUNNER_TOKEN_FILE` | — | a JWT a host agent keeps fresh (re-read on every refresh) |
| `KAAS_RUNNER_DOCKER_HOST` | `unix:///var/run/docker.sock` | |
| `KAAS_RUNNER_SANDBOX_RUNTIME` | `gvisor` | `gvisor` in production; there is no fallback |
| `KAAS_RUNNER_RUNSC_PATH` | — | if set, the daemon's registration must equal it |
| `KAAS_RUNNER_ATTESTATION_KEY_ID` | — | the pinned key id |
| `KAAS_RUNNER_ATTESTATION_KEY_FILE` | `/run/kaas/attestation.key` | PKCS#8; missing ⇒ NOT READY; never generated |
| `KAAS_RUNNER_RUNTIME_SUBJECT` | — | this host's subject, accepted by the control plane |
| `KAAS_RUNNER_PROBE_IMAGE`, `KAAS_RUNNER_ENGINE_IMAGE` | — | `repo@sha256:…` from the release manifest; tags refused |
| `KAAS_RUNNER_EGRESS_ALLOWLIST_ENABLED` | `false` | if `true`, also: |
| `KAAS_RUNNER_EGRESS_PROXY_IMAGE`, `KAAS_RUNNER_EGRESS_CONTROL_PLANE_URL`, `KAAS_RUNNER_EGRESS_DNS_SERVER`, `KAAS_RUNNER_EGRESS_NETWORKS`, and the proxy's own credential `KAAS_RUNNER_EGRESS_TOKEN_ENDPOINT`/`_CLIENT_ID`/`_CLIENT_SECRET_FILE` or `KAAS_RUNNER_EGRESS_TOKEN_FILE` | — | the proxy identity is `kaas.egress-proxy`; its token lifetime must exceed the longest execution |
| `KAAS_RUNNER_MAX_CONCURRENCY` | `1` | 1–64 |
| `KAAS_RUNNER_CLAIM_WAIT` | `PT15S` | ≤ `PT20S` |
| `KAAS_RUNNER_CLAIM_BACKOFF_INITIAL`, `_MAX` | `PT1S`, `PT30S` | API-outage back-off, jittered |
| `KAAS_RUNNER_REQUEST_TIMEOUT` | `PT30S` | |
| `KAAS_RUNNER_RECONCILE_INTERVAL` | `PT5M` | |
| `KAAS_RUNNER_SANDBOX_WALL_CLOCK_TIMEOUT` | `PT1H` | reconciler's abandonment age |
| `KAAS_RUNNER_ATTESTATION_REFRESH_INTERVAL` | `PT20H` | must be shorter than the max age |
| `KAAS_RUNNER_ATTESTATION_RETRY_INTERVAL` | `PT10M` | |
| `KAAS_RUNNER_ATTESTATION_MAX_AGE` | `PT24H` | must equal the API's |
| `KAAS_RUNNER_SHUTDOWN_TIMEOUT` | `PT2M` | set the container stop grace period **≥ this + 60 s** |
| `KAAS_RUNNER_HEALTH_HOST`, `KAAS_RUNNER_HEALTH_PORT` | `127.0.0.1`, `9090` | bind privately |

Invalid configuration exits 2 before anything starts, printing each problem by variable name and never a value.

### Runner endpoints

| Path | |
|---|---|
| `GET /health/liveness` | 200 `UP` / 503 `DOWN` — process health only |
| `GET /health/readiness` | 200 `UP` / 503 `NOT_READY`, with per-condition reason codes |
| `GET /metrics` | Prometheus text; see [production-runner.md](../architecture/production-runner.md#metrics) |

## runsc

Installed, pinned and registered by Operations. The control plane accepts only the implementation digests in
`KAAS_EXECUTION_ATTESTATION_RUNTIME_IMPLEMENTATIONS`: **upgrading runsc is a change to that list**, made before
or with the upgrade; until then the runner is NOT READY (`RUNTIME_NOT_ACCEPTED`) and executes nothing — by design.

## TLS on the internal path

ADR-034 recorded the planned runner-to-API address as `http://10.77.0.1:8443`. The runner redeems tenant secrets
over this connection and — since KAAS-22 — refuses to do so over anything but https (or loopback); it now also
refuses such an API URL at startup. WireGuard is not treated as a substitute for TLS by the application.
**Serve the internal API over https on the WireGuard address** (a certificate the runner's JVM trusts). This is
an infrastructure prerequisite; the application does not relax the rule.

## RabbitMQ loss

KAAS-DEPLOY-001 measured that a published dispatch the broker lost was never rebuilt. KAAS-MSG-001
([ADR-036](../adr/036-postgres-authoritative-dispatch-reconstruction.md),
[dispatch-recovery.md](../architecture/dispatch-recovery.md)) reconstructs it.

| | |
|---|---|
| Guarantee | a dispatch that was **published** (broker-confirmed) and **never durably admitted** by the API consumer is republished from PostgreSQL — same message id, same bytes — while its run is `QUEUED`, unclaimed, not cancelled, and before its queue deadline |
| Recovery grace | `KAAS_DISPATCH_RECOVERY_GRACE`, default **30 s** after the last publication |
| Recovery cadence | a pass every `KAAS_DISPATCH_RECOVERY_INTERVAL` (**10 s**); republications spaced 30 s, 60 s, 120 s…, capped at `KAAS_DISPATCH_RECOVERY_MAX_INTERVAL` (**2 min**) |
| Recovery cap | at most `KAAS_DISPATCH_RECOVERY_MAX_PUBLICATIONS` (**3**) republications per dispatch |
| Terminal bound | the **queue deadline** (`KAAS_QUEUE_TIMEOUT`, default 5 min): a dispatch that cannot be delivered before it still ends `TIMED_OUT / QUEUE_DEADLINE`, fail-closed |
| Delivery semantics | at least once; a duplicate is decided once by the consumer inbox |
| Where it runs | in every API instance, production profile on by default (`KAAS_DISPATCH_RECOVERY_ENABLED`); `/actuator/deployment` shows `dispatchRecovery` and is NOT_READY if the loop stalls |
| Other tunables | `KAAS_DISPATCH_RECOVERY_INITIAL_DELAY` (15 s), `_BATCH_SIZE` (20), `_BASE_BACKOFF` (10 s) and `_MAX_BACKOFF` (2 min) after a failed republication |
| Start-up validation (when enabled) | grace < half the queue timeout; lease ≥ batch size × publisher confirm timeout (20 × 5 s); lease + grace < queue timeout. A violation stops the API at startup |
| Stall detection | `dispatchRecovery=STALLED` once no pass has completed for 3 × interval + lease + initial delay (**165 s** by default); a stalled instance is NOT_READY |
| Crash of an instance mid-recovery | its lease (`KAAS_DISPATCH_RECOVERY_CLAIM_TTL`, **2 min**; must satisfy lease + grace < queue timeout) expires and another instance continues |
| Requirement | `KAAS_CONSUMER_NAME` must be **one fixed value** for the life of the deployment — the delivered marker is keyed by it |

What is **not** claimed: recovery of a message the relay never published (the relay's own retries and
dead-letter policy own it), of work whose consumer never returns before the deadline, or of anything other than
execution dispatches.

**Operations impact:** application correctness no longer depends on RabbitMQ backup or restore for lost queued
execution dispatches; RabbitMQ is transport. Whether to back RabbitMQ up anyway — for faster recovery, or other reasons — is
an Operations decision the application does not make.

## Synthetic deployment check

`kaas-api deploy-check` exits 0 only when `/actuator/deployment` says `READY` — database up, schema current,
broker connection open, **at least one runner that asked for work in the last 90 s and holds evidence fresh
enough to be authorized**, and dispatch recovery not `STALLED` — and every `--runner` named answers
`/health/readiness` with 200. It runs no tenant
workload and writes nothing. It fails with no runner deployed.
