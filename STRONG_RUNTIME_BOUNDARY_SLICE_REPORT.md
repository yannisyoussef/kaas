# Strong runtime boundary slice report

**Scope:** KAAS-15. A stronger hostile-code runtime boundary, evaluated against candidates, implemented as a
mediating sandbox runtime with no fallback, integrated with the signed attestation, and proven in CI.

**Status:** Implemented and green on all eight required checks.

**What it does not do:** it does not make tenant execution available, and it does not close ADR-022.

---

## 1. The problem this slice exists to solve

ADR-022 hardened a container sandbox, proved every control with a trusted probe, and then declined to approve
the result for hostile tenant code. Its reason was structural: a `runc` container shares the host kernel, so
the reachable attack surface is every syscall the kernel implements, and a kernel bug is an escape rather than
a containment failure.

ADR-027 signed the evidence. A signature makes an attestation trustworthy; it does not make the boundary it
describes any stronger. Every slice since ADR-022 has been building the machinery that would make hostile code
safe to run except the boundary itself.

## 2. What was evaluated, and how

`docs/architecture/hostile-runtime-evaluation.md` — 26 criteria, a control-compatibility table, and five open
items stated as open rather than asserted. gVisor and Firecracker were both measured rather than compared on
documentation.

## 3. Why gVisor, and why Firecracker is deferred rather than rejected

Firecracker requires KVM. The development host exposes none (`/dev/kvm` absent, no `vmx`/`svm`), and
GitHub-hosted runners do not provide nested virtualization — so it could not be exercised at all, in any
environment available to this project. Adopting it would also mean a kernel image, a rootfs, a guest agent,
tap-device lifecycle and microVM reconciliation: a different execution model rather than a stronger launcher.

gVisor keeps the OCI workflow, the image model, the security profile, the egress proxy topology and the orphan
reconciler. It is a launcher change plus a runtime registration.

## 4. The measurement that overturned a written claim

This repository had recorded that `cannot run with network enabled in root network namespace` was an artifact
of nested Docker. **That was wrong.** The first CI run reproduced it on a GitHub-hosted Ubuntu runner
(`6.17.0-1022-azure`, cgroups v2) on *every* network mode, including `--network=none`:

```
release-20240729  --network=none       cannot run with network enabled in root network namespace
release-20240729  --network=bridge     cannot run with network enabled in root network namespace
release-20240729  --network=<internal> cannot run with network enabled in root network namespace
release-20260817  --network=none       Linux version 4.19.0-gvisor
```

The runtime was two years older than the kernel it was asked to run on. The correction is recorded in the
evaluation document rather than quietly edited away.

## 5. The pin is a measurement, not a preference

`release-20260817.0`, x86_64, `sha256:048b89aada69dc3333422e139d6e9d02f8ab06bda52398060e0fbdacca00074c`. The
digest was taken from two independent hosts on two networks and matched the publisher's own sha512. Pinning a
digest gives a stable identity; it does not give a working runtime. **Re-pinning means re-measuring on the
kernel that will run it.**

## 6. The open question, answered

`ALLOWLIST` egress under the mediating runtime was the evaluation's largest open item, and no claim was
written anywhere while it was open. Measured on the runner: `none`, the default bridge, and a user-defined
`--internal` network all carry a sandbox under the pinned release.

## 7. The runtime is a closed set of two, with no ordering

`ExecutionRuntimeType` has two constants, no `preferred`, no `fallbackTo`, and no comparison operator. A
runtime name is the name of a program the daemon will execute, so a runtime selected by a request, a tenant, or
an editable profile field would be a larger privilege than anything else in the system.

## 8. No fallback, stated as an absence

There is no flag, no catch block, and no development convenience that runs a mediated-authorized sandbox under
the baseline. Where the runtime is unavailable the sandbox is refused. A sandbox authorized for one boundary
that ran under the other would produce evidence describing a boundary it did not have, and the failure would be
silent because everything else about the container would look identical.

## 9. Requested versus enforced, separately observable

- **Launcher side:** `docker inspect` reports `HostConfig.Runtime`, read back **before the workload starts**.
- **Sandbox side:** `uname -r` inside the sandbox ends in `-gvisor`.

