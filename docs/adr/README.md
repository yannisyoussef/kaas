# Architecture Decision Records

| ADR | Decision | Status |
|---|---|---|
| [001](001-monorepo.md) | Monorepo | IMPLEMENTED |
| [002](002-modular-monolith.md) | Modular control plane with a separate execution boundary | IMPLEMENTED |
| [003](003-java-spring-boot.md) | Java 25, Spring Boot 4.1.1, Gradle 9.7.1 | IMPLEMENTED |
| [004](004-postgresql.md) | PostgreSQL for control-plane persistence | IMPLEMENTED for Project/FeatureRevision and versioned configuration |
| [006](006-docker-runner.md) | Per-run container isolation candidate | SUPERSEDED by [ADR-022](022-hostile-execution-boundary-and-synthetic-probe.md) |
| [007](007-sse.md) | Bounded durable SSE replay for run events | PROPOSED |
| [009](009-state-machine.md) | Separate lifecycle, cancellation, outcomes, and quality evaluation | PROPOSED |
| [011](011-structured-results.md) | Structured execution evidence and artifact manifests | PROPOSED |
| [013](013-at-least-once-execution-protocol.md) | At-least-once protocol with outbox, inbox, and fencing | SUPERSEDED IN PART by [ADR-018](018-outbox-relay-and-rabbitmq-publication.md) and [ADR-021](021-durable-dispatch-consumption-fencing-and-worker-lease.md): outbox, publication, inbox, and fencing are all implemented |
| [014](014-project-feature-revision-slice.md) | Authenticated Projects and immutable FeatureRevisions | IMPLEMENTED |
| [015](015-versioned-execution-configuration.md) | Immutable Environment/RunProfile configuration and metadata-only SecretReferences | IMPLEMENTED |
| [016](016-test-run-intent-and-immutable-snapshot.md) | CREATED TestRun intent with a sealed immutable execution snapshot | IMPLEMENTED |
| [017](017-transactional-scheduling-and-outbox.md) | Transactional CREATED to QUEUED scheduling with execution attempt, queue-time dispatch intent, and outbox | IMPLEMENTED |
| [018](018-outbox-relay-and-rabbitmq-publication.md) | Outbox relay with at-least-once RabbitMQ publication, database-owned retry, and a production scheduling trigger | IMPLEMENTED |
| [019](019-tenant-admission-and-durable-scheduler-backoff.md) | Per-organization run admission, queued-run ceiling, durable scheduler backoff, and migration-upgrade testing | IMPLEMENTED |
| [020](020-early-terminal-lifecycle-and-queue-deadline-reaping.md) | Early run cancellation, queue-deadline reaping, dispatch suppression, and the scheduling-only guard rewrite | IMPLEMENTED |
| [021](021-durable-dispatch-consumption-fencing-and-worker-lease.md) | Durable dispatch consumption, consumer inbox, worker claim, assignment-epoch fencing, and lease recovery | IMPLEMENTED |
| [022](022-hostile-execution-boundary-and-synthetic-probe.md) | Hostile-execution trust boundary, hardened sandbox, trusted synthetic probe, and executable release gate | IMPLEMENTED |
| [023](023-execution-authorization-and-assignment-scoped-capabilities.md) | Execution authorization, assignment-scoped short-lived capabilities, platform-owned network policy, and an immutable command that nothing executes | IMPLEMENTED for authorization and command production; commands are executed as of ADR-024 |
| [024](024-synthetic-execution-lifecycle.md) | The four execution phases, bounded phase deadlines, orthogonal outcomes, result provenance, and a truthfully named synthetic engine | IMPLEMENTED; what executes is a platform-owned synthetic workload, not a test engine |
| [025](025-execution-egress-remains-deny-all.md) | Egress stays deny-all until it can be enforced, with the eight requirements an allowlist must satisfy | ACCEPTED; PARTIALLY SUPERSEDED by 026 |
| [026](026-enforceable-assignment-scoped-execution-egress.md) | Enforceable assignment-scoped egress through a purpose-built trusted proxy the sandbox cannot route around | ACCEPTED; allowlist enforceable for synthetic execution |
| [027](027-signed-runtime-security-attestations.md) | Sandbox security evidence is signed by the gate that observed it and verified against a pinned key, replacing a self-consistency digest an operator wrote | ACCEPTED; v3 required, v2 refused |
| [028](028-mediated-sandbox-runtime.md) | The sandbox runs under a mediating runtime with no fallback to the baseline, and the mandatory control set is scoped to the runtime that produced the evidence | ACCEPTED; v4 required, v3 refused; ADR-022 stays open |
| [022](022-hostile-execution-boundary-and-synthetic-probe.md) *(amended)* | The hostile-content runtime prerequisite is satisfied **for the mediated runtime**, permitting inert tenant-byte delivery only | AMENDED 2026-09-05; execution still not approved |
| [029](029-continuous-execution-authority.md) | The lease bounds how long a worker may keep executing, not only what it may write: revocation stops a running sandbox, and an unrenewable lease stops it fail-closed | ACCEPTED; ADR-022 stays open |
| [030](030-inert-tenant-source-delivery.md) | Tenant-authored bytes enter the sandbox as data — mounted read-only, hashed, compared — and are never parsed, executed or interpreted; the mediated mount does not carry `noexec`, and that gap is reported rather than downgraded | ACCEPTED; ADR-022 stays open |
| [031](031-sandbox-private-hardened-source-filesystem.md) | The source filesystem is a sandbox-private tmpfs a trusted bootstrap populates and then freezes, with no host mount of tenant source at all: `noexec` becomes real, and `nodev` remains unimplemented by the runtime and is reported as a gap | ACCEPTED; supersedes ADR-030's mechanism; ADR-022 stays open |

