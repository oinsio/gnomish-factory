# tracker-take — delta for add-pipeline-routing

## ADDED Requirements

### Requirement: Selection happens after claim, before the first round
Take and serve SHALL load and validate all pipelines and the routing table
at startup from the refreshed default branch, but SHALL resolve a task's
pipeline only after claiming it: fresh claims resolve the type through the
table (trusted-tier configuration) and pin the result; resumes read the pin
and skip resolution entirely. The pinned name SHALL be bound to its
definition at the task's law commit, and the task's law frozen from that
definition — not from the startup load. No engine round SHALL run before
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

#### Scenario: Same pipeline, different bases
- **WHEN** serve works two `feature` tasks whose bases are `develop` and
  `release/1.18`, and the two bases define `feature` differently
- **THEN** each slot binds the `feature` definition of its own base's law
  commit
