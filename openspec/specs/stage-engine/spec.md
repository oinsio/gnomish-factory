# stage-engine

## Purpose

Orchestrate one task through a declarative pipeline from its recorded state to a terminal outcome. The engine is pure: it executes nothing itself — all execution, verification, persistence, and observability happen behind injected ports — so a run is fully reproducible from `(definition, context, state, workspace, ports)`. It runs verify checks in fail-fast order, classifies each verdict as Pass / Fail / CannotVerify, retries quality failures within an attempt limit while feeding prior findings forward, escalates decisions and exhaustion as data-only reports, and persists every executed round so any instance can resume at attempt-boundary granularity. The engine holds no shared mutable state and never retries a port call.

## Requirements

### Requirement: Pure orchestration run
The engine SHALL run one task from its recorded state to a terminal outcome via `run(definition, context, state, workspace, ports)`, touching no tracker, filesystem, or git — the engine executes nothing itself; all effects happen behind the seven ports.
<!-- implements FR1, NFR-S1 of add-stage-engine -->

#### Scenario: Reference run with fakes only
- **WHEN** a pipeline exercising all four check types runs with fake ports, including a quality-failure retry, a decision escalation with a decision-carrying re-run, and a manual pause
- **THEN** the run reaches the expected terminal outcomes with no I/O outside the ports

### Requirement: Ordered fail-fast verification
Verify checks SHALL run strictly in manifest order; the first non-Pass verdict stops the chain.
<!-- implements FR2 of add-stage-engine -->

#### Scenario: Later checks not reached
- **WHEN** the second of four checks returns Fail
- **THEN** checks three and four are never invoked and the attempt is a quality failure

### Requirement: External check poll loop
The engine SHALL poll an `external` check via single-poll port calls returning `PollStatus` (Pass | Fail with findings | Running | CannotVerify with reason and details) at the manifest interval until a verdict or the manifest timeout, using an injected sleeper/clock; timeout SHALL be a quality failure. The engine SHALL NOT submit external checks: they are assumed to be triggered by the task-branch push (submission is deferred — proposal NG8).
<!-- implements FR3, NFR-R3 of add-stage-engine -->

#### Scenario: Verdict within timeout
- **WHEN** the poll port returns Running twice and then Pass
- **THEN** the engine sleeps the manifest interval between polls and the check passes

#### Scenario: Timeout burns the attempt
- **WHEN** the poll port still returns Running when the timeout elapses (per the injected clock)
- **THEN** the check fails as a quality failure with a timeout finding

### Requirement: Judge majority voting
The engine SHALL cast up to the manifest `votes` count of sequential single-vote calls, stopping as soon as the majority is mathematically decided, and resolve the check by that majority; findings of failing cast votes are aggregated; any CannotVerify among cast votes SHALL fail the whole check as infrastructure. Manifest validation guarantees an odd `votes` count, so a decided majority is always reached; should all cast votes complete without one (an even count that ties), the check SHALL resolve to Pass — the defensive `pass >= fail` tie-break — never leaving the verdict undefined.
<!-- implements FR3 of add-stage-engine -->

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
Verdicts SHALL be Pass, Fail (findings, possibly empty), or CannotVerify (reason, details). Fail increments the attempt counter and feeds the non-passing check results (every non-Pass result, including CannotVerify) of all prior attempts of the stage into the next executor request; CannotVerify escalates without burning an attempt. Adapter exceptions SHALL be caught and treated as CannotVerify with the stack trace preserved. The following classification SHALL hold row by row:

| Situation                                   | Class                      |
|---------------------------------------------|----------------------------|
| `command`: exit code ≠ 0                    | Fail                       |
| `command`: binary not found / cannot start  | CannotVerify               |
| `external`: poll returns failure            | Fail                       |
| `external`: poll timeout elapsed            | Fail                       |
| `external`: check id unknown to the service | CannotVerify               |
| `judge`: majority of votes negative         | Fail                       |
| `judge`: model reply unparseable as verdict | CannotVerify               |
| `judge`: any single vote CannotVerify       | CannotVerify (whole check) |
| any check adapter throws                    | CannotVerify               |
<!-- implements FR4 of add-stage-engine -->

#### Scenario: Classification table verified
- **WHEN** each table row's condition is produced by a fake port
- **THEN** the engine classifies it as the table's class (data-driven spec, one row per case)

