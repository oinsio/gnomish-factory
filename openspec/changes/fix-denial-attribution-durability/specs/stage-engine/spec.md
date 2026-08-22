# stage-engine — delta

## MODIFIED Requirements

### Requirement: Outcome and report model
`TaskOutcome` SHALL be Completed | Paused(passedStage) | Escalated(report) | Aborted(failedAt, cause), each carrying the final TaskState. Escalation reports SHALL be data-only values of five kinds: AttemptsExhausted, DecisionNeeded, CannotVerify, PipelineMismatch, CannotExecute (executor infrastructure failure — no attempt burned, no round recorded). CannotExecute SHALL additionally carry the denials list drained from the environment of the round that could not execute — the round left no attempt record to hold them, and its blocked egress attempts SHALL still reach the report. Carrying them SHALL NOT change the classification: the outcome stays an infrastructure failure, no attempt is burned, no round is recorded, and no verdict is derived from the list. Engine-internal errors SHALL propagate as exceptions, never as outcomes. An escalation SHALL be renderable from the outcome and its final state alone.
<!-- implements FR10, UX1 of add-stage-engine -->
<!-- implements FR1 of fix-denial-attribution-durability -->

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
