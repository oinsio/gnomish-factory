# stage-engine — delta for add-epic-decomposition

## ADDED Requirements

### Requirement: Decomposed terminal outcome from a decomposition-capable stage
A stage declared decomposition-capable SHALL, after its verification passes,
end the engine run with a `Decomposed` terminal outcome when the validated
plan artifact declares an epic, and SHALL advance normally when the plan
declares a single task. Stages without the declaration SHALL be unaffected. A
plan that fails validation SHALL be a quality failure of the attempt
(feedback and retry within the attempt limit), never a terminal outcome.
<!-- implements FR1 of add-epic-decomposition -->

#### Scenario: Epic plan ends the run as Decomposed
- **WHEN** the decomposition-capable stage passes verification and the plan
  declares an epic
- **THEN** the run ends with the `Decomposed` outcome carrying the final
  state, and no later stage executes

#### Scenario: Single-task plan advances
- **WHEN** the plan declares a single task
- **THEN** the engine advances to the next stage exactly as today

#### Scenario: Invalid plan burns an attempt
- **WHEN** the plan fails validation
- **THEN** the attempt records a quality failure with the validation
  findings as feedback, and the stage retries within its attempt limit
