# MVP Scope

This file describes target MVP scope, not current implementation. See `IMPLEMENTATION_STATUS.md` for repository reality.

## Included

Project management; Karate feature revisions and editor; environments and non-secret variables; basic secret references; run profiles; targeted feature execution; asynchronous run lifecycle; Docker runner boundary; SSE logs; pass/fail and infrastructure outcomes; structured feature/scenario/step results; HTML/JSON/log artifacts; history; basic dashboard; REST/OpenAPI foundation; local Compose; unit, integration, security, contract, and API dogfooding foundations.

## Explicitly excluded

Git hosting, schedules, runner clusters, Kubernetes, multi-region execution, AI generation, plugins, billing, enterprise SSO, advanced RBAC, and non-Karate engines. These remain roadmap items and have no production stubs.

## Release gate

MVP execution cannot be enabled until sandbox controls, tenant authorization, secret redaction, timeout/cancellation, artifact limits, network policy, and hostile-input tests are reviewed and passing.
