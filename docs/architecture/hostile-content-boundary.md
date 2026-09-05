# The hostile-content boundary, composed

**Status: ADJUDICATED for inert tenant-byte delivery.** Nothing tenant-authored executes. See
[the readiness document](../security/hostile-content-readiness.md) for what that permits and forbids.

## The composed system

Edges marked `(FUTURE)` do not exist in the repository. They are drawn because the boundary has to be judged
against what will cross it, not only against what crosses it today.

```
                                  TENANT
                                    |
                     X  no source delivery exists today
                                    |
                    (FUTURE) assignment-scoped SourceCapability
                                    |
                                    v
+---------------------------------------------------------------------------+
|                              CONTROL PLANE                                 |
|                                                                            |
|   immutable snapshot ---- assignment / epoch ---- signed runtime evidence   |
|          |                        |                        |               |
|          +------------------------+------------------------+               |
|                                   |                                        |
|                       ExecutionCommand (digest-bound)                      |
|                       + egress policy  + profile version  + runtime        |
+-----------------------------------|---------------------------------------+
                                    |
                                    v
+---------------------------------------------------------------------------+
|                                 RUNNER                                     |
|                                                                            |
|   continuous authority monitor -----> terminates the sandbox on:           |
|      renew / definitive refusal /       cancellation, fencing,             |
|      unreachable -> lease budget        lease expiry, budget exhaustion    |
|                                                                            |
|   required mediated runtime, requested AND confirmed from inside           |
|   no fallback to the baseline                                              |
+-----------------------------------|---------------------------------------+
                                    |
                                    v
+---------------------------------------------------------------------------+
|                            gVISOR SANDBOX                                  |
|                                                                            |
|   non-root uid       empty capability bounding set    read-only root       |
|   no setuid binaries    tmpfs noexec,nosuid,nodev     no docker socket     |
|                                                                            |
|   (FUTURE) read-only source bundle, noexec/nosuid/nodev, bounded, verified |
|   NO secrets            NO tenant-chosen runtime configuration             |
+-----------------------------------|---------------------------------------+
                                    |
                DENY_ALL            |            ALLOWLIST
                (no network)        |            (per-execution internal network,
                                    |             proxy is the only peer)
                                    v
+---------------------------------------------------------------------------+
|                          BOUNDED UNTRUSTED OUTPUT                          |
|                                                                            |
|   byte ceiling + truncation corroborated against bytes actually retained   |
|   control characters and Unicode format characters stripped at the collector|
|   only `key=value` lines become observations                               |
+-----------------------------------|---------------------------------------+
                                    |
                                    v
+---------------------------------------------------------------------------+
|                       STRICT CONTROL-PLANE BOUNDARY                        |
|                                                                            |
|   workload_outcome  ==  "PASSED"  ->  ONE BOOLEAN                          |
|   every other field of the result comes from the COMMAND, not the sandbox  |
|   provenance checked against authoritative state before acceptance         |
+---------------------------------------------------------------------------+
```

## The property that makes output safe

The narrowest and most important edge in the diagram is the last one. A sandbox can say a great deal, and
exactly one thing it says is load-bearing: whether `workload_outcome` equals the literal `PASSED`. That
comparison produces a boolean. Every other field in the submitted result — identifiers, instants, digests,
epoch — comes from the command the control plane issued.

So tenant-influenced bytes cannot become a field, a path, a name, or a configuration value. They can only
move one bit, and the control plane checks that bit's provenance before accepting it.

## Where each axis is bounded

| Axis | Bound | Established by |
| --- | --- | --- |
| What runs | An enum constant naming a repository-built probe | ADR-022 |
| Which boundary | Profile version derived from the runtime, refused if it does not match | ADR-028 |
| How long | Profile deadline **and** continuous authority | ADR-024, ADR-029 |
| Where it can reach | `DENY_ALL`, or one proxy on one internal network | ADR-025, ADR-026 |
| What it can say | Byte ceiling, sanitised, reduced to a boolean | ADR-022, this slice |
| Whether we believe it | Ed25519-signed evidence, pinned key, runtime-scoped controls | ADR-027, ADR-028 |

## What is deliberately not drawn

There is no edge from tenant input to any box in the runner or the sandbox other than the future bundle. That
absence is structural rather than conventional: the launch request carries a probe enum, a profile version
compared for equality, and a correlation id — and the tenant-authored `tags` the command carries are never
read by the runner at all.
