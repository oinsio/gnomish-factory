# load-pipeline-config

## Why

The factory is a generic engine that runs a **declarative** pipeline defined in the target project repo under `.gnomish/`. Nothing can read that definition yet: there is no typed model of a pipeline or a stage, and no validation. Every downstream capability — the stage engine, verification, escalation, resume — depends on first turning `.gnomish/` into a trustworthy, validated in-memory model. This change builds that spine: load `.gnomish/` into a typed `PipelineDefinition` or report every problem with it, and nothing more. It is pure logic with no network and no execution, which makes it an ideal first domain slice under the project's 100% mutation gate.

## What Changes

- ADDED: a typed domain model of a pipeline configuration (`PipelineDefinition` → ordered `StageDefinition`s), mirroring the eight IDEF0/ICOM + Quality Control sections of the stage contract (`.claude/rules/stage-description.md`), including all four verify-check shapes (`builtin`, `command`, `external`, `judge`) as data — modeled, not executed
- ADDED: a `PipelineLoader` that reads a `.gnomish/` directory (`config.yaml`, `pipeline.yaml`, `stages/<name>/stage.yaml`) via Jackson/YAML and produces either a valid `PipelineDefinition` or the complete list of problems
- ADDED: two-layer validation — structural (schema, required fields, enums, types) and semantic (stage-name uniqueness, `pipeline.yaml` ↔ stage-directory consistency, artifact DAG connectivity, referenced-file existence)
- ADDED: artifact references by stable `id` (outputs declare an `id`; internal inputs reference a producing stage's output `id`; `source` inputs need no producer), with DAG consistency validated against the explicit stage order in `pipeline.yaml`
- ADDED: resolution of the stage attempt limit from the `config.yaml` default with per-stage overrides (token/money budgets are deferred — NG8)
- ADDED: the first `domain` ↔ `adapter` package boundary, guarded by **ArchUnit** rules wired into `./gradlew check` (domain stays pure — no filesystem, no Jackson)

## Capabilities

### New Capabilities
- `pipeline-config`: the typed model of a target project's `.gnomish/` pipeline definition, and the loading + validation that turns the on-disk configuration into that model or into a complete, located set of validation errors

### Modified Capabilities
_None — this is the first domain capability._

## Goals

- G1: given a valid `.gnomish/` directory, produce a `PipelineDefinition` whose contents match the on-disk configuration exactly (order, stages, checks, resolved autonomy limits)
- G2: given an invalid `.gnomish/`, return **all** validation problems in one pass, each naming its file and location — never fail on the first error
- G3: the `domain` package is provably pure — an ArchUnit test fails the build if it gains a dependency on the filesystem or Jackson

## Non-Goals

- NG1: no execution of anything — no stage runs, no verify check runs, no advancement; check configs are modeled as inert data
- NG2: no ports or calls to tracker / ai-provider / executor
- NG3: no task state file, no `Task` entity, no runtime attempt counters (autonomy limits are only parsed and resolved, not enforced)
- NG4: no git interaction — the loader takes a plain directory `Path`; how the task-branch working copy is produced is a later change
- NG5: no formal `PipelineConfigSource` port/interface — a single concrete `PipelineLoader`; the abstraction is extracted only when a second source genuinely appears (YAGNI)
- NG6: no full `layeredArchitecture()` ArchUnit model — only the domain-purity rules for the one boundary this change introduces
- NG7: no target-liveness validation — the loader does not check whether an `external` CI-check name exists, whether an executor `model` is a real model, or whether `judge` acceptance criteria are gradeable; those need the tracker/ai-provider ports and a live task branch and belong to later changes
- NG8: no budget limits — `AutonomyLimits` carries only the attempt limit; token/money budgets belong to the ai-provider/executor changes that can give them semantics (cost tracking, enforcement)

## Users & Scenarios

- U1: the factory engine (future change) asks the loader for a project's pipeline and receives either a validated model to execute or a hard failure it cannot proceed past
- U2: a human or gnome authoring `.gnomish/` for a target project runs the loader (directly or via a future validate command) and gets a complete, located list of what is wrong, fixable in one pass

## Requirements

### Functional
- FR1: the loader SHALL read a `.gnomish/` directory (`config.yaml`, `pipeline.yaml`, `stages/<name>/stage.yaml`) and build an immutable typed `PipelineDefinition`
- FR2: the `StageDefinition` model SHALL represent the eight stage-contract sections (purpose, input, output, control, mechanism/executor, verify/quality-control, failure/escalation limits, advancement) and SHALL model all four verify-check types (`builtin`, `command`, `external`, `judge`) as distinct typed variants
- FR3: `pipeline.yaml` SHALL be the source of truth for stage order as an explicit non-empty linear sequence of unique stage names; the artifact DAG SHALL be validated for consistency against that order, not used to derive it
- FR4: artifact references SHALL be by `id` — each output declares a stable `id`; an internal input references the `id` of an output produced by an **earlier** stage in the pipeline order; a `source` input needs no producer
- FR5: structural validation SHALL reject invalid schema, missing required fields, unknown enum values (e.g. executor type, verify-check type, advancement mode), and type mismatches
- FR6: semantic validation SHALL enforce: unique stage names; output artifact `id`s unique across the whole pipeline (internal inputs reference a bare `id`, so a duplicate would make the reference ambiguous); every `pipeline.yaml` stage has a `stages/<name>/stage.yaml`; no stage directory is absent from `pipeline.yaml` (no dangling definitions); every internal input is satisfied by an earlier stage's output; every referenced file (`instructions.md`, acceptance-criteria files for `judge` checks) exists
- FR7: the stage attempt limit SHALL be resolved from the `config.yaml` default with a per-stage override taking precedence; the resolved limit SHALL be an integer ≥ 1 (budgets are out of scope — NG8)
- FR8: the loader SHALL aggregate **all** validation problems and return a result that is either a valid `PipelineDefinition` or a non-empty list of located `ConfigError`s; validation failure SHALL NOT be signalled by exceptions (exceptions are reserved for I/O faults such as an unreadable file)
- FR9: a `schemaVersion` SHALL be declared in `config.yaml` — one version for the whole `.gnomish/` tree; a missing, unknown, or unsupported version SHALL be a validation error
- FR10: an ArchUnit rule SHALL fail the build when a `domain` class depends on the filesystem (`java.nio.file..`) or Jackson (`com.fasterxml.jackson..`), or when `domain` depends on `adapter`
- FR11: local (catalog-free) sanity validation SHALL apply to the mechanism and check configs: the executor `model` is required and non-blank for **every** executor type (`api` and `agent-cli`) — a stage must be reproducible by any instance, so the model is always pinned in the manifest rather than left to a CLI default — and `settings` is carried as an opaque pass-through map (validated as a well-formed mapping, not by key/value/range); an `external` check SHALL have a positive `interval`, a positive `timeout`, and `interval ≤ timeout`, and a non-blank check identifier; a `judge` check SHALL have `votes ≥ 1` and odd. Target-liveness validation (whether a CI check name exists, whether a model is real, whether judge criteria are gradeable) is explicitly deferred (NG7)

### Non-Functional
- NFR-R1 (reliability): loading SHALL be deterministic and read-only — the same `.gnomish/` directory always yields the same result, and the loader never writes to disk
- NFR-O1 (observability): every `ConfigError` SHALL name its location (file and field/stage) so the problem is fixable without guessing
- NFR-S1 (security): the loader SHALL NOT execute any command, model call, or external check defined in the configuration — this change only parses and validates
- NFR-S2 (security): the loader SHALL reject file references that resolve outside the `.gnomish/` directory root (no `../` path traversal escaping the configuration tree)
- NFR-C1 (cost): loading SHALL make no model or network calls — zero token cost

### Operator Experience Criteria
- UX1: one load call returns either the complete model or the complete problem list — the author fixes everything in a single pass, not one error per run
- UX2: each reported problem reads as `<file>: <what and where>` (e.g. `stages/build/stage.yaml: unknown executor 'foo'`)

## Success Metrics

- M1: a golden valid `.gnomish/` fixture loads into the expected `PipelineDefinition` (asserted field-by-field)
- M2: a data-driven battery of invalid fixtures each produces exactly the expected set of located errors (one Spock table per validation rule)
- M3: PIT mutation score on the `pipeline-config` domain code = 100%; the ArchUnit purity rule is proven to fail on a deliberate domain→Jackson dependency

## Open Questions

- Q1 (resolved): executor `model`/`settings` are carried opaque — `model` validated only for presence/non-blankness (required for every executor type), `settings` as a well-formed pass-through map, with no catalog/key/range validation until the ai-provider port exists. To keep the domain Jackson-free (FR10), the adapter maps the parsed YAML tree into plain JDK types (`Map<String, Object>` of String/Number/Boolean/List/Map), not a Jackson `JsonNode`. See FR11, NG7.
- Q2 (resolved): validation of `external`/`judge` is not "structural-only" but two-tier — structure plus local sanity (positive `interval`/`timeout` with `interval ≤ timeout`; `votes ≥ 1` and odd; non-blank `external` identifier; `judge` acceptance-criteria file exists per FR6) — while target-liveness (CI-check existence, model reality, criteria gradeability) is deferred (NG7). See FR11.

## Impact

- New code only: `com.github.oinsio.gnomish.domain.pipeline` (model + validation rules, pure) and a loader in the adapter layer (`java.nio.file` + Jackson/YAML)
- New test dependency: ArchUnit in `gradle/libs.versions.toml`; new Spock specs and `.gnomish/` fixture directories under `src/test`
- No existing code affected; establishes the `domain`/`adapter` package split referenced by `.claude/rules/process-invariants.md`
