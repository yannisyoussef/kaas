# Secret delivery path

Components and hops for one secret-bearing Karate execution. Decisions: [ADR-034](../adr/034-assignment-scoped-secrets-vault-transit.md).

```
                         ┌──────────────────────── control plane (apps/api) ────────────────────────┐
 tenant ──HTTPS──▶ edge ─▶ SecretVersionController ─▶ SecretVersionService ─▶ VaultTransitClient ──HTTPS──▶ Vault Transit
                         │        (octet-stream)             │                (AppRole, CA-pinned)        (derived key,
                         │                                   ▼                                            kaas-tenant-secrets)
                         │                    PostgreSQL: secret_versions, secret_version_ciphertexts,
                         │                                secret_version_revocations
                         │
                         │ run creation ─▶ RunSnapshotPolicy: pins (reference, version)   [metadata only]
                         │ authorization ─▶ ExecutionAuthorizationService: SECRET capability, exact scope
                         │ /internal/v1/secret-bundles ─▶ SecretCapabilityService ─▶ VaultTransitClient (decrypt)
                         └──────────────────────────────────────────────────────────────────────────────┘
                                        ▲                                  │ binary bundle
             worker credential +        │                                  ▼
             X-KaaS-Secret-Capability   │        production: http://10.77.0.1:8443 inside WireGuard
                                        │        CI: http://localhost:<port>
                         ┌──────────────┴────────────────── runner ───────────────────────────────────┐
                         │ ExecutionLoop: authorize → redeem source → redeem secrets (CLAIMED)          │
                         │   SecretBundle.parse (exact key set vs command) → EngineInput               │
                         │ DockerSandboxLauncher (runsc):                                              │
                         │   stdin attach  ─▶ [source frame][engine frame] ─▶ EOF                     │
                         │   output attach ◀─ stdout/stderr frames (LogConfig=none)                     │
                         │   OutputCollector: ProtocolScanner (raw) | SecretRedactor ─▶ ceiling ─▶      │
                         │                    UTF-8 ─▶ lines ─▶ transcript / observations               │
                         │   EngineOutcome: identity=karate 2.1.2, kaas.secrets=CONSUMED, verdict       │
                         └──────────────────────────────────┬───────────────────────────────────────────┘
                                                            ▼
                         ┌─────────────── sandbox (gVisor) ───────────────────────────────────────────┐
                         │ /source-bootstrap (root, SYS_ADMIN): read source frame exactly → write →  │
                         │   freeze /kaas/source ro → drop all caps → setuid 65534 → execve (envp={})│
                         │ /probe.sh → java (no heap/core dumps) → KaasKarateAdapter:                 │
                         │   read engine frame → close fd 0 → kaas.secrets=CONSUMED →                  │
                         │   Karate 2.1.2 with global kaas = {secrets, egress?}                        │
                         │   classpath karate-config.js: proxy = kaas.egress (Basic, capability)       │
                         └──────────────────────────────────┬───────────────────────────────────────────┘
                                                            ▼ (ALLOWLIST only)
                         egress proxy ── asks control plane per request ──▶ authorized destination
```

## Production versus CI

| Aspect | Production (Ops contract) | CI (`secret-execution-gate`) |
| --- | --- | --- |
| Vault | existing Vault, 1.18.5 → digest-pinned, TLS with `/run/kaas/vault-ca.pem` | `hashicorp/vault:1.18.5` pinned by index digest, dev mode used only to bootstrap, dev-TLS CA; the application authenticates through the AppRole, never the root token |
| Runner ↔ API | WireGuard, `http://10.77.0.1:8443` | loopback, same process host |
| Runner | a daemon (deployment-readiness slice) | `ExecutionLoop` composed by the test |
| Runtime | runsc on the execution host | runsc from the shared pinned action |
| Target | tenant's own systems | a busybox responder that accepts only the SHA-256 of the generated value |

## Ordering guarantee

The engine cannot run tenant code before: the source is verified by digest, written, frozen read-only; every
capability is dropped; the runtime is read back as the one authorized; the network (and proxy, for an
allowlist) exists; every secret was resolved and the bundle accepted; the adapter consumed its frame and closed
standard input. The platform's `RUNNING` phase is announced before the sandbox starts (the KAAS-16 design: a
phase is reported before the work it names), so the barrier is enforced by the order inside the sandbox rather
than by the phase name, and a run whose adapter does not confirm the channel produces no result.
