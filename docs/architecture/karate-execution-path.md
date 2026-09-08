# The Karate execution path

What actually happens, in order, when a `KARATE` run executes. Adjudicated by
[ADR-033](../adr/033-first-secret-free-karate-execution.md); the security argument is in
[secret-free Karate execution](../security/secret-free-karate-execution.md).

**Step 7 requires the mediating runtime.** The `MS_REMOUNT|MS_RDONLY` below is refused under the baseline
runtime — [ADR-031's evaluation](mediated-source-filesystem-evaluation.md) measured it — and the bootstrap
then reports `bootstrap_failure=FREEZE`, exits **0**, and never reaches step 8. This chain therefore has no
baseline-runtime form: on `runc` it stops at step 7 with no engine, not with a weaker one.

## The chain

```
1. AUTHORIZE          control plane   run is claimed, lease live, snapshot sealed
                                      ↓  refuses ENGINE_REQUIRES_SECRET_FREE_RUN if any secret binding
2. COMMAND            control plane   engine=KARATE, engineVersion=2.1.2, sourceBundle.features[]
                                      ↓  signed, digest-covered
3. VALIDATE           runner          refuses an unknown engine, a wrong version, any secretCapabilities
                                      ↓
4. FETCH + VERIFY     runner          bundle bytes → SourceBundle.verified(): exact entry set, per-entry
                                      ↓  digest, bundle digest. Path safety before anything is written.
5. FRAME              runner          SourceFrame: KAASSRC1, count, (path, digest, size, bytes)…, KAASEND1
                                      ↓
6. LAUNCH             runner          engine image + SourceDelivery; entrypoint = /source-bootstrap,
                                      ↓  stdin attached BEFORE start, user 0 for construction only
─────────────────────────────────────── sandbox wall ───────────────────────────────────────
7. BOOTSTRAP          platform, C     reads the frame, writes 0444 regular files (O_EXCL|O_NOFOLLOW),
                                      writes manifest.tsv, then:
                                        mount(MS_REMOUNT|MS_RDONLY|MS_NOEXEC|MS_NOSUID|MS_NODEV)
                                        drop_all_privilege()
                                      ↓  execve("/bin/sh", ["/probe.sh", "engine"], envp = {})
8. HANDOVER           platform, sh    one line: exec java -cp '/engine/lib/*' KaasKarateAdapter
                                      ↓  unprivileged, uid 65534, EMPTY environment
9. ADAPTER            platform, JVM   prints kaas.engine=karate <version> from karate-meta.properties
                                      reads manifest.tsv → absolute paths, third escape check
                                      ↓  Runner.path(features), every report form off, parallel(1)
10. KARATE 2.1.2      engine          runs TENANT CODE
                                      ↓
11. VERDICT           adapter         kaas.karate-result.v1=PASSED|FAILED|ENGINE_ERROR
─────────────────────────────────────── sandbox wall ───────────────────────────────────────
12. COLLECT           runner          bounded, sanitised, key occurrences COUNTED
13. READ              runner          EngineOutcome.of(): duplicate → MALFORMED; absent → ABSENT
14. RESULT            runner          platform owns identity, timing, provenance; tenant owns only passed
```

## Where each decision is made

| Decision | Step | Notes |
| --- | --- | --- |
| may this run execute at all | 1 | secret bindings refused before the provider check |
| which engine and version | 2–3 | refused by the runner; the image build refuses a mismatched context |
| which bytes exist | 4–5 | exact entry set, digest-checked; nothing is fetched inside the sandbox |
| which files exist on disk | 7 | the bootstrap writes them; `0444`, no symlinks, no directories the frame did not name |
| what is executable | 7 | nothing under `/kaas/source`; scratch is `noexec` |
| which features RUN | 9 | the manifest, never a scan, glob, default, or path argument |
| the test outcome | 10 | **the tenant's**, accepted |
| what the platform believes | 13–14 | one key; a second occurrence is a refusal |

## Module boundaries

```
apps/api                 control plane. No Karate, no docker client, no runner.
services/runner          orchestration. Docker client. NO KARATE — resolves the engine's
                         IMAGE CONTEXT, never the engine as a library.
services/karate-engine   THE ONLY MODULE WITH KARATE. Its runtime classpath is reachable
                         from tenant source, so it is a reviewed security surface.
services/egress-proxy    no Karate, no control plane.
```

The runner's test task declares the engine image context as an input, so adding a jar to the engine's
classpath re-runs the suites that measure what tenant code can reach.

## Two images, and why they differ

| | security probe | engine |
| --- | --- | --- |
| base | busybox, digest-pinned | eclipse-temurin JRE, digest-pinned |
| `/probe.sh` | the shell verifier | one line starting the JVM |
| bootstrap handover word | `sourceverify` / `sourceboundary` | `engine` (discarded — the image runs one program) |
| what runs | platform measurement | Karate, then tenant code |

Both receive the same bootstrap from the same C source; there is one copy of it, assembled into each context
by Gradle rather than duplicated.

## What is NOT on this path

No secret resolution. No artifact upload. No report generation or persistence. No SSE. No retries driven by
the engine's outcome. No tenant-chosen flags, classpath, image, version, or working directory. No network
under `DENY_ALL`, and only the proxy under `ALLOWLIST`.
