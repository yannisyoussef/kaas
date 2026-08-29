# Hostile-Execution Security Slice Report

## 1. Executive summary

KAA-004 has been open since the first commit: Docker/host/daemon/network/secret isolation needed a dedicated hostile-execution architecture and an executable release gate. This slice provides both — for a **trusted synthetic probe**, and for nothing else.

The order matters more than the contents. The tempting sequence is to build execution and then secure it; this does the reverse, because a boundary that has never been tested against anything is indistinguishable from a boundary that does not work. So the only executable content the sandbox will run is a shell script from this repository that reports what it can observe about its own confinement, and every mandatory control is proven by what the probe could actually do rather than by reading back the setting that requested it.

No feature source, no secrets, no execution command, no production execution path, no Karate.

## 2. KAA-004 status

**Evidenced for a trusted probe. Not closed.** Closing it requires a stronger kernel boundary and the capability models in §28–29. The status line is deliberately not "resolved": a container shares the host kernel, and this slice does not change that.

## 3. Threat model

Enumerated in `docs/security/hostile-execution-boundary.md`. Mitigated here: privileged mode, capability abuse, host filesystem access, daemon-socket exposure, device access, uncontrolled process spawning, fork bombs, memory exhaustion, log amplification, control-sequence attacks on log readers, indefinite execution, SSRF and private-network probing, cloud-metadata access, DNS exfiltration, environment-variable leakage, inherited host credentials, host bind mounts, orphaned sandboxes, launcher crash.

Not mitigated, and stated as such: container escape via a kernel vulnerability, side channels between concurrent workloads, daemon compromise, and any control the host kernel does not support.

## 4. Runtime alternatives considered

Standard hardened Docker, rootless Docker, Docker with user namespaces, gVisor, Firecracker-style microVMs. Kubernetes was considered and rejected as orchestration rather than isolation — selecting it here would be security theatre.

## 5. Selected trust boundary

**Standard Docker with maximal hardening**, on one criterion: every mandatory control can be enabled *and demonstrated from inside the sandbox* on every host KaaS runs on, with no special daemon configuration. A control only some hosts can enforce cannot be a baseline; a control that cannot be demonstrated is a claim rather than a boundary.

It is explicitly **not** sufficient for user content. gVisor or a microVM replaces it, with this same gate re-run against it, before user content is admitted.

## 6. Trusted launcher architecture

The distinction the design rests on is between the *trusted launcher process*, which holds daemon access, and the *untrusted sandbox process*, which holds none. A container with the Docker socket is a root shell on the host wearing a container's clothes.

The launcher lives in `services/runner`. The control plane's build fails if it acquires a container-runtime dependency, and the launcher's build fails if it acquires Karate, an object store, or a secret provider — guarded in both directions, because the launcher is trusted with a daemon precisely because it has no business reason to touch user content.

Its input is three fields: a probe from a server-side enumeration, a profile version, and a correlation id. None is a container setting. Making a dangerous configuration *unrepresentable* is stronger than validating it away, because validation is something you can forget to call and a type is not.

## 7. Non-root enforcement

`65534:65534`, set by the launcher and by the image's `USER`. Neither alone suffices: the image directive is advisory because a launcher can override it, and a launcher-only setting leaves an image that runs as root anywhere else. The probe reads `id -u` and `id -g` from the kernel rather than trusting either.

## 8. Filesystem isolation

Read-only root; one writable location, `/tmp`, as a bounded tmpfs mounted `noexec,nosuid,nodev` — a writable path that can also be executed is a place to stage a payload. Binds are explicitly empty, not filtered: an empty list cannot be got wrong the way an exclusion list can.

**This is where mutation testing earned its place.** The first read-only check wrote to `/`, which user `65534` cannot write to whether the filesystem is read-only or not. Disabling the control left the test green — it was proving file permissions. The image now contains a directory the sandbox user owns, so the mount flag is the only thing that can refuse the write.

## 9. Linux capabilities

