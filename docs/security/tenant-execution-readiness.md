# Tenant execution readiness

Whether the platform is ready for a first secret-free Karate execution, what is being accepted to say so, and
exactly what that first slice may do.

**Verdict: READY FOR SECRET-FREE KARATE EXECUTION SLICE**, with the restrictions in the last section, which
are binding rather than advisory.

## The question that changed

Every slice up to KAAS-19 answered: *can tenant bytes enter the sandbox safely as data?* This one asks: *can
those bytes be interpreted as instructions?* Those are different boundaries, and `noexec` answers only the
first. It stops the kernel executing a file from `/kaas/source`. It does nothing about an interpreter reading
those bytes and acting on them — and the interpreter is the point.

## What is READY

Each of these is measured under the mediating runtime, from inside the sandbox, by the process a future engine
would be.

1. **The runtime implementation is bound.** Signed evidence now names the exact `runsc` binary — version,
   SHA-256, and an opaque identity for where it was registered — measured from the daemon's own configuration
   with no operator-supplied value anywhere. Replacing the binary makes every existing attestation stop
   authorizing execution, automatically, with no procedural step to forget.
2. **The source filesystem holds against a JVM.** Write, chmod, direct execution, remount and `mknod` all
   refused, attacked from Java against a real delivered bundle.
3. **Generated code cannot execute.** Scratch space is writable and `noexec`: the write succeeds and the
   execution does not, so `noexec` on the source filesystem is not trivially bypassed by writing elsewhere.
4. **No privilege survives construction.** Every capability set empty and uid 65534, read out of `/proc` by
   the consumer rather than asserted by whatever dropped them. No-new-privs is requested by the launcher and
   set by the bootstrap; this runtime does not expose it for reading back, and that is reported as
   `unsupported` rather than as a pass.
5. **Raw Java networking reaches nothing.** DNS and three socket destinations, including cloud metadata and
   loopback, all refused under `DENY_ALL` — by topology, not by any engine's client honouring configuration.
6. **No platform authority is visible.** No daemon socket, no credential in the environment, and the
   environment's names are asserted against a closed set.
7. **Process creation is bounded.** Child processes are subject to the PID ceiling and die with the sandbox.
8. **A JVM fits the profile.** It starts and completes within 256 MiB, 64 PIDs and a 16 MiB scratch
   filesystem, so the first engine slice will not discover a resource problem disguised as a security one.

## What is ACCEPTED

Residual risks carried deliberately. Each is written as what it is, not as a pass.

### `nodev` is not enforced

gVisor does not implement `MS_NODEV`. The flag is absent from the source filesystem and a device node on such
a filesystem behaves as a device — measured, with the baseline runtime refusing the same read.

**Accepted**, because no reachable path puts a device node there: the format cannot express one, the bootstrap
has no call that creates one, the filesystem is frozen before the consumer starts, the consumer holds no
`CAP_MKNOD`, and it cannot remount or replace the filesystem. Attacked from a JVM, `java_mknod=false`.

Recorded as **NODEV NOT ENFORCED, COMPENSATING CONTROLS ACCEPTED FOR EXECUTION**. Each compensating control has
its own test, so weakening any one of them turns the gate red rather than quietly widening the acceptance.

### The construction phase is privileged

The sandbox's first process holds `CAP_SYS_ADMIN` and three identity capabilities for the length of one
populate-and-freeze. Accepted because it is platform-owned with a fixed argument vector, no tenant byte reaches
a privileged operation, the drop is read back from the far side, and the absence of the capability fails the
freeze rather than degrading it.

### The measure-to-use window

The producer hashes the runtime binary; the daemon opens it later. Nothing in-process can bind that. Accepted,
with deployment integrity named as the control rather than implied.

### The PID ceiling does not bound JVM threads

Measured, and it differs by runtime: under the baseline runtime a JVM is stopped at 49 of 200 attempted
threads by `pthread_create` returning `EAGAIN`; under the mediating runtime the same workload starts all 200.
gVisor does not charge Java threads against the container's pids limit.

**Accepted**, because a thread explosion is still bounded — by the memory ceiling each thread's stack draws
against, and by the wall-clock deadline behind that. But it is a weaker and less direct bound than the
profile's `PID_LIMIT` appears to promise, and it is written down rather than left to be discovered by whoever
first tunes the limits. The test asserts the current behaviour in the direction it is true, so a runtime that
begins charging threads fails it and this acceptance is removed rather than persisting unnoticed.

### Cloud metadata by hostname on some providers

Endpoint addresses that are globally routable cannot be identified by IP classification alone. Accepted as a
deployment-level control, and stated as a production prerequisite rather than a solved problem.

## What is NOT ready, and is therefore a KAAS-21 requirement

These are not blockers to *starting* the engine slice; they are things that slice must build, and it cannot be
called complete without them.

1. **Source read containment.** `read()`, `call()` and `classpath:` must be bounded to `/kaas/source`, with
   traversal outside it proven to fail. Not inherited from the engine's own path handling.
2. **`read()` of a URL.** Its actual behaviour in 2.1.2 is not established. It must be determined and then
   either refused or routed through the egress policy — never allowed to become an unpoliced include.
3. **A platform-owned result adapter.** The runner must not treat engine stdout as a result protocol.
4. **A minimal engine image.** Under the fully-hostile model the classpath is a security control: a JRE,
   Karate and its declared dependencies, and nothing else.

## First execution restrictions

Binding on KAAS-21. Each exists because something above is accepted rather than solved.

| restriction | why |
|---|---|
| secret-free only; a run with secret bindings is refused by both the control plane and the runner | every acceptance above was reasoned without secrets in the sandbox |
| mediated runtime only | the boundary is measured there and nowhere else |
| an exact pinned engine image, digest-addressed | the classpath is the control |
| source only from the prepared, frozen filesystem | the only source view whose properties are known |
| no rich artifacts, no HTML report ingestion, no report persistence | tenant-controlled content rendered or stored is a separate decision |
| bounded, platform-shaped results | the engine contributes evidence, not control-plane fields |
| existing egress policies only | no new network shape alongside a new execution shape |
| no tenant control of runtime, image, mounts, JVM flags or system properties | tenant config stays data |

## What would reopen this decision

A change to any compensating control for `nodev`; a runtime upgrade (which invalidates the evidence
automatically and requires re-measurement); a Karate version whose interop or include semantics differ; the
arrival of secrets; or any widening of the result boundary.
