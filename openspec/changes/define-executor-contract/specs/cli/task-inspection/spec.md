# cli/task-inspection — delta for define-executor-contract

## MODIFIED Requirements

Layered on `Usage report` as modified by `tighten-spec-contract` (sequenced before this
change): that delta cut the body and added the scenarios "Text usage is a stage and round table
with totals" and "JSON usage carries full granularity under its own version"; this text bounds
the walk by `baseCommit` and the first-parent line, takes each row's identity from its record,
checks the fold against the tip, adds `--markdown`, and adds the scenarios "A first-round pass
is billed to its own stage", "The base's envelope is not this task's history", "A merged-in
foreign record is rejected", "A mismatch between the fold and the tip is an error", "Markdown
states the lower bound" and "Markdown usage carries the stage, role and model tables".
All must survive the later sync.

### Requirement: Usage report
`gnomish usage --dir <clone> <task> [--json | --markdown]` SHALL reconstruct per-stage/per-round
usage from the git history of `state.json` on the task branch's first-parent line between the
task's recorded `baseCommit` (exclusive) and the tip: a chronological walk emitting a row per
recorded `AttemptRecord`, its stage and task taken from the record's own identity; salvage,
cleanup, and `task.json`-only commits produce no rows. Git mode only.
**Implements**: FR14, NFR-C1 of add-git-workflow; FR16 of harden-task-branch-contract; FR18, FR19, FR20, G5 of define-executor-contract

#### Scenario: Failed rounds are visible cost
- **WHEN** a stage passed on round 2 after a quality failure on round 1
- **THEN** both rounds appear with their token and time usage and are included in the snapshot

#### Scenario: Service commits are not rounds
- **WHEN** the branch history contains a salvage commit and a cleanup commit
- **THEN** neither produces a usage row

#### Scenario: Unreadable historical commit is skipped, not fatal
- **WHEN** one mid-history commit holds a corrupt `state.json` while earlier and later commits are readable
- **THEN** `usage` emits a warning naming the skipped commit and still renders the table and snapshot from the readable history

#### Scenario: A first-round pass is billed to its own stage
- **WHEN** three consecutive stages each pass on their first round, each passing commit carrying the advanced position
- **THEN** the three rows name the three stages that ran, in order

#### Scenario: The base's envelope is not this task's history
- **WHEN** the task branch was created from a base whose tree already carried another task's `.gnomish-task/`
- **THEN** no row is emitted for the base's rounds and no warning is needed, because the walk starts after `baseCommit`

#### Scenario: A merged-in foreign record is rejected
- **WHEN** a merge of the base into the task branch brings a `state.json` whose records name another task
- **THEN** the walk follows the first-parent line, and any such record it still meets is rejected with a warning naming the commit

#### Scenario: A mismatch between the fold and the tip is an error
- **WHEN** the tip's `totals` differs from the fold of the walked rows
- **THEN** `usage` exits with an error naming both figures and renders no report

#### Scenario: Markdown states the lower bound
- **WHEN** one judge vote carried no cost
- **THEN** the Markdown cost column prints "at least" with the priced sum and names the unpriced token count

#### Scenario: Text usage is a stage and round table with totals
- **WHEN** `gnomish usage <task>` renders without `--json` or `--markdown`
- **THEN** it prints one row per stage round with its result, its tokens (summed over participants and models by the one usage fold) and its wall time, followed by the snapshot

#### Scenario: JSON usage carries full granularity under its own version
- **WHEN** `gnomish usage <task> --json` renders
- **THEN** the document carries `"version": 1` of its own mini-contract, following the same JSON conventions as the status contract, with every participant entry, its per-model tokens and cost, and the snapshot

#### Scenario: Markdown usage carries the stage, role and model tables
- **WHEN** `gnomish usage <task> --markdown` renders
- **THEN** it prints the elapsed time from the first round, a per-stage table (rounds, agent time, checks time, tokens, cost), a per-participant-role table and a per-model table
