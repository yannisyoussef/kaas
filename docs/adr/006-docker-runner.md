# ADR-006: Docker runner

## Status

SUPERSEDED by [ADR-022](022-hostile-execution-boundary-and-synthetic-probe.md) — the boundary it proposed is now
implemented and evidenced for a trusted synthetic probe. Its central judgement is preserved rather than
overturned: Docker is still not a sufficient boundary for untrusted execution, and user content still cannot
enter the sandbox.

## Context

Karate feature files can execute JavaScript and make network requests. They must be treated as hostile executable content. Process separation alone does not contain filesystem, network, resource, credential, or kernel risk.

The paragraph below is preserved as written at the time and is no longer true: the repository now contains a container launcher, under ADR-022. It runs one trusted synthetic probe and no user content.

> The repository currently has no executor or container launcher.

## Decision

Evaluate one ephemeral container per run as the first execution isolation adapter, separate from the API and worker process. This is a proposal, not a claim that Docker is a sufficient security boundary. Execution remains disabled until the launcher trust model and required controls have executable evidence.

## Alternatives considered

- Execute Karate inside the API or worker JVM.
- A long-lived shared runner container.
- Rootless containers on a dedicated worker host.
- Stronger VM/microVM isolation.

## Why alternatives were rejected

In-process execution collapses the control/execution boundary. A shared container increases cross-run persistence and tenant leakage risk. Rootless dedicated hosts and microVMs may provide stronger containment but require a concrete threat/cost evaluation before selection; they are not rejected permanently.

## Advantages

- Per-run filesystem/process cleanup is conceptually simple.
- Container images can pin the Karate/runtime dependency set.
- Resource and capability controls are available on common worker hosts.

## Disadvantages

- Containers share the host kernel and do not eliminate escape risk.
- A Docker daemon or socket can turn the launcher into a host-privileged component.
- Network egress, secrets, logs, artifacts, quotas, and cleanup require controls outside the test process.

## Consequences

No arbitrary feature may execute until a separate architecture iteration defines rootless/non-root execution, daemon/socket isolation, read-only mounts, dropped capabilities, syscall policy, namespaces, resource/output/time limits, destination-aware egress, scoped secret/artifact capabilities, image provenance, cleanup, and hostile tests. Stronger isolation remains an open alternative.

## Validation and revisit conditions

Promote this ADR only after threat-model review and adversarial tests show the selected runtime profile enforces the release gate. Revisit after any container-runtime/kernel escape advisory, infrastructure change, new secret/network requirement, or evidence that containers do not provide adequate isolation.
