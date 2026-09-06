# Execution readiness matrix

Every property that has to hold before tenant-authored bytes may become instructions, with what establishes
it. Rows that say ACCEPTED are residual risks somebody decided to carry; rows that say BLOCKER stop the slice.

Read alongside [`karate-execution-model.md`](karate-execution-model.md), which decides that tenant Karate is
arbitrary JVM code and therefore that containment — not feature restriction — is the boundary.

## Runtime

| property | status | evidence |
|---|---|---|
| the mediated runtime is actually enforced | **ENFORCED** | the launcher reads the assigned runtime back from the daemon before start and refuses a mismatch |
| no fallback to the baseline runtime | **ENFORCED** | no catch, no flag, no branch; a command authorized for one runtime is refused by a launcher holding the other |
| runtime mismatch fails closed | **ENFORCED** | `SandboxRuntimeMismatchException` before the workload starts |
| runtime binary identity is bound into signed evidence | **CLOSED IN THIS SLICE** | attestation v5 signs `runtimeImplementationDigest`, measured from the daemon's own registration |
| runtime version is bound | **CLOSED IN THIS SLICE** | `runtimeImplementationVersion`, read by executing the registered binary |
| replacing the runtime binary invalidates evidence | **CLOSED IN THIS SLICE** | the control plane compares the attested digest against a configured set; a different digest is `RUNTIME_IMPLEMENTATION_MISMATCH` |
| a v4 document cannot authorize execution | **ENFORCED** | new domain separator; `superseded-v4` is a signed, trusted, refused vector |
| the digest cannot be operator-asserted | **ENFORCED** | the producer has no parameter for it; it is measured from `docker info` runtime registration |
| the measured binary is the one the daemon invokes | **ENFORCED** | measured from the registration path, resolved through symlinks; not `PATH`, not a typed value |
| hash-to-use window | **ACCEPTED** | the daemon opens the file later; no in-process measurement can bind that. Deployment integrity (immutable image, read-only host path) is the control, and is named as such |

## Filesystem

| property | status | evidence |
|---|---|---|
| source is read-only | **ENFORCED** | `source_mount_ro=true`, a refused write, and a refused remount |
| source is `noexec` | **ENFORCED** | `source_mount_noexec=true`, and an identical `0555` executable runs on a permissive mount and is refused here |
| source is `nosuid` | **REPORTED** | flag present; no red path exists because this runtime performs no setuid transition on any mount |
| source is `nodev` | **NOT ENFORCED — ACCEPTED** | gVisor does not implement `MS_NODEV`. See the analysis below |
| no weaker ingress copy | **ENFORCED** | no host mount of tenant source at all; `source_ingress_visible=0` |
| the consumer cannot remount | **ENFORCED** | `source_remount_refused=true`, and `java_remount=false` from a JVM |
| exact-byte integrity survives to the engine's input | **ENFORCED** | the in-sandbox verifier re-hashes the frozen filesystem against the manifest |
| scratch space is writable | **ACCEPTED, BOUNDED** | sized tmpfs, ephemeral, per-execution |
| scratch space is `noexec` | **ENFORCED** | `java_tmp_write=true` and `java_tmp_exec=false` — generated code cannot run |

### The `nodev` analysis

The question is not whether the flag is absent — it is, measured — but whether its absence enables anything.

For a device node to matter, one must exist on the source filesystem. Every way of getting one there:

1. **Through the bundle format.** It carries a logical path and content bytes. No mode, no owner, no device
   type, no major/minor. Asserted structurally, so adding such a field is a test failure.
2. **Through the bootstrap.** It creates regular files with `O_CREAT|O_EXCL|O_NOFOLLOW` at `0444` and
   platform-owned directories. It has no `mknod` call — asserted by reading its source in a test.
3. **After the freeze, by the consumer.** The filesystem is read-only and the bounding set is empty, so no
   `CAP_MKNOD`. Attacked from a JVM: `java_mknod=false`, `java_source_write=false`.
4. **By replacing the filesystem.** The consumer cannot remount it and holds no capability to mount anything.

**Decision: NODEV NOT ENFORCED; COMPENSATING CONTROLS ACCEPTED FOR EXECUTION.** Not "nodev equivalent" and not
"nodev pass". The gate asserts `source_mount_nodev=false` so the fact stays visible, and each compensating
control is asserted separately so that weakening one turns the gate red.

**What would invalidate this argument:** a bundle format that gained a mode or type field; a bootstrap that
gained a device-creating path; a consumer that gained any capability; a writable source view; or a second
source filesystem. Each has a test.