Deferred topics without active decisions remain: concrete object-storage/upload adapter, secret **delivery**
mechanism and a real secret provider, outbox and CREATED-run retention policy, self-service quarantine
recovery, OpenTelemetry implementation, worker heartbeating during execution, and test-engine integration.

Source capability issuance and the egress policy **model** are decided by ADR-023 and are no longer deferred —
what remains deferred for each is the part this platform cannot yet do: delivering a source bundle into a
sandbox, and enforcing any policy other than deny-all. Consumer inbox and worker claim/lease fencing are decided
by ADR-021, and the hostile-execution runtime by ADR-022, which names a stronger runtime (gVisor or a microVM)
as a prerequisite for admitting user content with its gate re-run against it as the acceptance criterion.
ADR-023 does not revisit and does not satisfy that prerequisite, and neither does ADR-024: the synthetic
workload it executes is repository-controlled content, so admitting *user* content still waits on the stronger
runtime ADR-022 names.

Egress allowlist **enforcement** is no longer deferred at all. ADR-025 recorded eight requirements and
refused until they were met; ADR-026 meets them and makes `ALLOWLIST` enforceable for trusted synthetic
execution. Two of ADR-025's eight were technically incorrect as written — an internal Docker network does
present a default route to a gateway that cannot forward, and a correct one-resolution-per-connection algorithm
cannot detect a DNS answer it never queried — and ADR-026 corrects both wordings while preserving the
properties they were reaching for. What remains deferred from that slice is IPv6 egress and tenant
self-service authorship of a policy.

The signed attestation ADR-026 recorded as its largest residual risk is no longer deferred. ADR-027 replaces
the self-consistency digest an operator wrote with an Ed25519 signature produced by the gate that made the
observations and verified against a deployment-pinned public key. The control plane holds verification
authority and structurally cannot hold signing authority. `kaas.sandbox-security-attestation.v3` is required
and v2 is refused outright — no migration window, and the v2 model is deleted rather than left dormant. What
it does **not** do is strengthen the sandbox boundary: a perfectly signed attestation describing a
shared-kernel container is a trustworthy statement about a boundary ADR-022 still does not approve for
hostile tenant code. Worker heartbeating during execution is a new
deferral created by ADR-024 and a prerequisite for any run longer than a lease.

