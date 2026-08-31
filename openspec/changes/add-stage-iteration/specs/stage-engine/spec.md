# stage-engine

## MODIFIED Requirements

### Requirement: The attempt loop hosts an item loop for iterating stages
For a stage with iteration configured, the engine SHALL drive rounds per
item under `attemptLimitPerItem`, record an item pass with a transition
that advances the cursor while holding the stage position and attempt
history, and record the stage's terminal pass only when the snapshot is
exhausted and stage-end verification passes. A resume SHALL never
interpret a per-item pass as stage completion, and per-item feedback
SHALL be scoped to the current item's prior rounds only.
<!-- implements FR5, FR6 of add-stage-iteration -->

#### Scenario: Item pass holds the stage position
- **WHEN** item k's per-item checks pass
- **THEN** the persisted state carries the advanced cursor, the unchanged
  stage position, and the full item history

#### Scenario: Resume after an item pass continues the list
- **WHEN** a pickup reads a state whose last round is an item pass
- **THEN** the engine continues with the next snapshot item, discarding
  nothing

#### Scenario: Feedback does not leak across items
- **WHEN** item B's round starts after item A recorded quality failures
- **THEN** item B's prompt feedback carries only item B's prior findings
