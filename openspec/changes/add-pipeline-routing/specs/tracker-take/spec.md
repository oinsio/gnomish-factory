# tracker-take — delta for add-pipeline-routing

## ADDED Requirements

### Requirement: Selection happens after claim, before the first round
Take and serve SHALL load and validate all pipelines and the routing table
at startup, but SHALL resolve a task's pipeline only after claiming it:
fresh claims resolve the type through the table and pin the result; resumes
read the pin and skip resolution entirely. No engine round SHALL run before
the task's pipeline is either pinned or freshly resolved-and-pinned. The
no-match and type-conflict escalations SHALL exit through the standard
escalation path without burning stage attempts.
<!-- implements FR3 of add-pipeline-routing -->

#### Scenario: Fresh claim resolves then pins
- **WHEN** a `bugfix`-typed task is claimed fresh
- **THEN** the table resolves `bugfix`, the pin lands with task creation,
  and the first round runs the selected pipeline

#### Scenario: Resume skips resolution
- **WHEN** a pinned task is resumed
- **THEN** no table lookup occurs and the pinned pipeline (hash-verified)
  runs

#### Scenario: Serve routes concurrent tasks independently
- **WHEN** serve works a `feature` task and a `research` task concurrently
- **THEN** each slot runs its own selected pipeline under its own frozen law
