# Spec Delta: task-inspection

## MODIFIED Requirements

### Requirement: External status reader
`gnomish status --dir <clone> <task> [--json]` SHALL read `.gnomish-task/` files directly from the task branch (`git show`) — no worktree, no checkout, no local branch creation; branch lookup: local → remote-tracking → narrow fetch of exactly `gnomish/<task>` → "task not found". Rendering SHALL reuse the status-report pure function and JSON contract v1, every field of which is state-derived. The command SHALL obtain the branch shape through the shape classifier and render every legal shape calmly: a delivered branch (cleanup done, `.gnomish-task/` stripped from the tip) renders as delivered; a freshly created branch whose tip carries the initial state and no completed round renders as pending, not as an error. A `Corrupt`, `UnsupportedVersion`, or `Unknown` shape SHALL exit with a clear diagnosis naming the offending file and the observed and expected shape — or, for `UnsupportedVersion`, the observed and supported versions — never a stack trace, mutating nothing. For a live task the command shows the last recorded round boundary, not "right now".
<!-- implements FR13, NFR-O1 of add-git-workflow -->
<!-- implements FR16 of harden-task-branch-contract -->
<!-- implements FR2 of harden-task-branch-contract -->
<!-- implements FR6 of make-run-headless -->

#### Scenario: Status of a running task from another terminal
- **WHEN** `gnomish status` runs against a task another process is executing
- **THEN** it prints the state as of the last round commit and mutates nothing in the clone

#### Scenario: Interrupted task reported honestly
- **WHEN** the branch has round commits but the recorded outcome is null
- **THEN** the report shows the task as in progress/interrupted, with a null `outcome` as contract v1 defines it mid-run

#### Scenario: Delivered branch renders as delivered
- **WHEN** `gnomish status <task>` targets a branch whose cleanup commit stripped `.gnomish-task/` from the tip
- **THEN** the command reports the task as delivered — no stack trace, no "missing state file" error

#### Scenario: Freshly created branch renders as pending
- **WHEN** `gnomish status <task>` targets a branch holding only the STARTED commit, before any completed round
- **THEN** the command reports the task as pending/not-yet-started with its snapshot title

#### Scenario: Unknown state-file version refuses inspection
- **WHEN** the branch's `state.json` carries `"version": 2`
- **THEN** the shape is `UnsupportedVersion` and the command exits with a diagnosis naming the file, the observed version, and the supported range — no stack trace, nothing mutated
