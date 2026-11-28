# stage-engine

## Purpose

Orchestrate one task through a declarative pipeline from its recorded state to a terminal outcome. The engine is pure: it executes nothing itself — all execution, verification, persistence, and observability happen behind injected ports — so a run is fully reproducible from `(definition, context, state, workspace, ports)`. It runs verify checks in fail-fast order, classifies each verdict as Pass / Fail / CannotVerify, retries quality failures within an attempt limit while feeding prior findings forward, escalates decisions and exhaustion as data-only reports, and persists every executed round so any instance can resume at attempt-boundary granularity. The engine holds no shared mutable state and never retries a port call.

## Requirements

### Requirement: Pure orchestration run
The engine SHALL run one task from its recorded state to a terminal outcome via `run(definition, context, state, workspace, ports)`, touching no tracker, filesystem, or git — the engine executes nothing itself; all effects happen behind the injected `EnginePorts` members.
**Implements**: FR1, NFR-S1 of add-stage-engine; FR15 of add-manual-run; FR17 of supervise-daemon-loops-and-embed-dashboard

#### Scenario: Every effect crosses an injected port
- **WHEN** the engine runs a task
- **THEN** every effect reaches the outside through the `EnginePorts` members: the seven behavioral ports (`StageExecutor`, `BuiltinCheckRunner`, `CommandCheckRunner`, `ExternalCheckClient`, `JudgeVoter`, `EngineEventListener`, `AttemptPersistence`) plus the time equipment (`TimeEquipment`), carrying the JDK instant source (`java.time.InstantSource`, the one type for the current instant across the codebase) and the `Sleeper` as one value, and the attempt-delivery seam (`AttemptDelivery`), which verifies an external check's push precondition before the poll loop starts

#### Scenario: Reference run with fakes only
- **WHEN** a pipeline exercising all four check types runs with fake ports, including a quality-failure retry, a decision escalation with a decision-carrying re-run, and a manual pause
- **THEN** the run reaches the expected terminal outcomes with no I/O outside the ports

#### Scenario: The engine's time source is the shared one
- **WHEN** the engine is assembled with the process's instant source
- **THEN** every timestamp and deadline the engine computes reads that same source, and no project-owned clock type stands between the engine and it

### Requirement: Ordered fail-fast verification
Verify checks SHALL run strictly in manifest order; the first non-Pass verdict stops the chain.
**Implements**: FR2 of add-stage-engine

#### Scenario: Later checks not reached
- **WHEN** the second of four checks returns Fail
- **THEN** checks three and four are never invoked and the attempt is a quality failure

### Requirement: Failure classification
Verdicts SHALL be Pass, Fail (findings, possibly empty), or CannotVerify (reason, details). Fail increments the attempt counter and feeds the non-passing check results (every non-Pass result, including CannotVerify) of all prior attempts of the stage into the next executor request; CannotVerify escalates without burning an attempt. Each check situation SHALL classify as the scenarios below state.
**Implements**: FR4 of add-stage-engine; FR4 of extract-agent-round-runner
**Notes**: The scenario "A judge check classifies by its votes" keeps its title from before votes were removed; a judge check now has one verdict.

#### Scenario: Classification table verified
- **WHEN** each classification case this requirement's scenarios state is produced by a fake port
- **THEN** the engine classifies it as stated (data-driven spec, one row per case)

#### Scenario: A command check classifies by how it ended
- **WHEN** a `command` check's process exits with a non-zero code
- **THEN** the check is Fail
- **AND** a `command` check whose binary is not found or cannot start is CannotVerify

#### Scenario: An external check classifies by its poll
- **WHEN** an `external` check's poll returns failure
- **THEN** the check is Fail
- **AND** a poll timeout is Fail under the timeout class `quality` (the default) and CannotVerify under `infrastructure`
- **AND** a check id unknown to the service is CannotVerify

