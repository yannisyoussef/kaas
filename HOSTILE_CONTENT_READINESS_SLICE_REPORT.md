# Hostile-content readiness slice report

**Scope:** KAAS-17. An adjudication of whether ADR-022's hostile-content runtime prerequisite is satisfied
well enough to let a *future* slice place inert tenant-authored bytes inside the mediated sandbox.

**Starting commit:** `aca2dda4`, eight-of-eight green.

**Final verdict:** see §45. It is not stated early, because the point of the exercise was to let the evidence
decide rather than to assemble support for a conclusion.

---

## 1. Executive summary

ADR-022 refused user content because a `runc` container shares the host kernel. It named five prerequisites.
Four are satisfied or not applicable to byte delivery. The fifth — artifact and output handling for genuinely
untrusted content — had no slice of its own, and splits cleanly: what *leaves* the sandbox is handled well,
and what *enters* it is exactly what the next slice must establish.

Adjudicating it found one real gap, in the half that matters most for the next step: the output sanitiser was
claimed in a comment and verified by nothing. Removing it broke no test. That is now fixed and mutation-proven.

## 2. Starting state

`aca2dda4`, clean worktree, in sync with remote, eight required checks green. Confirmed with
`git status --short --branch` before anything was read.

## 3. Scope and non-goals

Nothing tenant-authored was delivered, redeemed, or executed. No Karate, no secret resolution, no object
storage, no SSE, no quality gates. Code changes were confined to what the adjudication itself uncovered.

## 4. ADR-022's original requirement

Five prerequisites, verbatim: a stronger kernel boundary with the gate re-run against it; source capability
issuance; secret capability issuance; an egress policy model replacing deny-all; and artifact and output
handling for genuinely untrusted content.

## 5. Current composed architecture

Documented in [docs/architecture/hostile-content-boundary.md](docs/architecture/hostile-content-boundary.md),
including the edges that do not exist yet.

## 6. Closure criteria

The prompt's ten thresholds, adjudicated in §7 and §§8–32 below. The decisive framing: this is about
**inert bytes that do not execute**, and a control adequate for that may be inadequate for execution.

## 7. ADR-022 requirement matrix

| # | Prerequisite | Status | Basis |
| --- | --- | --- | --- |
| 1 | Stronger kernel boundary, gate re-run against it | **SATISFIED** | ADR-028; same probe, same contract, mandatory CI job |
| 2 | Source capability issuance | **SATISFIED (model), unredeemed** | ADR-023; binds org, project, run, attempt, epoch, expiry; dies with its assignment |
| 3 | Secret capability issuance | **NOT APPLICABLE to byte delivery** | Modelled; no provider, no resolution. Inert bytes need none |
| 4 | Egress policy replacing deny-all | **SATISFIED** | ADR-026; refused rather than downgraded |
| 5 | Artifact/output handling for untrusted content | **SATISFIED for output; OPEN for input** | §§25–26 below; input is the next slice's work |

## 8. Strong-runtime evidence

The mediated runtime is exercised in a mandatory job that cannot be satisfied by skipping: no `if:`, no
`continue-on-error`, no filter, and a suite that fails rather than skips when the runtime is absent. Evidence
inspection requires both suites by name and at least seven executed tests with zero skips.

## 9. Runtime enforcement and no fallback

Two independent observations: the daemon's assigned runtime, read back **before** the workload starts, and
the guest kernel's own name observed from inside. There is no flag, catch block or convenience that runs
mediated-authorized work under the baseline.

**Gap found and closed.** The launcher's own last line of defence — refusing a request naming a profile it
does not hold — had **no test anywhere**. It is what stops a command selecting a different security posture,
and it is now covered in both the generic and the baseline-refusing-mediated-work shapes.

## 10. gVisor residual risk

Stated as reduction and remainder rather than as a product claim.

**Reduced:** direct host-kernel syscall surface. The workload's syscalls are served by a userspace kernel
which is itself seccomp-confined against the host.

**Remaining:** the sentry is a host process, not a virtual machine. A sentry compromise is host-adjacent
rather than impossible. Two additional interfaces exist inside the sandbox — `/dev/fuse` and `/dev/net/tun` —
both sentry-served and permitted only under this runtime.

**Accepted, for bytes that do not execute.** The thing being placed inside is inert; the only executing code
remains a probe this repository wrote.

## 11. Host-kernel exposure

Not eliminated. The sentry makes host syscalls on the workload's behalf, under its own seccomp policy. What
changes is that a workload's syscall no longer reaches the host kernel directly; what does not change is that
the host kernel is still ultimately underneath.