## Process

| property | status | evidence |
|---|---|---|
| uid/gid | **ENFORCED** | 65534 both, read from `/proc/self` by the JVM itself |
| capabilities | **ENFORCED** | every set empty, read by the consumer after the drop |
| no-new-privs | **ENFORCED at process level** | the bootstrap sets it; the JVM reads `NoNewPrivs=1` |
| construction privilege does not survive | **ENFORCED** | the consumer reads its own empty sets; without the capability the freeze fails and nothing becomes ready |
| tenant content cannot start before the drop | **ENFORCED** | the bootstrap execs the consumer as its last action; nothing it does interprets what it read |
| process creation | **ALLOWED, CONTAINED** | expected under the chosen model; bounded by the PID ceiling |
| threads | **BOUNDED** | 49 of 200 attempted before `pthread_create` failed — the ceiling binds tasks, not only processes |
| resource limits | **ENFORCED** | memory, swap, CPU, PID, wall clock, output, log bytes |
| a JVM fits the profile | **FEASIBLE** | starts and completes within 256 MiB / 64 PIDs / 16 MiB scratch |

## Authority

| property | status | evidence |
|---|---|---|
| assignment fencing | **ENFORCED** | epoch compare-and-set in one transaction |
| continuous execution authority | **ENFORCED** | monotonic budget, definitive loss stops the sandbox |
| cancellation | **ENFORCED** | stops the running sandbox, not only future writes |
| lease-loss termination | **ENFORCED** | fail-closed when the budget is exhausted |
| stale results rejected | **ENFORCED** | the database refuses a fenced worker's writes |
| children die with the sandbox | **ENFORCED BY CONSTRUCTION** | the container is removed on every path; a process cannot outlive its own sandbox |

## Network

| property | status | evidence |
|---|---|---|
| `DENY_ALL` | **ENFORCED** | no network at all; four raw-Java destinations refused including metadata and loopback |
| `ALLOWLIST` | **ENFORCED** | internal-only network, proxy-only routing, policy resolved server-side per request |
| raw sockets cannot bypass the proxy | **ENFORCED BY TOPOLOGY** | there is no route, so it does not depend on any engine's client honouring configuration |
| SSRF / redirects | **ENFORCED** | the proxy re-resolves and re-classifies per request |
| revocation | **ENFORCED** | fencing tears down the tunnel within a measured bound |
| cloud metadata by IP | **ENFORCED** | refused by classification and by absence of route |
| deployment-specific metadata addresses | **ACCEPTED, DEPLOYMENT CONTROL** | globally-routable provider endpoints cannot be identified by IP class alone; named as an operator responsibility |

## Engine

Everything in this section is **engine capability**, not sandbox weakness. See the threat model for the full
inventory and the classification of each.

| property | status |
|---|---|
| Java interop reaches any classpath class | **ALLOWED** — unrestricted by the engine, contained by the sandbox, bounded by classpath minimality |
| JavaScript engine | `karate-js`, no host-access gate; treated as arbitrary code |
| reflection, dynamic loading, native access | **ALLOWED**, contained |
| `read()` / `call()` / `classpath:` path containment | **REQUIRES PLATFORM WRAPPER IN KAAS-21** — must be bounded to `/kaas/source` and proven, not inherited from the engine's own normalisation |
| `read()` of a URL | **UNKNOWN — must be established in KAAS-21** before it can be permitted or refused |
| classpath minimality | **REQUIRED OF THE FUTURE IMAGE** — the classpath is the control governing what exists to be reached |

## Output

| property | status |
|---|---|
| stdout/stderr bounded and sanitised | **ENFORCED** — existing collector, ceiling and sanitiser |
| the result is platform-shaped | **REQUIRED IN KAAS-21** — a platform-owned adapter, not engine stdout as a protocol |
| test vs infrastructure outcome orthogonality | **ENFORCED** — and must not be collapsed onto an engine exit code |
| no rich artifacts, no HTML reports | **REQUIRED RESTRICTION FOR KAAS-21** |
| secrets | **OUT OF SCOPE** — first execution is secret-free and must refuse a run with secret bindings |

## Supply chain

| item | status |
|---|---|
| runtime binary | pinned by release and digest; now bound into signed evidence |
| probe image | pinned by digest; bootstrap compiled from repository source with `-Werror` |
| JVM probe image | pinned by digest, corroborated from two sources |
| Karate dependency | **NOT ADDED** — evaluated as an artifact only |
| Karate transitive set | recorded in the threat model; versions to be pinned when the dependency is added |
