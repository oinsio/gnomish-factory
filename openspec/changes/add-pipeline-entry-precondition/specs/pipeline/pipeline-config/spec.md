# pipeline/pipeline-config — delta for add-pipeline-entry-precondition

## ADDED Requirements

### Requirement: Pipeline-level entry-precondition declaration
`pipeline.yaml` SHALL accept an optional pipeline-level `entry-precondition`
section declaring the command that verifies the baseline is green, with an
execution timeout. The declaration SHALL load into the immutable typed pipeline
model and SHALL be covered by whatever pins the pipeline definition to a task. An
absent section SHALL be valid and produce no warning. The loader SHALL NOT execute
the declared command.
**Implements**: FR1, NFR-C1, UX2 of add-pipeline-entry-precondition
**Notes**: Pipelines that must start red (fix-the-build, TDD-red) legitimately declare no section; the no-execution rule of the loader already covers the command.

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

#### Scenario: The declaration is covered by the named pipeline's content hash
- **WHEN** a multi-pipeline `.gnomish/` tree declares `entry-precondition` inside a named pipeline's block
- **THEN** the section belongs to that pipeline's definition, and changing it changes that pipeline's content hash

#### Scenario: Section errors aggregate with the other configuration errors
- **WHEN** an `entry-precondition` section has a blank command and a malformed timeout, and another part of the tree has an unrelated error
- **THEN** validation reports every one of them as a located `ConfigError` under the existing aggregation contract