#### Scenario: A judge check classifies by its votes
- **WHEN** a `judge` check's verdict is negative
- **THEN** the check is Fail
- **AND** a model reply unparseable as a verdict is CannotVerify

#### Scenario: A throwing adapter keeps its stack trace
- **WHEN** a check adapter throws
- **THEN** the engine catches the exception and the check's CannotVerify carries the exception's stack trace

#### Scenario: Feedback carries the whole stage history
- **WHEN** attempt 3 starts after two quality failures
- **THEN** the executor request contains the non-passing check results of attempts 1 and 2

### Requirement: Attempt limit and exhaustion
When the resolved attempt limit is exhausted — including `attemptsUsed >= limit` already on entry — the engine SHALL escalate with `AttemptsExhausted` carrying the full recorded attempt history of the stage.
**Implements**: FR5 of add-stage-engine

#### Scenario: Limit reached mid-run
- **WHEN** the final permitted attempt ends in a quality failure
- **THEN** the outcome is Escalated(AttemptsExhausted) and the final state lists every recorded round

### Requirement: Gnome-initiated decision escalation
An executor SHALL be able to return DecisionNeeded (question, free-text options) instead of completing; the engine SHALL escalate immediately without burning an attempt, recording the round with its metrics.
**Implements**: FR6 of add-stage-engine

#### Scenario: Undecidable choice
- **WHEN** the executor returns DecisionNeeded
- **THEN** the outcome is Escalated(DecisionNeeded) and `attemptsUsed` is unchanged

### Requirement: Human decisions as pass-through context
`TaskContext.decisions` SHALL be a chronological list of free-text decisions (optional stage/author/time) passed verbatim to executor and judge requests; the engine SHALL never interpret them. Resume adjustments (attempt reset, position moves) are the caller's state manipulation.
**Implements**: FR7 of add-stage-engine; FR4 of harden-task-branch-contract

#### Scenario: Decision-carrying resume
- **WHEN** a run starts with a decision appended and `attemptsUsed` reset by the caller
- **THEN** the executor request contains all decisions and execution proceeds from the recorded stage

#### Scenario: Decision and reset are inseparable
- **WHEN** a crash freezes state between a human decision being recorded and the next run starting
- **THEN** the recorded state carries the decision and the attempt-counter reset together, and the resumed run sees both or neither

### Requirement: Advancement modes
After verification passes, `auto` SHALL proceed to the next stage (Completed after the last); `manual` SHALL return Paused with the position **at the gate** — `AwaitingApproval(stage)`, naming the stage that passed — never past it.
**Implements**: FR1, FR2, FR4 of make-checkpoint-gate-durable
**Notes**: A run starting at the gate reproduces the stop from the record, not from the pipeline; the approval write of the task repository is what moves the position past the gate.

#### Scenario: Manual checkpoint
- **WHEN** a stage with `manual` advancement passes verification
- **THEN** the outcome is Paused naming that stage, the recorded position is `AwaitingApproval(stage)`, and a subsequent run with the returned state returns Paused again without invoking any port

#### Scenario: Manual pause on the last stage
- **WHEN** the final stage has `manual` advancement and passes verification
- **THEN** the recorded position is `AwaitingApproval(stage)`, not the pipeline end; a run from that state returns Paused, and only a state the approval advanced to the pipeline end returns Completed

#### Scenario: Approval opens the gate once
- **WHEN** the approval write moves the position from `AwaitingApproval(stage)` to the following stage and a run starts from the approved state
- **THEN** the engine starts the following stage at round zero with an empty attempt history and never re-invokes the passed stage's executor or checks

#### Scenario: The gate position rides the passing round's commit
- **WHEN** a stage with `manual` advancement passes verification
- **THEN** the single persist call for that round receives a state already positioned at `AwaitingApproval(stage)` (one durable transition, "One logical transition, one commit" of `git-task-persistence`), so no recorded tip says "continue" before the approval that allows it has been recorded

