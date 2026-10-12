# pipeline/stage-engine

## MODIFIED Requirements

Layered on `Gnome-initiated decision escalation` and `Human decisions as pass-through context`
as modified by `tighten-spec-contract` (sequenced before this change): all their scenarios are
kept — "Undecidable choice" narrowed to a stage without an arbiter, "Decision-carrying resume" to
the decisions in scope. This text adds the arbiter consult before the escalation, with
"Decided verdict continues the stage" and "cannot-decide escalates with analysis", and the scope
filter and superseded exclusion, with "Out-of-scope decision not injected" and "Superseded
decision excluded"; all must survive the later sync.

### Requirement: Gnome-initiated decision escalation
An executor SHALL be able to return DecisionNeeded instead of completing; the round is recorded
with its metrics and no attempt is burned. With an arbiter configured and budget remaining, the
engine SHALL consult it at its single NeedsDecision transition, record a decided verdict as a
decision and continue with the next round. Otherwise — no arbiter, cannot-decide, cap
exhausted — it SHALL escalate at once, with any consult history in the report.
**Implements**: FR6 of add-stage-engine; FR4, FR5, FR6 of add-decision-arbiter

#### Scenario: Undecidable choice
- **WHEN** the executor returns DecisionNeeded on a stage that configures no arbiter
- **THEN** the outcome is Escalated(DecisionNeeded) and `attemptsUsed` is unchanged

#### Scenario: Decided verdict continues the stage
- **WHEN** a decision round is followed by a decided consult
- **THEN** the decision is durable on the branch, the round stays
  unburned, and the next round's prompt carries the decision

#### Scenario: cannot-decide escalates with analysis
- **WHEN** the arbiter returns cannot-decide
- **THEN** the task escalates DecisionNeeded as today, and the report
  carries the arbiter's reason beside the gnome's question

### Requirement: Human decisions as pass-through context
`TaskContext.decisions` SHALL be a chronological list of decisions (optional stage/author/time)
passed verbatim to executor and judge requests, filtered to the records whose scope covers the
current work (task-scoped always; stage- or item-scoped only within their stage or item), with
superseded records excluded; the engine SHALL never interpret them. Resume adjustments (attempt
reset, position moves) are the caller's state manipulation.
**Implements**: FR7 of add-stage-engine; FR4 of harden-task-branch-contract; FR9 of add-decision-arbiter

#### Scenario: Decision-carrying resume
- **WHEN** a run starts with a decision appended and `attemptsUsed` reset by the caller
- **THEN** the executor request contains every decision in scope and execution proceeds from the recorded stage

#### Scenario: Decision and reset are inseparable
- **WHEN** a crash freezes state between a human decision being recorded and the next run starting
- **THEN** the recorded state carries the decision and the attempt-counter reset together, and the resumed run sees both or neither

#### Scenario: Out-of-scope decision not injected
- **WHEN** a stage-scoped decision from an earlier stage exists
- **THEN** later stages' prompts do not carry it

#### Scenario: Superseded decision excluded
- **WHEN** a decision record is referenced by a later record's supersedes
  field
- **THEN** only the superseding record is injected
