# Secret-free Karate execution slice report

## 1. Executive summary

**Real Karate 2.1.2 now runs real tenant features inside the mediated sandbox.** A bundle is authorized,
verified, framed, delivered over stdin, written to a sandbox-private filesystem, frozen read-only, and handed
to a JVM that starts Karate and executes code the platform did not write. The platform reads one key back.

This is the first slice in which the product executes something a customer wrote. Four prior slices built
toward it by refusing to: ADR-030 delivered tenant bytes as inert data, ADR-031 put them on a filesystem that
would not execute them, ADR-032 adjudicated whether they *may* become instructions and said yes for a
secret-free slice under binding restrictions. This slice ran it.

**Three things came out differently from how they were reasoned about beforehand, and all three are recorded
as reversals rather than quietly absorbed:**

1. **Source resolution is not confined, and confining it was abandoned as security theatre.** ADR-032's threat
   model said this slice "must bound source resolution to `/kaas/source` and prove traversal outside it
   fails". Measurement showed Karate applies no root confinement — `../../../etc/hostname` resolves to
   `/kaas/etc/hostname`, and `read('file:/etc/passwd')` returns the container's file. A wrapper over `read()`
   would not have been a boundary: `Files.readAllBytes` is one `Java.type` away. The platform confines *what
   there is to read* instead.
2. **Tenant code CAN emit the result protocol line**, via `System.out.println` through Java interop. Karate's
   own `print` does not reach stdout at all, which is why an earlier version of the test passed for the wrong
   reason. Forging is not preventable and is not treated as preventable: a stream carrying the result key
   twice is refused, so a forged pass becomes an infrastructure failure.
3. **The engine's environment is exactly three variables**, not the profile's declared allowlist. The
   bootstrap's `execve` passes an empty `envp`, discarding the profile's `KAAS_SANDBOX` and `PATH` entirely.
   `LD_LIBRARY_PATH`, `SHLVL`, `PWD` — all created after the boundary closed. This is stricter than designed,
   and it is asserted exactly rather than by the absence of known-bad names.

One measurement was nearly recorded as a security control it is not: a raw-socket probe reported `REFUSED`
because karate-js does not support `new Socket(...)`, not because anything blocked it. Re-measured with
`SocketChannel` and a **literal IP address** — so DNS absence is not doing the work — the answer is `Network
unreachable`, which is the real topological claim.

**Nothing earlier was weakened to make this green.** `source_mount_nodev=false` (ADR-031 §47) and
`java_threads_bounded=false` (ADR-032 §60) remain accepted residuals, untouched.

## 2. What was built

| Component | Location | What it is |
| --- | --- | --- |
| Engine module | `services/karate-engine` | the only module that may carry Karate, under either coordinate |
| Adapter | `KaasKarateAdapter` | platform-owned, unprivileged, decides what Karate is asked to run |
| Engine image | `services/karate-engine/src/main/docker` | two-stage, digest-pinned; JRE + engine classpath + bootstrap |
| Image builder | `KarateEngineImage` | refuses a context shipping the wrong engine version, none, or two |
| Result protocol | `EngineOutcome` | one key, five verdicts, duplicates refused |
| Engine probe | `SyntheticProbe.KARATE_ENGINE` | the one probe whose program is the product |
| Bootstrap mode | `source-bootstrap.c` `ENGINE_MODE` | a third compile-time handover literal |
| Secret-free refusal | `ENGINE_REQUIRES_SECRET_FREE_RUN` | denies secret-bearing runs before the provider check |
| CI gate | `karate-execution-gate` | ninth mandatory job; fails unless a run names the engine |

## 3. The success chain, demonstrated

Every step below was observed, not inferred:

- the bundle is verified against its authorized entry set and per-entry digests;
- the bootstrap writes `0444` regular files and a manifest, freezes the filesystem, drops all capability;
- the handover reaches a JVM with an **empty** environment;
- the adapter resolves `karate-meta.properties` **through Karate's own classloader** and reports
  `kaas.engine=karate 2.1.2`;
- Karate executes the exact features the manifest names;
- `* match 1 == 1` yields `PASSED`; `* match 1 == 2` yields `FAILED`;
- two features where the second fails yields `FAILED`, so both ran.

The last three are the real anti-vacuity: an adapter that printed verdicts without loading an engine could not
distinguish those inputs.

## 4. Measured semantics of `read()`, `call()` and URLs

Requested by ADR-032's threat model, which left them `UNKNOWN`. All measured against a running engine:

