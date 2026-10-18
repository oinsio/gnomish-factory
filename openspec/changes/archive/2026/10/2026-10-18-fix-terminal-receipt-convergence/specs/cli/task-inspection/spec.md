# Spec Delta: cli/task-inspection

The list mode renders the new `Unregistered` shape (design D13 of fix-terminal-receipt-convergence)
by name and with its remedy: the listing is a local read, so a branch whose register a plain
`git fetch` did not bring is "not yet fetched", not "unrecognized". The requirement is repeated
whole; every existing scenario keeps its title.

## MODIFIED Requirements

### Requirement: Task list mode
`gnomish status --dir <clone>` without a task argument SHALL print a minimal table over all `gnomish/*` branches — local and remote-tracking, deduplicated per task with the local tip preferred when both exist — task, stage, attempts, outcome — with a `--json` variant.
**Implements**: FR10, UX3 of fix-terminal-receipt-convergence; FR13 of add-git-workflow; FR16 of harden-task-branch-contract; FR13 of harden-logging-observability
**Notes**: An empty table means "verified: no tasks", never "could not look".

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

#### Scenario: A branch fetched without its register lists as Unregistered with its remedy
- **WHEN** the clone holds a `gnomish/*` branch, local or remote-tracking, for which it holds no factory register — as a plain `git fetch` leaves it
- **THEN** its row names the shape `Unregistered` and the `gnomish status <task>` command that fetches the register, never "an unrecognized combination", and the listing itself runs no fetch

#### Scenario: Every refusing shape is one diagnostic row
- **WHEN** the clone holds a `Corrupt`, an `UnsupportedVersion`, and an `Unknown` branch beside a healthy one
- **THEN** each of the three renders as one row naming its shape and diagnosis, and the healthy task renders normally

#### Scenario: The listing takes no sorting or filtering option
- **WHEN** `gnomish status --dir <clone>` is given a sorting or filtering option such as `--sort` or `--filter`
- **THEN** the command refuses it as an unknown option, as the `cli-arguments` capability defines
