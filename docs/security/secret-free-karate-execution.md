# Secret-free Karate execution

**What this describes:** the first execution of code the platform did not write, and why "secret-free" here is
a structural property rather than a list of names someone remembered to remove.

Adjudicated by [ADR-033](../adr/033-first-secret-free-karate-execution.md). The hostile-capability inventory
is [the Karate threat model](karate-hostile-execution-threat-model.md).

## The trust domains, and where the line is

```
  CONTROL PLANE            RUNNER                  |  SANDBOX (fully hostile)
  ─────────────            ──────                  |  ────────────────────────
  authorizes the run       verifies the bundle     |  source-bootstrap  (platform, privileged, brief)
  seals the snapshot       frames it               |        ↓ freeze + drop all capability
  refuses secrets  ───────▶ refuses secrets        |  /probe.sh         (platform, one line)
  names the engine         builds the image        |        ↓ exec, EMPTY envp
                           launches the sandbox    |  KaasKarateAdapter (platform, NOT privileged)
                           reads ONE key back  ◀───|        ↓
                                                   |  Karate 2.1.2 → TENANT CODE
```

The boundary is the sandbox wall, not any line inside it. The adapter is platform-authored but shares a JVM
with tenant code, so it holds no credential, no privileged handle, and no control-plane client. Private fields
are not a security boundary against reflection and are not used as one.

## Why the engine is its own Gradle module

Karate 2.1.2 resolves `Java.type(name)` to `Class.forName` with no allowlist — established by disassembly in
ADR-032. **Everything on the engine's runtime classpath is reachable from tenant source.** That makes the
classpath a security control, and `services/karate-engine` is the only module permitted to carry Karate under
either coordinate.

The runner obtains the engine by resolving its **image build context**, not as a project dependency, so it can
launch an engine without linking one. Guards on both sides:

| Guard | Where | Fails on |
| --- | --- | --- |
| `verifyLauncherHasNoUserContentDependencies` | runner, api, egress-proxy Gradle | a Karate coordinate on any classpath |
| `EngineTrustBoundaryTest` | runner JVM | a Karate class being loadable at runtime |
| `KaasKarateAdapterTest` | engine JVM | a platform launcher, signer, API, or datasource class being present |
| `ControlPlaneArchitectureTest` | api | a claim-path package depending on either Karate package |

The Gradle guard inspects a dependency graph; the runtime tests inspect what a classloader can reach. A jar
vendored into a resources directory passes the first and fails the second.

## What "secret-free" actually means here

Three independent statements, in increasing strength:

1. **The control plane refuses.** `ENGINE_REQUIRES_SECRET_FREE_RUN` denies any run whose sealed snapshot
   carries a secret binding for a secret-free engine — evaluated **before** the provider-availability check,
   so configuring a real provider later cannot silently unblock this path.
2. **The runner refuses independently.** A command carrying secret capabilities is rejected at validation, and
   has been since before this slice.
3. **There is no environment to leak into.** The bootstrap's `execve` passes an empty `envp`. The engine's JVM
   runs with **exactly three** variables — `LD_LIBRARY_PATH`, `SHLVL`, `PWD` — every one created after the
   boundary closed, by the handover shell and the JVM launcher. Not the host's environment with sensitive
   names subtracted: subtraction requires knowing every name worth removing, and the one nobody thought of is
   the one that leaks.

The test asserts that set **exactly**. A fourth name is an environment something reached into.

## What the platform decides, and what the tenant decides

| Decision | Owner | How it is enforced |
| --- | --- | --- |
| which features run | platform | the manifest the bootstrap wrote from the platform's frame; no scan, no glob, no path argument |
| the engine version | platform | pinned to 2.1.2; the runner refuses another; the image build refuses a context shipping another, none, or two |
| the image, working directory, JVM flags, classpath | platform | compile-time constants; nothing a command carries reaches a command line |
| report generation | platform | every Karate report form explicitly off |
| **the test outcome** | **tenant** | accepted: a property of executing arbitrary code |
| the run's identity, timing, provenance | platform | reconstructed from the command and control plane; never read from engine output |

## The result protocol

One key. `kaas.karate-result.v1=PASSED|FAILED|ENGINE_ERROR`.

`FAILED` is a tenant result and `ENGINE_ERROR` is the platform's problem; conflating them would make the
platform look broken every time a customer wrote a failing test. Two more verdicts exist only on the runner's
side:

- **`ABSENT`** — nothing usable was reported. Measured causes: the wall-clock kill, a stdout flood past the
  collector's ceiling, and `System.exit(0)` mid-suite. The last leaves a container that exited **zero** having
  run no assertion, so anything reading the exit status reports a pass.
- **`MALFORMED`** — the stream carried the key twice, or a value outside the closed set.

`MALFORMED` is the anti-forgery rule. Tenant code can print the protocol line — measured, via
`System.out.println` through Java interop; Karate's own `print` does not reach stdout at all. Rather than
choosing between two answers, the platform refuses a stream that answered twice. **Forging turns a run into an
infrastructure failure, not into a pass.**

## What is deliberately not confined

`read()` is not wrapped, and traversal outside `/kaas/source` is not blocked. Both were measured to work.

This reverses what ADR-032's threat model expected, and the reason is that a wrapper there would not be a
boundary: anything Karate's `read()` can open, `Files.readAllBytes` can open beside it, one `Java.type` away.
The platform confines what there is to read — a secret-free, digest-pinned image, three environment variables,
no host mount, no other tenant's data, and no secret — rather than pretending to confine the reading.

What **is** confined is the authorized set that gets executed. See ADR-033.

## Evidence

`karate-execution-gate` (mandatory CI) runs `KarateExecutionTests` against a real daemon: real bundle
verification, real framing, real stdin delivery, real freeze, real Karate. The job additionally fails unless a
run reported `kaas.engine=karate 2.1.2` — a string the adapter can only produce by resolving
`karate-meta.properties` through Karate's own classloader. The version is checked independently of the adapter
by the engine module's own suite, which reads the jar.

**The job installs the mediating runtime, and the suite runs only under it.** The freeze is a `mount`, and
ADR-031's evaluation measured that mount as refused under the baseline runtime — so a sandbox that carries
tenant source never gets past `bootstrap_failure=FREEZE` there, and no engine ever starts. Everything this
document claims about a running engine is therefore a claim about the mediated boundary. It is not a claim
about the baseline one, and a local build on Docker Desktop — where the remount happens to succeed because no
AppArmor policy is applied inside its VM — is not evidence for either.
