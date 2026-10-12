# pipeline/stage-engine — delta for enforce-artifact-contracts

## ADDED Requirements

### Requirement: Artifact contract gates
The engine SHALL enforce declared artifact paths with two read-only gates. **Producer
gate**: after a stage's verify chain passes, and before any later stage executes, every
path-declaring output of the passed stage SHALL resolve to at least one existing file in
the task's working-copy state. **Consumer gate**: before the first round of a stage in any
run — fresh entry or resume — every `internal` input of that stage whose producer output
declares a `path` SHALL resolve the same way.
**Implements**: FR3, FR4, FR7, NFR-R1, NFR-O1, NFR-P1, UX2 of enforce-artifact-contracts
**Notes**: A glob resolves when it matches at least one file; the consumer gate is the convergence backstop that re-detects a miss after any crash or dirty resume.

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

#### Scenario: Source inputs are never gated
- **WHEN** a stage's inputs include a `source` input
- **THEN** the consumer gate makes no working-copy observation for it

#### Scenario: A gate writes nothing durable
- **WHEN** a gate evaluates a stage, whether it passes or escalates
- **THEN** the gate itself makes no commit, no state-file write and no tracker write, so a
  re-run over the same state yields the same verdict

#### Scenario: A gate failure is logged at ERROR
- **WHEN** a gate escalates a missing declared artifact
- **THEN** one ERROR line is logged under the (taskId, stage) key, carrying the same detail as
  the escalation

### Requirement: Artifact gates see the round's files through one port
The artifact contract gates SHALL observe the working copy through one engine port whose
adapters answer from where the execution mode can see the round's files, and the
path-matching rule SHALL have exactly one implementation, shared by every adapter.
**Implements**: FR3, FR4, FR7 of enforce-artifact-contracts; FR4 of tighten-spec-contract

#### Scenario: Both execution modes match with the same rule
- **WHEN** the same declared path is gated once in host mode and once in container mode over
  the same set of round files
- **THEN** both gates reach the same verdict, each listing the files where its mode's round
  wrote them and matching them with the one path-matching rule

## MODIFIED Requirements

Layered on `Outcome and report model` as modified by `tighten-spec-contract` (sequenced
before this change): that delta gives the block its `**Implements**:` line, its note and the
scenario "An engine-internal error is thrown, not returned"; this text adds the missing
declared artifact to CannotExecute and the scenarios "Missing artifact is a self-attributing
factory fault" and "A producer-gate miss keeps the passed round", and both must survive the
later sync.

### Requirement: Outcome and report model
`TaskOutcome` SHALL be Completed | Paused(passedStage) | Escalated(report) | Aborted(failedAt, cause), each carrying the final TaskState. Escalation reports SHALL be data-only values of five kinds: AttemptsExhausted, DecisionNeeded, CannotVerify, PipelineMismatch, CannotExecute (a factory fault, no attempt burned: an executor infrastructure failure with no round recorded, or a missing declared artifact caught by an artifact contract gate).
**Implements**: FR10, UX1 of add-stage-engine; FR1 of fix-denial-attribution-durability; FR5, NFR-O1, UX1 of enforce-artifact-contracts
**Notes**: CannotExecute carries the denials drained from its round's environment because that round left no attempt record to hold them.

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

#### Scenario: A round killed before its close reports its denials
- **WHEN** a round is killed on its round timeout after the gnome attempted a denied egress request
- **THEN** the outcome is Escalated(CannotExecute) carrying that denial, `attemptsUsed` is unchanged, and the attempt history is unchanged

#### Scenario: Denials of a failed round gate nothing
- **WHEN** a CannotExecute escalation carries denials
- **THEN** the stage's recorded verdicts and the feedback context of any later retry are identical to those of the same failure with no denials

#### Scenario: An engine-internal error is thrown, not returned
- **WHEN** an error inside the engine's own logic, not a port failure, occurs during a run
- **THEN** it propagates to the caller as an exception and no `TaskOutcome` is returned

#### Scenario: A producer-gate miss keeps the passed round
- **WHEN** the producer gate escalates a missing declared artifact of a stage whose verify chain passed
- **THEN** the outcome is Escalated(CannotExecute), `attemptsUsed` is unchanged, and the passing round already recorded for that stage stays in the attempt history
