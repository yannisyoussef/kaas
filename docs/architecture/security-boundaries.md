# Security Boundaries

1. **Identity boundary:** authenticate at the edge and authorize every project-scoped operation.
2. **Control/execution boundary:** API validates and persists intent; it never loads or executes user feature code.
3. **Worker/sandbox boundary:** launch a fresh non-root container with read-only root filesystem, dropped Linux capabilities, no privileged mode, CPU/memory/PID/time limits, temporary workspace, allowlisted egress, and bounded artifact output.
4. **Secret boundary:** store references, not values; inject only at runtime through a provider; redact logs and result payloads.
5. **Tenant boundary:** every query and object key is organization/project scoped; enforce ownership server-side and test IDOR cases.
6. **Artifact boundary:** use opaque keys, content-type allowlists, size limits, malware scanning policy, and signed/authorized access.

All boundaries must be observable without logging credentials, tokens, feature secrets, or sensitive request bodies.
