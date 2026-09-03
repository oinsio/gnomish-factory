# task-inspection

## Purpose

Provide read-only `status` and `usage` commands that inspect a task's git-persisted state directly from its branch — for a live task, a completed one, or one being worked by another instance — without ever mutating the clone.

## Requirements

### Requirement: External status reader
`gnomish status --dir <clone> <task> [--json]` SHALL read `.gnomish-task/` files directly from the task branch (`git show`) — no worktree, no checkout, no local branch creation; branch lookup: local → remote-tracking → narrow fetch of exactly `gnomish/<task>` → "task not found". Rendering SHALL reuse the status-report pure function and JSON contract v1 with live-only fields null. The command SHALL obtain the branch shape through the shape classifier and render every legal shape calmly: a delivered branch (cleanup done, `.gnomish-task/` stripped from the tip) renders as delivered; a freshly created branch whose tip carries the initial state and no completed round renders as pending, not as an error. A `Corrupt`, `UnsupportedVersion`, or `Unknown` shape SHALL exit with a clear diagnosis naming the offending file and the observed and expected shape — or, for `UnsupportedVersion`, the observed and supported versions — never a stack trace, mutating nothing. For a live task the command shows the last recorded round boundary, not "right now".
<!-- implements FR13, NFR-O1 of add-git-workflow -->
<!-- implements FR16 of harden-task-branch-contract -->
<!-- implements FR2 of harden-task-branch-contract -->

#### Scenario: Status of a running task from another terminal
- **WHEN** `gnomish status` runs against a task another process is executing
- **THEN** it prints the state as of the last round commit and mutates nothing in the clone

#### Scenario: Interrupted task reported honestly
- **WHEN** the branch has round commits but the recorded outcome is null
- **THEN** the report shows the task as in progress/interrupted, matching the nullable live fields of contract v1

#### Scenario: Delivered branch renders as delivered
- **WHEN** `gnomish status <task>` targets a branch whose cleanup commit stripped `.gnomish-task/` from the tip
- **THEN** the command reports the task as delivered — no stack trace, no "missing state file" error

#### Scenario: Freshly created branch renders as pending
- **WHEN** `gnomish status <task>` targets a branch holding only the STARTED commit, before any completed round
- **THEN** the command reports the task as pending/not-yet-started with its snapshot title

#### Scenario: Unknown state-file version refuses inspection
- **WHEN** the branch's `state.json` carries `"version": 2`
- **THEN** the shape is `UnsupportedVersion` and the command exits with a diagnosis naming the file, the observed version, and the supported range — no stack trace, nothing mutated

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

### Requirement: Usage report
`gnomish usage --dir <clone> <task> [--json]` SHALL reconstruct per-stage/per-round usage from the git history of `state.json`: a chronological walk emitting a row per new `AttemptRecord`; salvage, cleanup, and `task.json`-only commits produce no rows. A historical commit whose state file cannot be read or parsed SHALL be skipped with a warning naming the commit — the walk continues and the report renders from the readable commits instead of failing. Text output: a stage/round table with result, tokens (in/out/cache summed over models), and wall time, plus totals; `--json`: full granularity (tokensByModel, judge votes per vote) under its own `"version": 1` mini-contract following the same JSON conventions. Git mode only; every recorded round of every stage visit — including failed attempts — is accounted.
<!-- implements FR14, NFR-C1 of add-git-workflow -->
<!-- implements FR16 of harden-task-branch-contract -->

#### Scenario: Failed rounds are visible cost
- **WHEN** a stage passed on round 2 after a quality failure on round 1
- **THEN** both rounds appear with their token and time usage and are included in the totals

#### Scenario: Service commits are not rounds
- **WHEN** the branch history contains a salvage commit and a cleanup commit
- **THEN** neither produces a usage row

#### Scenario: Unreadable historical commit is skipped, not fatal
- **WHEN** one mid-history commit holds a corrupt `state.json` while earlier and later commits are readable
- **THEN** `usage` emits a warning naming the skipped commit and still renders the table and totals from the readable history

### Requirement: Read-only guarantee
`status` and `usage` SHALL leave the clone unchanged: no working-copy mutation, no local branches; the only permitted side effect is the narrow fetch of the requested task ref.
<!-- implements FR13, FR14 of add-git-workflow -->

#### Scenario: Narrow fetch only
- **WHEN** the task branch exists only on origin
- **THEN** the command fetches exactly that ref, creates no local branch, and the working copy is untouched

### Requirement: Missing task reported plainly
When no `gnomish/<task>` branch exists locally or on origin — including after a merged PR's branch deletion — the commands SHALL answer "task not found" and exit; branch death after merge is normal, not an error condition of the tool.
<!-- implements FR13 of add-git-workflow -->

#### Scenario: Deleted branch after merge
- **WHEN** the task branch was deleted after its PR merged
- **THEN** `status` and `usage` report "task not found" without stack traces or warnings
