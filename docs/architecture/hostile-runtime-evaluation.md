# Hostile-code runtime evaluation

**Status: EVALUATION COMPLETE. Recommendation: gVisor (`runsc`).** This document is the Phase 1 deliverable
for the stronger-runtime slice and was written before any implementation, so the decision could be argued from
measurements rather than justified after the fact.

## The question

ADR-022 states that standard shared-kernel Docker is **not approved for hostile tenant content**. Everything
built since — the hostile-execution gate, the enforceable allowlist, the signed attestation — improves what
can be *proven* about that boundary without changing the boundary itself. A container escape is still a kernel
bug away.

So: **which runtime should KaaS use for hostile tenant execution?**

## What was actually measured

Every number and behaviour below was observed on a real machine. Where something could not be measured, this
document says so rather than repeating a vendor claim.

**Test environment:** macOS 25.6.0 arm64, Docker Desktop, LinuxKit VM kernel `6.12.76-linuxkit`, aarch64.
Registered runtimes: `io.containerd.runc.v2`, `runc`. `/dev/kvm` absent; no `vmx`/`svm` in `/proc/cpuinfo`.

**Two constraints follow immediately, and they shaped the whole evaluation:**

1. **Firecracker cannot be exercised here at all.** It requires KVM, and this host exposes no hardware
   virtualization to containers.
2. **The production gVisor integration cannot be exercised here either.** Docker Desktop provides no supported
   way to register a runtime in its embedded LinuxKit VM. gVisor was therefore measured inside a *privileged
   nested container* running its own daemon — real `runsc`, real `docker run --runtime=runsc`, but nested.

### gVisor: it runs, and the KaaS profile survives

`runsc release-20240729.0`, aarch64 artifact, systrap platform. Registered through `daemon.json` and confirmed
in `docker info`. The full KaaS `DENY_ALL` profile shape launched under it:

```
--network=none --user 65534:65534 --read-only --cap-drop=ALL
--security-opt=no-new-privileges:true --pids-limit 64 --memory 256m
--tmpfs /tmp:rw,noexec,nosuid,nodev,size=16m
```

Observed from inside that sandbox:

```
kernel      = Linux version 4.4.0          ← gVisor's synthetic kernel; host is 6.12.76-linuxkit
uid / gid   = 65534 / 65534
CapEff      = 0000000000000000
CapBnd      = 0000000000000000
root write  = readonly
tmpfs write = ok
/proc/kcore = absent
docker.sock = absent
```

### Startup cost

Ten warm trivial container starts, each runtime, same nested daemon. The timer had one-second resolution
(busybox `date` has no `%N`), so these carry roughly ±100 ms:

| Runtime | 10 starts | Mean |
|---|---|---|
| `runc` | 1 s | ~100 ms |
| `runsc` | 3 s | ~300 ms |

Roughly **3× startup, about 200 ms absolute**. For a test-execution platform whose runs last seconds to
minutes, that is immaterial. It would matter for a function-per-request workload, which this is not.

**This is not a production capacity measurement.** It is one nested aarch64 laptop. It says the overhead is in
the hundreds of milliseconds, not the tens of seconds — nothing more.

### What could not be measured, and is therefore not claimed

**Bridge networking under `runsc`.** The nested attempt failed with `cannot run with network enabled in root
network namespace`, which is an artifact of running a daemon inside a privileged container, **not** a gVisor
limitation. The `ALLOWLIST` path — sandbox on a per-execution internal network, reaching a proxy — is
therefore **unproven** and must be demonstrated on a real Linux host before any claim is made about it.

## Candidate comparison

Scored against the criteria this platform actually has, not against a generic threat model.