The second cannot be produced by a `runc` container unless the host kernel is literally named after the
runtime, and a container cannot choose what the kernel says about itself.

## 10. The marker binds identity, not version

Originally the version prefix `4.4.0`, measured from release-20240729. The pinned release reports
`4.19.0-gvisor`. A version-bound marker would have silently stopped matching — a mandatory control failing for
a reason with nothing to do with the boundary. The marker is now the `-gvisor` suffix, matched as a suffix
rather than a substring, and the comparison lives on the runtime constant so there is exactly one answer to
"does this evidence show mediation".

## 11. The five controls that behaved differently, and what each cost

| Control | Finding | Resolution |
| --- | --- | --- |
| `KERNEL_PATHS_MASKED` | only `/sys/firmware` exists, unmounted, and **empty** | judge what a path exposes, not how it was hidden |
| `NO_HOST_DEVICES` | `/dev/fuse`, `/dev/net/tun` are sentry-emulated | allowance scoped to the runtime |
| `NO_NEW_PRIVILEGES` | gVisor emits no `NoNewPrivs` line | reported `UNSUPPORTED`, never passed |
| `PID_LIMIT` | the sentry shares `pids.max` | per-assessment check relaxed; causation proven separately |
| `MEMORY_LIMIT` | **not a resource failure** — a timeout | the deadline is scaled to the runtime |

## 12. Kernel paths: masked, absent, or exposed

`runc` overmounts these paths, so they appear in the mount table. gVisor never implemented them, so they are
absent. Absent is at least as strong as masked and produces none of the same evidence, so a check demanding the
mount would fail the stricter sandbox and pass the weaker one.

The rule is now: **masked, or absent, or present-and-empty** passes; **present, unmounted and non-empty** — the
`systempaths=unconfined` case this check exists to catch — still fails.

## 13. And the vacuity trap that rule creates

"Not in the mount table and not in the present list" is also exactly what a probe that never looked produces.
So the probe emits the list it *examined*, and a required path missing from that list fails. Absent evidence is
not a pass.

## 14. Devices: scoped to the runtime, never added to the shared list

A global allowance would have silently stopped catching a genuinely exposed `/dev/fuse` under the baseline
runtime — the check would keep its name and stop meaning what it says. This survived the first mutation run and
is now covered in both directions.

## 15. `NO_NEW_PRIVILEGES`: reported honestly rather than preserved

The control is applied under both runtimes; what is missing under gVisor is any way to observe it from inside.
It is reported `UNSUPPORTED`, never `PASS`, because recording a request as though it were an observation is the
failure that makes a gate decorative.

**Consequence, stated plainly: the mediating runtime carries one fewer demonstrable mandatory control than the
baseline.**

## 16. The excusal is platform-owned, not evidence-driven

The obvious implementation — "if the observation is missing, the runtime must not support it" — hands the
decision to whatever produced the observation. A workload that simply withheld the line would downgrade a
blocking control into a non-blocking one: evidence suppression rewarded with a weaker gate. The excusal comes
from the runtime constant instead, so a missing flag under the baseline stays a failure.

## 17. The behavioural probe that was tried and rejected on evidence

The first design had the probe exec a setuid-root binary and report the effective uid. busybox refuses to run
setuid: it drops privileges for its own applets regardless of the target uid. Measured, not assumed — a copy
named `probe-setuid` answered `applet not found`, and one named `id` reported the unprivileged uid whether or
not the control was enforced.

**The negative control caught it.** Without a test asserting the mechanism could produce the *other* answer, a
mandatory control would have been passing because the binary was broken.

Supplying a real setuid binary would have required a compiler stage pulling unpinned packages into the one
image whose entire job is to be trustworthy. Rejected.

## 18. `NO_SETUID_BINARIES`, the compensating control

Observes directly that there is nothing in the sandbox for a privilege transition to act on. It reads
identically under both runtimes, which is why it is mandatory under both. Its negative control is real rather
than fixture-only: a container that plants a setuid binary must be found by the scan.