#### Scenario: A run at the gate emits only its bracket events
- **WHEN** a run starts from `AwaitingApproval(stage)`
- **THEN** it returns Paused(stage) immediately, emitting RunStarted and TaskFinished and no other event

### Requirement: Resume from any valid state
The engine SHALL resume at attempt-boundary granularity from any valid recorded state — mid-pipeline, mid-retry, at a gate, or post-approval.
**Implements**: FR2, FR6 of make-checkpoint-gate-durable; FR9 of add-stage-engine; FR4, NFR-C1 of harden-task-branch-contract

#### Scenario: Mid-retry resume
- **WHEN** a run starts from a state recorded after two quality failures
- **THEN** execution continues with attempt 3 and the prior findings in feedback

#### Scenario: Stale position
- **WHEN** the state references a stage no longer in the pipeline
- **THEN** the outcome is Escalated(PipelineMismatch), no execution or persistence port was invoked, and RunStarted and TaskFinished were emitted

#### Scenario: Resume from a recorded pass starts the following stage
- **WHEN** a run starts from the state a passing round persisted on a stage with `auto` advancement — the position already at the following stage, the passing round still in the recorded history
- **THEN** the engine executes only the following stage, starting it at round zero with an empty attempt history, and never re-invokes the passed stage's executor or any of its checks

#### Scenario: Resume at a gate runs nothing
- **WHEN** a run starts from `AwaitingApproval(stage)` — the state a passing `manual` round persisted, with or without a park recorded in `task.json`
- **THEN** the outcome is Paused(stage), no execution, verification or persistence port was invoked, and the recorded state is unchanged

#### Scenario: Resume from a recorded stop runs nothing
- **WHEN** a run starts from a state whose last recorded round in the current stage carries a stop
- **THEN** the outcome is the matching Escalated report (see "The stop rides the round record"), and no execution or verification port was invoked

### Requirement: Outcome and report model
`TaskOutcome` SHALL be Completed | Paused(passedStage) | Escalated(report) | Aborted(failedAt, cause), each carrying the final TaskState. Escalation reports SHALL be data-only values of six kinds: AttemptsExhausted, DecisionNeeded, CannotVerify, PipelineMismatch, CannotExecute (executor infrastructure failure — no attempt burned, no round recorded), BudgetExhausted (a `failed/limit` result — no attempt burned, the round recorded with its usage and the bound).
**Implements**: FR10, UX1 of add-stage-engine; FR1 of fix-denial-attribution-durability; FR7 of define-executor-contract
**Notes**: CannotExecute carries the denials drained from its round's environment because that round left no attempt record to hold them.
**Notes**: The BudgetExhausted accounting is stated by the scenario "Budget exhausted is its own escalation" of requirement "One failure-class mapping".

#### Scenario: Executor infrastructure failure
- **WHEN** the executor port throws after its own retries
- **THEN** the outcome is Escalated(CannotExecute), `attemptsUsed` is unchanged, and no round is appended

#### Scenario: Report is self-describing
- **WHEN** an Escalated(AttemptsExhausted) outcome is rendered using only the outcome value and its final state
- **THEN** the stage, the limit, and every recorded round's check results and findings are available without any other source

#### Scenario: A round killed before its close reports its denials
- **WHEN** a round is killed on its round timeout after the gnome attempted a denied egress request
- **THEN** the outcome is Escalated(CannotExecute) carrying that denial, `attemptsUsed` is unchanged, and the attempt history is unchanged

#### Scenario: Denials of a failed round gate nothing
- **WHEN** a CannotExecute escalation carries denials
- **THEN** the stage's recorded verdicts and the feedback context of any later retry are identical to those of the same failure with no denials

#### Scenario: An engine-internal error is thrown, not returned
- **WHEN** an error inside the engine's own logic, not a port failure, occurs during a run
- **THEN** it propagates to the caller as an exception and no `TaskOutcome` is returned

