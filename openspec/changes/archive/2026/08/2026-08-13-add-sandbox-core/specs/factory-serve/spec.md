# factory-serve (delta)

## MODIFIED Requirements

### Requirement: Worktree cleaner disposes aged task environments
The daemon SHALL dispose of task environments whose last activity is older
than a configured age and which do not currently occupy a slot of this
instance; ended tasks (delivered, escalated, revoked) stop touching their
environments and are the population this policy targets — including kept
sandboxed environments, whose containers are stopped per the keep semantics
of git-task-persistence. Disposal SHALL go through the bound task
environment port. Age SHALL be measured per adapter: host worktrees by last
file activity as today; sandboxed environments by the runtime's own object
metadata (e.g. the container's finished-at timestamp) — never by file
mtimes inside volumes. Tracker status SHALL NOT be consulted — a
disposed-too-early environment costs only a re-materialize on resume, never
correctness. A task currently occupying a slot of this instance SHALL never
be disposed regardless of age, and a same-instance resume SHALL keep
reusing a still-present environment.
<!-- implements FR14 of add-factory-serve -->
<!-- implements FR11, NFR-R2 of add-sandbox-core -->

#### Scenario: Aged environment removed
- **WHEN** an escalated task's worktree exceeds the age threshold
- **THEN** the cleaner removes it, and a later resume rematerializes the
  worktree from the branch

#### Scenario: Aged container environment removed by runtime age
- **WHEN** an escalated task's stopped container exceeds the age threshold
  per its runtime metadata
- **THEN** the cleaner disposes container, volume, and network through the
  port, and a later resume materializes a fresh environment from the branch

#### Scenario: Working task untouched
- **WHEN** the cleaner runs while a task is `Working` in a slot of this
  instance
- **THEN** that task's environment is not considered for disposal