#### Scenario: Feedback carries the whole stage history
- **WHEN** attempt 3 starts after two quality failures
- **THEN** the executor request contains the non-passing check results of attempts 1 and 2

### Requirement: Attempt limit and exhaustion
When the resolved attempt limit is exhausted — including `attemptsUsed >= limit` already on entry — the engine SHALL escalate with `AttemptsExhausted` carrying the full recorded attempt history of the stage.
<!-- implements FR5 of add-stage-engine -->

#### Scenario: Limit reached mid-run
- **WHEN** the final permitted attempt ends in a quality failure
- **THEN** the outcome is Escalated(AttemptsExhausted) and the final state lists every recorded round

### Requirement: Gnome-initiated decision escalation
An executor SHALL be able to return DecisionNeeded (question, free-text options) instead of completing; the engine SHALL escalate immediately without burning an attempt, recording the round with its metrics.
<!-- implements FR6 of add-stage-engine -->

#### Scenario: Undecidable choice
- **WHEN** the executor returns DecisionNeeded
- **THEN** the outcome is Escalated(DecisionNeeded) and `attemptsUsed` is unchanged

### Requirement: Human decisions as pass-through context
`TaskContext.decisions` SHALL be a chronological list of free-text decisions (optional stage/author/time) passed verbatim to executor and judge requests; the engine SHALL never interpret them. Resume adjustments (attempt reset, position moves) are the caller's state manipulation.
<!-- implements FR7 of add-stage-engine -->

#### Scenario: Decision-carrying resume
- **WHEN** a run starts with a decision appended and `attemptsUsed` reset by the caller
- **THEN** the executor request contains all decisions and execution proceeds from the recorded stage

### Requirement: Advancement modes
After verification passes, `auto` SHALL proceed to the next stage (Completed after the last); `manual` SHALL return Paused with the position already advanced past the paused stage. A manual pause on the final stage SHALL park the position at the explicit pipeline end; a run starting there SHALL return Completed immediately, emitting RunStarted and TaskFinished and invoking no execution or persistence port.
<!-- implements FR8 of add-stage-engine -->

#### Scenario: Manual checkpoint
- **WHEN** a stage with `manual` advancement passes verification
- **THEN** the outcome is Paused and a subsequent run with the returned state starts at the next stage

#### Scenario: Manual pause on the last stage
- **WHEN** the final stage has `manual` advancement and passes verification
- **THEN** the outcome is Paused with the position at pipeline end, and a subsequent run from that state returns Completed without invoking execution or persistence ports

### Requirement: Resume from any valid state
The engine SHALL resume at attempt-boundary granularity from any valid recorded state — mid-pipeline, mid-retry, or post-pause. A position naming a stage absent from the pipeline SHALL escalate as PipelineMismatch before any execution or persistence port call, with observability events still emitted.
<!-- implements FR9 of add-stage-engine -->

#### Scenario: Mid-retry resume
- **WHEN** a run starts from a state recorded after two quality failures
- **THEN** execution continues with attempt 3 and the prior findings in feedback

#### Scenario: Stale position
- **WHEN** the state references a stage no longer in the pipeline
- **THEN** the outcome is Escalated(PipelineMismatch), no execution or persistence port was invoked, and RunStarted and TaskFinished were emitted

### Requirement: Outcome and report model
`TaskOutcome` SHALL be Completed | Paused(passedStage) | Escalated(report) | Aborted(failedAt, cause), each carrying the final TaskState. Escalation reports SHALL be data-only values of five kinds: AttemptsExhausted, DecisionNeeded, CannotVerify, PipelineMismatch, CannotExecute (executor infrastructure failure — no attempt burned, no round recorded). Engine-internal errors SHALL propagate as exceptions, never as outcomes. An escalation SHALL be renderable from the outcome and its final state alone.
<!-- implements FR10, UX1 of add-stage-engine -->

#### Scenario: Executor infrastructure failure
- **WHEN** the executor port throws after its own retries
- **THEN** the outcome is Escalated(CannotExecute), `attemptsUsed` is unchanged, and no round is appended

#### Scenario: Report is self-describing
- **WHEN** an Escalated(AttemptsExhausted) outcome is rendered using only the outcome value and its final state
- **THEN** the stage, the limit, and every recorded round's check results and findings are available without any other source