### Requirement: Strict attempt persistence
The engine SHALL call `AttemptPersistence.persist(taskId, state, trace)` synchronously after every executed round — including rounds ending in CannotVerify or DecisionNeeded — before the AttemptFinished event and before any next attempt. A persistence failure SHALL end the run as Aborted with the in-memory final state, the failed round key, and the cause.
**Implements**: FR11, NFR-R4 of add-stage-engine; FR4 of harden-task-branch-contract

#### Scenario: Ordering invariant
- **WHEN** a round completes
- **THEN** the recorded call order is persist → AttemptFinished → next AttemptStarted

#### Scenario: Persistence failure aborts
- **WHEN** persist throws on round N
- **THEN** the outcome is Aborted with failedAt = round N and no further attempt starts

#### Scenario: Unpersisted round is safe to lose
- **WHEN** a run aborted on persisting round N is followed by a new run from the last persisted state
- **THEN** round N is re-executed and the task proceeds as if the aborted round never happened

#### Scenario: Pass and advancement are one durable transition
- **WHEN** a round passes verification on a stage with `auto` advancement
- **THEN** the single persist call for that round receives a state already positioned past the passed stage, and a resume from that persisted state starts at the next stage

### Requirement: Engine events
The engine SHALL emit sealed events — RunStarted, AttemptStarted, ExecutionFinished, CheckStarted, CheckPolled, CheckTimedOut, CheckFinished, AttemptFinished (new state + trace), TaskFinished — each self-contained with the (taskId, stage, attempt) key shared with logs and telemetry, delivered synchronously; every run, including pre-flight escalations, emits at least RunStarted and TaskFinished; listener exceptions SHALL be logged and swallowed.
**Implements**: FR12, NFR-O2, UX2 of add-stage-engine; NFR-O1, UX1 of migrate-verify-to-executor-contract

#### Scenario: Status reconstructed from events alone
- **WHEN** a recording listener observes a run with a retry
- **THEN** position, attempt counters, check verdicts, and metrics rebuilt from events match the final state

#### Scenario: Broken listener does not break the run
- **WHEN** the listener throws on every event
- **THEN** the run completes normally and each failure is logged

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

Layered on `Outcome and report model` as modified by `fix-denial-attribution-durability` and
`tighten-spec-contract` (both archived; their text is the stable text): this text adds the sixth
report kind, `BudgetExhausted`, and changes nothing else; every stable scenario is kept verbatim.
`enforce-artifact-contracts` is layered on this text (sequenced after this change).

### Requirement: Reentrant engine without internal retries
The engine SHALL hold no static or shared mutable state — concurrent runs with independent ports must not interfere — and SHALL never retry a port call.
**Implements**: NFR-R1, NFR-R2 of add-stage-engine

#### Scenario: Concurrent runs stay isolated
- **WHEN** two runs execute concurrently with independent fake ports
- **THEN** each produces the same result as when run alone, with no cross-talk in events or state

### Requirement: Exceptional-path logging
Adapter exceptions, listener failures, and aborts SHALL log at ERROR/WARN via SLF4J at the point of capture, with stack traces also preserved in CannotVerify details or the Aborted cause. (Domain purity of the engine packages is already governed by the pipeline-config capability, whose ArchUnit rule covers all `domain` packages and does not forbid `org.slf4j`.)
**Implements**: NFR-O1 of add-stage-engine

#### Scenario: Stack trace reaches the report
- **WHEN** a check adapter throws
- **THEN** an ERROR line is logged and the escalation report's details contain the stack trace

### Requirement: The stop rides the round record
A round whose result is a stop the engine will escalate SHALL carry that stop on its recorded attempt: a `DECISION_NEEDED` round carries the question and its options, a `CANNOT_VERIFY` round carries the check, the reason and the details — as untrusted-text carriers, persisted in the same round commit as the result.
**Implements**: FR5, FR6, NFR-C1 of make-checkpoint-gate-durable
**Notes**: A recorded stop re-escalates from the record exactly as a spent attempt limit re-escalates from the counter; the park commit that follows a stop is tracker delivery, holding nothing the next run needs.

