# task-inspection — delta for harden-logging-observability

Layered on the capability as modified by `harden-task-branch-contract`
(sequenced before this change); the base text below is that change's
modified requirement.

## MODIFIED Requirements

### Requirement: Task list mode
`gnomish status --dir <clone>` without a task argument SHALL print a minimal table over all `gnomish/*` branches — local and remote-tracking, deduplicated per task with the local tip preferred when both exist — task, stage, attempts, outcome — with a `--json` variant. Every branch SHALL yield exactly one row whatever its shape: delivered, freshly created (no completed round yet), in-flight, and parked branches all render; a `Corrupt`, `UnsupportedVersion`, or `Unknown` branch renders as one row naming its shape and diagnosis. A branch that cannot be classified or read SHALL degrade to its own diagnostic row and SHALL NOT fail the listing of the other tasks. No sorting or filtering options.

Per-branch degradation SHALL NOT extend to the enumeration itself: when the
branch enumeration fails (the ref listing exits non-zero), the command SHALL
fail with the git evidence rather than print an empty table — an empty table
means "verified: no tasks", never "could not look".
<!-- implements FR13 of add-git-workflow -->
<!-- implements FR16 of harden-task-branch-contract -->
<!-- implements FR13 of harden-logging-observability -->

#### Scenario: Overview of all tasks
- **WHEN** the clone has three `gnomish/*` branches
- **THEN** the table lists all three with their recorded stage, attempts, and outcome

#### Scenario: Remote-only tasks are listed once
- **WHEN** one `gnomish/*` branch exists only as a remote-tracking ref and another exists both locally and on origin
- **THEN** the table lists each task exactly once, the latter read from its local tip

#### Scenario: Mixed-shape repository lists one row per task
- **WHEN** the clone holds a delivered branch, a freshly created branch, an in-flight branch, and a parked branch
- **THEN** the table shows exactly four rows, each rendering its shape without an error

#### Scenario: One bad branch never kills the listing
- **WHEN** one `gnomish/*` branch carries an unparseable `state.json` while two others are healthy
- **THEN** the two healthy tasks render normally and the bad branch renders as a single diagnostic row naming its shape

#### Scenario: A failed enumeration is an error, not an empty table
- **WHEN** the ref listing behind the table cannot be obtained at all
- **THEN** the command fails naming the git failure, and no empty "no tasks"
  table is printed
