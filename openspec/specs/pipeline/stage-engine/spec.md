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

### Requirement: External check poll loop
The engine SHALL poll an `external` check via single-poll port calls
returning `PollStatus` (Pass with an optional platform run URL | Fail
with findings | Running | CannotVerify with reason and details) at the
manifest interval until a verdict or the manifest timeout, using an
injected sleeper/clock.
**Implements**: FR3, NFR-R3 of add-stage-engine; FR9 of add-external-check-github-actions; FR21, NFR-O2 of add-sandbox-core
**Notes**: A passing poll's run URL is kept so reporting publishes it through the same channel a failing check's findings travel; submitting external checks is deferred (add-stage-engine NG8).

#### Scenario: The engine never submits an external check
- **WHEN** the verify chain reaches an `external` check
- **THEN** the engine issues no submission call — the check is assumed to be triggered by the task-branch push — and only polls

#### Scenario: Delivery is confirmed before the first poll
- **WHEN** the verify chain reaches an `external` check and the attempt commit is not yet on the remote
- **THEN** the engine re-attempts the push and issues the first poll only after the attempt commit is confirmed on the remote

#### Scenario: Verdict within timeout
- **WHEN** the poll port returns Running twice and then Pass
- **THEN** the engine sleeps the manifest interval between polls and the
  check passes

#### Scenario: A passing poll's run link is recorded
- **WHEN** the poll returns Pass carrying a platform run URL
- **THEN** the recorded check result carries that URL for the task report

#### Scenario: Undelivered attempt commit never burns an attempt
- **WHEN** the attempt commit cannot be pushed to the remote and the
  re-attempted push also fails
- **THEN** the external check resolves as CannotVerify naming the
  undelivered commit, no poll is issued, and no stage attempt is burned

#### Scenario: Timeout burns the attempt by default
- **WHEN** the poll port still returns Running when the timeout elapses
  (per the injected clock) and the check declares no timeout class
- **THEN** the check fails as a quality failure with a timeout finding

#### Scenario: Infrastructure-classed timeout burns no attempt
- **WHEN** the poll port still returns Running when the timeout elapses
  and the check declares its timeout class `infrastructure`
- **THEN** the check resolves as CannotVerify naming the elapsed timeout
- **AND** no stage attempt is burned and the task escalates with a
  "cannot verify" report

### Requirement: Judge majority voting
The engine SHALL cast up to the manifest `votes` count of sequential single-vote calls, stopping as soon as the majority is mathematically decided, and resolve the check by that majority; findings of failing cast votes are aggregated; any CannotVerify among cast votes SHALL fail the whole check as infrastructure.
**Implements**: FR3 of add-stage-engine
**Notes**: Manifest validation guarantees an odd `votes` count, so a decided majority is always reached; the tie-break below is defensive.

#### Scenario: Majority verdict with aggregated findings
- **WHEN** votes return Fail, Pass, Fail for a 3-vote judge check
- **THEN** the check fails with the findings of both failing votes

#### Scenario: Early majority stops voting
- **WHEN** the first two votes of a 3-vote judge check both return Fail
- **THEN** the third vote is never requested and the check fails

#### Scenario: One vote unobtainable
- **WHEN** any vote returns CannotVerify
- **THEN** the whole check is CannotVerify regardless of other votes

#### Scenario: Even-count tie resolves to Pass
- **WHEN** a 2-vote judge check returns Pass then Fail — a tie in which neither verdict reaches the majority
- **THEN** the check resolves to Pass via the defensive `pass >= fail` tie-break rather than leaving the verdict undefined

