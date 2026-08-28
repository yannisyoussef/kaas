# Security Boundaries

**Status: CONTROL-PLANE IDENTITY AND TENANT CONTROLS IMPLEMENTED; EXECUTION CONTROLS REMAIN DESIGN ONLY.** Arbitrary execution is disabled.

1. **Identity boundary (implemented):** Spring Security validates external RS256 JWT signature, issuer, audience, time claims, subject, and one UUID organization claim. Product endpoints have no anonymous or local bypass.
2. **Control/execution boundary:** API validates and persists intent; it never loads or executes user feature code.
3. **Worker/sandbox boundary:** launch a fresh non-root container with read-only root filesystem, dropped Linux capabilities, no privileged mode, CPU/memory/PID/time limits, temporary workspace, allowlisted egress, and bounded artifact output.
4. **Secret boundary (metadata implemented, delivery absent):** the control plane stores only project-scoped SecretReference identity/name/audit metadata and EnvironmentRevision bindings. It has no value, ciphertext, provider/path, credential, resolve, reveal, or redemption field/endpoint. A UUID grants no authority. Future runtime capabilities require a separate reviewed subsystem and must never derive authority from metadata possession.
5. **Tenant boundary (implemented for current control-plane resources):** every repository query uses the trusted organization plus full project/parent hierarchy. Composite foreign keys enforce ownership for Projects, Features/Revisions, SecretReferences, Environments/Revisions, and RunProfiles/Revisions; cross-tenant/missing resources share concealed 404 behavior.
6. **Artifact boundary:** use opaque keys, content-type allowlists, size limits, malware scanning policy, and signed/authorized access.

Problem Details and correlated logging avoid credentials, tokens, feature/configuration values, secret-reference IDs, idempotency keys, SQL internals, and sensitive request bodies. Configuration responses use `Cache-Control: no-store`. Docker alone must not be treated as proof of hostile-code safety. See [project-feature-slice.md](project-feature-slice.md) and [environment-run-profile-slice.md](environment-run-profile-slice.md) for implemented boundaries.
