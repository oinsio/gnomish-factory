# task-inspection

## Purpose

Provide read-only `status` and `usage` commands that inspect a task's git-persisted state directly from its branch — for a live task, a completed one, or one being worked by another instance — without ever mutating the clone.

## Requirements

### Requirement: External status reader
`gnomish status --dir <clone> <task> [--json]` SHALL read `.gnomish-task/` files directly from the task branch (`git show`) — no worktree, no checkout, no local branch creation; branch lookup: local → remote-tracking → narrow fetch of exactly `gnomish/<task>` → "task not found". Rendering SHALL reuse the status-report pure function and JSON contract v1 with live-only fields null. State files are version-gated as on resume: an unknown version SHALL refuse the command with a clear error naming the file and version. For a live task the command shows the last recorded round boundary, not "right now".
<!-- implements FR13, NFR-O1 of add-git-workflow -->

#### Scenario: Status of a running task from another terminal
- **WHEN** `gnomish status` runs against a task another process is executing
- **THEN** it prints the state as of the last round commit and mutates nothing in the clone

#### Scenario: Interrupted task reported honestly
- **WHEN** the branch has round commits but `task.json` outcome is null
- **THEN** the report shows the task as in progress/interrupted, matching the nullable live fields of contract v1

#### Scenario: Unknown state-file version refuses inspection
- **WHEN** the branch's `state.json` carries `"version": 2`
- **THEN** the command exits with an error naming the file and the unsupported version, mutating nothing

### Requirement: Task list mode
`gnomish status --dir <clone>` without a task argument SHALL print a minimal table over all `gnomish/*` branches — local and remote-tracking, deduplicated per task with the local tip preferred when both exist — task, stage, attempts, outcome — with a `--json` variant. No sorting or filtering options.
<!-- implements FR13 of add-git-workflow -->

#### Scenario: Overview of all tasks
- **WHEN** the clone has three `gnomish/*` branches
- **THEN** the table lists all three with their recorded stage, attempts, and outcome

#### Scenario: Remote-only tasks are listed once
- **WHEN** one `gnomish/*` branch exists only as a remote-tracking ref and another exists both locally and on origin
- **THEN** the table lists each task exactly once, the latter read from its local tip

### Requirement: Usage report
`gnomish usage --dir <clone> <task> [--json]` SHALL reconstruct per-stage/per-round usage from the git history of `state.json`: a chronological walk emitting a row per new `AttemptRecord`; salvage, cleanup, and `task.json`-only commits produce no rows. Text output: a stage/round table with result, tokens (in/out/cache summed over models), and wall time, plus totals; `--json`: full granularity (tokensByModel, judge votes per vote) under its own `"version": 1` mini-contract following the same JSON conventions. Git mode only; every recorded round of every stage visit — including failed attempts — is accounted.
<!-- implements FR14, NFR-C1 of add-git-workflow -->

#### Scenario: Failed rounds are visible cost
- **WHEN** a stage passed on round 2 after a quality failure on round 1
- **THEN** both rounds appear with their token and time usage and are included in the totals

#### Scenario: Service commits are not rounds
- **WHEN** the branch history contains a salvage commit and a cleanup commit
- **THEN** neither produces a usage row

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