| Form | Result | Classification |
| --- | --- | --- |
| `read('../data/value.json')` within the bundle | works | ALLOWED — the normal include mechanism |
| `call read('helper.feature')` | works | ALLOWED |
| `read('../../../etc/hostname')` | resolves to `/kaas/etc/hostname` — **outside the source root** | ALLOWED, CONTAINED by the filesystem only |
| `read('file:/etc/passwd')` | returns the container's file; `contains 'root'` matched | ALLOWED, CONTAINED — the image is platform-built and secret-free |
| `read('classpath:META-INF/MANIFEST.MF')` | resolves | ALLOWED, CONTAINED — the classpath is a platform-owned control |
| `read('http://example.com/')` | **not a network operation** — resolved as the relative path `…/features/http:/example.com` | N/A |
| `Given url … / method get` | `java.net.UnknownHostException` | DENIED BY TOPOLOGY; reported as a tenant `FAILED` |

No `UNKNOWN` remains in the threat model.

## 5. The hostile capability surface, enumerated in one run

Asked as one process rather than nine, because the interesting claims are conjunctions — interop works *and*
platform classes are absent; scratch is writable *and* not executable.

| Probe | Result | Reading |
| --- | --- | --- |
| `Java.type('java.lang.ProcessBuilder')` | REACHABLE | **positive control** — refusals below mean something |
| `Java.type('com.kaas.runner.sandbox.DockerSandboxLauncher')` | `class not found` | **the classpath is the control** |
| process spawn | `EXIT:0` | ALLOWED, accepted by ADR-032 |
| system properties | readable | ALLOWED |
| scratch write | `WROTE` | ALLOWED, bounded |
| scratch execute | `EXIT:126` | DENIED — `noexec`, measured through the product |
| DNS | refused | DENIED |
| `SocketChannel` to a **literal IP** | `Network unreachable` | DENIED BY TOPOLOGY, without relying on DNS |
| `mount -o remount,rw /kaas/source` | `EXIT:1` | DENIED |
| write after the failed remount | `Read-only file system` | the freeze holds |

## 6. Absent evidence is never a pass

Three ways a run ends with no verdict, all measured, all resolving to `ABSENT`:

| Ending | What the container looks like | What the platform reports |
| --- | --- | --- |
| endless feature | killed at the wall clock | `SANDBOX_TIMEOUT` + `ABSENT` |
| `System.exit(0)` mid-suite | **exit status 0**, no assertion run | `ABSENT` |
| stdout flood past the ceiling | truncated | not `completed()` |

The second is the sharpest: anything deriving an outcome from the exit status reports `PASSED` there.

## 7. Secret-free, three ways

1. the control plane refuses `ENGINE_REQUIRES_SECRET_FREE_RUN`, **before** the provider check — ordering is
   the decision, because the provider check stops firing once a provider is configured;
2. the runner refuses secret-bearing commands independently, and did so before this slice;
3. there is no environment to leak into — three variables, all created after the boundary closed.

Tested on three axes: KARATE + secret → refused; KARATE + no secret → **not** refused for that reason;
SYNTHETIC + secret → refused for a *different* reason. Without the second, a rule refusing every KARATE run
would pass. Without the third, the two rules could be collapsed into one.

## 8. Mutation results

| # | Mutation | Caught by |
| --- | --- | --- |
| K-M1 | `EngineOutcome` stops refusing a duplicated result key | the forged-pass test |
| K-M2 | the adapter stops refusing an escaping manifest entry | two adapter tests (`../` and absolute) |
| K-M3 | the secret-free refusal is removed | `aKarateRunCarryingASecretBindingIsRefused` |
| K-M4 | the refusal fires for every KARATE run, secrets or not | `aKarateRunCarryingNoSecretIsNotRefusedForThatReason` |

All four killed. K-M4 is the direction a one-sided test would have missed.

## 9. Defects found and fixed during the slice

| # | Defect | How it was found |
| --- | --- | --- |
| 1 | the forged-result test used Karate's `print`, which never reaches stdout — it was green while measuring nothing | the test failed for the right reason and the output was read rather than the assertion adjusted |
| 2 | the environment assertion expected zero variables; the real answer is three | running it |
| 3 | the source-write probe's `catch` reported `REFUSED` for a JavaScript `TypeError` as readily as for a read-only filesystem | asking what the refusal actually was |
| 4 | a raw-socket probe reported `REFUSED` because karate-js does not support `new Socket(...)` | the result looked too good |
| 5 | `javax.sql.DataSource` was asserted absent from the engine; it ships in the JDK | the assertion failed |
| 6 | the engine module had no JUnit platform launcher, so its tests could not run at all | adding the first test |
| 7 | the API architecture test banned only `com.intuit.karate`, not `io.karatelabs` | auditing the guards after adding the engine |

