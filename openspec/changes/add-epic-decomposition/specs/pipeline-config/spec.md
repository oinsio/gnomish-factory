# pipeline-config — delta for add-epic-decomposition

## ADDED Requirements

### Requirement: Optional decompose section with validation
`stage.yaml` SHALL accept an optional `decompose:` section naming the plan
output artifact and the child-count limit. Validation SHALL reject: a
`decompose:` section whose plan artifact is not one of the stage's declared
outputs; more than one decomposition-capable stage in a pipeline; and a
child-count limit outside sane bounds. A pipeline with no `decompose:`
section anywhere SHALL load and behave exactly as today.
<!-- implements FR6 of add-epic-decomposition -->

#### Scenario: Decompose section loads
- **WHEN** one stage declares `decompose:` referencing its own plan output
- **THEN** the loaded definition marks that stage decomposition-capable with
  the configured limit

#### Scenario: Plan artifact must be an output
- **WHEN** `decompose:` references an artifact id the stage does not produce
- **THEN** validation reports a located error naming the artifact

#### Scenario: Two decomposition stages rejected
- **WHEN** two stages both declare `decompose:`
- **THEN** validation reports a located error naming both stages
