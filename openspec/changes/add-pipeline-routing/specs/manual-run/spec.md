# manual-run — delta for add-pipeline-routing

## ADDED Requirements

### Requirement: Run selects a pipeline explicitly or by default
`gnomish run` SHALL accept a `--pipeline <name>` flag selecting among the
loaded pipelines, defaulting to the routing default; an unknown name SHALL
fail fast listing the known pipelines. `--from-stage` SHALL resolve against
the selected pipeline, and its unknown-stage error SHALL list that
pipeline's stages. The synthesized task SHALL be pinned to the selected
pipeline exactly like a tracker task.
<!-- implements FR6 of add-pipeline-routing -->

#### Scenario: Explicit pipeline selected
- **WHEN** `gnomish run --pipeline spike` starts
- **THEN** the run executes the `spike` pipeline from its first stage and
  pins it on the task branch

#### Scenario: Unknown pipeline fails fast
- **WHEN** `--pipeline nightly` names no loaded pipeline
- **THEN** the run exits with an error listing the known pipeline names
  before any branch is created

#### Scenario: From-stage resolves within the selection
- **WHEN** `--pipeline spike --from-stage synthesize` is given and `spike`
  has no such stage
- **THEN** the error lists the stages of `spike`, not of other pipelines