### Requirement: Strict attempt persistence
The engine SHALL call `AttemptPersistence.persist(taskId, state, trace)` synchronously after every executed round — including rounds ending in CannotVerify or DecisionNeeded — before the AttemptFinished event and before any next attempt. A persistence failure SHALL end the run as Aborted with the in-memory final state, the failed round key, and the cause. An unpersisted round SHALL be safe to lose: a new run from the last persisted state re-executes it.
<!-- implements FR11, NFR-R4 of add-stage-engine -->

#### Scenario: Ordering invariant
- **WHEN** a round completes
- **THEN** the recorded call order is persist → AttemptFinished → next AttemptStarted

#### Scenario: Persistence failure aborts
- **WHEN** persist throws on round N
- **THEN** the outcome is Aborted with failedAt = round N and no further attempt starts

#### Scenario: Unpersisted round is safe to lose
- **WHEN** a run aborted on persisting round N is followed by a new run from the last persisted state
- **THEN** round N is re-executed and the task proceeds as if the aborted round never happened

### Requirement: Engine events
The engine SHALL emit sealed events — RunStarted, AttemptStarted, ExecutionFinished, CheckStarted, CheckFinished, AttemptFinished (new state + trace), TaskFinished — each self-contained with the (taskId, stage, attempt) key shared with logs and telemetry, delivered synchronously; every run, including pre-flight escalations, emits at least RunStarted and TaskFinished; listener exceptions SHALL be logged and swallowed. The stream SHALL suffice to reconstruct a status view (position, attempt, per-check results, aggregate metrics).
<!-- implements FR12, NFR-O2, UX2 of add-stage-engine -->

#### Scenario: Status reconstructed from events alone
- **WHEN** a recording listener observes a run with a retry
- **THEN** position, attempt counters, check verdicts, and metrics rebuilt from events match the final state

#### Scenario: Broken listener does not break the run
- **WHEN** the listener throws on every event
- **THEN** the run completes normally and each failure is logged

### Requirement: Two-level attempt telemetry
Every executed round SHALL be recorded in `TaskState.attempts` with an explicit result classification (Passed, QualityFailure, CannotVerify, DecisionNeeded) and optional metrics — wall time, per-tool aggregates (name, call count, total duration), input/output tokens (judge tokens per vote) — including rounds ending in CannotVerify, which are recorded but not counted. `TaskState` SHALL carry cumulative usage totals for the whole task, updated on every recorded round and preserved across stage advancement and resume. The raw chronological ToolTrace SHALL stay out of TaskState, correlated by the (taskId, stage, attempt) key. The history SHALL cover only the current stage, resetting on advancement.
<!-- implements FR13, FR14, NFR-C1 of add-stage-engine -->

#### Scenario: Unburned round is still recorded
- **WHEN** a round's verification ends in CannotVerify
- **THEN** the round appears in `attempts` with its token usage while `attemptsUsed` is unchanged

#### Scenario: History resets on advancement
- **WHEN** a stage passes and the pipeline advances
- **THEN** the new state's attempt history is empty and the previous rounds were persisted

#### Scenario: Round result is explicit
- **WHEN** a round ends in DecisionNeeded before any check runs
- **THEN** its record carries the DecisionNeeded result rather than leaving consumers to infer it from an empty check list

#### Scenario: Cumulative totals survive advancement
- **WHEN** a stage passes and the pipeline advances
- **THEN** the new state's attempt history is empty while the task totals still include the passed stage's usage

### Requirement: Reentrant engine without internal retries
The engine SHALL hold no static or shared mutable state — concurrent runs with independent ports must not interfere — and SHALL never retry a port call.
<!-- implements NFR-R1, NFR-R2 of add-stage-engine -->

#### Scenario: Concurrent runs stay isolated
- **WHEN** two runs execute concurrently with independent fake ports
- **THEN** each produces the same result as when run alone, with no cross-talk in events or state

### Requirement: Exceptional-path logging
Adapter exceptions, listener failures, and aborts SHALL log at ERROR/WARN via SLF4J at the point of capture, with stack traces also preserved in CannotVerify details or the Aborted cause. (Domain purity of the engine packages is already governed by the pipeline-config capability, whose ArchUnit rule covers all `domain` packages and does not forbid `org.slf4j`.)
<!-- implements NFR-O1 of add-stage-engine -->

#### Scenario: Stack trace reaches the report
- **WHEN** a check adapter throws
- **THEN** an ERROR line is logged and the escalation report's details contain the stack trace