## 12. `NO_NEW_PRIVILEGES` analysis

Not observable under the mediated runtime — gVisor's guest emits no `NoNewPrivs` line. Reported
`UNSUPPORTED`, never converted to a pass. The control is still applied; what is missing is the observation.

## 13. `NO_SETUID_BINARIES` as a compensating control

`no_new_privs` blocks exactly two escalation mechanisms at `execve`. Each is closed independently **and
observably**:

| Path | Closed by | Observed |
| --- | --- | --- |
| setuid / setgid binary | `NO_SETUID_BINARIES` | yes, mandatory |
| file capabilities | `CAPABILITIES_DROPPED` — empty bounding set | yes, mandatory |

The setuid scan reads permission bits and **not** file-capability attributes. It does not need to: an empty
bounding set is a ceiling that file capabilities cannot raise, and that ceiling is separately observed. A
read-only root means nothing inside the sandbox can create the file that would defeat either.

Recorded as **joint coverage**: two controls covering what one unobservable control would have covered.
Judged sufficient for inert bytes. It generates a hard requirement on the next slice — see §43.

## 14. Runtime-scoped mandatory controls

Seventeen each, and deliberately not the same seventeen. The mediated runtime demonstrates one fewer legacy
control and proves a runtime-specific one the baseline cannot. Security strength is not the number of green
rows; forcing the two sets into one would mean either demanding evidence a runtime cannot produce or dropping
a control the other can prove.

## 15. Signed attestation composition

Verified against code and the existing suites: the runtime type and profile version are signed; a
self-contradictory document is refused as `RUNTIME_MISMATCH`; control coverage is exact per profile version;
subject and generation are verified; probe and proxy image digests are bound; v2 and v3 cannot downgrade;
unknown key, invalid signature and stale evidence all fail.

**Can baseline evidence authorize gVisor?** No — proven in both directions, including a CI negative that a
genuine mediated attestation does not authorize a baseline deployment.

**Can evidence for one gVisor release authorize an incompatible one?** **Yes, within the freshness window.**
See §16 — this is a real finding, not a rhetorical one.

## 16. Runtime pin and upgrade semantics

`RuntimeIdentity.generation` is a hash of the **daemon's** instance id. It survives a host restart and does
not change when the `runsc` binary is replaced. The attestation binds the runtime *family*, the daemon, and
the profile version — not the runtime binary digest, and not the emulated kernel version.

So an operator who upgrades the runtime without regenerating evidence keeps valid evidence describing the
previous release, bounded only by `kaas.execution.attestation-max-age` (24 hours by default).

This is not hypothetical drift: this repository has already measured a `runsc` release that worked against one
kernel and refused every network mode against a newer one. A release change can alter what the boundary
enforces.

**Accepted for inert bytes** — nothing hostile executes, and every control is still enforced at launch
whatever the evidence says — and **listed as invalidating readiness** in the readiness document, with the
operational rule that a pin change is not a dependency bump. It must be revisited before execution.

## 17. Continuous execution authority

Both axes hold and are separately evidenced: a stale worker cannot mutate state (database fencing), and cannot
continue computing (ADR-029). The review did not count either as the other.

## 18. Cancellation bound

Measured end to end at **10.2 seconds** for an hour-long workload. The pipeline test's bound is 18 seconds,
chosen because the abandoned-sandbox path measured 30.1 seconds — the bound is what separates "terminated"
from "ran out its own deadline".

## 19. Lease-loss bound

Derived, not asserted: one heartbeat interval (5s) plus one graceful stop window (5s) after a definitive
refusal; for an unreachable control plane, the remaining lease budget, which is the lease window less a
margin of one interval plus one renewal timeout. The initial budget before any renewal is 15 seconds.

## 20. No stale success

The worker declines to submit after authority ends, **and** the database would refuse it. Two axes, both
evidenced.

## 21. Crash and orphan handling

Unchanged and not overclaimed. A `SIGKILL`ed runner runs no cleanup; the lease expires, the reconciler
fences, and the orphan reconciler removes sandbox, proxy and network.

## 22. `DENY_ALL`

No network at all, and therefore no proxy-side control that could otherwise cut a workload's capability —
which is why sandbox termination is the only mechanism there, and why the revocation tests use it.

## 23. `ALLOWLIST`

The sandbox joins one per-execution internal network whose only other member is the proxy, so the no-bypass
property is topological. Revocation converges: workload, proxy and network all go, asserted separately.

