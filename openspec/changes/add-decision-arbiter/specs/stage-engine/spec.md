# stage-engine

## MODIFIED Requirements

### Requirement: Decision rounds consult the arbiter before escalating
When a round ends with a valid structured decision request and the stage
configures an arbiter with budget remaining, the engine SHALL consult the
arbiter at its single NeedsDecision transition; a decided verdict SHALL be
recorded as a decision and the attempt loop SHALL continue with the next
round instead of returning an escalation. cannot-decide, cap exhaustion,
or no arbiter SHALL escalate exactly as before this change, with any
consult history attached to the report.
<!-- implements FR4, FR5, FR6 of add-decision-arbiter -->

#### Scenario: Decided verdict continues the stage
- **WHEN** a decision round is followed by a decided consult
- **THEN** the decision is durable on the branch, the round stays
  unburned, and the next round's prompt carries the decision

#### Scenario: cannot-decide escalates with analysis
- **WHEN** the arbiter returns cannot-decide
- **THEN** the task escalates DecisionNeeded as today, and the report
  carries the arbiter's reason beside the gnome's question

### Requirement: Prompt injection of decisions is scope-filtered
The engine SHALL pass decision records to prompt builders filtered to the
records whose scope covers the current work (task-scoped always; stage- or
item-scoped only within their stage or item), still verbatim and
uninterpreted; superseded records SHALL be excluded.
<!-- implements FR9 of add-decision-arbiter -->

#### Scenario: Out-of-scope decision not injected
- **WHEN** a stage-scoped decision from an earlier stage exists
- **THEN** later stages' prompts do not carry it

#### Scenario: Superseded decision excluded
- **WHEN** a decision record is referenced by a later record's supersedes
  field
- **THEN** only the superseding record is injected
