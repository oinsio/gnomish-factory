# pipeline-config — delta for add-pipeline-entry-precondition

## ADDED Requirements

### Requirement: Pipeline-level entry-precondition declaration
`pipeline.yaml` SHALL accept an optional pipeline-level `entry-precondition`
section declaring the command that verifies the baseline is green, with an
execution timeout. The declaration is part of the pipeline definition — it loads
into the immutable typed model and is covered by whatever pins the pipeline
definition to a task (coordinated with `add-pipeline-routing`: in the
multi-pipeline shape the section lives inside the named pipeline's block and is
covered by its content hash). An absent section SHALL be valid and produce no
warning — pipelines that must start red (fix-the-build, TDD-red) legitimately
declare none. Validation SHALL reject, as located `ConfigError`s under the
existing aggregation contract: a blank command, a non-positive or malformed
timeout, and unknown keys in the section. The loader SHALL NOT execute the
declared command — the existing no-execution rule covers it.
<!-- implements FR1, NFR-C1, UX2 of add-pipeline-entry-precondition -->

#### Scenario: Declaration loads into the typed model
- **WHEN** a pipeline declares `entry-precondition` with a command and a timeout
- **THEN** the `PipelineDefinition` exposes both, typed, and validation passes
  without executing the command

#### Scenario: Absent section is valid and silent
- **WHEN** a `.gnomish/` tree declares no `entry-precondition` section
- **THEN** loading succeeds exactly as before, the definition reports no entry
  precondition, and no warning is emitted

#### Scenario: Blank command is rejected
- **WHEN** an `entry-precondition` section declares an empty or blank command
- **THEN** validation reports a located error naming the section

#### Scenario: Invalid timeout is rejected
- **WHEN** an `entry-precondition` section declares a non-positive or malformed
  timeout
- **THEN** validation reports a located error identifying the field

#### Scenario: Unknown key in the section is rejected
- **WHEN** an `entry-precondition` section carries a key outside the declared
  schema
- **THEN** validation reports a located error naming the unknown key
