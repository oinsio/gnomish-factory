# stage-engine — delta for enforce-artifact-contracts

## ADDED Requirements

### Requirement: Artifact contract gates
The engine SHALL enforce declared artifact paths with two read-only gates. **Producer
gate**: after a stage's verify chain passes, and before any later stage executes, every
path-declaring output of the passed stage SHALL resolve to at least one existing file in
the task's working-copy state (a glob resolves when it matches at least one file).
**Consumer gate**: before the first round of a stage in any run — fresh entry or resume —
every `internal` input of that stage whose producer output declares a `path` SHALL resolve
the same way; inputs whose producer declares no path, and `source` inputs, are never gated.
The gates SHALL observe the working copy through an engine port whose adapters answer from
where the execution mode can genuinely see the round's files; the path-matching rule itself
SHALL have exactly one implementation. Both gates SHALL be idempotent and add no durable
step: a re-run over the same state yields the same verdict, and the consumer gate is the
convergence backstop that re-detects a miss after any crash or dirty resume. A gate failure
SHALL log at ERROR with the (taskId, stage) key and the same detail the escalation carries.
A pipeline whose declarations carry no paths SHALL behave observably exactly as before this
requirement existed.
<!-- implements FR3, FR4, FR7, NFR-R1, NFR-O1, NFR-P1, UX2 of enforce-artifact-contracts -->

#### Scenario: Producer gate passes on a matching glob
- **WHEN** a stage whose output declares `path: reports/**/*.xml` passes verification and at
  least one matching file exists in the working-copy state
- **THEN** the run proceeds to the next stage exactly as without the gate

#### Scenario: Producer gate catches a missing declared output
- **WHEN** a stage passes verification but a path-declaring output of that stage matches no
  file
- **THEN** the run escalates as `Escalated(CannotExecute)` before any later stage executes
- **AND** `attemptsUsed` is unchanged by the gate and the passed round's record is preserved

#### Scenario: Consumer gate catches a dirty resume
- **WHEN** a run resumes at stage N whose internal input references a producer output with a
  declared path, and no file matches that path in the working-copy state
- **THEN** the outcome is `Escalated(CannotExecute)` and no round of stage N is executed
- **AND** no stage attempt is burned and no executor port is invoked for stage N

#### Scenario: Path-less declarations are never gated
- **WHEN** a pipeline's inputs and outputs declare ids but no paths
- **THEN** neither gate performs any working-copy observation and the run's outcomes,
  events, and logs are identical to a run without this requirement

#### Scenario: Gate re-run converges identically
- **WHEN** a run escalated by the consumer gate is resumed without the missing artifact
  being restored
- **THEN** the new run's consumer gate escalates again with an equivalent cause, and a
  resume after the artifact is restored proceeds normally

#### Scenario: One listing per gate
- **WHEN** a gate evaluates a stage declaring several path-carrying artifacts
- **THEN** the working copy is enumerated once for the gate and all claims are matched
  against that single listing

## MODIFIED Requirements

### Requirement: Outcome and report model
`TaskOutcome` SHALL be Completed | Paused(passedStage) | Escalated(report) | Aborted(failedAt, cause), each carrying the final TaskState. Escalation reports SHALL be data-only values of five kinds: AttemptsExhausted, DecisionNeeded, CannotVerify, PipelineMismatch, CannotExecute. CannotExecute SHALL cover the factory-fault ways a stage cannot run or the pipeline cannot advance without any gnome fault: an executor infrastructure failure (no attempt burned, no round recorded) and a missing declared artifact caught by an artifact contract gate (no attempt burned; a producer-gate miss preserves the already-recorded passing round). A missing-artifact cause SHALL name the stage, the artifact `id`, the declared `path`, and which gate fired, and SHALL attribute the miss to the factory/pipeline (dirty resume, manifest error) — never to the gnome's work. Engine-internal errors SHALL propagate as exceptions, never as outcomes. An escalation SHALL be renderable from the outcome and its final state alone.
<!-- implements FR10, UX1 of add-stage-engine -->
<!-- implements FR5, NFR-O1, UX1 of enforce-artifact-contracts -->

#### Scenario: Executor infrastructure failure
- **WHEN** the executor port throws after its own retries
- **THEN** the outcome is Escalated(CannotExecute), `attemptsUsed` is unchanged, and no round is appended

#### Scenario: Report is self-describing
- **WHEN** an Escalated(AttemptsExhausted) outcome is rendered using only the outcome value and its final state
- **THEN** the stage, the limit, and every recorded round's check results and findings are available without any other source

#### Scenario: Missing artifact is a self-attributing factory fault
- **WHEN** an artifact contract gate escalates a missing declared artifact
- **THEN** the outcome is Escalated(CannotExecute) with a cause naming the stage, the
  artifact `id`, the declared `path`, and the gate that fired
- **AND** the cause states the pipeline/factory is broken and never phrases the miss as the
  gnome's failure, and no verify feedback is generated from it
