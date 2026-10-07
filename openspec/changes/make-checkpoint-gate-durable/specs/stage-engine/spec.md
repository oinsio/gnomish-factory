# Spec Delta: stage-engine

## MODIFIED Requirements

### Requirement: Advancement modes
After verification passes, `auto` SHALL proceed to the next stage (Completed after the last); `manual` SHALL return Paused with the position **at the gate** — `AwaitingApproval(stage)`, naming the stage that passed — never past it. The passing round's own commit records that position (one durable transition, "One logical transition, one commit" of `git-task-persistence`), so no tip ever says "continue" before the approval that allows it has been recorded. A run starting from `AwaitingApproval(stage)` SHALL return Paused(stage) immediately, emitting RunStarted and TaskFinished and invoking no execution, verification or persistence port — the stop is reproduced from the record, not derived from the pipeline. The position moves past the gate only through the approval write of the task repository; a manual pause on the final stage therefore never writes the pipeline end — the approval does, and a run starting there returns Completed as before.
<!-- implements FR1, FR2, FR4 of make-checkpoint-gate-durable -->

#### Scenario: Manual checkpoint
- **WHEN** a stage with `manual` advancement passes verification
- **THEN** the outcome is Paused naming that stage, the recorded position is `AwaitingApproval(stage)`, and a subsequent run with the returned state returns Paused again without invoking any port

#### Scenario: Manual pause on the last stage
- **WHEN** the final stage has `manual` advancement and passes verification
- **THEN** the recorded position is `AwaitingApproval(stage)`, not the pipeline end; a run from that state returns Paused, and only a state the approval advanced to the pipeline end returns Completed

#### Scenario: Approval opens the gate once
- **WHEN** the approval write moves the position from `AwaitingApproval(stage)` to the following stage and a run starts from the approved state
- **THEN** the engine starts the following stage at round zero with an empty attempt history and never re-invokes the passed stage's executor or checks

### Requirement: Resume from any valid state
The engine SHALL resume at attempt-boundary granularity from any valid recorded state — mid-pipeline, mid-retry, at a gate, or post-approval. A position naming a stage absent from the pipeline SHALL escalate as PipelineMismatch before any execution or persistence port call, with observability events still emitted. A resume from the state a passing `auto` round persisted — the position already advanced past the passed stage, the round still in the recorded history — SHALL start the following stage with a fresh attempt history, never re-invoking the passed stage's executor or any of its checks. A resume from `AwaitingApproval(stage)` SHALL return Paused(stage) and run nothing (see "Advancement modes"). A resume from a state whose last recorded round carries a stop SHALL return the matching Escalated outcome and run nothing (see "The stop rides the round record").
<!-- implements FR2, FR6 of make-checkpoint-gate-durable -->
<!-- implements FR9 of add-stage-engine; FR4, NFR-C1 of harden-task-branch-contract -->

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

## ADDED Requirements

### Requirement: The stop rides the round record
A round whose result is a stop the engine will escalate SHALL carry that stop on its recorded attempt: a `DECISION_NEEDED` round carries the question and its options, a `CANNOT_VERIFY` round carries the check, the reason and the details — as untrusted-text carriers, persisted in the same round commit as the result. A run starting from a state whose last recorded round in the current stage carries a stop SHALL return the matching Escalated report built from the record, invoking no execution or verification port and burning no attempt — exactly as a spent attempt limit already re-escalates from the counter. The park commit that follows a stop is tracker delivery only: losing it loses nothing the next run needs. `AttemptsExhausted` (the counter) and `CannotExecute` (no round recorded; a retry is the right recovery) are unchanged.
<!-- implements FR5, FR6, NFR-C1 of make-checkpoint-gate-durable -->

#### Scenario: A lost park re-raises the question from the record
- **WHEN** a round ends in `DecisionNeeded`, its round commit lands, and the process dies before the park is recorded
- **THEN** the next run from that state returns Escalated(DecisionNeeded) with the same question and options, runs no executor and records no new round

#### Scenario: A lost park re-raises CannotVerify from the record
- **WHEN** a round ends in `CannotVerify`, its round commit lands, and the process dies before the park is recorded
- **THEN** the next run returns Escalated(CannotVerify) naming the same check, reason and details, with `attemptsUsed` unchanged

#### Scenario: A consumed stop is not re-raised
- **WHEN** the resumed write or a decision commit has cleared the outcome and reset the attempt history after a stop
- **THEN** the next run executes the stage from round zero and does not re-raise the recorded stop
