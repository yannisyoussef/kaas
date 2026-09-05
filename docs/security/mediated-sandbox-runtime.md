# Running the sandbox under a mediating runtime

**Status: IMPLEMENTED and VALIDATED IN CI.** See [ADR-028](../adr/028-mediated-sandbox-runtime.md) for the
decision and [the runtime evaluation](../architecture/hostile-runtime-evaluation.md) for what was measured.

This is the operator's half. It describes how to install the runtime, how to produce evidence for it, and
what changes about the evidence when you do.

## What you are changing

By default the sandbox runs under `runc`: a container that shares the host kernel, so the reachable attack
surface is every syscall the kernel implements. Under gVisor the workload's syscalls are served by a userspace
kernel — the sentry — which is itself seccomp-confined against the host.

**This is not a virtual machine.** The sentry is a host process. A sentry compromise is host-adjacent rather
than impossible, and ADR-022 remains open either way.

## 1. Install the runtime, pinned and verified

Use the release this repository pins. The digest is in `.github/workflows/ci.yml` under `strong-runtime-gate`
and is verified before the binary is made executable.

```
RELEASE=20260817
ARCH=$(uname -m)   # the pinned digest below is x86_64
curl -fsSL "https://storage.googleapis.com/gvisor/releases/release/${RELEASE}/${ARCH}/runsc" -o /tmp/runsc
echo "048b89aada69dc3333422e139d6e9d02f8ab06bda52398060e0fbdacca00074c  /tmp/runsc" | sha256sum -c -
sudo install -o root -g root -m 0755 /tmp/runsc /usr/local/bin/runsc
```

**A pinned digest is not a working runtime.** The release this repository pinned first — `20240729` — refused
every network mode on a 2026 kernel, including `--network=none`. Re-pinning to a newer release means
re-measuring on the kernel that will run it, not merely updating a hash. If you pin a different release, run
the `strong-runtime-gate` suite against it before trusting it.

## 2. Register it as a named runtime, and do not make it the default

```json
{ "runtimes": { "runsc": { "path": "/usr/local/bin/runsc" } } }
```

in `/etc/docker/daemon.json`, then restart the daemon and confirm `docker info` lists `runsc`.

Named, not default, deliberately. The baseline runtime stays available and stays the default: the trusted
probe suites, the egress topology and the synthetic pipeline exercise it on purpose, and a host where every
container silently became mediated is a host where the comparison this evidence rests on no longer exists.

## 3. Produce an attestation for the runtime you actually run

```
./gradlew :services:runner:produceSandboxSecurityAttestation \
  -PkaasAttestationKeyId=<your key id> \
  -PkaasAttestationPrivateKeyFile=<path> \
  -PkaasAttestationRuntimeSubject=<this host's subject> \
  -PkaasAttestationSandboxRuntime=gvisor \
  -PkaasAttestationOutput=<path>
```

`-PkaasAttestationSandboxRuntime` defaults to `docker`. It accepts `docker` or `gvisor` and refuses anything
else — it is not a free-form runtime name, because a runtime name is the name of a program the daemon will
execute.

The document it writes names the boundary twice: `securityProfileVersion` becomes
`kaas.sandbox.gvisor.v1` and `sandboxRuntime` becomes `GVISOR`. Both are inside the signature, and a verifier
refuses a document whose two answers disagree.

## 4. Tell the control plane which boundary to expect

Set the expected profile version to `kaas.sandbox.gvisor.v1`. Evidence for one boundary does not authorize the
other **in either direction**: a genuine, correctly signed baseline attestation will not authorize a
deployment expecting the mediating runtime, and the reverse is equally refused.

## What changes about the evidence

The mandatory control set is scoped to the profile version
(`packages/api-contracts/mandatory-sandbox-controls.json`). Under the mediating runtime:

| Control | What changes |
| --- | --- |
| `NO_NEW_PRIVILEGES` | **Not observable.** gVisor's guest emits no `NoNewPrivs` line. Reported `UNSUPPORTED`, never passed. The control is still applied — it is in the OCI spec — but this deployment cannot demonstrate it. |
| `HOST_KERNEL_SYSCALL_MEDIATION` | **Now mandatory.** The sandbox must report a kernel the runtime serves, observed from inside. |
| `NO_SETUID_BINARIES` | Required under both runtimes. Covers the escalation path `NO_NEW_PRIVILEGES` closes. |
| `KERNEL_PATHS_MASKED` | Passes on absence as well as masking. Under gVisor these paths are not implemented rather than overmounted, which is stronger and produces different evidence. |
| `NO_HOST_DEVICES` | `/dev/fuse` and `/dev/net/tun` are permitted **only** under this runtime, where they are sentry-emulated. Under the baseline they still fail. |

**The mediating runtime therefore carries one fewer demonstrable mandatory control than the baseline.** That
is a real reduction and is recorded here rather than buried.

## What it costs

- **Roughly 3x wall clock.** The sandbox deadline is scaled automatically; you do not configure this.
- **Fewer usable processes.** The sentry's threads come out of the sandbox's own `pids.max`, so a ceiling of
  64 leaves roughly 16–21 for the workload. Raising the memory limit does not change this.
- **Two more character devices** visible inside the sandbox, as above.

## If it will not start

`cannot run with network enabled in root network namespace` on **every** network mode, including
`--network=none`, means the runtime is too old for the host kernel. Install a newer release and re-measure.

A sandbox refused with `SANDBOX_RUNTIME_UNAVAILABLE` means the daemon has no such runtime — check step 2.
`SANDBOX_RUNTIME_MISMATCH` means the daemon created the container under a *different* runtime than was asked
for; that is a more serious condition and the workload is never started.

**There is no fallback.** A deployment configured for the mediating runtime that cannot provide it refuses
executions rather than running them behind the weaker boundary.
