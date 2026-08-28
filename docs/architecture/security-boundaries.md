# Security Boundaries

**Status: CONTROL-PLANE IDENTITY AND TENANT CONTROLS IMPLEMENTED; EXECUTION CONTROLS REMAIN DESIGN ONLY.** Arbitrary execution is disabled.

1. **Identity boundary (implemented):** Spring Security validates external RS256 JWT signature, issuer, audience, time claims, subject, and one UUID organization claim. Product endpoints have no anonymous or local bypass.
2. **Control/execution boundary:** API validates and persists intent; it never loads or executes user feature code.
3. **Worker/sandbox boundary:** launch a fresh non-root container with read-only root filesystem, dropped Linux capabilities, no privileged mode, CPU/memory/PID/time limits, temporary workspace, allowlisted egress, and bounded artifact output.
4. **Secret boundary:** store references, not values; inject only at runtime through a provider; redact logs and result payloads.
5. **Tenant boundary (implemented for Project/FeatureRevision):** every repository query uses the trusted organization plus full parent hierarchy. Composite foreign keys enforce ownership and cross-tenant/missing resources share concealed 404 behavior.
6. **Artifact boundary:** use opaque keys, content-type allowlists, size limits, malware scanning policy, and signed/authorized access.

Problem Details and correlated logging avoid credentials, tokens, feature source, idempotency keys, SQL internals, and sensitive request bodies. Docker alone must not be treated as proof of hostile-code safety. See [project-feature-slice.md](project-feature-slice.md) for the implemented boundary.