#### Scenario: A lost park re-raises the question from the record
- **WHEN** a round ends in `DecisionNeeded`, its round commit lands, and the process dies before the park is recorded
- **THEN** the next run from that state returns Escalated(DecisionNeeded) with the same question and options, runs no executor and records no new round

#### Scenario: A lost park re-raises CannotVerify from the record
- **WHEN** a round ends in `CannotVerify`, its round commit lands, and the process dies before the park is recorded
- **THEN** the next run returns Escalated(CannotVerify) naming the same check, reason and details, with `attemptsUsed` unchanged

#### Scenario: A consumed stop is not re-raised
- **WHEN** the resumed write or a decision commit has cleared the outcome and reset the attempt history after a stop
- **THEN** the next run executes the stage from round zero and does not re-raise the recorded stop

#### Scenario: Stops without a recorded round are unchanged
- **WHEN** a run starts from a state whose attempt counter is spent, or from the state a `CannotExecute` escalation left
- **THEN** the first re-escalates as `AttemptsExhausted` from the counter, and the second, carrying no recorded round and so no stop, executes the stage again as a retry

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

#### Scenario: Executor outcome table verified
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

### Requirement: Verify chain runs through the executor contract
The engine SHALL run every `builtin`, `command` and `external` check through the executor
contract in role `verdict`: the same registry, result shape and outcome classifier as stage
rounds. `EnginePorts` SHALL expose no `BuiltinCheckRunner`, `CommandCheckRunner` or
`ExternalCheckClient`; `JudgeVoter` remains until the agent dialect migrates.
**Implements**: FR1, FR2, G1 of migrate-verify-to-executor-contract

#### Scenario: Ordered fail-fast is preserved
- **WHEN** a stage declares three checks and the second fails
- **THEN** the third is not started and the round records the first two results in order

#### Scenario: Check infrastructure failure burns no attempt
- **WHEN** a verdict executor returns `failed/infrastructure`
- **THEN** the check is cannot-verify, the attempt counter is unchanged and the task escalates
  after the engine's retries

### Requirement: Verdict derivation is one component
The pass/fail of a check SHALL be derived in one engine component from the SARIF answer and the
check's threshold; no adapter and no built-in SHALL construct a verdict.
**Implements**: FR7, M2 of migrate-verify-to-executor-contract

#### Scenario: Threshold table verified
- **WHEN** each combination of result level and threshold is produced by a fake executor
- **THEN** the derived verdict matches the table (data-driven spec, one row per case)

### Requirement: Pending check events
The engine SHALL emit one CheckPolled for each poll of a pending check, none for the check's start, and SHALL emit CheckTimedOut once when a pending check's wait reaches its timeout, as the wait's last event, carrying the failure the wait resolved to.
**Implements**: NFR-O1, UX1 of migrate-verify-to-executor-contract

#### Scenario: Each poll of a pending check is one event
- **WHEN** a started check answers pending on its start and on its first poll, then passes on its second poll
- **THEN** the run emits two CheckPolled events, one per poll, none for the start, and no CheckTimedOut

#### Scenario: A pending check's timeout is its last event
- **WHEN** a pending check's wait reaches its timeout
- **THEN** CheckTimedOut is emitted once, after the last CheckPolled, and carries the failure the wait resolved to

### Requirement: A judge check is one verdict request
A `judge` check SHALL be one `verdict` request to the executor its `executor:` block declares,
run as a check round and judged by the one verdict derivation; an answer that is no verdict SHALL
make the check cannot-verify. The checks of a stage SHALL run in declaration order and stop at
the first that does not pass. `EnginePorts` SHALL expose no `JudgeVoter`.
**Implements**: FR4, M3 of extract-agent-round-runner