## 19. `PID_LIMIT`: what the sentry costs

The sentry's threads come out of the sandbox's own `pids.max`. Measured on the runner with a ceiling of 64:

| runtime | memory | processes started |
| --- | --- | --- |
| `runc` | 256m | 63 |
| `runc` | 512m | 63 |
| `runsc` | 256m | 17 |
| `runsc` | 512m | 16 |

Raising memory does not move it, which is what rules out the memory cgroup as the binding constraint.

## 20. Why that weakens the check, and what replaces the strength

The baseline gate proves the ceiling is causal by requiring the loop to stop *at* the ceiling — specifically so
a run stopped by the memory cgroup cannot satisfy it. That test cannot hold here, because stopping short is
correct. The per-assessment check falls back to "a bound exists at or below the ceiling", and causation is
established once, separately, by moving the ceiling (128 against 64) and requiring the stopping point to
follow. A ceiling of 16 fails to create the sandbox at all — that measures the sentry, not the workload.

## 21. `MEMORY_LIMIT` was never a resource failure

Run by hand under the mediating runtime, the memory probe exits 137 with the daemon reporting
`OOMKilled=true` — identical to the baseline. Through the launcher it reported
`memory_allocated_mb=-1 oom_killed=false exit=null`. The cause was `SANDBOX_TIMEOUT` at `PT30.2S`: the deadline
calibrated for the baseline fired before the evidence could arrive.

A deadline that truncates the workload turns every slower control into a timeout, and the gate then reports
something other than what it is measuring. The deadline is scaled by the runtime. This is not a relaxation of
`WALL_CLOCK_TIMEOUT`, which asserts the launcher enforces its own deadline whatever that deadline is.

## 22. The evidence for that diagnosis came from making the gate say more

The check reported "no exit code", which has several causes — the deadline fired, the daemon was lost, the
drain was incomplete — that call for different responses. The evidence string now carries the failure category
and the elapsed time, and the diagnosis took one CI run rather than a guess.

## 23. The structural change: the control set is scoped to the runtime

`controlsByProfileVersion` in `packages/api-contracts/mandatory-sandbox-controls.json` is the single source of
truth. Seventeen controls each; they are not the same seventeen. Exact equality still applies **within** each
set, in both directions, so an assessment produced under one profile can never satisfy the other.

This was forced by measurement rather than chosen: keeping one shared list would have meant either demanding
evidence a runtime cannot produce, or dropping a control the other runtime can prove.

## 24. An unknown profile version is refused, not defaulted

`mandatoryFor` throws for a version this build does not know. An empty required set is satisfied by an
attestation that demonstrates nothing, so the permissive default is the dangerous one. The authorization path
refuses rather than letting that throw reach a caller: an exception is an error page, and an error page is not
a decision.

## 25. Attestation v4

`kaas.sandbox-security-attestation.v4`, domain separator `KAAS_SANDBOX_SECURITY_ATTESTATION_V4`. The separator
moved **with** the schema, because v4 added a signed field: without moving it, a v3 document would differ from
a v4 one only in fields a reader might not compare. v3 is refused exactly as v2 is — no downgrade, no
migration window.

Canonical preimage: **1845 bytes**, digest
`sha256:d5ca91ee1d6acba8f791a1285d96d259d2bc0171e28df1a9121ede2ff16e423d`.

## 26. The runtime is bound into the signature, redundantly and on purpose

`sandboxRuntime` is signed alongside `securityProfileVersion`, which already implies it. Two statements about
one boundary, both under one signature, so a document that contradicts itself is caught rather than resolved in
favour of whichever field a reader consulted. `RUNTIME_MISMATCH` is a distinct verification outcome from
`PROFILE_MISMATCH`: "describes another boundary" and "contradicts itself" send an operator to different places.

Neither side of that comparison comes from a request. The runner derives its half from the launcher that ran
the probes; the control plane derives its half from the shared contract.

## 27. Three implementations, agreeing through a written contract

The producer in `services/runner`, the verifier in `apps/api`, and a new generator in
`packages/api-contracts/scripts/generate-signing-vectors.py` — Python, a different crypto library, written from
the contract document. The vectors both Java implementations are checked against are produced by neither of
them.

