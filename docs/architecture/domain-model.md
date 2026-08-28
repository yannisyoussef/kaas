# Domain Model

**Status: PARTIALLY IMPLEMENTED.** Organization ownership anchors, Projects, immutable FeatureRevisions, versioned execution configuration, TestRun intent, and immutable RunSnapshot are implemented and persisted. ExecutionAttempt and execution/result types remain design only.

```mermaid
classDiagram
  Organization "1" --> "many" Project
  Project "1" --> "many" Feature
  Feature "1" --> "many" FeatureRevision
  Project "1" --> "many" SecretReference
  Project "1" --> "many" Environment
  Environment "1" --> "many" EnvironmentRevision
  EnvironmentRevision --> SecretReference
  Project "1" --> "many" RunProfile
  RunProfile "1" --> "many" RunProfileRevision
  RunProfileRevision --> EnvironmentRevision
  Project "1" --> "many" TestRun
  TestRun "1" --> "1" RunSnapshot
  RunSnapshot --> FeatureRevision
  RunSnapshot --> RunProfileRevision
  RunSnapshot --> EnvironmentRevision
  TestRun "1" --> "many" ExecutionAttempt
  TestRun "1" --> "many" FeatureResult
  FeatureResult "1" --> "many" ScenarioResult
  ScenarioResult "1" --> "many" StepResult
  TestRun "1" --> "many" ExecutionArtifact
  TestRun "1" --> "many" QualityGateEvaluation
  TestRun --> RunProfile
  TestRun --> Environment
```

`Project` and the `Feature`/`FeatureRevision` lifecycle are implemented as the first control-plane capability. A Feature is the stable logical identity and owns a contiguous, insert-only revision history; its `logicalPath` does not change in this slice. See [project-feature-slice.md](project-feature-slice.md).

`SecretReference` is project-scoped metadata only and grants no access by possession. Environment and RunProfile are stable identities with contiguous, insert-only revisions; each RunProfileRevision pins one exact EnvironmentRevision. Normalized aggregate rows are sealed transactionally and database triggers reject all later mutation. See [environment-run-profile-slice.md](environment-run-profile-slice.md).

`TestRun` and its one-to-one `RunSnapshot` are implemented for the CREATED-only control-plane slice. The snapshot records the exact RunProfileRevision, its pinned EnvironmentRevision, selected FeatureRevisions, effective typed non-secret configuration, metadata-only secret bindings, profile settings, and server engine descriptor. See [test-run-intent-slice.md](test-run-intent-slice.md).

Every execution-side relationship remains proposed. `ExecutionAttempt` is distinct from test-level scenario retries. Lifecycle, cancellation, test outcome, infrastructure outcome, and quality evaluation are orthogonal as specified in [run-semantics.md](run-semantics.md).
