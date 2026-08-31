# pipeline-config — delta for enforce-artifact-contracts

## MODIFIED Requirements

### Requirement: Artifact references by identifier
Each stage output SHALL declare a stable `id`, unique across the whole pipeline, and MAY
additionally declare a `path` — a single file path or a glob, interpreted relative to the
task working copy root — that makes the artifact machine-verifiable at runtime. An output
without `path` is a fully supported declaration mode (documentation plus DAG lint): loading
and validation SHALL treat it exactly as before and SHALL emit no warning for it. An
internal input SHALL reference the `id` of an output produced by a stage that appears
**earlier** in the pipeline order; the input's runtime path, when one exists, is the
referenced producer's declared `path` — inputs declare no path of their own. A `source`
input SHALL declare that it has no producing stage and SHALL carry no path.
<!-- implements FR4 of load-pipeline-config -->
<!-- implements FR1 of enforce-artifact-contracts -->

#### Scenario: Internal input resolves to an earlier output
- **WHEN** a stage input references an output `id` produced by an earlier stage
- **THEN** validation accepts the reference and the model links input to producing stage

#### Scenario: Source input needs no producer
- **WHEN** a stage input is declared as `source`
- **THEN** validation accepts it without requiring a producing stage

#### Scenario: Dangling or forward reference is rejected
- **WHEN** an internal input references an `id` that no stage produces, or that is produced only by a later stage
- **THEN** validation reports a located error identifying the input and the missing/late producer

#### Scenario: Duplicate output id is rejected
- **WHEN** two stages (or one stage twice) declare outputs with the same `id`
- **THEN** validation reports a located error naming the duplicated `id` and both declaring stages

#### Scenario: Output with a path loads into the typed model
- **WHEN** a stage declares `outputs: [{ id: plan-doc, path: docs/plan.md }]`
- **THEN** the `PipelineDefinition` exposes the output with both its `id` and its `path`
- **AND** validation passes

#### Scenario: Output without a path stays documentation mode
- **WHEN** a stage declares an output with an `id` and no `path`
- **THEN** loading succeeds exactly as before this change, the typed model reports no path
  for the output, and no warning or error is emitted

## ADDED Requirements

### Requirement: Declared artifact paths are lexically validated
The loader SHALL validate every declared artifact output `path` lexically, without reading
any working copy (none exists at load time): the path MUST be relative (not absolute), in
normalized relative form (no `.` or `..` segments), and syntactically a valid glob. A
violation SHALL be a located `ConfigError` naming the stage manifest and the output `id`.
Artifact paths are working-copy-relative data, not `.gnomish/` file references: the
"No execution and no path traversal" rule confining file references to the `.gnomish/` root
SHALL NOT apply to them — pointing anywhere inside the working copy (e.g. `docs/plan.md`,
`reports/**/*.xml`) is their normal use — and the loader SHALL NOT check their existence.
<!-- implements FR2, NFR-S1 of enforce-artifact-contracts -->

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
