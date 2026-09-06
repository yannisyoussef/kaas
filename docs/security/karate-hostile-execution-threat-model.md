# Karate hostile execution threat model

What tenant-authored Karate source could attempt once it becomes instructions, and what stops each attempt.
Written before Karate exists in this repository, because the point of adjudicating first is to know what is
being admitted.

Two things are kept apart throughout, and conflating them is the mistake this document exists to prevent:

- **ENGINE CAPABILITY** — what the interpreter lets source do.
- **SANDBOX CONTAINMENT** — what happens when it does.

A capability is not a vulnerability. An uncontained capability is.

## The engine

Karate **2.1.2**, `io.karatelabs:karate-core`, MIT, published 2026-08-14. JavaScript is `karate-js`, the
project's own engine, which replaced GraalJS in Karate 2.x. Declared runtime dependencies: `karate-js`,
`json-smart`, `json-path`, `httpclient5`, `netty-handler`, `netty-codec-http`, `snakeyaml`, `fastcsv`,
`thymeleaf`, `picocli`, `brotli-dec`, and `logback` at runtime scope.

## Capability inventory

Classified per §26 with no "probably blocked".

| capability | engine | classification | what contains it |
|---|---|---|---|
| `Java.type(...)` → any class | **available, unrestricted** — `Class.forName` on the context classloader, no filter | **ALLOWED** | classpath minimality; sandbox |
| construct + invoke by reflection | available | **ALLOWED** | sandbox |
| `Runtime.exec` / `ProcessBuilder` | reachable through interop | **ALLOWED** | PID ceiling; dies with sandbox; `java_child_spawned=true` measured |
| dynamic class loading (`URLClassLoader`, `Class.forName`) | reachable | **ALLOWED** | no network to fetch from under `DENY_ALL`; classpath is fixed |
| define bytecode at runtime | reachable | **ALLOWED** | contained; no host effect |
| native library load (`System.load`) | reachable | **ALLOWED** | gVisor mediates syscalls regardless of who issues them |
| write files | reachable | **ALLOWED, BOUNDED** | scratch tmpfs only: sized, `noexec`, ephemeral |
| execute a written file | reachable | **DENIED BY SANDBOX** | `java_tmp_exec=false` measured |
| write/replace/chmod source | reachable | **DENIED BY SANDBOX** | frozen read-only filesystem; all measured `false` |
| create a device node | reachable | **DENIED BY SANDBOX** | read-only filesystem + empty bounding set; `java_mknod=false` |
| raw sockets, DNS | reachable | **DENIED BY TOPOLOGY** under `DENY_ALL`; proxy-only under `ALLOWLIST` | measured for four destinations |
| read environment / system properties | available | **ALLOWED** | environment is built from empty; asserted to carry no credential |
| threads / async | available | **ALLOWED, BOUNDED** | PID ceiling bounds tasks; measured at 49 of 200 |
| `read()` / `call()` / `classpath:` | available | **REQUIRES PLATFORM WRAPPER** | see below — the one item needing work in KAAS-21 |
| `read()` of a URL | **UNKNOWN — must be established in KAAS-21** | **REQUIRES PLATFORM WRAPPER** | egress topology contains it either way |
| XML / YAML / JSON parsing | available | **ALLOWED** | resource limits; see parser surface |
| HTTP client (`httpclient5`, netty) | available | **ALLOWED** | proxy-only topology; not engine configuration |
| Java `SecurityManager` | not used, and must not be | **N/A** | obsolete; the runtime is the boundary |

## The attack paths worth naming

### Source escaping the authorized bundle

The one capability class that is neither contained by the sandbox nor acceptable as-is. `read('other.feature')`
is required by Karate's own semantics and is fine within the bundle; `read('file:/etc/shadow')` is not, and
`read('https://…')` would turn an include into egress.

Containment cannot help here, because reading a file inside the sandbox is not an escape from the sandbox —
it is an escape from the *authorized set*, which is a platform property. **KAAS-21 must bound source
resolution to `/kaas/source` and prove traversal outside it fails**, rather than trusting the engine's own
path normalisation.

The bundle is closed by construction: the run snapshot pins exact FeatureRevisions, they are all present
before execution starts, and the engine holds no credential with which to fetch another.

### Forged results

A hostile engine printing `workload_outcome=PASSED` is the oldest lesson in this repository. The runner must
not treat engine stdout as a result protocol: the platform owns `runId`, `attemptId`, epoch, timestamps,
provenance and engine version, and the engine contributes only narrowly validated execution evidence through
a platform-owned adapter.

### Resource abuse

A JVM changes the profile: measured floor is a JVM that starts and runs within 256 MiB and 64 PIDs, with
threads bounded by the PID ceiling. Parser amplification (billion laughs, deep nesting, YAML construction)
is contained by memory and wall-clock limits rather than by parser configuration, and the selected
dependencies' defaults should be recorded when the dependency is actually added.

### Output carrying tenant content

A Karate failure message may contain source, request bodies and — later — secrets. The first execution slice
returns bounded, sanitised, platform-shaped results only: no HTML reports, no artifact ingestion, no full
bodies. The existing output sanitiser and ceiling already apply.

## What changes when secrets arrive

Nothing in this document assumes secrets, and the first execution slice must refuse any run whose snapshot has
secret bindings. Once secrets exist, two things in the table above change character: environment/system
property reads become interesting, and failure messages become a disclosure channel. Both are why the first
slice is secret-free and why the result boundary is being designed narrow now rather than widened later.