## 24. DNS and SSRF

Unchanged from ADR-026: one resolution, every returned address classified, connection to that exact address,
private and non-global destinations denied, redirects re-authorized.

## 25. Output and artifact security review

Everything leaving the sandbox was reviewed. The findings, in order of importance:

- **The result carries one bit of sandbox-authored information.** `workload_outcome` is compared against the
  literal `PASSED` and reduced to a boolean. Every other field in the submitted document comes from the
  command the control plane issued. Tenant-influenced bytes therefore cannot become a field, a name, a path
  or a configuration value.
- **Output is bounded, and the bound is corroborated.** The collector's truncation flag is checked against the
  bytes it actually retained, because a flag set while everything was still being kept is a flag and not a
  bound — a mutation once demonstrated exactly that, with 13 MB buffered and the check green.
- **Control and Unicode format characters are stripped at the collector**, before any other code sees a value,
  in keys as well as values.
- **Nothing in the runner resolves an observation against a filesystem or a process API**, asserted
  structurally rather than by inspection.

## 26. Output-bound red-path evidence

`OUTPUT_BOUNDED` cannot pass unless truncation actually occurred: the check requires the probe to have
started, the collector to have truncated, and the retained bytes to be within the ceiling. The probe emits
200,000 lines against a 64 KB ceiling, so the green state itself is the proof that excess output was produced
and bounded.

**The gap that was found here.** The sanitiser had no such property. Removing the control-character filter
broke **no test** — measured, not assumed. It is now covered by `UntrustedOutputTests`, which runs a probe
that emits terminal escapes, a bell, a right-to-left override, a zero-width joiner, a traversal-shaped value,
an absolute path and a key containing control characters. Both filters are now mutation-killed.

The path-like values are asserted to be **retained exactly as written**. The property is not that traversal is
scrubbed — it is that an observation is a string and nothing resolves one against a filesystem. Scrubbing it
would hide the evidence and imply the opposite.

## 27. Future `SourceCapability` review

Reviewed, not redeemed. It binds organization, project, run, attempt, assignment epoch and expiry, and is
revalidated against authoritative state on redemption, so it dies with its assignment. That is sufficient
authority for the next slice; §43 lists what the next slice must add around it.

## 28. Future source-bundle threat model

Stated as requirements in §43 rather than implemented here.

## 29. Secret non-presence

No production provider, no values, no redemption, no tenant secret in an environment variable or a file. A
secret-bearing command is refused at authorization and refused again by the runner.

## 30. No-engine verification

Verified against the dependency graph rather than the documentation: zero Karate references on the runner's
runtime classpath.

## 31. Infrastructure-configuration isolation

Structural, and stronger than expected. The launch request carries a probe **enum**, a profile version
compared for equality, and a correlation id. The container command comes from the enum's fixed argument
vector. The tenant-authored `tags` the command carries are **never read by the runner at all**. There is no
path from any tenant-influenced value to a runtime, image, entrypoint, mount, device, capability, security
option, network or resource ceiling.

## 32. Composed attack review

| Question | Answer | Basis |
| --- | --- | --- |
| Can a command authorized for gVisor reach runc? | No | Runtime compared by name before any sandbox; launcher refuses a foreign profile |
| Can source metadata alter runtime selection? | No | No source exists; no string-to-runtime resolution anywhere in main |
| Can expired authority leave computation running? | No | ADR-029, measured |
| Can output from a fenced sandbox become authoritative? | No | Checked before submission, and fenced by the database |
| Can a gVisor sandbox reach the network without the proxy? | No | Topological, not configured |
| Can an `ALLOWLIST` capability outlive its assignment? | No | Revalidated on a timer, closed within a bound |
| Can evidence from one runtime authorize another? | No | Profile version and signed runtime, both directions |
| Can a stale pin retain valid evidence after an incompatible upgrade? | **Yes, up to the freshness window** | §16 |
| Can cancellation kill the container but leave the sentry? | No | Asserted in CI, and the check itself is now proven |
| Can tenant bytes become shell arguments? | No | §31 |

## 33. Security review findings

- **P2 — the launcher's profile refusal had no test.** Fixed (§9).
- **P2 — the output sanitiser had no test.** Fixed (§26).
- **Finding, accepted — evidence can outlive its runtime** for the freshness window (§16).
- **Finding, joint coverage — the setuid scan does not read file capabilities** and does not need to (§13).

## 34. QE findings

Three harness defects have now been found across this and the previous slice, all of which produced
*confident wrong answers*:

