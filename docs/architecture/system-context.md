# System Context

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

The control plane is the trust boundary for configuration and orchestration. The sandbox is a lower-trust boundary for user-provided executable test content. Workers exchange only versioned contracts and never expose database credentials to the sandbox.
