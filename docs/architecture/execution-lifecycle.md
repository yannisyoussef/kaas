# Execution Lifecycle

```mermaid
stateDiagram-v2
  [*] --> CREATED
  CREATED --> QUEUED: accepted
  QUEUED --> PROVISIONING: worker reserved
  PROVISIONING --> RUNNING: sandbox ready
  RUNNING --> PROCESSING_RESULTS: process ended
  PROCESSING_RESULTS --> PASSED: tests pass
  PROCESSING_RESULTS --> TEST_FAILED: assertions fail
  RUNNING --> TIMED_OUT: deadline exceeded
  CREATED --> CANCELLED: cancel
  QUEUED --> CANCELLED: cancel
  PROVISIONING --> INFRASTRUCTURE_ERROR: launch failure
  RUNNING --> INFRASTRUCTURE_ERROR: crash / lost worker
  PROCESSING_RESULTS --> INFRASTRUCTURE_ERROR: invalid result
```

`TEST_FAILED` means the runner completed and a test assertion failed. `INFRASTRUCTURE_ERROR` means KaaS cannot trust the test outcome. `TIMED_OUT` is an execution outcome with explicit deadline evidence. State transitions are monotonic, persisted, audited, and emitted as ordered events.
