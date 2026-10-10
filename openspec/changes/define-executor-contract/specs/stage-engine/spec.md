# stage-engine — delta for define-executor-contract

## ADDED Requirements

### Requirement: Execution result is status plus answer
The engine's execution result SHALL be the contract's status (`completed`, `failed` with
class, `pending` with key) plus an optional answer, usage with provenance and the round's
denials. Today's `Completed` and `DecisionNeeded` SHALL be expressible in this shape:
`DecisionNeeded` is `completed` with a `decision-request` answer. An executor-reported
`failed/quality` SHALL be a recorded round whose findings join the feedback of later attempts;
the verify chain SHALL NOT run for that round.
<!-- implements FR7 of define-executor-contract -->

#### Scenario: Decision request routes as before
- **WHEN** a round completes with a `decision-request` answer
- **THEN** the engine escalates `DecisionNeeded` with the question and options exactly as it did
  for the former `DecisionNeeded` result

#### Scenario: Executor-reported quality failure
- **WHEN** a round returns `failed/quality` with one finding on attempt 1
- **THEN** `attemptsUsed` becomes 1, the round is recorded and persisted under the strict
  ordering, no check runs for that round, and attempt 2's request carries the finding

### Requirement: One failure-class mapping
The mapping from a result's status to attempt accounting and escalation SHALL be made in one
engine component: `quality` burns an attempt; `infrastructure` burns none and escalates
`CannotExecute` after the engine's existing retries; `limit` burns none and escalates
`BudgetExhausted` naming the bound; `pending` for a role the engine cannot poll is
`infrastructure`. Adapters SHALL NOT decide a class. Bounds are judged against the task's
folded usage snapshot after the round: a money bound SHALL be judged only when the snapshot's
cost is complete, and an incomplete cost under a money bound SHALL be `limit` with the reason
"cost unknown"; the verdict SHALL be recorded in the same commit as the round and its snapshot.
<!-- implements FR7, G4, M2, NFR-C2, NFR-C3 of define-executor-contract -->

#### Scenario: Classification table verified
- **WHEN** each status and class is produced by a fake executor
- **THEN** the engine's accounting matches the table (data-driven spec, one row per case)

#### Scenario: Budget exhausted is its own escalation
- **WHEN** a round returns `failed/limit` naming the token bound
- **THEN** the outcome is `Escalated(BudgetExhausted)` carrying the bound, `attemptsUsed` is
  unchanged and the round is recorded with its usage

#### Scenario: Money bound on an unpriced snapshot fails closed
- **WHEN** a stage carries a money bound and the round's judge vote reported tokens without cost
- **THEN** the round ends `limit` with reason "cost unknown", and the commit that records the
  round carries the snapshot with `complete = false` and the `BudgetExhausted` outcome together

### Requirement: Executor identity is pinned at first claim
At first claim the engine SHALL record in the task state the resolved executor name, version
from describe, and model for every stage and check of the pipeline; a resume SHALL use the
pinned identity and SHALL escalate `PipelineMismatch` if the law now names a different one.
<!-- implements FR13 of define-executor-contract -->

#### Scenario: Resume uses the pinned executor
- **WHEN** a task is resumed after the law changed a stage's executor version
- **THEN** the resume escalates `PipelineMismatch` naming the pinned and the current identity

### Requirement: Runs are observable by executor
Every executor run SHALL log, under the task's MDC, the executor name, protocol version, role,
status, class and any bound hit; the ledger line of a round SHALL carry
`executor=<name>@<version> role=<role> status=<status>[/<class>]`.
<!-- implements NFR-O2, UX3 of define-executor-contract -->

#### Scenario: Ledger carries executor identity
- **WHEN** a `program` stage round completes
- **THEN** its ledger line names the executor, version, role and status

### Requirement: Totals are one fold, checked
`TaskState.totals` SHALL be a snapshot of one pure fold over every participant entry of every
recorded round — role × model × the four counts, cost as a lower bound, and the snapshot's
position (`rounds`, `through`). One domain function SHALL be the only fold, used by every
writer of `totals` and every reader that sums usage; a round already named by `through` folds
to no change; lifecycle rewrites carry the snapshot unchanged.
<!-- implements FR17, FR18, NFR-O3 of define-executor-contract -->

