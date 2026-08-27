# Threat Model

## Assets

Credentials and secret references, feature definitions, test data, tenant metadata, result integrity, artifact availability, and runner infrastructure.

## Trust boundaries

Client → API; API → broker; broker → worker; worker → ephemeral sandbox; sandbox → external systems; API/worker → storage.

## Key threats and controls

| Threat | Control |
|---|---|
| Arbitrary code escapes API | API has no Karate runtime; execution is a separate process and container |
| Container escape | non-root, no privileged flags, dropped capabilities, read-only filesystem, patched pinned images, runtime scanning, seccomp/AppArmor review |
| SSRF/data exfiltration | explicit egress policy, DNS/IP controls, proxy, per-project network policy, audit logs |
| Secret leakage | provider references, short-lived injection, redaction, no secrets in events/artifacts, rotation |
| Tenant IDOR | authorization at service and repository layers, opaque IDs, negative tests |
| Resource exhaustion | queue quotas, concurrency limits, CPU/memory/PID/output/time limits |
| Result tampering | immutable run snapshot, signed/traceable lifecycle events, restricted artifact writes |
| Poisoned messages | schema validation, authenticated broker, bounded payloads, idempotent consumers |
| XSS via reports/logs | content-disposition, sanitization, CSP, no unsafe HTML rendering in app |
| Dependency/image compromise | lockfiles, SBOM, vulnerability scanning, provenance and review |

## Abuse cases to test before execution enablement

Malicious feature payloads, shell/metacharacter injection in all fields, oversized files/results, cancellation races, duplicate idempotency keys, cross-project IDs, secret-shaped output, network access to metadata endpoints, and worker crash during every lifecycle transition.