1. A mutation run without `cleanTest` reported SURVIVED when the tests had not re-executed.
2. A `python` edit whose anchor no longer matched was not applied, and the script did not assert.
3. **This slice:** `grep -c FAILED` on Gradle output matches enum names such as `SANDBOX_CREATE_FAILED`, not
   only test verdicts. It reported the sanitiser mutation as KILLED when it survived. The correct predicate
   matches Gradle's own verdict lines.

Each was caught by cross-checking a result that looked too convenient.

## 35. Mutation evidence

| # | Mutation | Result |
| --- | --- | --- |
| C1 | output bound removed | killed |
| C2 | truncation not corroborated against retained bytes | killed |
| C3 | control-character sanitiser removed | **survived** → new tests → killed |
| C4 | Unicode format-character filter removed | killed by the same new tests |
| C5 | gVisor leak rule made vacuous again | killed by construction — the new gate step requires a non-zero count with a sentry running, and `pgrep -c runsc` was *measured* returning zero in exactly that state |

## 36. Joint coverage

Two recorded, both deliberate:

- `NO_SETUID_BINARIES` + `CAPABILITIES_DROPPED` together cover what `NO_NEW_PRIVILEGES` would have (§13).
- Docker's own stop timeout + the explicit kill both terminate a sandbox; the kill additionally covers a
  failed stop, which cannot be produced against a real daemon (carried from ADR-029).

## 37. CI evidence

No ninth check. The leak check is now proven in **both** directions inside the gate:

```
with a mediated container running: 2      (sentry and gofer)
after removing it: 0
executed=7  skipped=0
containers=0  networks=0  runsc_processes=0
```

That step exists because the leak check has been wrong twice in opposite directions, each time reporting a
number that read as evidence.

## 38. Documentation reconciliation

ADR-022 amended rather than rewritten — its rationale for refusing ordinary Docker is preserved, with the
adjudication appended. New: the readiness document and the composed-boundary architecture document. ADR index
and `IMPLEMENTATION_STATUS.md` updated.

## 39. ADR-022 adjudication

The runtime prerequisite is **satisfied for the mediated runtime**, for inert tenant-byte delivery only. Four
of five prerequisites are satisfied or not applicable; the fifth is satisfied on its output half, and its
input half is the next slice's subject rather than a blocker for deciding.

## 40. Exact capability unlocked

A future slice may deliver **inert tenant-authored source bytes** into the mediated sandbox, read-only, to be
hashed and inspected by a platform-owned verifier. That is all.

## 41. Capabilities still forbidden

Karate. Tenant source execution. Arbitrary commands, images, entrypoints, mounts or devices. Production
secret resolution. Secret redemption. Unrestricted network access. Tenant selection of any runtime or
infrastructure setting.

## 42. Residual risks

Listed in full in [the readiness document](docs/security/hostile-content-readiness.md): the sentry is a host
process; two sentry-served devices exist inside the sandbox; `NO_NEW_PRIVILEGES` is unobservable and covered
jointly; evidence can outlive its runtime for the freshness window; the image supply chain is pinned but
unsigned and unscanned.

## 43. Exact KAAS-18 requirements

1. The bundle is mounted **read-only** with `noexec,nosuid,nodev`. Without `nosuid` a tenant-supplied file
   could defeat `NO_SETUID_BINARIES`, which is one of the two controls compensating for the missing
   `NoNewPrivs` observation. This is the single hardest requirement generated by this adjudication.
2. `NO_SETUID_BINARIES` re-verified **with a bundle present**, not only on the bare image.
3. Logical paths cannot escape the bundle root: traversal, absolute paths, symlinks, duplicate canonical
   names and Unicode-normalisation collisions all resolve inside it or are refused.
4. Aggregate and per-file size ceilings enforced **before** extraction.
5. The bundle digest verified, and the bundle immutable between verification and mount.
6. No source byte reaches a shell, a launcher argument, a container setting, or a host path.
7. The verifier is platform-owned, its command still an enum, and its output still bounded and sanitised.

## 44. Recommended next slice

Source delivery, scoped to §40 and constrained by §43. It should not be combined with anything else — in
particular not with an engine, which is a separate decision requiring its own adjudication of everything this
one deliberately did not approve.

## 45. Final verdict

**READY FOR TENANT SOURCE DELIVERY SLICE**

Qualified precisely: ready for *delivery of inert bytes*, under the mediated runtime, with the seven
requirements in §43 binding on that slice, and with the residual risks in §42 accepted for content that does
not execute and re-opened for content that does.
