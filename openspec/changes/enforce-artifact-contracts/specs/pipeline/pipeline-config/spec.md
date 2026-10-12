# pipeline/pipeline-config — delta for enforce-artifact-contracts

## MODIFIED Requirements

Layered on `Artifact references by identifier` as modified by `tighten-spec-contract` and then
by `add-pipeline-routing` (both sequenced before this change): tighten-spec-contract gives the
block its `**Implements**:` line, add-pipeline-routing scopes uniqueness, order and the dangling
check per pipeline and adds the scenario "Same id in disjoint pipelines is legal"; this text adds
the optional output `path`, the input's path taken from its producer, and the scenarios "Output
with a path loads into the typed model", "Output without a path stays documentation mode" and
"Input path comes from its producer", and all of it must survive the later sync.

### Requirement: Artifact references by identifier
Each stage output SHALL declare a stable `id`, unique across each pipeline that includes the
stage, and MAY declare a `path` (a file path or glob relative to the task working copy root).
An internal input SHALL reference the `id` of an output produced by a stage that appears
**earlier** in the same pipeline's order. A `source` input SHALL declare that it has no
producing stage and SHALL carry no path. A stage shared by several pipelines has its
references validated per pipeline.
**Implements**: FR4 of load-pipeline-config; FR5 of add-pipeline-routing; FR1 of enforce-artifact-contracts
**Notes**: A declared `path` makes the artifact machine-verifiable at runtime; an output without one is the documentation-plus-DAG-lint mode.

#### Scenario: Internal input resolves to an earlier output
- **WHEN** a stage input references an output `id` produced by an earlier
  stage of the same pipeline
- **THEN** validation accepts the reference and the model links input to
  producing stage

#### Scenario: Source input needs no producer
- **WHEN** a stage input is declared as `source`
- **THEN** validation accepts it without requiring a producing stage

#### Scenario: Dangling or forward reference is rejected
- **WHEN** an internal input references an `id` that no stage produces, or
  that is produced only by a later stage of that pipeline
- **THEN** validation reports a located error identifying the input and the
  missing/late producer

#### Scenario: Duplicate output id is rejected
- **WHEN** two stages of one pipeline (or one stage twice) declare outputs
  with the same `id`
- **THEN** validation reports a located error naming the duplicated `id`
  and both declaring stages

#### Scenario: Same id in disjoint pipelines is legal
- **WHEN** pipelines `feature` and `spike` (sharing no stage) each contain
  an output id `report`
- **THEN** validation accepts both

#### Scenario: Output with a path loads into the typed model
- **WHEN** a stage declares `outputs: [{ id: plan-doc, path: docs/plan.md }]`
- **THEN** the `PipelineDefinition` exposes the output with both its `id` and its `path`
- **AND** validation passes

#### Scenario: Output without a path stays documentation mode
- **WHEN** a stage declares an output with an `id` and no `path`
- **THEN** loading succeeds exactly as before this change, the typed model reports no path
  for the output, and no warning or error is emitted

#### Scenario: Input path comes from its producer
- **WHEN** an internal input references an output that declares `path: docs/plan.md`
- **THEN** the input's runtime path is `docs/plan.md`, taken from the producer, and the input
  declares no path of its own

## ADDED Requirements

### Requirement: Declared artifact paths are lexically validated
The loader SHALL validate every declared artifact output `path` lexically, without reading
any working copy: the path MUST be relative (not absolute), in normalized relative form (no
`.` or `..` segments), and syntactically a valid glob. A violation SHALL be a located
`ConfigError` naming the stage manifest and the output `id`.
**Implements**: FR2, NFR-S1 of enforce-artifact-contracts
**Notes**: No working copy exists at load time; artifact paths are working-copy-relative data, not `.gnomish/` file references, so pointing anywhere inside the working copy (`docs/plan.md`, `reports/**/*.xml`) is their normal use.

#### Scenario: Absolute or root-escaping path is rejected
- **WHEN** a stage output declares a `path` that is absolute or contains `.` or `..` segments
- **THEN** validation reports a located `ConfigError` naming the stage manifest and the
  output `id`, and the working copy is never consulted

#### Scenario: Malformed glob is rejected
- **WHEN** a stage output declares a `path` whose glob syntax is invalid
- **THEN** validation reports a located `ConfigError` identifying the output

#### Scenario: Path outside .gnomish/ is the normal case
- **WHEN** a stage output declares `path: docs/plan.md`
- **THEN** validation passes without reading any file — the `.gnomish/`-root confinement of
  file references does not apply and existence is not checked
