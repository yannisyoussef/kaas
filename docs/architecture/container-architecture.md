# Container Architecture

The initial deployment is a modular Spring Boot control plane, a separately deployable runner worker, and a Next.js web application. PostgreSQL stores authoritative metadata and immutable result records. RabbitMQ transports run commands and lifecycle events. Redis is optional transient state. MinIO emulates S3 locally.

```mermaid
flowchart LR
  subgraph Control[Control plane]
    API[REST + SSE]
    Domain[Application/domain modules]
    Persist[Persistence adapters]
    API --> Domain --> Persist
  end
  subgraph Execution[Execution plane]
    Consumer[Runner consumer]
    Launcher[Sandbox launcher]
    Consumer --> Launcher
  end
  Persist --> DB[(PostgreSQL)]
  API --> MQ[(RabbitMQ)]
  Consumer --> MQ
  Launcher --> Container[One ephemeral container per run]
  Container --> S3[(Object storage)]
```

A modular monolith avoids premature service boundaries while keeping ports explicit. Kubernetes is a future launcher adapter, not an MVP dependency.
