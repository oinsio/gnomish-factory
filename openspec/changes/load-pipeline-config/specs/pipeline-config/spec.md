# pipeline-config

## ADDED Requirements

### Requirement: Load .gnomish/ into a typed pipeline definition
The loader SHALL read a `.gnomish/` directory — `config.yaml`, `pipeline.yaml`, and `stages/<name>/stage.yaml` for each stage — and build an immutable, typed `PipelineDefinition`. Loading SHALL be deterministic and read-only.
<!-- implements FR1, NFR-R1 of load-pipeline-config -->

#### Scenario: Valid configuration loads
- **WHEN** the loader is given a `.gnomish/` directory whose files are structurally and semantically valid
- **THEN** it returns a `PipelineDefinition` whose stages, order, verify checks, and resolved autonomy limits match the on-disk configuration exactly
- **AND** the returned model is immutable

#### Scenario: Loading never writes to disk
- **WHEN** the loader processes any `.gnomish/` directory, valid or invalid
- **THEN** no file under the directory is created, modified, or deleted
- **AND** loading the same directory twice yields an equal result

### Requirement: Stage model mirrors the stage contract and all verify-check types
Each `StageDefinition` SHALL represent the stage-contract sections (purpose, input, output, control, mechanism/executor, verify, failure/escalation limits, advancement) and SHALL model the four verify-check types — `builtin`, `command`, `external`, `judge` — as distinct typed variants carrying their own configuration.
<!-- implements FR2 of load-pipeline-config -->

#### Scenario: All four check types are modeled distinctly
- **WHEN** a stage's `verify` list contains a `builtin`, a `command`, an `external`, and a `judge` check
- **THEN** the model exposes each as its own typed variant with its type-specific fields (e.g. `command` carries the executable, `judge` carries the acceptance-criteria path and vote count)
- **AND** the ordered position of each check within the stage is preserved

#### Scenario: Unknown check type is rejected
- **WHEN** a `verify` entry declares a check `type` that is not one of the four known types
- **THEN** validation reports a located error naming the unknown type

### Requirement: Stage order is declared, not derived
`pipeline.yaml` SHALL be the source of truth for stage order as an explicit, non-empty, linear sequence of unique stage names. The artifact dependency graph SHALL be validated for consistency against that order and SHALL NOT be used to derive it.
<!-- implements FR3 of load-pipeline-config -->

#### Scenario: Order comes from pipeline.yaml
- **WHEN** `pipeline.yaml` lists stages in a given order
- **THEN** the `PipelineDefinition` presents stages in exactly that order

#### Scenario: Empty or duplicate order is rejected
- **WHEN** `pipeline.yaml` has an empty stage list or repeats a stage name
- **THEN** validation reports a located error

### Requirement: Artifact references by identifier
Each stage output SHALL declare a stable `id`, unique across the whole pipeline. An internal input SHALL reference the `id` of an output produced by a stage that appears **earlier** in the pipeline order. A `source` input SHALL declare that it has no producing stage.
<!-- implements FR4 of load-pipeline-config -->

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

### Requirement: Structural validation
The loader SHALL reject invalid YAML schema, missing required fields, unknown enum values (executor type, verify-check type, advancement mode), and type mismatches.
<!-- implements FR5 of load-pipeline-config -->

#### Scenario: Missing required field is reported
- **WHEN** a `stage.yaml` omits a required field
- **THEN** validation reports a located error naming the file and the missing field

#### Scenario: Unknown enum value is reported
- **WHEN** a stage declares an executor type or advancement mode outside the allowed set
- **THEN** validation reports a located error naming the offending value

### Requirement: Semantic validation
The loader SHALL enforce cross-file rules: unique stage names; every `pipeline.yaml` stage has a matching `stages/<name>/stage.yaml`; every stage directory is referenced by `pipeline.yaml` (no dangling definitions); every internal input is satisfied by an earlier stage's output; and every referenced file (`instructions.md`, `judge` acceptance-criteria files) exists.
<!-- implements FR6 of load-pipeline-config -->

#### Scenario: Pipeline stage without a manifest is rejected
- **WHEN** `pipeline.yaml` names a stage that has no `stages/<name>/stage.yaml`
- **THEN** validation reports a located error

#### Scenario: Dangling stage directory is rejected
- **WHEN** a `stages/<name>/` directory exists but `pipeline.yaml` does not reference it
- **THEN** validation reports a located error naming the unreferenced stage

#### Scenario: Missing referenced file is rejected
- **WHEN** a stage's `instructions.md` or a `judge` check's acceptance-criteria file does not exist
- **THEN** validation reports a located error naming the missing file

### Requirement: Autonomy limit resolution
The loader SHALL resolve the stage attempt limit from the `config.yaml` default, with a per-stage override taking precedence. The resolved limit SHALL be an integer ≥ 1. Token/money budgets are out of scope for this capability version.
<!-- implements FR7 of load-pipeline-config -->