### Requirement: Failure classification
Verdicts SHALL be Pass, Fail (findings, possibly empty), or CannotVerify (reason, details). Fail increments the attempt counter and feeds the non-passing check results (every non-Pass result, including CannotVerify) of all prior attempts of the stage into the next executor request; CannotVerify escalates without burning an attempt. Each check situation SHALL classify as the scenarios below state.
**Implements**: FR4 of add-stage-engine

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
- **WHEN** the majority of a `judge` check's votes are negative
- **THEN** the check is Fail
- **AND** a model reply unparseable as a verdict is CannotVerify, and any single CannotVerify vote makes the whole check CannotVerify

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
`TaskOutcome` SHALL be Completed | Paused(passedStage) | Escalated(report) | Aborted(failedAt, cause), each carrying the final TaskState. Escalation reports SHALL be data-only values of five kinds: AttemptsExhausted, DecisionNeeded, CannotVerify, PipelineMismatch, CannotExecute (executor infrastructure failure — no attempt burned, no round recorded).
**Implements**: FR10, UX1 of add-stage-engine; FR1 of fix-denial-attribution-durability
**Notes**: CannotExecute carries the denials drained from its round's environment because that round left no attempt record to hold them.

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
The engine SHALL emit sealed events — RunStarted, AttemptStarted, ExecutionFinished, CheckStarted, CheckFinished, AttemptFinished (new state + trace), TaskFinished — each self-contained with the (taskId, stage, attempt) key shared with logs and telemetry, delivered synchronously; every run, including pre-flight escalations, emits at least RunStarted and TaskFinished; listener exceptions SHALL be logged and swallowed.
**Implements**: FR12, NFR-O2, UX2 of add-stage-engine

#### Scenario: Status reconstructed from events alone
- **WHEN** a recording listener observes a run with a retry
- **THEN** position, attempt counters, check verdicts, and metrics rebuilt from events match the final state

#### Scenario: Broken listener does not break the run
- **WHEN** the listener throws on every event
- **THEN** the run completes normally and each failure is logged

### Requirement: Two-level attempt telemetry
Every executed round — including rounds ending in CannotVerify, which are recorded but not counted — SHALL be recorded in `TaskState.attempts` with an explicit result classification (Passed, QualityFailure, CannotVerify, DecisionNeeded), a `startedAt` timestamp read from the engine's instant source when the round begins, and optional metrics. The history SHALL cover only the current stage, resetting on advancement.
**Implements**: FR13, FR14, NFR-C1 of add-stage-engine; FR15 of add-manual-run; FR5, FR9, NFR-C1 of add-agent-executor; FR17 of supervise-daemon-loops-and-embed-dashboard

#### Scenario: Unburned round is still recorded
- **WHEN** a round's verification ends in CannotVerify
- **THEN** the round appears in `attempts` with its token usage while `attemptsUsed` is unchanged

#### Scenario: History resets on advancement
- **WHEN** a stage passes and the pipeline advances
- **THEN** the new state's attempt history is empty and the previous rounds were persisted

#### Scenario: Round result is explicit
- **WHEN** a round ends in DecisionNeeded before any check runs
- **THEN** its record carries the DecisionNeeded result rather than leaving consumers to infer it from an empty check list

#### Scenario: Round start is timestamped
- **WHEN** the engine records any round — passed, failed, unverifiable, or decision-needed
- **THEN** the record carries `startedAt` equal to the instant-source reading taken when the round began

#### Scenario: Cumulative totals survive advancement
- **WHEN** a stage passes and the pipeline advances
- **THEN** the new state's attempt history is empty while the task totals still include the passed stage's usage

#### Scenario: Per-model grain survives merging
- **WHEN** two rounds report usage for overlapping model ids and the task totals fold them in
- **THEN** the totals carry the union of model ids with the four token counts summed per model

#### Scenario: Unreported tokens stay distinguishable from zero
- **WHEN** a round reports an empty token map
- **THEN** the cumulative totals are unchanged by that round rather than gaining zero-valued entries

#### Scenario: Round metrics carry wall time, tool aggregates and per-model tokens
- **WHEN** a round reports its metrics
- **THEN** its record carries the wall time, per-tool aggregates (name, call count, total duration), and per-model token maps keyed by resolved model id, each entry carrying input, output, cache-creation, and cache-read counts, with judge tokens as per-model maps per vote

#### Scenario: No display total is stored
- **WHEN** a round's usage or the task's cumulative totals are recorded
- **THEN** the record holds the per-model token map and no stored total beside it; any display total is derived from the map

#### Scenario: Cumulative totals survive resume
- **WHEN** a run resumes from a persisted state
- **THEN** the task's cumulative usage totals start from the persisted totals and keep accumulating on every recorded round

#### Scenario: The raw tool trace stays out of the state
- **WHEN** a round produces its chronological ToolTrace
- **THEN** `TaskState` holds only the round's aggregates, and the trace travels beside it, correlated by the (taskId, stage, attempt) key

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