#### Scenario: Judge votes are in the totals
- **WHEN** a round records a `transform` entry and two `verdict` entries
- **THEN** the snapshot's `verdict` role carries both votes' tokens, `rounds` is 1 and
  `through` is the round's token

#### Scenario: Re-folding the recorded round is a no-op
- **WHEN** the fold is applied to a snapshot and a round whose token equals `through`
- **THEN** the snapshot is unchanged

#### Scenario: Missing cost makes the total a lower bound
- **WHEN** one folded entry reports tokens without cost
- **THEN** `complete` is false, `unpricedTokens` names that role and model with those tokens,
  and `priced` excludes nothing that was reported

#### Scenario: The walked fold equals the tip
- **WHEN** a task on a bare origin passes a stage, fails a round, takes a decision reset and is
  resumed by a second instance
- **THEN** folding every round `gnomish usage` walks equals the `totals` recorded at the tip

## MODIFIED Requirements

### Requirement: Two-level attempt telemetry
Layered on `stage-engine` as modified by `supervise-daemon-loops-and-embed-dashboard`
(sequenced before this change): the instant-source wording of that delta survives; this delta
replaces the usage and totals sentences.

Every executed round SHALL be recorded in `TaskState.attempts` with its identity — `taskId`,
`stage` and the round token the round opened on, written by the round's writer and never
inferred by a reader from the position, the list shape or neighbouring records — an explicit
result classification (Passed, QualityFailure, CannotVerify, DecisionNeeded), a `startedAt`
timestamp read from the engine's instant source when the round begins, and one `usage` list
with an entry per executor invocation of the round: the contract role (`transform`,
`verdict`, `decision`, `effect`), the executor name, the vote index for `verdict`, provenance
(`REPORTED | ABSENT`), optional wall time and per-tool aggregates (name, call count, total
duration), and per-model entries keyed by resolved model id carrying input, output,
cache-creation and cache-read counts and an optional cost in USD — including rounds ending in
CannotVerify, which are recorded but not counted. No role-named usage slot SHALL exist. An
entry with provenance `ABSENT` carries an empty model map, preserving the unknown ≠ zero
distinction; a missing cost is absent, never zero. `TaskState` SHALL carry the usage snapshot
("Totals are one fold, checked") for the whole task, folded on every recorded round and
preserved across stage advancement and resume. The raw chronological ToolTrace SHALL stay out
of TaskState, correlated by the (taskId, stage, attempt) key. The history SHALL cover only the
current stage, resetting on advancement.
<!-- implements FR13, FR14, NFR-C1 of add-stage-engine -->
<!-- implements FR15 of add-manual-run -->
<!-- implements FR5, FR9, NFR-C1 of add-agent-executor -->
<!-- implements FR17 of supervise-daemon-loops-and-embed-dashboard -->
<!-- implements FR14, FR15, NFR-O3 of define-executor-contract -->

#### Scenario: Unburned round is still recorded
- **WHEN** a round's verification ends in CannotVerify
- **THEN** the round appears in `attempts` with its usage entries while `attemptsUsed` is unchanged

#### Scenario: History resets on advancement
- **WHEN** a stage passes and the pipeline advances
- **THEN** the new state's attempt history is empty and the previous rounds were persisted

#### Scenario: Round result is explicit
- **WHEN** a round ends in DecisionNeeded before any check runs
- **THEN** its record carries the DecisionNeeded result rather than leaving consumers to infer it from an empty check list

#### Scenario: Round start is timestamped
- **WHEN** the engine records any round — passed, failed, unverifiable, or decision-needed
- **THEN** the record carries `startedAt` equal to the instant-source reading taken when the round began

#### Scenario: The record names its stage
- **WHEN** a stage passes on its first round and the same commit advances the position
- **THEN** the recorded round carries the stage that ran it, not the stage the position now names

#### Scenario: Cumulative totals survive advancement
- **WHEN** a stage passes and the pipeline advances
- **THEN** the new state's attempt history is empty while the snapshot still includes the passed stage's usage and its `through` names that round

#### Scenario: Per-model grain survives merging
- **WHEN** two rounds report usage for overlapping model ids under the same role
- **THEN** the snapshot carries the union of model ids with the four token counts summed per role and model

#### Scenario: Unreported tokens stay distinguishable from zero
- **WHEN** a round's entry has provenance `ABSENT`
- **THEN** the snapshot gains no zero-valued entries and `rounds` still advances by one