## 10. What this slice did not do

Secrets, `SecretCapability` redemption, secret injection, HTML or JSON reports, report persistence,
screenshots, artifact retention, object storage, SSE, quality gates, engine-outcome-based retries, arbitrary
Karate CLI flags, arbitrary JVM flags, arbitrary classpath entries, tenant-selected engine versions, images or
working directories, plugins, tenant-uploaded libraries, arbitrary JAR loading.

`source_mount_nodev=false` and `java_threads_bounded=false` remain accepted residuals, unchanged.

## 11. Residual risks

| Risk | Status |
| --- | --- |
| a tenant can influence its own test outcome | **accepted** — a property of executing arbitrary code; ADR-032 |
| a tenant can read the engine image and the classpath | **accepted** — both are platform-built and secret-free |
| a tenant can read outside `/kaas/source` within the container | **accepted** — no host mount, no other tenant's data, no secret |
| `MS_NODEV` unimplemented by gVisor | unchanged from ADR-031 §47 |
| Java threads not bounded by the PID ceiling | unchanged from ADR-032 §60 |
| the engine's classpath widens with every dependency added | mitigated: the runner's tests treat it as an input; guards ban it elsewhere |

## 12. Verification

`./gradlew clean check` — **BUILD SUCCESSFUL**, 11m 21s.

| Module | Task | Tests |
| --- | --- | --- |
| `apps/api` | `test` | 336 |
| `services/egress-proxy` | `test` | 116 |
| `services/karate-engine` | `test` | 7 |
| `services/runner` | `test` | 226 |
| `services/runner` | `egressSecurityTest` | 36 |
| `services/runner` | `karateExecutionTest` | **11** |
| `tests/pipeline` | `test` | 39 |
| | **Total** | **771** |

**0 skipped, 0 failures.** A skipped test in a security suite is an unproven claim, so the CI gates assert
zero skips rather than only zero failures.

`strongRuntimeTest` is not in this total and cannot be: it needs `runsc` registered on the daemon, which
Docker Desktop offers no supported way to install. It runs in the mandatory `strong-runtime-gate` CI job.
**A green local build proves nothing about the mediating runtime** — that has been true since ADR-028 and is
still true.

## 13. What the CI gate refuses to accept

`karate-execution-gate` is mandatory and has no `if:`, no `continue-on-error`, and no path filter. Beyond
running the suite it independently checks:

- test results exist at all — a missing directory is a failure, not an absence;
- at least 9 tests executed and **zero** were skipped;
- some run reported `kaas.engine=karate 2.1.2`. That string can only be produced by resolving
  `karate-meta.properties` through Karate's own classloader, so a green job is inconsistent with an adapter
  that printed verdicts and never started an engine;
- no KaaS-managed container outlived the suite;
- the task was deleted before running, so an `UP-TO-DATE` result cannot satisfy the gate.

It also runs the engine module's own suite, which reads the version from the jar rather than from a line the
adapter printed — the version claim does not depend on the adapter's honesty.

## 14. Where the evidence lives

| Claim | File |
| --- | --- |
| real Karate executes the authorized features | `services/runner/src/test/java/com/kaas/runner/sandbox/KarateExecutionTests.java` |
| the runner cannot load Karate; the image ships exactly 2.1.2 | `.../sandbox/EngineTrustBoundaryTest.java` |
| the manifest decides what runs; escapes refused; the jar is 2.1.2 | `services/karate-engine/src/test/java/com/kaas/karate/KaasKarateAdapterTest.java` |
| secret-bearing runs are refused, on three axes | `apps/api/src/test/java/com/kaas/api/ExecutionAuthorizationTests.java` |
| the decision record | `docs/adr/033-first-secret-free-karate-execution.md` |
| the security argument | `docs/security/secret-free-karate-execution.md` |
| the measured capability inventory | `docs/security/karate-hostile-execution-threat-model.md` |
| the execution path, step by step | `docs/architecture/karate-execution-path.md` |

## 15. What must happen before the next slice

- **Secrets are unadjudicated.** ADR-033 authorizes secret-free execution only. Introducing a secret changes
  two rows of the threat model at once: environment reads become interesting, and failure messages become a
  disclosure channel. Both were reasoned about as *absent*.
- **Reports and artifacts are unadjudicated.** Every Karate report form is off, and the result surface is one
  key. Widening it is a decision, not a configuration change.
- **The engine's classpath is now a reviewed surface.** Adding a dependency to `services/karate-engine`
  widens what tenant code can reach and should be treated as a security change.
