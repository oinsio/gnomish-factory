# Spec Delta: tracker-take

## MODIFIED Requirements

### Requirement: Escalation parks and exits
An escalation SHALL end the take run identically with or without a TTY: park the task with its report, then exit telling the operator where the question is and how to return the task ("reply in the tracker and move the task back to ready"). There is no in-run decision wait. A resume claim that finds a recorded `DecisionNeeded` outcome and no pending reply SHALL park the task again with the question restated. A resume claim that finds a `manual` checkpoint — the branch at `AwaitingApproval(stage)`, returned by the human — SHALL perform the approval write before any stage runs; one that finds a recorded outcome it continues without a reply (`AttemptsExhausted` returned bare, an infrastructure park returned after the fix) SHALL perform the resumed write before any stage runs. A claim that finds a gate whose park was never recorded SHALL deliver the park it is owed — record the outcome, park on the tracker — and exit without running a stage.
<!-- implements FR4, FR7, FR11 of make-checkpoint-gate-durable -->
<!-- implements FR13 of add-tracker-port -->

#### Scenario: Escalation ends the run
- **WHEN** a take run escalates while a TTY is attached
- **THEN** the task is parked with the report and the run exits with the return-path message — no console prompt is opened

#### Scenario: Returned without an answer
- **WHEN** a human moves a `DecisionNeeded`-parked task back to ready without replying and a take run claims it
- **THEN** the task is parked again with the question restated

#### Scenario: Returned checkpoint is approved before the run continues
- **WHEN** a human moves a checkpoint-parked task back to ready and a take run claims it
- **THEN** the branch gains the approval commit (position past the gate, outcome null) before the following stage's first round, and a kill right after the claim leaves the next pickup continuing from the approved position

#### Scenario: Bare return of an exhausted stage resets once
- **WHEN** a human returns an `AttemptsExhausted`-parked task without a reply and the run is killed after its first round
- **THEN** the tip shows `outcome: null` with the reset counter from the resumed commit, and the next claim continues at the recorded attempt instead of resetting again

#### Scenario: A gate killed before its park is delivered, not run
- **WHEN** a `manual` stage passed, the process died before the park was recorded, and a take run claims the task
- **THEN** the claim records the `paused` outcome, parks the task as a checkpoint, and exits; no stage ran

### Requirement: Any instance can pick up a returned task
Resume of a returned task SHALL require nothing instance-local: claim, decisions, abort facts, and reports live in the tracker; work artifacts, state, the gate and its approval live on the task branch. A different instance than the one that escalated or paused SHALL be able to claim, collect the decision or perform the approval, and continue from the recorded pipeline position.
<!-- implements FR4 of make-checkpoint-gate-durable -->
<!-- implements NFR-R3 of add-tracker-port -->

#### Scenario: Cross-instance resume
- **WHEN** instance A escalates a task and instance B runs `take <ref>` after a human reply and return to ready
- **THEN** B claims, collects the reply, acknowledges it, and resumes from the branch state without any data from A

#### Scenario: Cross-instance approval
- **WHEN** instance A parks a task at a checkpoint and instance B claims it after the human returned it
- **THEN** B performs the approval on the branch and continues the following stage without any data from A