| # | Criterion | Standard Docker (`runc`) | **gVisor (`runsc`)** | Firecracker microVM |
|---|---|---|---|---|
| 1 | Kernel isolation strength | Shared host kernel; namespaces + cgroups + seccomp | Userspace application kernel intercepts guest syscalls; host syscall surface materially reduced | Hardware-virtualized guest kernel; strongest of the three |
| 2 | Host-kernel exposure | Full syscall surface, filtered by seccomp | Sentry serves most syscalls in userspace; a much smaller set reaches the host, itself seccomp-filtered | Only the KVM/virtio interface |
| 3 | Image compatibility | Native OCI | **Native OCI, unchanged** — measured | Requires rootfs conversion from images |
| 4 | Java compatibility | Native | Good; JVM is a well-exercised gVisor workload | Native inside the guest, once booted |
| 5 | Startup latency | ~100 ms (measured) | **~300 ms (measured)** | Typically ~125 ms boot plus rootfs/kernel setup; unmeasured here |
| 6 | Per-run overhead | Minimal | One sentry process per sandbox | One VMM process plus guest kernel memory |
| 7 | Network model | Docker bridge / none | Netstack or host; Docker-integrated | tap devices, managed per microVM |
| 8 | **Allowlist proxy topology** | Proven (kaas-13) | **Unproven — see above.** Expected to work, not asserted | Would need the whole tap lifecycle rebuilt |
| 9 | Read-only root / tmpfs | Proven | **Measured working** | Supported, via rootfs construction |
| 10 | PID / memory / CPU limits | cgroups | cgroups, via the same OCI spec | VM-level sizing, different semantics |
| 11 | Filesystem semantics | Host VFS | Gofer-mediated; some `/proc` and `/sys` differences (see below) | Guest kernel VFS |
| 12 | Syscall compatibility | Complete | High but not total; unimplemented syscalls return `ENOSYS` | Complete inside the guest |
| 13 | Signal / termination | Standard | Standard through the OCI lifecycle | VMM shutdown, guest agent needed |
| 14 | **Observable runtime identity** | `/proc/version` is the host kernel | **`/proc/version` = `Linux version 4.4.0`, gVisor's fixed synthetic kernel — measured, and not forgeable by container configuration** | Guest kernel identity, chosen by the operator |
| 15 | Orphan cleanup | Existing reconciler | Same Docker objects, same labels; sentry dies with the container | Extra state: VMM processes, tap devices, jailer directories |
| 16 | Multi-tenant isolation | Namespace-level | Per-sandbox userspace kernel | Per-tenant VM |
| 17 | Operational complexity | None added | One pinned binary plus a `daemon.json` entry | A new execution architecture |
| 18 | Local development | Works everywhere | **Not on Docker Desktop** — no supported runtime registration | Needs KVM; unavailable on this machine |
| 19 | GitHub-hosted CI | Works today | Expected to work via systrap without KVM — **to be proven in CI** | Needs nested virtualization GitHub-hosted runners do not provide |
| 20 | Self-hosted runner need | None | None expected | Almost certainly required |
| 21 | Portability | Universal | Linux x86_64 and arm64 | Linux with KVM |
| 22 | Supply-chain surface | Existing | One additional pinned binary from a Google-operated bucket | VMM, guest kernel image, rootfs pipeline |
| 23 | Debugging | Familiar | Mostly familiar; `ENOSYS` failures can be confusing | Guest-level debugging is materially harder |
| 24 | Can re-run the existing gate | Yes | **Yes, with two evidence changes** — see below | Would need a new probe delivery mechanism |
| 25 | Can produce signed evidence | Yes | Yes, same producer | Yes, but the producer would need a guest agent |
| 26 | Long-running Karate suitability | Adequate | Adequate | Adequate |

## Control compatibility

The prompt's rule was: *do not preserve a `PASS` merely because the name existed before.* Every mandatory
control was checked against what gVisor actually does.

| Control | Docker semantics | gVisor semantics | Enforcement | Evidence | Same test? |
|---|---|---|---|---|---|
| `NON_ROOT_UID` | OCI user | Identical | OCI spec | `id -u` | **yes** — measured |
| `NON_ROOT_GID` | OCI user | Identical | OCI spec | `id -g` | **yes** — measured |
| `READ_ONLY_ROOT` | ro rootfs | Identical | OCI spec | write attempt | **yes** — measured |
| `WRITABLE_TMPFS` | tmpfs mount | Identical | OCI spec | write attempt | **yes** — measured |
| `NO_DOCKER_SOCKET` | no bind | Identical | no mounts | path absent | **yes** — measured |
| `NO_HOST_MOUNTS` | no binds | Identical | no mounts | mount table | **yes** |
| `NO_HOST_DEVICES` | device cgroup | Gofer exposes no host devices | runtime | device enumeration | **yes** |
| `KERNEL_PATHS_MASKED` | masked with `/dev/null` binds | **Not implemented at all** — `/proc/kcore`, `/proc/sys/kernel/core_pattern` simply absent | runtime | path absent | **evidence must accept absence as well as masking** |
| `CAPABILITIES_DROPPED` | `CapEff`/`CapBnd` = 0 | Identical | OCI spec | `/proc/self/status` | **yes** — measured `0000000000000000` |
| `NO_NEW_PRIVILEGES` | `NoNewPrivs: 1` | **The `NoNewPrivs` line is absent from `/proc/self/status`** | OCI spec — still enforced | **breaks** | **NO — needs new evidence** |
| `MINIMAL_ENVIRONMENT` | env allowlist | Identical | launcher | `env` | **yes** |
| `NETWORK_DENIED` | no netns | Identical | OCI spec | interface enumeration | **yes** |
| `PID_LIMIT` | pids cgroup | cgroup, same spec | cgroups | fork attempt | **yes**, to re-prove |
| `MEMORY_LIMIT` | memory cgroup | cgroup, same spec | cgroups | allocation attempt | **yes**, to re-prove |
| `WALL_CLOCK_TIMEOUT` | launcher | launcher | launcher | elapsed | **yes** |
| `OUTPUT_BOUNDED` | collector | collector | launcher | truncation | **yes** |

