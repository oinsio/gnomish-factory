# factory-serve — delta

## ADDED Requirements

### Requirement: Container-bound stages run in slots
`gnomish serve` slots SHALL execute container-bound stages through the same container assembly as `gnomish take`, concurrently across slots, each slot's environment isolated by its own task key. At task end the slot SHALL stop-keep a non-disposed environment (stop the box, retain volume and network) so the kept population is well-formed for the reaper. Host-mode slots SHALL be unchanged.
<!-- implements FR1, FR6 of add-serve-sandbox-lifecycle -->

#### Scenario: Two slots run containers concurrently
- **WHEN** two slots hold container-bound tasks at once
- **THEN** each task runs in its own box, volume, and network, and neither slot's lifecycle operations touch the other's objects

#### Scenario: Slot end leaves a kept environment
- **WHEN** a slot's task exits non-completed (escalated, paused)
- **THEN** the box is stopped with volume and network retained, and the environment appears in the kept inventory

### Requirement: Daemon schedules the sandbox sweep tick
The daemon SHALL run the `sandbox-lifecycle` sweep and aged reaper on a periodic tick for its whole lifetime, starting with an immediate startup tick. The tick SHALL run off the slot path — slot launch latency is unaffected — and a failed tick is logged and retried at the next cadence, never killing the daemon. Tick cadence SHALL be configurable.
<!-- implements FR6, NFR-P1, NFR-R3 of add-serve-sandbox-lifecycle -->

#### Scenario: Sweep tick coexists with launching slots
- **WHEN** a tick evaluates the host while a slot is mid-materialize
- **THEN** the launching objects are protected (labels from birth plus minimum age) and the tick completes without delaying the launch

#### Scenario: Dead sibling reclaimed without its reboot
- **WHEN** another instance died mid-task and its claim went stale
- **THEN** this daemon's next tick stops the abandoned running box; no restart of the dead instance is involved

## MODIFIED Requirements

### Requirement: Worktree cleaner disposes aged task environments
The daemon SHALL dispose of host worktree environments whose last file activity is older than a configured age and which do not currently occupy a slot of this instance; ended tasks (delivered, escalated, revoked) stop touching their worktrees and are the population this policy targets. Disposal SHALL go through the bound task environment port. For host worktrees, tracker status SHALL NOT be consulted — worktrees are instance-local and a disposed-too-early worktree costs only a re-materialize on resume, never correctness. A task currently occupying a slot of this instance SHALL never be disposed regardless of age, and a same-instance resume SHALL keep reusing a still-present environment. Sandboxed (container) environments are NOT governed by this cleaner: they live in a host-global namespace and are governed by the ownership-based sweep and aged reaper of `sandbox-lifecycle`.
<!-- implements FR14 of add-factory-serve -->
<!-- implements FR5, FR6 of add-serve-sandbox-lifecycle -->

#### Scenario: Aged environment removed
- **WHEN** an escalated task's worktree exceeds the age threshold
- **THEN** the cleaner removes it, and a later resume rematerializes the worktree from the branch

#### Scenario: Aged container environment removed by runtime age
- **WHEN** an escalated task's stopped container exceeds the age threshold per its runtime metadata
- **THEN** this cleaner leaves it untouched — it is not a host worktree — and the `sandbox-lifecycle` aged reaper disposes container, volume, and network by the same runtime-metadata age, so a later resume still materializes a fresh environment from the branch

#### Scenario: Working task untouched
- **WHEN** the cleaner runs while a task is `Working` in a slot of this instance
- **THEN** that task's environment is not considered for disposal
