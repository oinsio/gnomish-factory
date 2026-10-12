# tracker/tracker-take — delta for add-pipeline-routing

## ADDED Requirements

### Requirement: Selection happens after claim, before the first round
Take and serve SHALL load and validate all pipelines and the routing table
at startup from the refreshed default branch, but SHALL resolve a task's
pipeline only after claiming it: fresh claims resolve the type through the
table and pin the result; resumes read the pin and skip resolution entirely.
The pinned name SHALL be bound to its definition at the task's law commit, and
no engine round SHALL run before the task's pipeline is pinned.
**Implements**: FR3 of add-pipeline-routing
**Notes**: The routing table is trusted-tier configuration; the pipelines it names are task-tier, bound per task.

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

#### Scenario: Same pipeline, different bases
- **WHEN** serve works two `feature` tasks whose bases are `develop` and
  `release/1.18`, and the two bases define `feature` differently
- **THEN** each slot binds the `feature` definition of its own base's law
  commit

#### Scenario: The law is frozen from the law commit, not the startup load
- **WHEN** the default branch loaded at startup defines pipeline `feature` differently from a claimed task's law commit
- **THEN** the task's frozen law is built from the definition at its law commit

#### Scenario: A routing escalation burns no attempt
- **WHEN** a claimed task's type has no table entry, or its type facts conflict
- **THEN** the take escalates through the standard escalation path, no engine round runs, and no stage attempt is burned
