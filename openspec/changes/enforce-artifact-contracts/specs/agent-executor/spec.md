# agent-executor — delta for enforce-artifact-contracts

## ADDED Requirements

### Requirement: Input artifact paths in the briefing
The briefing's input-artifacts section SHALL render, for each `internal` input whose
producer output declares a `path`, that declared path beside the producer id, so the gnome
is told the real location of its input instead of a symbolic id alone. For inputs whose
producer declares no path, and for `source` inputs, the rendered output SHALL be
byte-identical to the rendering before this change.
<!-- implements FR6, M2, UX2 of enforce-artifact-contracts -->

#### Scenario: Declared path reaches the prompt
- **WHEN** a stage's briefing renders an internal input whose producer declares
  `path: docs/plan.md`
- **THEN** the input-artifacts section names both the producer id and `docs/plan.md`

#### Scenario: Path-less rendering is unchanged
- **WHEN** a stage's briefing renders internal inputs without declared producer paths and
  `source` inputs
- **THEN** the rendered input-artifacts section is byte-identical to the pre-change output
