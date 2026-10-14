# Spec Delta: cli/task-inspection

## MODIFIED Requirements

### Requirement: External status reader
`gnomish status --dir <clone> <task> [--json]` SHALL read `.gnomish-task/` files directly from the task branch (`git show`) — no worktree, no checkout, no local branch creation; branch lookup: local → touchpoint fetch of exactly `gnomish/<task>` and its register → a remote-tracking ref already present, only when origin cannot deliver → "task not found".
**Implements**: FR13, NFR-O1 of add-git-workflow; FR16 of harden-task-branch-contract; FR2 of harden-task-branch-contract; FR6 of make-run-headless; FR13 of tighten-spec-contract

#### Scenario: Status of a branch the clone holds only as a tracking ref
- **WHEN** `gnomish status <task>` targets a task whose branch the clone holds only as a remote-tracking ref, with no register
- **THEN** the lookup fetches branch and register from origin and renders the recorded state, creating no local branch

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
- **THEN** the command reports the task with its snapshot title and its first stage, with no round run and no outcome (`--json` carries a null `outcome` and zero attempts used), and the listing names its shape `Created`

#### Scenario: Unknown state-file version refuses inspection
- **WHEN** the branch's `state.json` carries `"version": 2`
- **THEN** the shape is `UnsupportedVersion` and the command exits with a diagnosis naming the file, the observed version, and the supported range — no stack trace, nothing mutated

#### Scenario: Status renders through the status-report contract
- **WHEN** `gnomish status <task>` renders a found task, as text or with `--json`
- **THEN** it renders through the status-report pure function, the `--json` document is JSON contract v1, and every field is derived from the recorded state

#### Scenario: The shape classifier decides how a branch renders
- **WHEN** `gnomish status <task>` reads a task branch
- **THEN** it obtains the branch shape from the shape classifier and renders every legal shape calmly, without an error

#### Scenario: A corrupt or unknown branch refuses inspection
- **WHEN** the branch's shape is `Corrupt` or `Unknown`
- **THEN** the command exits with a diagnosis naming the offending file and the observed and expected shape — no stack trace, nothing mutated

### Requirement: Task list mode
`gnomish status --dir <clone>` without a task argument SHALL print a minimal table over all `gnomish/*` branches — local and remote-tracking, deduplicated per task with the local tip preferred when both exist — task, stage, attempts, outcome — with a `--json` variant.
**Implements**: FR13 of add-git-workflow; FR16 of harden-task-branch-contract; FR13 of harden-logging-observability
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

#### Scenario: Every refusing shape is one diagnostic row
- **WHEN** the clone holds a `Corrupt`, an `UnsupportedVersion`, and an `Unknown` branch beside a healthy one
- **THEN** each of the three renders as one row naming its shape and diagnosis, and the healthy task renders normally

#### Scenario: The listing takes no sorting or filtering option
- **WHEN** `gnomish status --dir <clone>` is given a sorting or filtering option such as `--sort` or `--filter`
- **THEN** the command refuses it as an unknown option, as the `cli-arguments` capability defines

### Requirement: Usage report
`gnomish usage --dir <clone> <task> [--json]` SHALL reconstruct per-stage/per-round usage from the git history of `state.json`: a chronological walk emitting a row per new `AttemptRecord`; salvage, cleanup, and `task.json`-only commits produce no rows. Git mode only.
**Implements**: FR14, NFR-C1 of add-git-workflow; FR16 of harden-task-branch-contract

#### Scenario: Failed rounds are visible cost
- **WHEN** a stage passed on round 2 after a quality failure on round 1
- **THEN** both rounds appear with their token and time usage and are included in the totals

#### Scenario: Service commits are not rounds
- **WHEN** the branch history contains a salvage commit and a cleanup commit
- **THEN** neither produces a usage row

#### Scenario: Unreadable historical commit is skipped, not fatal
- **WHEN** one mid-history commit holds a corrupt `state.json` while earlier and later commits are readable
- **THEN** `usage` emits a warning naming the skipped commit and still renders the table and totals from the readable history

#### Scenario: Text usage is a stage and round table with totals
- **WHEN** `gnomish usage <task>` renders without `--json`
- **THEN** it prints one row per stage round with its result, its tokens (in, out and cache, summed over models) and its wall time, followed by the totals

#### Scenario: JSON usage carries full granularity under its own version
- **WHEN** `gnomish usage <task> --json` renders
- **THEN** the document carries `"version": 1` of its own mini-contract, following the same JSON conventions as the status contract, with tokens per model and every judge vote

### Requirement: Read-only guarantee
`status` and `usage` SHALL leave the clone unchanged: no working-copy mutation, no local branches; the only permitted side effect is the narrow fetch of the requested task ref.
**Implements**: FR13, FR14 of add-git-workflow

#### Scenario: Narrow fetch only
- **WHEN** the task branch exists only on origin
- **THEN** the command fetches exactly that ref, creates no local branch, and the working copy is untouched

### Requirement: Missing task reported plainly
When no `gnomish/<task>` branch exists locally or on origin — including after a merged PR's branch deletion — the commands SHALL answer "task not found" and exit; branch death after merge is normal, not an error condition of the tool.
**Implements**: FR13 of add-git-workflow

#### Scenario: Deleted branch after merge
- **WHEN** the task branch was deleted after its PR merged
- **THEN** `status` and `usage` report "task not found" without stack traces or warnings