Two findings deserve to be called out, because they are exactly the sort of thing that turns into a false
`PASS` if nobody looks:

**`NO_NEW_PRIVILEGES` loses its evidence mechanism.** The control is still *enforced* — Docker puts
`noNewPrivileges` in the OCI spec and `runsc` honours it — but the guest `/proc/self/status` gVisor synthesizes
does not include the `NoNewPrivs` line. A probe that keeps grepping for it finds nothing, and "nothing" must
never be read as a pass. This control needs a different observation under gVisor, or must be reported as
`UNSUPPORTED` there, which is not a pass either.

**Guest `Seccomp` reads `0`.** gVisor applies seccomp between the *sentry* and the *host* kernel, not inside
the guest. Any assertion that the guest is running under a non-trivial seccomp mode would be false under
gVisor — and would be a claim about the wrong boundary anyway.

## Runtime identity: can `runc` masquerade as gVisor?

This is the question the whole slice turns on, because a "strong runtime" that silently ran under `runc` would
be worse than not having one — it would be a false claim with a gate behind it.

Two independent observations, deliberately from different sides:

1. **Launcher side.** `docker inspect` reports `HostConfig.Runtime`. This is what was *requested*.
2. **Inside the sandbox.** `/proc/version` reports `Linux version 4.4.0 #1 SMP Sun Jan 10 15:06:54 PST 2016` —
   gVisor's fixed synthetic kernel. This is what was *enforced*.

The second cannot be produced by a `runc` container unless the host kernel is literally 4.4.0 from January
2016, and a container cannot choose what `/proc/version` says about it. Requested and enforced are therefore
separately observable, which is the same discipline every previous slice applied to image digests, network
isolation, and attestation signatures.

## Recommendation

**Select gVisor (`runsc`). Do not select Firecracker now.**

The decision rule was: the smallest runtime change that materially reduces host-kernel attack surface, without
making the platform operationally unviable.

**gVisor satisfies it.** It keeps the OCI image model, the security profile, the egress proxy topology, the
labels the reconciler matches on, and the signed-attestation producer. It is a launcher change plus a pinned
binary and a `daemon.json` entry — not a new execution architecture. The measured cost is ~200 ms per run.

**Firecracker is deferred, not rejected.** It provides the stronger boundary, and if KaaS ever runs untrusted
code at a scale where a sentry compromise is an unacceptable residual, it becomes the right answer. What it
costs today is a different execution model: kernel image management, rootfs generation, guest init, a command
delivery agent, result extraction, tap interface lifecycle, microVM reconciliation, and CI that
GitHub-hosted runners cannot provide. That is a slice of its own, and taking it now would replace a boundary
problem with an architecture problem.

**Standard Docker remains** for what it is already trusted to do, and gains no new permission. It must not
become selectable as a weaker option for anything that requires the stronger runtime — the downgrade has to be
unrepresentable rather than merely discouraged.

## What gVisor does not fix

Stated precisely, because "gVisor means no shared kernel" is wrong and worth refusing out loud:

- **It is not a virtual machine.** The sentry is a userspace process on the host kernel. It reduces the
  syscall surface reachable from the guest; it does not eliminate the host kernel from the picture.
- **A sentry compromise is a host-adjacent compromise.** The sentry itself is seccomp-confined, which is why
  the escape path is narrower — not absent.
- **Unimplemented syscalls surface as `ENOSYS`.** A future Karate workload may hit one. That is a
  compatibility risk to be discovered by running things, not by reading a table.
- **It does not satisfy every ADR-022 prerequisite.** It addresses the runtime one. Tenant source delivery,
  secret injection, and the rest are untouched, and no part of this evaluation approves tenant code.

## Open items before implementation can claim anything

1. **Prove `ALLOWLIST` under `runsc` on a real Linux host.** Unproven here, and the matrix says so.
2. **Prove `runsc` on GitHub-hosted Ubuntu**, or define the self-hosted runner requirement instead of mocking
   a gate green.
3. **Replace the `NO_NEW_PRIVILEGES` evidence mechanism**, or report it truthfully as unsupported under gVisor.
4. **Pin the runtime.** Version, source, and binary digest — `runsc found on PATH` is not an identity. The
   aarch64 `release-20240729.0` artifact measured here has SHA-256
   `f2c5f96fd9e60910c6b7c2d7ce9741ce40131250f93a866302ef081c66179279`; the CI architecture will need its own.
5. **Re-prove resource limits**, rather than assuming cgroup semantics carry over.