## 28. The negative vectors are now generated, because hand-maintained ones drifted

Every hand-written negative still said `v3` after the schema moved, so each was refused at the schema check
*before* reaching the stage it was written to exercise: a suite of refusal tests that all passed while testing
one thing. They are now derived from the valid document, so a negative differs from it in exactly the one way
its name claims.

Three vectors were added: `superseded-v3`, `tampered-sandbox-runtime`, `self-contradictory-runtime`.

## 29. And a test that the vectors are actually exercised

A reconciliation asserts the directory, the index that explains each vector, and the names the suite actually
refuses are the same set. A vector on disk that no test names is a refusal nobody checks; a name in the index
with no file is a claim about a document that does not exist.

## 30. `signed-by-a-different-trusted-key` had to be rebuilt correctly

Generating it by signing with key 2 *and* relabelling the payload moved the preimage, so the cheap digest check
refused it first — testing the wrong thing. The payload must be unchanged and only the signature different, so
that nothing but signature verification can refuse it. That is the vector proving signature verification
actually runs.

## 31. Command binding: three things must name the same boundary

The signed attestation, the command the control plane issues from it, and the runtime the worker instantiates.
The first two are tied at authorization, where the command's runtime is copied out of the signed payload. The
third link can only be checked in the worker, because no other component knows what that process is configured
to launch.

It is compared **by name**; nothing resolves the string to a runtime.

## 32. Where that check runs, and what it reports

Before any sandbox exists, and reported to the control plane rather than merely returned. By that point the run
is `RUNNING` with a phase deadline against it, and a worker that noticed the mismatch and went quiet would
leave the run to be reclaimed later as a timeout — a failure observed in milliseconds and then discarded.

The run goes `STOPPING` / `INFRASTRUCTURE_FAILURE`, with **no test outcome invented**: the tenant's work never
started, and recording a verdict for it would be the platform blaming a tenant for its own misconfiguration.

## 33. Why the launcher's existing check was not enough

The launcher already refuses a profile version it does not hold, and because the profile version is derived
from the runtime, that check catches the same case — as "Unknown security profile version", which sends an
operator looking for a profile problem. A worker configured for the baseline receiving work authorized for the
mediating runtime is not a profile problem.

## 34. The CI gate, and why it has no escape hatches

The runtime cannot be installed into Docker Desktop's VM, so **a green local build proves nothing about the
stronger boundary.** The evidence exists in exactly one place: the mandatory `strong-runtime-gate` job.

No job-level `if:`, no `continue-on-error`, no path or branch filter. A required check is satisfied by success,
skipped **or** neutral, so a job that skipped itself when the runtime was absent would be indistinguishable
from one that proved a workload was confined by it. The suite asserts the daemon has the runtime before running
anything, so "not installed" is a red gate rather than an empty one.

Required checks become **eight**.

## 35. The gate inspects its own evidence

`executed=6 skipped=0`, the suite named explicitly, and a leak check covering containers, networks **and**
`runsc` sentry processes — a mediated sandbox leaves a sentry behind if the container is not reaped, so Docker
objects alone are not the whole question.

## 36. Mutation ledger: 15 run, 15 killed

| # | Mutation | Result |
| --- | --- | --- |
| M1 | requested `runsc`, launched `runc` | killed after a fix (§37) |
| M2 | mediation verdict always `PASS` | killed |
| M3 | flag excused on every runtime | killed |
| M4 | setuid scan reads absence as compliance | killed |
| M5 | runtime cross-check removed from the verifier | killed |
| M6 | unknown profile version requires nothing | killed |
| M7 | v3 domain separator accepted | killed |
| M8 | kernel-path emptiness ignored | killed |
| M9 | unexamined kernel paths tolerated | killed |
| M10 | emulated devices allowed on every runtime | **survived** → new test → killed |
| M11 | command runtime never compared | killed |
| M12 | deadline not scaled | killed |
| M13 | both runtimes share one profile version | killed |
| M14 | blank flag treated as reported | **survived** → new test → killed |
| M15 | command digest omits the runtime | **survived** → new test → killed |