#### Scenario: Stage override wins over default
- **WHEN** `config.yaml` sets a default attempt limit and a stage overrides it
- **THEN** the resolved `StageDefinition` carries the stage's overriding value

#### Scenario: Default applies when no override
- **WHEN** a stage declares no override for a limit
- **THEN** the resolved `StageDefinition` carries the `config.yaml` default

#### Scenario: Non-positive attempt limit is rejected
- **WHEN** the resolved attempt limit (default or override) is less than 1
- **THEN** validation reports a located error

### Requirement: Aggregated, located validation results
The loader SHALL aggregate all validation problems and return a result that is either a valid `PipelineDefinition` or a non-empty list of `ConfigError`s, each naming its file and location. Validation failure SHALL NOT be signalled by exceptions; exceptions are reserved for I/O faults such as an unreadable file.
<!-- implements FR8, NFR-O1 of load-pipeline-config -->

#### Scenario: All problems reported in one pass
- **WHEN** a `.gnomish/` directory contains several independent validation problems
- **THEN** the returned error list contains all of them, not only the first
- **AND** each error names its file and the field or stage at fault

#### Scenario: Validation failure is data, not an exception
- **WHEN** the configuration is invalid
- **THEN** the loader returns an error result rather than throwing

### Requirement: Schema version recognition
A `schemaVersion` SHALL be declared in `config.yaml` — one version for the whole `.gnomish/` tree. A missing, unknown, or unsupported version SHALL be a validation error.
<!-- implements FR9 of load-pipeline-config -->

#### Scenario: Unsupported schema version is rejected
- **WHEN** `config.yaml` declares a `schemaVersion` the loader does not support
- **THEN** validation reports a located error naming the version

#### Scenario: Missing schema version is rejected
- **WHEN** `config.yaml` declares no `schemaVersion`
- **THEN** validation reports a located error naming `config.yaml`

### Requirement: Local sanity validation of mechanism and check configs
The loader SHALL apply catalog-free sanity rules that do not require a live target. The executor `model` SHALL be present and non-blank for every executor type — the model is pinned in the stage manifest so any instance reproduces the stage identically, never left to an executor default — and `settings` SHALL be carried as an opaque, well-formed mapping (not validated by key, value, or range). An `external` check SHALL have a positive `interval`, a positive `timeout`, `interval ≤ timeout`, and a non-blank check identifier. A `judge` check SHALL have `votes ≥ 1` and an odd `votes`. The loader SHALL NOT validate target liveness — whether a CI-check name exists, whether a `model` is real, or whether `judge` criteria are gradeable.
<!-- implements FR11 of load-pipeline-config -->

#### Scenario: Missing model is rejected
- **WHEN** a stage's `model` is absent or blank, whatever the executor type
- **THEN** validation reports a located error
- **AND** `settings` present as a mapping is accepted without inspecting its keys or values

#### Scenario: External check timing is sane
- **WHEN** an `external` check has a non-positive `interval` or `timeout`, or `interval > timeout`
- **THEN** validation reports a located error identifying the check
- **AND** a check with positive `interval`, positive `timeout`, and `interval ≤ timeout` is accepted

#### Scenario: Judge vote count must be positive and odd
- **WHEN** a `judge` check declares `votes` that is less than 1 or even
- **THEN** validation reports a located error identifying the check

#### Scenario: Target liveness is not validated
- **WHEN** an `external` check names a CI check, or a `judge`/executor declares a model, that does not exist in any live system
- **THEN** validation does not attempt to confirm its existence and does not fail on that ground

### Requirement: No execution and no path traversal
The loader SHALL NOT execute any command, model call, or external check defined in the configuration, and SHALL reject file references that resolve outside the `.gnomish/` directory root.
<!-- implements NFR-S1, NFR-S2, NFR-C1 of load-pipeline-config -->

#### Scenario: No configured action is executed
- **WHEN** the configuration defines `command`, `external`, and `judge` checks
- **THEN** loading parses and validates them as data without running any command, network request, or model call

#### Scenario: Path escaping the config root is rejected
- **WHEN** a file reference resolves outside the `.gnomish/` directory (e.g. via `../`)
- **THEN** validation reports a located error rather than reading the outside file

### Requirement: Domain purity guarded by ArchUnit
The `domain` package SHALL remain free of filesystem and Jackson dependencies and SHALL NOT depend on the `adapter` package; an ArchUnit rule wired into `./gradlew check` SHALL fail the build on violation.
<!-- implements FR10 of load-pipeline-config -->

#### Scenario: Domain dependency on I/O fails the build
- **WHEN** a `domain` class gains a dependency on `java.nio.file..`, `com.fasterxml.jackson..`, or the `adapter` package
- **THEN** the ArchUnit test fails `./gradlew check` naming the violating class