ADR-028 changes the boundary ADR-027 signed evidence about. The sandbox runs under a mediating runtime —
gVisor — with **no fallback to the baseline**, and requested-versus-enforced is separately observable: the
daemon's reported runtime read back before the workload starts, and the guest kernel's own name observed from
inside. `kaas.sandbox-security-attestation.v4` is required and v3 is refused as v2 was. The mandatory control
set is now **scoped to the security profile version**, because the two runtimes do not make the same controls
observable: the mediating one cannot demonstrate `NO_NEW_PRIVILEGES` at all and gains
`HOST_KERNEL_SYSCALL_MEDIATION`, and `NO_SETUID_BINARIES` is required of both. It therefore carries one fewer
demonstrable mandatory control than the baseline, which is recorded as a finding rather than a footnote.
**ADR-022 stays open**: a stronger runtime starting is not the same as hostile tenant content being safe to
run, and tenant execution remains unavailable.

ADR-029 closes an authority-lifetime gap the earlier slices left open. Database fencing already stopped a
stale worker **writing**; nothing stopped it **running**. A workload already inside a sandbox continued after
cancellation, fencing or lease expiry until it finished on its own or hit the sandbox deadline — acceptable
for a workload this repository wrote, and not acceptable for hostile code. The heartbeat now returns the
decision the control plane had already computed and was discarding, together with the lease window in the
database's own clock; the runner converts that to a **monotonic** budget, stops promptly on a definitive
refusal, and stops fail-closed when the budget is exhausted. A transient outage inside the budget still does
not end a healthy run. **ADR-022 stays open**: bounding a stale worker's execution is a prerequisite for
hostile tenant code, not permission to run it.

**ADR-022 was amended rather than closed.** Its runtime prerequisite is satisfied by the mediated runtime, and
the amendment permits exactly one thing: a future slice may deliver *inert* tenant-authored source bytes into
the sandbox to be hashed and inspected. Execution of tenant content is not approved, and the original
rationale for refusing ordinary Docker is preserved unchanged — an ADR is decision history, not a description
of the present. Of its five prerequisites, four are satisfied or not applicable to byte delivery; the fifth
splits, with output handling satisfied and *input* handling being what the source-delivery slice must
establish.

ADR-030 delivers on the amendment above and stops exactly where it said it would. Tenant bytes are now
stored, transported, mounted, read, hashed and compared; nothing parses, executes, sources or interprets them,
and the bundle format cannot express a mode, a link or a device. **The mount requirement was not fully met and
was not downgraded**: `noexec` is not carried onto a gofer-backed bind under the mediating runtime, execution
is refused instead by the absence of an executable bit, and both facts are asserted in CI in the direction
each is actually true. **Tenant code execution remains NOT APPROVED**, blocked on that gap, on the KAAS-15
runtime-pin attestation gap, and on an adjudication ADR-030 does not perform.

ADR-031 closes the gap ADR-030 recorded, and only that gap. Tenant source now lives on a filesystem that
refuses to execute it — proven against a file that is genuinely executable and demonstrably runs on a
permissive mount in the same sandbox — rather than on one that would execute it if the file happened to be
marked executable. **ADR-030 is not rewritten**: its mechanism was implemented, its measurement was correct,
and its verdict was that the boundary was not closed. That remains the history.

What ADR-031 does not close: gVisor does not implement `MS_NODEV`, so the source filesystem carries no such
flag and a device node on it would behave as a device. Three other layers stand in its place and none of them
is the flag. **Tenant code execution remains NOT APPROVED**, blocked on that, on the KAAS-15 runtime-pin
attestation gap, and on an adjudication no slice has yet performed.

`IMPLEMENTED` means verified by repository code or tooling. `PROPOSED` means design intent only. `DEFERRED` means no decision is active and implementation must not assume one.