## 37. M1 is the one worth remembering

The mechanism already caught it — the read-back refuses before the workload starts — but the outcome was
*indistinguishable* from a host that simply lacks the runtime: same failure value, and no surviving container
either way, because cleanup removes it. The defence held and nothing could prove it was the defence.

`SANDBOX_RUNTIME_UNAVAILABLE` (the daemon has no such runtime) and `SANDBOX_RUNTIME_MISMATCH` (the daemon
assigned a different one) are now distinct. That is better operationally — the two send an operator to entirely
different places — and it makes the mutation observable on a host without the runtime, which is where the local
build runs.

## 38. The three survivors shared a shape

A runtime-scoped allowance, a blank-versus-absent distinction, and a field implied by another field. All three
are redundant-by-construction facts — exactly the things easiest to leave untested because "it cannot be
wrong" — and each was wrong under mutation.

## 39. Review finding: the production producer could not attest the stronger runtime

The attestation CLI — the component an operator actually runs — hardcoded the baseline runtime. The launcher,
the gate, the runtime-scoped control set, the signed field and the verifier's cross-check all worked, and none
of it could be reached by the one component that produces evidence for a deployment. A control plane could be
configured to expect `kaas.sandbox.gvisor.v1` and nothing in the product could sign such a document.

**Nothing failed.** Every test passed, because every test built its own profile.

The runtime is now named by the operator, chosen from a closed set by name and never resolved with `valueOf`
over whatever was supplied. It defaults to the baseline: an operator who has not installed the mediating
runtime should get a truthful refusal, not evidence naming a boundary their host does not have.

## 40. And the production path is now proven end to end in CI

The gate runs the producer CLI with `-PkaasAttestationSandboxRuntime=gvisor` and hands what it writes to the
control plane's own verifier. Observed: `kaas.sandbox.gvisor.v1 GVISOR 17 controls`, `verification=VALID`,
`authorizes=true`. **And the negative:** the same authentic document does not authorize a deployment expecting
the baseline.

## 41. Review finding: the allowlist path defaulted to the baseline runtime

The allowlist path builds its *own* sandbox profile — it has to, because the sandbox joins a per-execution
network the deny-all profile knows nothing about — and that second construction site defaulted to the baseline.
A deployment configured for the mediating runtime would have been unable to run an allowlist execution at all:
the command-versus-profile check refused it, which is fail-closed and still wrong. The runtime is now carried
on the deployment.

## 42. Review finding: the runtime binding was in the contract with nothing holding either side to it

`runtimeByProfileVersion` was added to the shared contract file without a contract test. The two sides agreed
by accident rather than by construction, which is the same as not agreeing. Both modules now assert against it,
and both go red when the file is mutated.

## 43. Review finding: a mandatory control with no red path

`HOST_KERNEL_SYSCALL_MEDIATION` had no "switched off" test. It now has four, covering a kernel the runtime does
not serve, the kernel it does, a sandbox that reported none, and the baseline's honest `UNSUPPORTED`.

## 44. Residual risks

- **The sentry is a userspace process on the host kernel**, itself seccomp-confined. This is not a virtual
  machine and nothing here describes it as one. A sentry compromise is host-adjacent rather than impossible.
- **One fewer demonstrable mandatory control** under the mediating runtime.
- **Performance figures come from two hosts, neither of them production.** They are not a capacity statement.
- **The pin will age.** The release this repository first pinned was broken by a newer kernel, and the next one
  will be too. Re-pinning requires re-measuring.
- **`/dev/fuse` and `/dev/net/tun`** are two more interfaces reachable from inside the sandbox, sentry-served
  rather than host-served.
- **Firecracker remains a candidate**, not a rejected option.

## 45. What this does not change

**ADR-022 remains open.** A stronger runtime starting is not the same as hostile tenant content being safe to
run. Tenant execution stays unavailable: no feature source, no tenant secrets, no production secret provider,
no Karate, and the only thing the sandbox runs is still a probe this repository wrote.

What changed is the boundary that probe runs behind — and the evidence that says which one it was.
