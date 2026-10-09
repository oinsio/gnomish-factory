# task-inspection — delta for define-executor-contract

## MODIFIED Requirements

### Requirement: Usage report
`gnomish usage --dir <clone> <task> [--json | --markdown]` SHALL reconstruct per-stage/per-round usage from the git history of `state.json` on the task branch's first-parent line between the task's recorded `baseCommit` (exclusive) and the tip: a chronological walk emitting a row per recorded `AttemptRecord`, each row's stage and task taken from the record's own identity, never from the position or the list shape; a record whose `taskId` is not the task's SHALL be rejected with a warning naming the commit; salvage, cleanup, and `task.json`-only commits produce no rows. A historical commit whose state file cannot be read or parsed SHALL be skipped with a warning naming the commit — the walk continues and the report renders from the readable commits instead of failing. The command SHALL fold the walked rows with the one usage fold and compare the result with the tip's `totals`; a difference SHALL be an error naming both figures, never a rendered report. Text output: a stage/round table with result, tokens (summed over participants and models by the fold), and wall time, plus the snapshot; `--json`: full granularity (every participant entry with its per-model tokens and cost, the snapshot) under its own `"version": 1` mini-contract following the same JSON conventions; `--markdown`: the elapsed time from the first round, a per-stage table (rounds, agent time, checks time, tokens, cost), a per-participant-role table and a per-model table, with an incomplete cost rendered as a lower bound naming the unpriced token count. Git mode only; every recorded round of every stage visit — including failed attempts — is accounted.
<!-- implements FR14, NFR-C1 of add-git-workflow -->
<!-- implements FR16 of harden-task-branch-contract -->
<!-- implements FR18, FR19, FR20, G5 of define-executor-contract -->

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
