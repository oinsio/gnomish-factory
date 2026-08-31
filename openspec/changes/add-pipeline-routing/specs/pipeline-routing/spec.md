# pipeline-routing — new capability (add-pipeline-routing)

## Purpose

Owns how a task reaches its pipeline: the task-type entity and the adapter's
duty to report it, the routing table with its explicit default and loud
no-match behavior, deterministic claim-time selection, the pipeline pin on
the task branch with resume-time verification, and the retype policy.

## ADDED Requirements

### Requirement: Task type is a core entity reported by adapters
The task type SHALL be a core value — an operator-defined designator string —
carried as an adapter-reported task fact. Each tracker adapter SHALL define
how the type is stored in its tracker and report it; the core SHALL never
parse tracker-specific representations. A task whose tracker data yields
more than one conflicting designator SHALL be reported as a type-conflict
fact, not resolved by picking one.
<!-- implements FR2 of add-pipeline-routing -->

#### Scenario: Type flows as a fact
- **WHEN** a claimed task carries the designator `bugfix` in its tracker
- **THEN** the core sees the type fact `bugfix` without knowing how the
  adapter stored it

#### Scenario: Conflict is a fact, not a pick
- **WHEN** a task's tracker data yields both `bugfix` and `research`
- **THEN** the adapter reports a type conflict and the core treats it as a
  routing error

### Requirement: Routing table with explicit default
The routing configuration SHALL map type designators to named pipelines and
SHALL declare a default pipeline explicitly. Selection SHALL resolve: a
task's type through the table; a typeless task to the default; several types
may map to one pipeline. Load-time validation SHALL reject a table entry or
default naming a pipeline that does not exist. There SHALL be no implicit
fallback — with no declared default, a typeless task is a routing error.
<!-- implements FR1, FR3 of add-pipeline-routing -->

#### Scenario: Type routes to its pipeline
- **WHEN** the table maps `research` to pipeline `spike` and a `research`
  task is claimed
- **THEN** the task runs the `spike` pipeline

#### Scenario: Typeless task takes the declared default
- **WHEN** a task with no type fact is claimed and the table declares a
  default
- **THEN** the task runs the default pipeline

#### Scenario: Table referencing a missing pipeline fails at load
- **WHEN** the routing table maps a type to a pipeline name with no
  definition
- **THEN** loading reports a located error before any task is claimed

### Requirement: No-match escalates loudly without burning attempts
When a claimed task's type resolves to no table entry, or its type facts
conflict, the factory SHALL escalate the task with a report naming the
designators and the known table — before any stage runs, burning no stage
attempts and leaving no silent no-op. The task parks for the human exactly
like other escalations and resumes normally once the tracker data or the
table is fixed.
<!-- implements FR3 of add-pipeline-routing -->

#### Scenario: Unknown type parks with a naming report
- **WHEN** a task typed `chore` is claimed and the table has no `chore`
  entry and routing has no rule for it
- **THEN** the task parks with a report naming `chore` and the routable
  types, and no engine round runs

#### Scenario: Fixed table resumes the parked task
- **WHEN** the operator adds the missing table entry and returns the task
- **THEN** the next claim selects the now-mapped pipeline and pins it

### Requirement: Pipeline pin at first claim, verified on every resume
The resolved pipeline name and the content hash of its definition SHALL land
in the task's branch identity file in the same commit that creates the task
on the branch. Every subsequent invocation SHALL load the pinned pipeline by
name from the current law source and verify the hash before running; a
missing pipeline name or a hash mismatch SHALL escalate as a pipeline
mismatch, never run on a differing definition, and never re-resolve the
type. The pin SHALL be immutable for the task's lifetime.
<!-- implements FR4, NFR-R1 of add-pipeline-routing -->

#### Scenario: Pin lands atomically with task creation
- **WHEN** a fresh claim creates the task on the branch
- **THEN** the creation commit already carries the pipeline name and
  definition hash — no window exists where the task is durable but unpinned

#### Scenario: Resume runs the pinned pipeline
- **WHEN** another instance resumes the task while the routing table now
  maps its type elsewhere
- **THEN** the resume runs the pinned pipeline, ignoring the new table

#### Scenario: Changed definition escalates, never silently runs
- **WHEN** the pinned pipeline's definition hash differs from the current
  `.gnomish/` content
- **THEN** the invocation escalates a pipeline mismatch naming the pipeline
  and no round runs

### Requirement: Retype affects only unpinned tasks
Changing a task's type in the tracker SHALL take effect only for tasks not
yet pinned: the next fresh claim resolves the new type. A pinned task SHALL
keep its pipeline regardless of tracker retype; re-routing a pinned task is
not supported by this capability.
<!-- implements FR4 of add-pipeline-routing -->

#### Scenario: Retype before first claim reroutes
- **WHEN** a ready, never-claimed task is retyped from `feature` to
  `bugfix` and then claimed
- **THEN** selection resolves `bugfix` and pins its pipeline

#### Scenario: Retype after pinning changes nothing
- **WHEN** a parked, pinned task is retyped in the tracker and resumed
- **THEN** the resume runs the pinned pipeline unchanged