All dropped, none added. `ALL` is the runtime's own token rather than an enumeration, which would silently stop being complete the day the kernel gains a capability. Verified from `/proc/self/status` with all five sets zero — `CapEff`, `CapPrm`, `CapBnd`, `CapInh`, and `CapAmb`. The gate originally checked two of them, which the Linux-isolation review flagged as weaker than both the documentation and the test: a bounding set of zero does not by itself imply a permitted set of zero, because post-exec permitted includes `P(inheritable) & F(inheritable)`, which the bounding set does not mask.

## 10. No-new-privileges

Set as a security option, verified as `NoNewPrivs: 1` from inside.

## 11. Process and resource controls

PID ceiling 64; memory 256 MB with swap pinned equal to it — without that a workload simply swaps past the ceiling and the limit is decorative; CPU quota 50 ms per 100 ms period.

The PID probe required care. It asks for 200 processes, more than the ceiling allows, and every child exits on its own, so it establishes a bound without ever becoming the fork bomb it is testing for. It also reports progress **as it goes**, because busybox's shell is killed outright when it cannot fork — nothing after the loop would run, so a summary at the end would never be printed. The evidence is the last count reached plus the absence of the completion marker.

CPU quota is configured but deliberately kept out of the mandatory *behavioural* set: a timing assertion would be flaky, and a flaky security test is worse than an honest gap.

## 12. Timeout enforcement

The launcher enforces a 30-second wall-clock deadline independent of the workload. A probe that sleeps for an hour is terminated well inside it and removed. A sandbox that has to cooperate in its own termination is not bounded at all.

## 13. Output amplification controls

Two ceilings, because there are two costs.

The collector keeps at most 64 KB, after which it stops keeping bytes rather than buffering and trimming — the cost of a flood has to be paid as it arrives. The gate corroborates the collector's truncation flag against the bytes it actually retained, since a flag set while every byte was still kept is a flag rather than a bound; a mutation doing exactly that left the launcher holding 13 MB with the check green.

The second ceiling is the one this slice originally missed entirely. The daemon writes every byte the sandbox prints to a host file, outside the container's cgroup — charged to nothing, throttled by nothing, invisible to the collector. Measured at **883 MB/s and 35.11 GB from a single sandbox**, with the container's own `io.stat` empty. A log configuration now bounds what the host is made to store. It bounds capacity, not throughput.

Control characters are stripped at the boundary, not wherever the output is eventually rendered, because terminal escape sequences in untrusted output are an attack on whoever reads the logs. Format characters — right-to-left overrides and zero-width joiners — are stripped with them: they survive an `isISOControl` filter and reorder how a line reads to a human without changing its meaning.

## 14. Network and SSRF controls

No network at all. Not a restricted one — none.

The evidence is positive: the probe reports its global addresses, default routes, and interfaces that are up, and all three must be present and zero. Reachability alone could not carry this claim, and the network review demonstrated why — a container on an internal Docker network produced byte-identical reachability output to an isolated one, while a sibling container reached it by name and read its payload. On an egress-filtered host, which is the normal case for CI, the old check certified a sandbox with a routable address. Measured 0/0/0 isolated against 1/2/1 bridged.

Reachability attempts remain as corroboration: public internet, RFC1918 private space, `169.254.169.254`, IPv4 link-local, the IPv6 metadata address, `host.docker.internal`, and the real default gateway discovered rather than hardcoded. Loopback was removed: `--network none` leaves a fully working `lo`, so that attempt could only ever report that nothing was listening — and the security document's claim that loopback was unreachable was simply false. DNS is asserted separately because it is a distinct exfiltration channel.

Deny-all is the baseline this slice proves. A destination allowlist is a real product requirement with its own policy model; approximating one now would mean claiming egress control that has never been tested. **Denying DNS does not solve SSRF, and this slice does not claim it does.**

Deny-all is the baseline this slice proves. A destination allowlist is a real product requirement with its own policy model; approximating one now would mean claiming egress control that has never been tested. **Denying DNS does not solve SSRF, and this slice does not claim it does.**

## 15. Mount and device controls

No bind mounts, no daemon socket, no host repository, no home directory — and the probe now proves that by enumeration rather than by asking after names.

