# Domain Model

```mermaid
classDiagram
  Organization "1" --> "many" Project
  Project "1" --> "many" FeatureRevision
  Project "1" --> "many" Environment
  Project "1" --> "many" RunProfile
  Project "1" --> "many" TestRun
  TestRun "1" --> "many" FeatureResult
  FeatureResult "1" --> "many" ScenarioResult
  ScenarioResult "1" --> "many" StepResult
  TestRun "1" --> "many" ExecutionArtifact
  TestRun --> RunProfile
  TestRun --> Environment
```

Core aggregates are `Project` and `TestRun`. A run stores snapshots of the selected revision, resolved variables (excluding secret values), profile, environment, and target. Results are append-only after completion. Artifact metadata is relational; artifact bytes live in object storage. Secret values are represented by `SecretReference` and resolved only through a provider at the execution boundary.
