# ADR-033: The first secret-free Karate execution

**Status: ACCEPTED.** Authorizes real Karate 2.1.2 to execute tenant features inside the mediated sandbox for
runs carrying **zero** secret bindings. Does not authorize secrets, artifacts, reports, or a second engine.

## Context

ADR-032 adjudicated whether tenant bytes *may* become instructions and concluded that they may, under
containment, for a secret-free slice. It did not run anything. This ADR records what happened when the engine
was actually started, and it changes two positions that were held on reasoning alone.

## Decision

### Karate lives in its own module, and the classpath is the security control

`services/karate-engine` is the only module that may depend on Karate, under either coordinate
(`io.karatelabs`, and the abandoned `com.intuit.karate`). Everything on that module's runtime classpath is
reachable from tenant source through `Java.type`; everything not on it is not. The runner, the control plane,
the egress proxy, and the shared domain all carry Gradle guards refusing both coordinates, and a runtime test
asserts from inside the runner's own JVM that no Karate class is loadable there — a build-time dependency
check and a classloader check fail on different mistakes.

The runner obtains the engine by resolving its **image build context**, never as a project dependency. That is
what lets the runner launch an engine without linking one.

### The engine version is pinned, and the pin is verified where the image is built

Karate is pinned to exactly `2.1.2`. The runner refuses a command naming any other version, and that refusal
is worth nothing unless the image actually contains 2.1.2 — so `KarateEngineImage` inspects the assembled
context and refuses to build an image shipping a different version, no engine, or **two** engines. Two is the
quiet one: with a wildcard classpath, the running engine would be chosen by directory iteration order.

One advisory was found in the resolved tree and pinned away rather than argued around: netty 4.2.16 carries
GHSA-8c42-7qj2-3j46, so 4.2.17 is forced. The affected class is server-side CORS and this engine is a client,
but "not reachable today" stops being true the moment somebody enables Karate's mock server.

### The result protocol is one key, and a duplicate is a refusal

The adapter prints `kaas.karate-result.v1=PASSED|FAILED|ENGINE_ERROR` and nothing else the platform reads. No
run identity, no timing, no counts, no paths, no free text: the runner reconstructs all of that from the
command and the control plane, because a field a hostile process can choose is a field the platform does not
own.

Tenant code can print that line too — measured, via `System.out.println` through Java interop. It is not
preventable and is not treated as preventable. The output collector counts key occurrences, and a stream
carrying the result key twice is refused: the run becomes an **infrastructure failure**, never a pass.
Forging removes the tenant's own result rather than substituting a better one.

**A tenant CAN influence its own test outcome.** That is a property of executing arbitrary code, ADR-032
accepted it explicitly, and nothing here pretends otherwise. What a tenant cannot do is forge platform
authority or affect another tenant.

### Absent evidence is never a pass

Three ways a run ends without a verdict were measured, and all three resolve to `ABSENT`: the wall-clock kill,
a stdout flood past the collector's ceiling, and `System.exit(0)` mid-suite. The last one leaves a container
that **exited zero having run no assertion** — anything deriving an outcome from the exit status reports a
pass. The runner derives it from the protocol line instead.

### Source resolution is NOT confined, and this reverses a prior expectation

ADR-032's threat model said KAAS-21 "must bound source resolution to `/kaas/source` and prove traversal
outside it fails". **That is not what shipped.**

Measurement showed Karate 2.1.2 applies no root confinement: `read()` with enough `../` resolves above the
source root, and `read('file:/etc/passwd')` returns the container's file. A wrapper over `read()` would not
have been a boundary — tenant source is arbitrary JVM code, so anything `read()` can open,
`Files.readAllBytes` can open beside it. Constraining one API while the other is a `Java.type` away is a
control that looks like enforcement and enforces nothing.

So the platform confines **what there is to read** instead:

- a platform-built, digest-pinned image with no credential and no tenant data;
- **exactly three environment variables** — `LD_LIBRARY_PATH`, `SHLVL`, `PWD` — all created after the boundary
  closes, because the bootstrap's `execve` passes an empty `envp`. Nothing from the host, the image's `ENV`,
  or the command survives;
- no host mount, so no other tenant's data exists on the filesystem;
- **no secret**, by refusal rather than by filtering.

What the platform *does* confine is the authorized set that gets **executed**. The adapter runs exactly the
manifest the bootstrap wrote from the platform's own frame: no directory scan, no glob, no default, no
argument naming a path, and a third path-escape refusal at the last point before a path becomes a file the
engine opens. Karate's own default is to scan a directory, which is precisely what this must not become.

### Zero secret bindings, refused before the provider check

`ENGINE_REQUIRES_SECRET_FREE_RUN` denies any run whose snapshot carries a secret binding for an engine
authorized only for secret-free execution. It is evaluated **before** the provider-availability check, and the
ordering is the decision: the provider check stops firing the moment a real provider is configured, and a
Karate run carrying secrets would then sail through an adjudication that never considered it. Every acceptance
behind ADR-032 — the missing `nodev`, the construction privilege, ambient file access under a fully hostile
model — was reasoned with no secret anywhere in the sandbox.

The runner refuses secret-bearing commands independently, and has since before this slice. Two independent
refusals, on both sides of the boundary.

## Consequences

- The first code the platform did not write now executes in production paths.
- A ninth mandatory CI job, `karate-execution-gate`, runs real Karate on a real frozen source filesystem and
  fails if no run reports `kaas.engine=karate 2.1.2`.
- Adding a dependency to the engine module widens what tenant code can reach. The module's classpath is now a
  reviewed security surface, and the runner's test task treats every jar on it as an input.
- **Unchanged by this slice, deliberately:** `source_mount_nodev=false` (ADR-031 §47) and
  `java_threads_bounded=false` (ADR-032 §60) remain accepted residuals. Neither was weakened to make the first
  feature green, and neither was quietly re-adjudicated.

## What this does not authorize

Secrets, `SecretCapability` redemption, secret injection, HTML or JSON reports, report persistence,
screenshots, artifact retention, object storage, SSE, quality gates, engine-outcome-based retries, arbitrary
Karate CLI flags, arbitrary JVM flags, arbitrary classpath entries, tenant-selected engine versions, images or
working directories, plugins, tenant-uploaded libraries, or arbitrary JAR loading as a product feature.