This was the slice's most serious defect, found independently by five reviewers and demonstrated three ways. Asking whether `/host` and `/workspace` existed found mounts at those two names and nothing else: the daemon socket at `/run/docker.sock` and a home directory at `/mnt/host` both reported `absent` while the sandbox held a root shell on the host. Counting device filenames beginning `sd`, `nvme`, or `vd` was defeated by attaching the host's root partition as `/dev/loop0`, after which `dd` read the raw disk as uid 65534. And a bind of the host's `/etc` at `/opt/hostetc` left the entire suite green.

The probe now emits its whole mount table, every writable mount, every unix socket on its own filesystems, and every device node by type. The gate compares those sets against what the profile permits, so a surface nobody thought to name is still visible. It also requires the runtime's masked `/proc` overmounts to be present, which is positive evidence that the kernel's own internals were kept out — a daemon started with `systempaths=unconfined` would otherwise produce an identical assessment.

## 16. Environment isolation

Built from nothing — never the host environment with known-sensitive names removed. Subtraction requires knowing every name worth removing, and the one nobody thought of is the one that leaks. The probe asserts on what is *present*: only the profile's two variables plus shell intrinsics, which are named explicitly so anything genuinely inherited still fails.

## 17. Secret boundary

No secrets exist in the sandbox because no secret mechanism exists at all. `SecretReference` redemption is not implemented and the probe receives no capability of any kind. This slice proves that accidental host and control-plane credentials do not leak.

## 18. Source boundary

No `FeatureRevision` source, no project mount, no `RunSnapshot` materialisation. The only executable content is the trusted probe.

## 19. Image and supply-chain policy

Built from a repository-controlled Dockerfile whose base is pinned by digest. A tag is a mutable pointer to executable code, which is a supply-chain hole in the one component whose entire purpose is being trustworthy. The identity trusted at runtime is the digest of what was just built. No signing, SBOM, or vulnerability scanning yet — recorded as residual risk rather than implied.

## 20. Cleanup and reconciliation

Cleanup runs on success, failure, timeout, and launcher exception alike. A cleanup failure is now genuinely reported rather than swallowed: it was previously thrown from a `finally` block, which replaced the pending return, so a run that succeeded and merely failed to be removed lost every observation it had produced and the gate aborted by exception instead of returning verdicts. `SANDBOX_CLEANUP_FAILED` existed for this and was unreachable. The failure is folded into the outcome instead.

Sandboxes carry `kaas.managed=true` plus a launcher generation, and the reconciler acts **only** on that label — never a name prefix, an image match, or "everything stopped", because a reconciler that guesses is one that eventually deletes somebody's database.

Reclamation is judged by **age**, not by generation. Generation-scoping was unsafe in the direction that matters: it force-removed *running* containers belonging to every other generation, the opposite of what its own documentation claimed, and it was observed deleting a concurrent run's live sandboxes mid-flight — which is what turned this suite red under concurrency and produced a P0 that had to be withdrawn. Its safety test could not have caught this, because the container it asserted survived carried the reconciler's own generation. A container younger than the deadline plus a grace window is now left alone whoever owns it, since a launcher's liveness is not something another process can see; past that point no launcher can still be legitimately waiting, so it is reclaimed regardless of generation. That also closes the case generation-scoping could never reach — a sandbox orphaned by the current generation, which nothing previously reclaimed.

The deadline itself still lives only in the launcher process. With the JVM halted, a flooding sandbox kept running, roughly 16.5 minutes from filling the host filesystem. The log ceiling caps the damage rate; nothing schedules the reconciler yet, and §27 says so.

## 21. Security release gate

`HostileExecutionSecurityGate` runs the probes and produces a structured assessment: one check per control, each with a verdict and an enforcement class. Mandatory controls must be positively demonstrated; a host that cannot enforce a deployment-specific control is recorded `UNSUPPORTED`, never as a pass. The gate fails closed — there is no "log a warning and continue" path, because a green assessment that tolerates missing controls is worse than none.

The assessment is operational evidence, deliberately absent from the public API: telling an attacker which controls a deployment cannot enforce is a gift.

## 22. Mutation-test evidence

Two batteries, along two different axes, and the second exists because the first was insufficient in a way that was not visible from inside it.

