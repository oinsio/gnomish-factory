# Tasks: load-pipeline-config

TDD throughout (Red-Green-Refactor per `.claude/rules/testing.md`): write the failing Spock spec first, then the code. Every production unit references its FR in a doc comment (`.claude/rules/traceability.md`). The domain stays pure — no filesystem, no Jackson (D1/D7).

## 1. Build setup and package skeleton

- [ ] 1.1 Add ArchUnit (test scope) to `gradle/libs.versions.toml`; wire it into the test source set
- [ ] 1.2 Add Jackson to `gradle/libs.versions.toml` — the catalog currently has no Jackson entries: declare `jackson-databind` and `jackson-dataformat-yaml` (versions managed by the Spring Boot BOM); confirm via dependency-analysis they are declared where used
- [ ] 1.3 Create empty packages `com.github.oinsio.gnomish.domain.pipeline` and `com.github.oinsio.gnomish.adapter.pipeline` with `package-info.java`
- [ ] 1.4 Run `./gradlew check --write-locks`; confirm green baseline and commit-ready lockfiles

## 2. ArchUnit purity guard (write the guard before the code it guards)

- [ ] 2.1 Spec (Red): ArchUnit rule — `domain` must not depend on `adapter`, `java.nio.file..`, or `com.fasterxml.jackson..` (FR10). Prove it fails against a deliberate throwaway domain→Jackson reference, then remove the reference (M3)
- [ ] 2.2 Green: rule passes on the empty domain package; keep it in `./gradlew check`

## 3. Domain model (pure records)

- [ ] 3.1 Spec + impl: `ConfigError` (file, where, message) and sealed `LoadOutcome` = `Loaded(PipelineDefinition)` | `Invalid(List<ConfigError>)` (FR8)
- [ ] 3.2 Spec + impl: `AutonomyLimits` (attempt limit only — budgets deferred per NG8) with default+override resolution semantics (FR7)
- [ ] 3.3 Spec + impl: sealed `VerifyCheck` with variants `Builtin`, `Command`, `External`, `Judge`, order-preserving, each carrying only its own fields (FR2)
- [ ] 3.4 Spec + impl: `ArtifactOutput` (stable `id`) and sealed `ArtifactInput` = `Internal(producerOutputId)` | `Source` (FR4)
- [ ] 3.5 Spec + impl: immutable `StageDefinition` (name, purpose, inputs, outputs, executor+opaque model/settings map, control/instructions ref, verify list, resolved limits, advancement mode) (FR2, Q1)
- [ ] 3.6 Spec + impl: immutable `PipelineDefinition` (schemaVersion, resolved autonomy, ordered stages) (FR1)

## 4. Domain validators (pure, semantic)

- [ ] 4.1 Spec + impl: schema-version check — `schemaVersion` in `config.yaml` missing, unknown, or unsupported → located error (FR9)
- [ ] 4.2 Spec + impl: order validation — non-empty, unique stage names in `pipeline.yaml` order (FR3)
- [ ] 4.3 Spec + impl: artifact DAG validation — output `id`s unique across the pipeline; every `Internal` input resolves to a strictly-earlier stage's output `id`; `Source` inputs accepted; duplicate ids and dangling/forward refs → located error (FR3, FR4, FR6)
- [ ] 4.4 Spec + impl: local-sanity rules — `model` present and non-blank for every executor type; `external` timing (positive `interval`/`timeout`, `interval ≤ timeout`); `judge` `votes ≥ 1` and odd (FR11); resolved attempt limit ≥ 1 (FR7). No target-liveness checks (NG7)
- [ ] 4.5 Spec + impl: a `validate(model)` aggregator that runs all pure rules and returns the full `ConfigError` list (FR8)

## 5. Adapter — parsing and structural validation

- [ ] 5.1 Spec + impl: annotated DTO records for `config.yaml`, `pipeline.yaml`, `stage.yaml` and the verify-check/input variants, deserialized from YAML via Jackson (D2)
- [ ] 5.2 Spec + impl: structural error capture during deserialization — missing required fields, type mismatches, unknown enum/`type` values → located `ConfigError` (FR5)
- [ ] 5.3 Spec + impl: DTO → domain mapper, including default+override resolution of the attempt limit (FR7, D2); map executor `settings` from the parsed YAML tree into plain JDK types (`Map<String, Object>`) so the domain stays Jackson-free (FR11, D5a)

## 6. Adapter — I/O checks and the loader entry point

- [ ] 6.1 Spec + impl: filesystem discovery — read `config.yaml`, `pipeline.yaml`, and each `stages/<name>/stage.yaml`; unreadable file → I/O exception, not a `ConfigError` (FR1, FR8)
- [ ] 6.2 Spec + impl: `pipeline.yaml` ↔ stage-directory consistency — pipeline stage without a manifest, and dangling stage directory → located errors (FR6)
- [ ] 6.3 Spec + impl: referenced-file existence — `instructions.md` and `judge` acceptance-criteria files must exist (FR6)
- [ ] 6.4 Spec + impl: path-traversal rejection — normalize and reject references resolving outside the `.gnomish/` root (NFR-S2)
- [ ] 6.5 Spec + impl: `PipelineLoader.load(Path)` orchestration — read → parse (structural) → map → domain validate → I/O validate → aggregate all into one `LoadOutcome`; assert nothing is executed (NFR-S1) and nothing is written (NFR-R1)

## 7. Fixtures and acceptance

- [ ] 7.1 Golden valid `.gnomish/` fixture tree exercising all four verify-check types and both input kinds; assert the loaded `PipelineDefinition` field-by-field (M1)
- [ ] 7.2 Data-driven invalid-fixture battery — one minimal tree per validation rule; assert the exact located error set (M2, UX1/UX2)
- [ ] 7.3 Run `./gradlew check`; confirm 100% PIT mutation on `domain.pipeline`, ArchUnit green, coverage/dependency gates green (M3)
- [ ] 7.4 Update `README.md` if the `.gnomish/` structure section needs the concrete field names now fixed; recommend a commit message based on the diff (the human commits — `.claude/rules/process-invariants.md`)
