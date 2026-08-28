# Domain Model

**Status: PARTIALLY IMPLEMENTED.** Organization ownership anchors, Projects, Features, and immutable FeatureRevisions are implemented and persisted. Run-side types remain design only.

```mermaid
classDiagram
  Organization "1" --> "many" Project
  Project "1" --> "many" Feature
  Feature "1" --> "many" FeatureRevision
  Project "1" --> "many" Environment
  Project "1" --> "many" RunProfile
  Project "1" --> "many" TestRun
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

`TestRun` and every execution-side relationship remain proposed. A future run snapshots selected revisions, resolved non-secret configuration, profile/environment identity, engine and policy versions. `ExecutionAttempt` is distinct from test-level scenario retries. Lifecycle, cancellation, test outcome, infrastructure outcome, and quality evaluation are orthogonal as specified in [run-semantics.md](run-semantics.md).
