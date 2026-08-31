# task-inspection

## MODIFIED Requirements

### Requirement: Status renders item progress for iterating stages
For a task whose current stage iterates, `status` (list and single-task
modes) SHALL render the item cursor as "item k/N" with the discovered
count when nonzero, from the branch tip's iteration state; the JSON
surface SHALL carry the same facts additively. A tip without iteration
state SHALL render exactly as before this change.
<!-- implements NFR-O1, UX1 of add-stage-iteration -->

#### Scenario: Mid-list task shows position
- **WHEN** status reads a task at item 14 of 30 with one adopted item
- **THEN** the row shows the stage plus "item 14/31 (+1 discovered)"

#### Scenario: Non-iterating task unchanged
- **WHEN** status reads a task on a non-iterating stage
- **THEN** the rendering is byte-identical to the pre-change contract
