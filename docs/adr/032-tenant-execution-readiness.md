# ADR-032: Tenant execution readiness and the hostile Karate execution model

**Status: ACCEPTED.** Authorizes one secret-free Karate execution slice under binding restrictions. Closes
ADR-022's runtime-identity prerequisite. Does not authorize secrets, artifacts, or a second engine slice.

## Context

ADR-030 delivered tenant bytes as data. ADR-031 put them on a filesystem that refuses to execute them. Both
answered the same question — *can these bytes be here safely?* — and both said yes only because nothing
interpreted them.

The question now is different, and no amount of filesystem hardening answers it. `noexec` stops the kernel
executing a file from `/kaas/source`. It says nothing about an interpreter reading those bytes and acting on
them, and the interpreter is the entire product.

## Decision

### Tenant Karate is arbitrary JVM code, and containment is the boundary

Karate 2.1.2 was inspected as a build artifact. `Java.type(name)` resolves to
`Class.forName(name, true, contextClassLoader)` with no allowlist, no class filter, and no configuration gate;
the engine's only filtering logic is a JPMS accessibility workaround that searches for an accessible method
rather than refusing one. The Java bridge is installed unconditionally on the ordinary path, and the string
`javaBridgeEnabled` does not appear in the artifact at all.

So a feature file can reach `ProcessBuilder`, `Socket`, `File`, `ClassLoader` and anything else on the
classpath. **This is accepted rather than fought.** A restricted DSL would mean a denylist over a reflective
interpreter, which is not a boundary; a complete allow-model would mean forking the engine's class resolution
and maintaining that claim forever. The sandbox, by contrast, is measured.

The consequence is that **the classpath becomes a security control**: the future engine image must contain a
JRE, Karate and its declared dependencies and nothing else, so that reaching a platform class fails because it
is absent rather than because something refused it.

### Signed evidence binds the runtime implementation (attestation v5)

Everything v4 signed described the runtime *family*. All of it survives replacing the `runsc` binary, so
evidence gathered against one build could authorize execution against another — accepted while the sandbox ran
a repository-controlled probe over inert bytes, and not acceptable for tenant code, because the sentry is the
kernel the tenant's syscalls meet.

v5 signs the runtime implementation's name, version, binary digest and an opaque path identity, all **measured
from the daemon's own runtime registration**. There is no producer option that sets them: an operator who could
type a digest would be attesting to a claim rather than to a binary. The control plane compares the attested
digest against a configured set, and an empty set accepts nothing.

The domain separator moved with the schema, so a v4 document cannot verify as v5 — proven by a signed,
trusted, refused vector rather than asserted.

**The operational rule is that replacing the runtime binary invalidates existing evidence automatically.**
There is no "remember to re-run the gate" step, because forgetting it fails closed.

### The missing `nodev` is accepted, and named as missing

gVisor does not implement `MS_NODEV`. What stands in its place is that the bundle format cannot express a
device node, the bootstrap has no call that creates one, the filesystem is frozen before the consumer starts,
the consumer holds no `CAP_MKNOD`, and it cannot remount or replace the filesystem — with the whole set
attacked from a JVM and refused.

Recorded as **NODEV NOT ENFORCED; COMPENSATING CONTROLS ACCEPTED FOR EXECUTION**. Not "nodev pass" and not
"equivalent". Each compensating control has a test, so weakening one turns the gate red.

### The first execution slice is secret-free and narrow

Every acceptance above was reasoned without secrets in the sandbox, so the first slice must refuse any run with
secret bindings — in the control plane and in the runner, not one or the other. It gets one execution path,
one pinned engine image, source only from the prepared filesystem, existing egress policies, a bounded
platform-shaped result, and no artifacts or report persistence.

## Consequences

- Tenant-authored bytes may become instructions, for the first time, under stated restrictions.
- The classpath of the future engine image is now a security control and must be reviewed as one.
- A runtime upgrade is a security event that invalidates evidence rather than a maintenance task.
- Three things KAAS-21 must build before it can be called complete: source-read containment bounded to
  `/kaas/source`, a determination of `read()`-of-URL behaviour, and a platform-owned result adapter.
- ADR-022's runtime prerequisite is closed. Its content prerequisite is closed by ADR-030 and ADR-031. What
  remains open is everything about secrets and artifacts, which no slice has adjudicated.

## Alternatives considered

**A restricted Karate DSL.** Rejected on evidence: the engine offers unrestricted reflection and there is no
allow-model short of forking it. §68 of the brief requires a robust allow-model for this option and none is
available.

**Blocking `Runtime.exec` and friends by name.** Rejected as a denylist over reflection, which is the exact
shape the brief warns against and which `Class.forName` defeats by construction.

**Waiting for `MS_NODEV`.** Rejected, with the reasoning recorded rather than assumed: no reachable path puts
a device node on the source filesystem, and each layer that closes those paths is separately tested.

**Deferring the runtime identity gap again.** Rejected. It was accepted twice for inert bytes on the grounds
that the runtime was not the thing under attack. Once tenant code executes, it is.
