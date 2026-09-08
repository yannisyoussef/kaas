# Karate execution model

Which of two products KaaS is, decided rather than left implicit, because the two need opposite security
architectures and a hybrid gets the weaknesses of both.

## The decision

**KaaS treats tenant Karate source as FULLY HOSTILE, ARBITRARY JVM CODE.**

Not as a test DSL that happens to allow scripting. Not as HTTP syntax with an escape hatch. As a program the
tenant wrote, running in a JVM the platform started, contained by the sandbox and by nothing else.

## Why, and it is not a preference

Karate 2.1.2 — the version KaaS would pin — was inspected as a build artifact, not read about. What the
bytecode says:

- `Java.type(name)` reaches `io.karatelabs.js.JavaType(String)`, which calls
  `Class.forName(name, true, Thread.currentThread().getContextClassLoader())`.
- There is **no allowlist, no class filter, no sandbox and no configuration gate** on that lookup. The only
  filtering logic in the engine, `JavaUtils.isModuleRestricted`, is a JPMS accessibility *workaround*: when a
  method's declaring class is in a non-exported package it searches for an accessible alternative. It exists
  to make reflection work across module boundaries, not to stop it.
- `KarateJsBase` installs `DEFAULT_BRIDGE` on the ordinary construction path with no preceding conditional.
  The string `javaBridgeEnabled` does not appear anywhere in `karate-core-2.1.2.jar`.
- Karate 2.x replaced GraalJS with its own engine, `karate-js`, whose own description is "full support for
  reflection-based Java interop (construct, call methods)". GraalJS restricted host access by default;
  this does not.

So a feature file can reach `java.lang.ProcessBuilder`, `java.net.Socket`, `java.io.File`,
`java.lang.ClassLoader` and everything else on the classpath. That is not a loophole to be closed — it is the
engine working as designed, and it is why the restricted model is not available without modifying the engine.

## Why not the restricted model

A restricted DSL would mean removing or gating Java interop and proving the removal complete. Against a
programmable interpreter with reflection and dynamic class loading, that is a denylist, and a denylist over
reflection is not a boundary: every blocked name has an alias, and `Class.forName` is itself reachable through
any class that can be reached.

Proving a *complete* allow-model would mean replacing the engine's class resolution with a platform one and
demonstrating that no reflective path bypasses it. That is a fork of Karate maintained forever against
upstream, and its correctness would be an ongoing claim rather than a measured property.

The sandbox, by contrast, is measured. Every property in the readiness matrix was observed from inside a
mediated sandbox, and each one fails closed.

## What this commits the platform to

**Containment is the boundary.** The following are ACCEPTED capabilities of tenant code, not defects:

| capability | measured | why it is acceptable |
|---|---|---|
| construct any class on the classpath | yes, by design of the engine | the classpath is minimal and platform-owned |
| spawn child processes | `java_child_spawned=true` | bounded by the PID ceiling; dies with the sandbox |
| create threads | 200 of 200 attempted start under the mediating runtime; the baseline runtime stops at 49 | **not the PID ceiling** — gVisor does not charge Java threads against it. Memory and the wall clock bound a thread explosion, which is weaker and is an accepted residual rather than a control |
| write to scratch space | `java_tmp_write=true` | bounded tmpfs, ephemeral, `noexec` |
| read its own process state | yes | not authority; `/proc/self` describes the sandbox |

And the following are REQUIREMENTS, each observed rather than argued:

| requirement | evidence |
|---|---|
| no capability at all | `java_capabilities=EMPTY`, `NoNewPrivs=1` |
| cannot execute generated code | `java_tmp_write=true` **and** `java_tmp_exec=false` |
| cannot write, chmod, execute or remount the source filesystem | all `false`, attacked from Java |
| cannot create a device node | `java_mknod=false` |
| no network at all under `DENY_ALL` | DNS and three socket destinations all `false` |
| no platform credential, no daemon socket | environment names asserted; `java_docker_socket=false` |
| bounded concurrency | thread ceiling reached and reported |

## The consequence for the classpath

Under this model the classpath **is** a security control, and the only one governing which classes exist to be
reached. The future engine image must contain a JRE, Karate, and its declared dependencies — and nothing else.
No control-plane client, no Docker client, no cloud SDK, no database driver, no secret-provider client, no
package manager, no compiler.

`Java.type('com.kaas.api.…')` must fail because the class is not there, not because something refused it.

## What this model does not excuse

It does not make the missing `nodev` acceptable by itself, it does not make an unbound runtime identity
acceptable, and it does not authorize secrets. Those are adjudicated separately in
[`tenant-execution-readiness.md`](tenant-execution-readiness.md).

It also does not mean the engine's own features are irrelevant. `read()`, `call()` and `classpath:` resolution
decide what tenant source can reach *within* the sandbox, and the first execution slice must bound them to the
authorized bundle — see [`karate-hostile-execution-threat-model.md`](karate-hostile-execution-threat-model.md).

## Sources

Version and dependency facts were read from Maven Central metadata and from the published artifacts
themselves; interop behaviour was read by disassembling `karate-js-2.1.2.jar` and `karate-core-2.1.2.jar` with
`javap`. The jars were fetched into a scratch directory for inspection and **no Karate dependency was added to
any build file**.

- `https://repo1.maven.org/maven2/io/karatelabs/karate-core/maven-metadata.xml` — latest release 2.1.2,
  published 2026-08-14; the older `com.intuit.karate` coordinates stop at 1.4.1 (2023-10-16).
- `https://repo1.maven.org/maven2/io/karatelabs/karate-core/2.1.2/karate-core-2.1.2.pom` — dependencies
  including `io.karatelabs:karate-js`, `httpclient5`, `netty`, `snakeyaml`, `thymeleaf`, `json-path`;
  MIT licence.
- `https://github.com/karatelabs/karate-js` — "Full support for reflection-based Java interop"; no allowlist
  or class filter documented.