**First battery — remove the control.** Ten mutations, each disabling one control and re-running the suite: run as root; read-only root off; network enabled; PID ceiling raised; a capability added back; no-new-privileges removed; the output ceiling removed; the wall-clock deadline removed; a host secret injected into the environment; the memory ceiling raised. All ten turned tests red.

One earlier result was recorded and then withdrawn. "Memory ceiling raised to 2G" turned ten of eleven tests red, which was not evidence of anything: it raised the memory limit without the swap limit, tripping the profile's own `swap == memory` invariant so that every Docker-backed test died in construction. Re-run correctly, with both fields raised to 512MB against a probe requesting 256MB, exactly two tests go red — the memory test and the gate test. A mutation that kills the fixture measures the fixture.

**The gap.** Every one of those ten mutations removed a *control* and left the probe reporting normally. Not one withheld the *evidence*. That axis was never exercised, and it is where the defects were: five mandatory controls scored absent observations as PASS, and the single mutation `blocksRelease() -> return false` — rendering the gate incapable of blocking anything — left the entire suite green. Ten green mutations sat on top of a gate that passed when it saw nothing.

**Second battery — remove the evidence.** `SecurityGateRedPathTests` drives the gate from a fake launcher: 40 tests covering all sixteen mandatory controls twice over, once with the control reported off and once with its observation absent entirely. `blocksRelease() -> return false` now turns 38 of 40 red. Three further mutations were run end to end against Docker: deleting the Dockerfile's `RUN mkdir /probe-owned` now fails `READ_ONLY_ROOT` with `probe_owned_owner=missing`, replacing the base image's digest with a tag now fails the pinning test, and both now *re-run the test task*, which they did not before. `ExecutionCapabilityGuard`, which had no test of any kind, now fails 7 of 9 under `if (false)` and 1 of 9 under removal of `@Lazy(false)`.

The read-only-root case in the first battery found a real defect (§8), and that remains the argument for the practice. The second battery is the argument for asking what the practice does not cover: the first test written for the lazy-initialization bypass passed with the fix deleted, because `ApplicationContextRunner` ignores `spring.main.lazy-initialization`. It was mutation-checked, found vacuous, and rewritten.

## 23. Adversarial review

Eight independent reviews: container/runtime security, Linux isolation, network and SSRF, secrets and data leakage, resource exhaustion, supply chain, quality engineering, and platform architecture. Each was asked the same two questions — which single launcher option would let hostile code escape the intended policy, and which claims are documented but not behaviourally proven.

**No reviewer found an escape from the sandbox.** uid/gid 65534 with no supplementary groups, all five capability sets zero, `NoNewPrivs=1`, a read-only root that resisted writes at all thirteen writable paths tried, `/tmp` genuinely `noexec,nosuid,nodev` and genuinely capped, only `lo` in the network namespace, and a daemon that refuses to attach a network to a `none`-mode container. The image contains no setuid, setgid, or file-capability binary. Docker's default `/proc` and `/sys` masking was intact on every path checked.

**What failed was the gate.** Five reviewers converged independently on one structural defect with two faces: absent evidence was scored as a pass, and controls asked after hardcoded path names instead of enumerating what was present. Three demonstrations, each with the control fully disabled and the gate still green:

- The daemon socket at `/run/docker.sock` and the reviewer's home directory at `/mnt/host`: `docker_socket=absent`, `host_mount=absent`. Two mandatory controls passing while the sandbox held a root shell on the host.
- The host's root partition attached as `/dev/loop0`: `block_devices=0`, and `dd` read 512 bytes of the raw disk as uid 65534. The check counted filename prefixes, so a rename defeated it.
- A bind of the host's `/etc` at `/opt/hostetc`, added without touching the `.withBinds(List.of())` line the source-level test greps for: the entire suite green.

Two P0s came only from measurement. No `LogConfig` was set, so container stdout was an unbounded host disk-write channel — **883 MB/s, 35.11 GB from one sandbox** — charged to no cgroup, because the daemon performs that write outside the container's. And the 30-second deadline lived only inside the launcher process: with the JVM halted the sandbox ran on, about 16.5 minutes from filling the host filesystem, with a reconciler structurally unable to reclaim it.

