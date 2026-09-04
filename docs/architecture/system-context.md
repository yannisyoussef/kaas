# System Context

**Status: PROPOSED PRODUCT CONTEXT.** Only the API, web, and runner scaffolds exist today. The runner now holds a container launcher that runs one trusted synthetic probe under a fixed security profile; it executes no user content.

```mermaid
flowchart TB
  Human[Engineer] --> Client[Web or API client]
  CI[CI pipeline] --> Client
  Client --> Control[KaaS control plane]
  Control --> Identity[Identity provider]
  Control --> Data[(PostgreSQL)]
  Control --> Broker[(RabbitMQ)]
  Control --> Storage[(S3 object storage)]
  Worker[Runner worker] --> Broker
  Worker --> Sandbox[Ephemeral sandbox]
  Sandbox --> Storage
  Control --> Telemetry[OTel collector / metrics]
  Worker --> Telemetry
```

The control plane is the trust boundary for configuration and orchestration. The sandbox is a lower-trust boundary, and is exercised today by a platform-owned workload rather than user-provided content. Worker contracts and sandbox enforcement are implemented — see [ADR-022](../adr/022-hostile-execution-boundary-and-synthetic-probe.md) and [ADR-024](../adr/024-synthetic-execution-lifecycle.md); credential isolation for tenant secrets is not, because no secret provider exists.
