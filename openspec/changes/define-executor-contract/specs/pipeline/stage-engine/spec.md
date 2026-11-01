# pipeline/stage-engine — delta for define-executor-contract

## ADDED Requirements

### Requirement: Execution result is status plus answer
The engine's execution result SHALL be the contract's status (`completed`, `failed` with
class, `pending` with key) plus an optional answer, usage with provenance and the round's
denials. Today's `Completed` and `DecisionNeeded` SHALL be expressible in this shape:
`DecisionNeeded` is `completed` with a `decision-request` answer. An executor-reported
`failed/quality` SHALL be a recorded round whose findings join the feedback of later attempts;
the verify chain SHALL NOT run for that round.
**Implements**: FR7 of define-executor-contract

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
`infrastructure`. Adapters SHALL NOT decide a class.
**Implements**: FR7, G4, M2 of define-executor-contract

#### Scenario: Classification table verified
- **WHEN** each status and class is produced by a fake executor
- **THEN** the engine's accounting matches the table (data-driven spec, one row per case)

#### Scenario: Budget exhausted is its own escalation
- **WHEN** a round returns `failed/limit` naming the token bound
- **THEN** the outcome is `Escalated(BudgetExhausted)` carrying the bound, `attemptsUsed` is
  unchanged and the round is recorded with its usage

### Requirement: Executor identity is pinned at first use
The task state SHALL carry, per executor name, the describe version of the first round that
ran it, written in that round's own commit and carried unchanged by every lifecycle rewrite. The
engine pre-flight SHALL compare it with the version the describe preflight obtained and SHALL
escalate `PipelineMismatch` naming the executor and both versions before any round runs.
**Implements**: FR13 of define-executor-contract

**Notes**: Name and model are law content, pinned with the law at creation; the version pin adds no durable step.

#### Scenario: Resume uses the recorded executor version
- **WHEN** a task whose rounds recorded executor version `1` is resumed on an instance whose image answers describe with version `2`
- **THEN** the resume escalates `PipelineMismatch` naming the recorded and the current version, and no round runs

#### Scenario: The pin survives a park
- **WHEN** a task whose round recorded executor version `1` parks on an escalation, a human returns it, and the image now answers describe with version `2`
- **THEN** the resume escalates `PipelineMismatch` naming the executor and both versions, and no round runs

### Requirement: Runs are observable by executor
Every executor run SHALL log, under the task's MDC, the executor name, protocol version, role,
status, class and any bound hit; the ledger line of a round SHALL carry
`executor=<name>@<version> role=<role> status=<status>[/<class>]`.
**Implements**: NFR-O2, UX3 of define-executor-contract

#### Scenario: Ledger carries executor identity
- **WHEN** a `program` stage round completes
- **THEN** its ledger line names the executor, version, role and status

### Requirement: Totals are one fold, checked
`TaskState.totals` SHALL be a snapshot of one pure fold over every participant entry of every
recorded round — role × model × the four counts, cost as a lower bound, and the snapshot's
position (`rounds`, `through`). One domain function SHALL be the only fold, used by every
writer of `totals` and every reader that sums usage; a round already named by `through` folds
to no change; lifecycle rewrites carry the snapshot unchanged.
**Implements**: FR17, FR18, NFR-O3 of define-executor-contract

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

Layered on `Two-level attempt telemetry` as modified by `supervise-daemon-loops-and-embed-dashboard`
(archived; its instant-source wording is in the stable text) and then by `tighten-spec-contract`
(archived; its text is the stable text): tighten-spec-contract cut the body and added the scenarios "Round
metrics carry wall time, tool aggregates and per-model tokens", "No display total is stored",
"Cumulative totals survive resume" and "The raw tool trace stays out of the state"; this text
replaces the usage and totals wording with the round identity, the participant `usage` list and
the usage snapshot, and adds the scenarios "The record names its stage", "Round identity is
written, never inferred" and "A missing cost is absent, never zero". All must survive the later
sync.

### Requirement: Two-level attempt telemetry
Every executed round SHALL be recorded in `TaskState.attempts` with its identity (`taskId`,
`stage` and, in a branch mode, the round token it opened on), a result classification (Passed, QualityFailure,
CannotVerify, DecisionNeeded, BudgetExhausted), a `startedAt` read from the engine's instant
source, and one `usage` list with an entry per executor invocation. The history SHALL cover only
the current stage, resetting on advancement.
**Implements**: FR13, FR14, NFR-C1 of add-stage-engine; FR15 of add-manual-run; FR5, FR9, NFR-C1 of add-agent-executor; FR17 of supervise-daemon-loops-and-embed-dashboard; FR14, FR15, NFR-O3 of define-executor-contract
**Notes**: A branch mode is host git or container; an in-place run has no task branch, so it records no round token (design D13).
**Notes**: `TaskState` carries the usage snapshot of requirement "Totals are one fold, checked"; an `ABSENT` entry keeps the unknown ≠ zero distinction.

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

#### Scenario: Round identity is written, never inferred
- **WHEN** a reader reads a recorded round
- **THEN** it takes the round's `taskId`, `stage` and round token from the record, written by the round's writer, and never infers them from the position, the list shape or neighbouring records

#### Scenario: Cumulative totals survive advancement
- **WHEN** a stage passes and the pipeline advances
- **THEN** the new state's attempt history is empty while the snapshot still includes the passed stage's usage and its `through` names that round

#### Scenario: Per-model grain survives merging
- **WHEN** two rounds report usage for overlapping model ids under the same role
- **THEN** the snapshot carries the union of model ids with the four token counts summed per role and model

#### Scenario: Unreported tokens stay distinguishable from zero
- **WHEN** a round's entry has provenance `ABSENT`
- **THEN** the entry carries an empty model map, the snapshot gains no zero-valued entries and `rounds` still advances by one

#### Scenario: A missing cost is absent, never zero
- **WHEN** a usage entry reports tokens for a model without a cost
- **THEN** the recorded entry carries no cost for that model, never a zero cost

#### Scenario: Round metrics carry wall time, tool aggregates and per-model tokens
- **WHEN** a round reports its metrics
- **THEN** each of its `usage` entries carries the contract role (`transform`, `verdict`, `decision`, `effect`), the executor name, the vote index for `verdict`, the provenance (`REPORTED | ABSENT`), the optional wall time and per-tool aggregates (name, call count, total duration), and per-model entries keyed by resolved model id, each carrying input, output, cache-creation and cache-read counts and an optional cost in USD
- **AND** each judge vote is its own `verdict` entry, and no role-named usage slot exists

#### Scenario: No display total is stored
- **WHEN** a round's usage or the task's cumulative totals are recorded
- **THEN** the record holds the per-model token map and no stored total beside it; any display total is derived from the map

#### Scenario: Cumulative totals survive resume
- **WHEN** a run resumes from a persisted state
- **THEN** the task's usage snapshot starts from the persisted one and keeps folding on every recorded round

#### Scenario: The raw tool trace stays out of the state
- **WHEN** a round produces its chronological ToolTrace
- **THEN** `TaskState` holds only the round's aggregates, and the trace travels beside it, correlated by the (taskId, stage, attempt) key
