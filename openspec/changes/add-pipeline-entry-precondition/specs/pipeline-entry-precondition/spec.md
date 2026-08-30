# pipeline-entry-precondition

## Purpose

Verify that the target project's baseline is green before the first agent round: an
optional, pipeline-declared command the factory runs in a fresh box at the task's
baseline commit, so a broken project escalates to its owner instead of burning gnome
attempts and mis-attributing the failure.

## ADDED Requirements

### Requirement: Entry precondition runs between task creation and the first round
For a pipeline that declares an entry precondition, the factory SHALL run it after
the task exists on the branch (task creation commit recorded) and before the first
attempt of the first stage — on fresh claims and on any resume that lands before the
first recorded round — in host and container modes alike. A pipeline with no
declaration SHALL skip the step silently: no probe, no environment, no log or
tracker traffic. A recorded green verdict whose key matches the current baseline SHA
and environment image identity SHALL also skip the probe.
<!-- implements FR2, NFR-C1, UX2 of add-pipeline-entry-precondition -->

#### Scenario: Probe runs before the first stage attempt
- **WHEN** a fresh claim creates a task for a pipeline declaring an entry precondition
- **THEN** the probe executes after the task-creation commit and before any stage
  executor call, and the first stage starts only on a green verdict

#### Scenario: Absent declaration is invisible
- **WHEN** a task runs on a pipeline with no entry-precondition declaration
- **THEN** no probe environment is materialized and the run is observably identical
  to the behavior before this capability existed

#### Scenario: Resume before the first round honors the step
- **WHEN** an instance resumes a task that was created but has no recorded round and
  no recorded verdict
- **THEN** the probe runs before the first stage attempt, exactly as on a fresh claim

### Requirement: Probe executes in a fresh box at the baseline commit and mutates nothing
The probe SHALL execute in a fresh execution environment materialized at the task's
recorded baseline commit — a factory-chosen pin — under the same binding, image, and
isolation as work stages: materialize, exec the declared command, read the exit code
and a bounded output tail, dispose WITHOUT harvest. Probe results SHALL be discarded
with the environment; the task branch and working state SHALL be unmodifiable by the
probe by construction. Re-running the probe SHALL therefore always be safe.
<!-- implements FR3, NFR-S1, NFR-R1 of add-pipeline-entry-precondition -->

#### Scenario: Probe box is disposed without harvest
- **WHEN** a probe completes with any exit code
- **THEN** its environment is disposed without any harvest, and no commit, file, or
  ref produced inside the probe box reaches the task branch or the factory clone

#### Scenario: Probe sees exactly the baseline tree
- **WHEN** the probe environment is materialized
- **THEN** its working copy matches the baseline commit's tree, unaffected by any
  later commit on the task branch

### Requirement: Red baseline escalates to the project owner without burning attempts
A probe exit code other than 0 — excluding the environment-failure codes below — and
a probe that exceeds its declared timeout SHALL classify as a red baseline: the task
SHALL escalate through the existing `CannotVerify` escalation kind with a cause
naming the baseline SHA (of the form "baseline red at <SHA>") and carrying the
bounded command output, then park via the existing escalation protocol for the
project owner. No stage attempt SHALL be burned and no round SHALL be recorded; the
report SHALL be distinguishable from gnome quality failures and from infrastructure
failures. The escalation kind set SHALL NOT be extended.
<!-- implements FR4, NFR-O1, UX1, G1 of add-pipeline-entry-precondition -->

#### Scenario: Red baseline parks the task with an owner-actionable report
- **WHEN** the probe command exits 1
- **THEN** the task escalates as `CannotVerify` with a cause naming the baseline SHA
  and the bounded output tail, `attemptsUsed` is 0, the attempt history is empty,
  and no stage executor was ever invoked

#### Scenario: Probe timeout is a red baseline
- **WHEN** the probe command is still running when its declared timeout elapses
- **THEN** the probe process tree is terminated and the task escalates as a red
  baseline naming the elapsed timeout, not as an infrastructure failure

### Requirement: Environment breakage during the probe stays an infrastructure failure
A failure to obtain a baseline verdict — materialize failure, container runtime
outage, probe command not executable or not found (exit 126/127), or an interrupted
probe — SHALL classify as an infrastructure failure through the existing channels:
retried per existing policy, never recorded as a baseline verdict, escalating as
cannot-execute if the outage persists. The probe command's ordinary exit code SHALL
speak only about the baseline, never about the environment.
<!-- implements FR5 of add-pipeline-entry-precondition -->

#### Scenario: Exit 127 is not a baseline verdict
- **WHEN** the probe exits 127 (command not found)
- **THEN** the failure classifies as infrastructure, no verdict is recorded, and the
  task does not park as a red baseline

#### Scenario: Runtime outage does not blame the baseline
- **WHEN** the container runtime is unreachable at probe materialization
- **THEN** the operation classifies as an infrastructure failure under the existing
  policy, and no red-baseline escalation is produced

### Requirement: Probe verdict is durable, keyed, and paid once
A completed probe's verdict SHALL be recorded durably in the task's state file,
keyed by the baseline SHA and the environment image identity, before the probe
environment is disposed and before the run proceeds (green) or parks (red). On any
later visit, a recorded green verdict with a matching key SHALL skip the probe; a
key mismatch (a changed image) SHALL re-run it and replace the verdict. Recovery
after a crash in the single kill window — probe executed, verdict not yet durable —
SHALL be to re-run the probe: idempotent and convergent, because the probe mutates
nothing. Running recovery on an already-recorded verdict SHALL change nothing.
<!-- implements FR6, NFR-R1, NFR-C1, G2, M2 of add-pipeline-entry-precondition -->

#### Scenario: Resume reuses the green verdict
- **WHEN** an instance resumes a task whose state file records a green verdict keyed
  to the current baseline SHA and image identity
- **THEN** no probe environment is materialized and the run proceeds directly to the
  recorded position

#### Scenario: Changed image re-runs the probe
- **WHEN** a resume finds a recorded verdict whose image identity differs from the
  currently bound one
- **THEN** the probe runs again and the state file records the new verdict under the
  new key

#### Scenario: Crash before the verdict commit converges by re-run
- **WHEN** an instance dies after the probe executed but before the verdict reached
  the state file, and any instance later resumes the task
- **THEN** the resume finds no verdict, re-runs the probe, and the outcome is
  identical to a run that never crashed; a second recovery pass is a no-op