#### Scenario: The judge's verdict is the check's verdict
- **WHEN** the executor of a `judge` check answers with a failing verdict carrying two findings
- **THEN** the check fails with those two findings and exactly one request was made

#### Scenario: A malformed judge answer is cannot-verify
- **WHEN** the executor of a `judge` check returns an answer that is no verdict
- **THEN** the check is cannot-verify, no attempt is burned

#### Scenario: A second judge runs only after the first passes
- **WHEN** a stage declares two `judge` checks and the first one fails
- **THEN** the second is never requested
- **AND** when the first passes, the second is requested and its verdict decides the stage

#### Scenario: Only one executor-facing port remains
- **WHEN** the engine ports record is inspected
- **THEN** it holds a `StageExecutor` and no judge or check port

### Requirement: A pending transform round is persisted before it is polled
When a `transform` executor whose describe declares `poll` returns `pending`, the engine SHALL
land the round's working copy and a pending record (round token, stage, attempt, key, start time)
in one pending round commit with its register, pushed, before the first `poll`. That commit SHALL
close no attempt: the verify chain SHALL NOT run and the stage SHALL NOT advance on it.
**Implements**: FR10, FR11, NFR-R3 of extract-agent-round-runner

#### Scenario: Pending round commit lands before the first poll
- **WHEN** a `transform` executor declaring `poll` returns `pending` with a key
- **THEN** origin's registered commit carries the round's working copy and the pending record before any `poll` call is made

#### Scenario: A pending round is never verified
- **WHEN** a pickup reads a registered commit carrying a pending round record
- **THEN** no verify check starts, the position is unchanged, and the only action taken is a `poll` of the recorded key

#### Scenario: Pending without the poll capability stays refused
- **WHEN** a `transform` executor whose describe declares no `poll` returns `pending`
- **THEN** the round is `failed/infrastructure` naming the unsupported pending, and no pending record is written

### Requirement: A pending transform round is polled to its end
The engine SHALL poll a pending `transform` round at the factory's pending interval in the
round's environment until a terminal status, and SHALL close the round with that result as any
round, dropping the pending record in the closing commit. The wait SHALL end at the factory's
pending ceiling counted from the recorded start time, as `failed/infrastructure` naming it.
**Implements**: FR10, FR12, NFR-O2, UX3 of extract-agent-round-runner
**Notes**: A changed key follows "A changed pending key is persisted before the next poll" of `verification/pending-poll` (`migrate-verify-to-executor-contract`); for a round it lands in a pending round commit.

#### Scenario: Completed poll closes the round
- **WHEN** a poll of a pending round returns `completed`
- **THEN** the round closes with the working copy the poll left, the attempt record carries no pending record, the verify chain runs, and the ledger shows `pending key=<prefix>` for the round

#### Scenario: Restart does not reset the pending ceiling
- **WHEN** a pending round recorded its start time and is resumed after a restart
- **THEN** the remaining wait is the ceiling minus the time elapsed since the recorded start, and expiry is `failed/infrastructure` naming the ceiling

#### Scenario: A changed key of a pending round lands in a pending round commit
- **WHEN** a poll of a pending round returns `pending` with a key other than the recorded one
- **THEN** a pending round commit carrying the new key is pushed before the next `poll`, and the attempt stays open

### Requirement: A poll continues the open round
A `poll` of a pending `transform` round SHALL run in the round its `start` opened: it SHALL NOT
open a round, mint a round token or close the round, and a `poll` that answers `pending` with the
recorded key SHALL write nothing on the task branch. A `poll` with no open round SHALL be refused
as `failed/infrastructure`.
**Implements**: FR10, NFR-R4 of extract-agent-round-runner
**Notes**: A check's `poll` is a check round in a box of its own, as `verification/pending-poll` states; this requirement is the stage round's.