That reconciler was itself the third P0. It force-removed *running* containers belonging to other launcher generations, the opposite of what its own documentation promised, and was observed deleting a concurrent run's live sandboxes mid-flight.

**Every P0 and every evidence-axis P1 is fixed and covered by the second mutation battery (§22).** The composition findings — no `runId`/`attemptId`/`assignmentEpoch` on the sandbox, no cancellation hook, no admission control, `docker-java` transitive advisories, CI actions pinned by mutable tag, absent Gradle dependency verification — are recorded in §27 and §30 and deferred with named prerequisites rather than silently carried.

Two review artifacts were contaminated by running the reviewers concurrently against one working tree, and are recorded here because the contamination was mine. A reviewer's temporary test harness was read by another reviewer as if it were part of the slice, producing a finding about a file that never existed. And a report that the suite was reproducibly red was cross-run interference from the reconciler defect above — the suite is green in isolation, confirmed at 1m19s and independently at 1m20s. Adversarial reviewers that may write need isolated worktrees.

## 24. CI and runtime portability

A separate, mandatory `hostile-execution-gate` job. Not a suite that skips when no daemon is present: a security gate that quietly does not run produces a green build that means nothing. If a future runtime cannot run on GitHub-hosted runners, the job moves to infrastructure that supports it rather than becoming conditional.

## 25. Files changed

New: `docs/security/hostile-execution-boundary.md`, `docs/adr/022-…`, this report, `services/runner/src/main/docker/probe/{Dockerfile,probe.sh}`, the `sandbox` and `gate` packages, `ExecutionCapabilityGuard`. Changed: `ADR-006` (superseded), both build guards, CI, `RunnerApplication`, `application.properties`.

## 26. Verification results

`./gradlew clean check` — BUILD SUCCESSFUL, 4m20s, zero failures and zero skips, with PostgreSQL and RabbitMQ Testcontainers and the full sandbox suite. Contract schema validation passes for all four schemas plus the semantic invariants. No container carrying `kaas.managed=true` survives the run.

- `services/runner`: 61 tests. 40 of them are the gate's red-path suite, which requires no daemon; the remainder start real containers.
- `apps/api`: full suite green, including the nine new `ExecutionCapabilityGuard` tests — a control that previously had none.
- Mutation evidence: §22.

The suite is green only when it has the daemon to itself. That is a property of the reconciler's age-based reclamation window, not a flake, and it is why the mutation battery and the resource-exhaustion measurements were run in isolation.

## 27. Residual risks

Kernel-shared isolation, which is the one that matters and the reason this is not approved for user content. Launcher daemon access confined rather than eliminated. Host-dependent hardening: seccomp is reported as loaded but its contents are not verified, and the launcher pins no profile. Unsigned, unscanned images with no SBOM, and no Gradle dependency verification — the Java dependencies are the one link in the chain with no integrity pin, while the wrapper has a SHA-256 and the base image a digest. `docker-java` 3.4.1 carries roughly 25 known transitive advisories into the only process with daemon access; 3.7.1 exists and resolves them.

Orphan cleanup still depends on a reconciler that nothing schedules, and the module has no logger, no metric, and no health indicator — an operator cannot currently see that a sandbox was created, timed out, or failed to be removed. The CPU quota has no behavioural test, and the host CPU a sandbox causes was measured at roughly 3.2× its quota because the daemon's own encoding and writeback fall outside the cgroup. There is no admission control, so per-unit-time costs multiply with concurrency: four concurrent sandboxes saturated host disk I/O. The sandbox carries no run, attempt, or epoch identity, so a fenced attempt's container cannot be located, and it exposes no cancellation hook.

## 28. Prerequisites for source capability

A stronger kernel boundary first. Then: what a capability binds to, how long it lives, whether it survives fencing, how a fenced worker's capability is revoked, and how source reaches the sandbox without a host mount.

## 29. Prerequisites for secret capability

All of the above, plus a separate threat model. Secrets are the higher-value target and should not ride along with source in one slice.

## 30. Recommended next slice

Replace the boundary with gVisor and re-run this gate against it. That produces a genuine comparison on identical evidence and is the prerequisite every other slice depends on — doing capability issuance first would mean building the mechanism that admits user content before the boundary that contains it.
