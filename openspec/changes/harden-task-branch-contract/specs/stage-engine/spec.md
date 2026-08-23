# stage-engine — delta for harden-task-branch-contract

## MODIFIED Requirements

### Requirement: Resume from any valid state
The engine SHALL resume at attempt-boundary granularity from any valid recorded state — mid-pipeline, mid-retry, or post-pause. A position naming a stage absent from the pipeline SHALL escalate as PipelineMismatch before any execution or persistence port call, with observability events still emitted. A resume SHALL never re-execute a stage whose last recorded round at the recorded position carries a passing verdict: the engine fast-forwards past that stage — advancing per its advancement mode without invoking the executor or any check — so no recovery path re-invokes a paid executor or judge for work whose passing verdict is already recorded.
<!-- implements FR9 of add-stage-engine -->
<!-- implements FR9, NFR-C1 of harden-task-branch-contract -->

#### Scenario: Mid-retry resume
- **WHEN** a run starts from a state recorded after two quality failures
- **THEN** execution continues with attempt 3 and the prior findings in feedback

#### Scenario: Stale position
- **WHEN** the state references a stage no longer in the pipeline
- **THEN** the outcome is Escalated(PipelineMismatch), no execution or persistence port was invoked, and RunStarted and TaskFinished were emitted

#### Scenario: Recorded pass is never re-executed
- **WHEN** a run starts from a state whose last recorded round at the recorded position carries a passing verdict
- **THEN** the engine advances past that stage without invoking the executor or any check for it, and execution continues at the following stage

#### Scenario: Recorded pass on the final stage completes without re-running it
- **WHEN** the recorded position is the final stage and its last recorded round carries a passing verdict
- **THEN** the run reaches Completed without re-executing the stage or re-invoking any judge

### Requirement: Strict attempt persistence
The engine SHALL call `AttemptPersistence.persist(taskId, state, trace)` synchronously after every executed round — including rounds ending in CannotVerify or DecisionNeeded — before the AttemptFinished event and before any next attempt. A persistence failure SHALL end the run as Aborted with the in-memory final state, the failed round key, and the cause. An unpersisted round SHALL be safe to lose: a new run from the last persisted state re-executes it. A passing round SHALL become durable together with its consequence in one persist call: the state handed to persistence for a round that passed verification already carries the advanced pipeline position, so the pass and the advancement land as a single durable transition — never split across two persistence calls.
<!-- implements FR11, NFR-R4 of add-stage-engine -->
<!-- implements FR4 of harden-task-branch-contract -->

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

### Requirement: Human decisions as pass-through context
`TaskContext.decisions` SHALL be a chronological list of free-text decisions (optional stage/author/time) passed verbatim to executor and judge requests; the engine SHALL never interpret them. Resume adjustments (attempt reset, position moves) are the caller's state manipulation. The appended decision and the attempt-counter reset it implies SHALL become durable together as one transition: a resumed run never observes a state carrying the decision without the reset, or the reset without the decision.
<!-- implements FR7 of add-stage-engine -->
<!-- implements FR4 of harden-task-branch-contract -->

#### Scenario: Decision-carrying resume
- **WHEN** a run starts with a decision appended and `attemptsUsed` reset by the caller
- **THEN** the executor request contains all decisions and execution proceeds from the recorded stage

#### Scenario: Decision and reset are inseparable
- **WHEN** a crash freezes state between a human decision being recorded and the next run starting
- **THEN** the recorded state carries the decision and the attempt-counter reset together, and the resumed run sees both or neither