#### Scenario: A poll that answers pending writes nothing
- **WHEN** a poll of a pending round returns `pending` with the recorded key
- **THEN** the task branch and its register are the ones the pending round commit left, and the round's environment is the one its `start` ran in

#### Scenario: Every commit of a pending round names one token
- **WHEN** a round goes `pending`, is polled twice and closes on a `completed` poll
- **THEN** its pending-round snapshot, its pending round commit, its closing snapshot and its attempt record carry the round token the round opened with, and the branch holds no other commit of the round

#### Scenario: A poll with no open round is refused
- **WHEN** a poll reaches a stage executor whose round source holds no open round and whose round cell was not restored
- **THEN** the result is `failed/infrastructure` naming the missing round, and no round is opened

### Requirement: Any instance resumes a pending transform round
A pickup that finds a pending round record at the registered commit SHALL continue that round
with `poll(key)` in an environment materialized at that commit, on any instance, and SHALL NOT
call `start`.
**Implements**: FR11, NFR-R3, NFR-R4, G5 of extract-agent-round-runner; FR11 of migrate-verify-to-executor-contract

#### Scenario: Kill before the pending commit re-drives start
- **WHEN** the factory is killed after a `transform` `start` returned `pending` and before the pending round commit was pushed
- **THEN** the resuming instance re-runs the round with a new `start`, and the ledger names the re-driven start

#### Scenario: Kill after the pending commit resumes the poll
- **WHEN** the factory is killed after the pending round commit was pushed
- **THEN** a second instance polls the recorded key, makes no `start` call, and the ledger shows `resumed key=<prefix>`

#### Scenario: Kill after the terminal poll closes the round without a start
- **WHEN** the factory is killed after a poll of a pending round returned a terminal status and before that poll's snapshot was registered
- **THEN** a second instance polls the recorded key again, closes the round with the terminal result it returns, and makes no `start` call

#### Scenario: Kill after the terminal snapshot re-verifies without a poll
- **WHEN** the factory is killed after the terminal poll's snapshot was registered and before the round close was pushed
- **THEN** a second instance re-verifies that snapshot, makes no `poll` and no `start` call, and the closing commit carries no pending record

#### Scenario: Resumed round keeps its identity
- **WHEN** a pending round is closed by a second instance
- **THEN** the closed attempt carries the round token the round went pending with, and the usage of its `start` and of every `poll` is recorded under that one round identity

#### Scenario: Second recovery of a pending round is a no-op
- **WHEN** recovery runs again on a branch whose pending round already closed
- **THEN** nothing is polled or started and the branch is unchanged

### Requirement: A record names the executor by its registry entry
Every record of a round or of an executor check — the log line, the ledger line, the usage
participant entry and the pending record — SHALL name the executor by the registry entry its
executor block resolved to, the name dispatch resolved, and a line that carries a version SHALL
carry that entry's describe version, which for a built-in is `built-in`.
**Implements**: NFR-O1, FR14 of extract-agent-round-runner
**Notes**: The type word of a block (`agent-cli`) is how a law asks for an executor; rounds recorded before this requirement keep it in their participant entries.

#### Scenario: An agent stage's round is named claude
- **WHEN** a stage whose executor block is `type: agent-cli` completes a round
- **THEN** its log line and its ledger line carry `executor=claude@built-in role=transform`, and the round's `transform` participant entry names `claude`

#### Scenario: A judge check's ledger line carries the version
- **WHEN** a `judge` check whose executor block is `type: agent-cli` ends
- **THEN** its ledger line carries `executor=claude@built-in role=verdict`, and its `verdict` participant entry names `claude`

#### Scenario: A program check's ledger line carries the described version
- **WHEN** an executor check of a `program` executor described with version `1` ends
- **THEN** its ledger line carries `executor=<name>@1 role=verdict`

#### Scenario: A built-in's version is not pinned
- **WHEN** a task's rounds ran an `agent-cli` stage and a `judge` check
- **THEN** the task state's `executorVersions` carries no entry for `claude`
