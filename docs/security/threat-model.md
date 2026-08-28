# Threat Model

**Status: REQUIREMENTS CATALOG.** Execution is disabled; controls in this document are not implemented unless `IMPLEMENTATION_STATUS.md` says otherwise.

## Assets

Credentials and secret references, feature definitions, test data, tenant metadata, result integrity, artifact availability, and runner infrastructure.

## Trust boundaries

Client → API; API → broker; broker → worker; worker → ephemeral sandbox; sandbox → external systems; API/worker → storage.

## Key threats and required controls

| Threat | Required control before exposure |
|---|---|
| Arbitrary code escapes API | API has no Karate runtime; execution is a separate process and container |
| Container escape | non-root, no privileged flags, dropped capabilities, read-only filesystem, patched pinned images, runtime scanning, seccomp/AppArmor review |
| SSRF/data exfiltration | explicit egress policy, DNS/IP controls, proxy, per-project network policy, audit logs |
| Secret leakage | implemented APIs accept metadata-only SecretReferences and never values/provider paths; future delivery requires short-lived capabilities, redaction, no secrets in events/artifacts, and rotation |
| Tenant IDOR | authorization at service and repository layers, full parent scoping, composite ownership constraints, concealed 404s, and negative tests |
| Configuration time-of-check/time-of-use drift | RunProfileRevision pins an exact immutable EnvironmentRevision; future TestRun must pin RunProfileRevision and FeatureRevision IDs, never mutable logical identities |
| Late aggregate mutation | transactional revision sealing, deferred commit checks, and PostgreSQL rejection of later parent/child inserts, updates, and deletes |
| Metadata-as-authority confusion | SecretReference UUID/name has no provider location or redemption authority; a future secret broker must independently authorize and mint bounded capabilities |
| Resource exhaustion | queue quotas, concurrency limits, CPU/memory/PID/output/time limits |
| Result tampering | immutable run snapshot, signed/traceable lifecycle events, restricted artifact writes |
| Poisoned messages | schema validation, authenticated broker, bounded payloads, idempotent consumers |
| XSS via reports/logs | content-disposition, sanitization, CSP, no unsafe HTML rendering in app |
| Dependency/image compromise | lockfiles, SBOM, vulnerability scanning, provenance and review |
| Replayed or stale commands | message/command identity, trusted run/attempt binding, run version, assignment epoch, absolute deadline, inbox deduplication |
| Cross-tenant command/result substitution | authenticated producer plus full organization/project/run/attempt comparison against authoritative assignment; payload IDs never select tenant data |
| Forged result/manifest | stable ID+digest conflict detection, structured semantic validation, reserved opaque object references, storage-observed size/digest verification, quarantine/security audit |
| Contract confusion/amplification | locally allowlisted exact schema/type, no remote schema selection, pre-parse byte/depth/decompression limits, bounded arrays/strings/events/artifacts |

## Abuse cases to test before execution enablement

Malicious feature payloads, shell/metacharacter injection in all fields, oversized files/results, cancellation races, duplicate idempotency keys, cross-project IDs, secret-shaped output, network access to metadata endpoints, and worker crash during every lifecycle transition.

Contract-level mitigations are specified in `docs/architecture/execution-protocol.md`, `messaging-reliability.md`, `result-model.md`, and `live-events.md`. They are design requirements, not operating controls, and do not replace sandboxing or authenticated infrastructure.
