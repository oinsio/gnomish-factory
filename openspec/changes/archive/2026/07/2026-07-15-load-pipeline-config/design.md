# Design: load-pipeline-config

## Context

Driven by FR1–FR11 of the proposal. This is the first domain capability and the first change to introduce a `domain` ↔ `adapter` package split, so it also settles how that boundary is drawn and enforced. The stack is fixed by ADR 0001 (Java 25 records, Jackson+YAML, Spock, PIT 100%, ArchUnit arriving with the first ports/adapters change). The model to build is the machine-readable counterpart of the stage contract in `.claude/rules/stage-description.md`: `stage.yaml` encodes, per stage, the same eight IDEF0/ICOM + Quality Control sections that the stage doc describes in prose. This change parses and validates only — nothing is executed.

## Goals / Non-Goals

**Goals:** turn a `.gnomish/` directory into an immutable, validated `PipelineDefinition` or a complete, located list of `ConfigError`s; keep the domain provably pure so it stays under the 100% mutation gate; draw the domain/adapter boundary and cement it with ArchUnit.

**Non-Goals:** executing stages or checks; ai-provider/executor/tracker ports; the task state file or `Task` entity; git interaction (the loader takes a directory `Path`); a formal config-source port; a full `layeredArchitecture()` model.

## Decisions

**D1 — Package split: pure `domain.pipeline`, I/O in `adapter`.** The typed model and the pure semantic validators live in `com.github.oinsio.gnomish.domain.pipeline` — no filesystem, no Jackson, no network. Everything that touches the outside (reading files, YAML parsing, file-existence and path-traversal checks) lives in an adapter package (`adapter.pipeline`). *Rationale:* purity keeps the domain fully unit-testable and honest under PIT; the boundary is the one `process-invariants.md` demands. *Alternative rejected:* one flat package — would let Jackson/FS bleed into the model and make ArchUnit meaningless.

**D2 — Domain is Jackson-free; adapter maps DTOs → domain.** The domain model records carry **no** Jackson annotations (annotating them would pull Jackson into `domain` and break D1/ArchUnit). The adapter defines its own annotated DTO records, deserializes YAML into them (this catches most structural errors: missing required fields, type mismatches, unknown enums), then maps DTOs into the pure domain model. *Alternative rejected:* Jackson mixins to keep DTOs out — still couples the domain shape to a serializer's expectations; explicit DTOs are clearer and let the wire format evolve independently of the model.

**D3 — Loading returns a result, not exceptions (for validation).** The public entry point returns a sealed `LoadOutcome` — either `Loaded(PipelineDefinition)` or `Invalid(List<ConfigError>)`. Validation problems are aggregated data with a location (`file`, `where`, `message`); exceptions are reserved for genuine I/O faults (unreadable file, permission denied). *Rationale:* FR8/UX1 — the author fixes everything in one pass; Spock data tables assert error sets directly. *Alternative rejected:* throwing on the first problem — one-error-per-run is hostile to the human/gnome authoring `.gnomish/`.

**D4 — `pipeline.yaml` declares order; the DAG is a consistency check.** Order is the explicit linear sequence in `pipeline.yaml`. The artifact graph (outputs by `id`, internal inputs referencing an earlier stage's output `id`, `source` inputs with no producer) is validated *against* that order — an internal input must resolve to a strictly-earlier stage — never used to derive order. *Rationale:* the factory is a linear pipeline; an explicit list is readable and robust where inputs are partly external. *Alternative rejected:* topological sort of the DAG — hides order, brittle with `source` inputs, over-general for a linear pipeline.

**D5 — Sealed variants for the two axes of variation.** `VerifyCheck` is a sealed interface with four record variants (`Builtin`, `Command`, `External`, `Judge`); `ArtifactInput` is sealed over `Internal` (references a producer `id`) and `Source`. The adapter deserializes by an explicit `type`/kind discriminator into DTOs and maps to these variants. *Rationale:* exhaustive `switch` in validators and future engine; each variant carries only its own fields. Model all four check variants now so the schema is stable even though none execute yet.

**D5a — Mechanism model/settings are opaque and Jackson-free (FR11, proposal Q1).** `model` is a plain string, required and non-blank for every executor type — stages must be reproducible by any instance, so the model is pinned in the manifest and never left to a CLI default; `settings` is carried as an opaque `Map<String, Object>`. Crucially, to satisfy D1/ArchUnit the domain must not hold a Jackson `JsonNode` — the adapter maps the parsed YAML tree into plain JDK types (String/Number/Boolean/List/Map) so the domain stays Jackson-free. No key/value/range validation until the ai-provider port owns that knowledge.

**D6 — Validation split by purity, in three tiers.** *Structure* (schema shape, enum/`type` membership, required fields) is caught largely at DTO deserialization in the adapter. *Local sanity* — catalog-free semantic rules that need no live target — runs where its data lives: pure ones in the domain (stage-name uniqueness, non-empty/unique order, pipeline-wide output-`id` uniqueness, id-reference resolution and earlier-than ordering, schema version from `config.yaml`, `model` presence, attempt limit ≥ 1, `external` timing `interval ≤ timeout` with positivity, `judge` `votes ≥ 1` and odd); I/O-bound ones in the adapter (file existence for `instructions.md` and acceptance-criteria; path-traversal rejection) because they need the filesystem. *Target liveness* (CI-check existence, model reality, criteria gradeability) is deferred to later changes (NG7). All tiers feed the same `ConfigError` list. As far as possible each file is validated independently; only a file that will not parse at all short-circuits its own downstream semantic checks (documented in Risks).

**D7 — ArchUnit: minimal purity rules only.** Add ArchUnit (test scope) to `libs.versions.toml`; one Spock/JUnit-platform spec with rules: `domain` must not depend on `adapter`, `java.nio.file..`, or `com.fasterxml.jackson..`. *Rationale:* guards exactly the boundary this change introduces (NG6). The full `layeredArchitecture()` model waits for `application` and the real ports. ArchUnit rules are static declarations, not production code, so they do not affect the PIT `targetClasses`.

**D8 — Fixtures as real directories.** Tests use real `.gnomish/` fixture trees (test resources / temp dirs per `testing.md`): one golden valid tree asserted field-by-field (M1), and a battery of minimal invalid trees, one per validation rule, driven by Spock data tables asserting the exact located error set (M2). One deliberate domain→Jackson dependency proves the ArchUnit rule fails (M3).

## Risks / Trade-offs

- [Unparseable YAML masks semantic errors in that file] → validate each file independently; a hard parse failure short-circuits only its own file's semantic checks, other files still produce their errors, so aggregation degrades gracefully rather than collapsing to a single error.
- [Sealed-variant + Jackson mapping boilerplate] → keep DTOs thin and confined to the adapter; the mapper is plain code and fully unit-testable, keeping the domain clean.
- [100% mutation pressure on validators] → write validators as small pure functions returning `ConfigError`s; each rule gets its own data-driven spec so every branch is killed.
- [Path-traversal check correctness] → normalize (`Path.normalize()`) and assert the real path stays under the config root; cover symlink/`..` cases explicitly in tests (NFR-S2).
- [schemaVersion churn] → a single supported-version constant now; unknown versions are a clean validation error, giving a forward-compatible failure mode.

## Migration Plan

New code and new test dependency (ArchUnit) only; no existing code affected, no rollback concerns. After changing dependencies, `./gradlew check --write-locks` and commit the updated lockfiles (per the project's dependency-locking workflow).

## Open Questions

Both proposal open questions are resolved (see D5a, D6):
- Q1: `model` opaque, required for every executor type (reproducibility); `settings` an opaque plain-JDK `Map` (Jackson-free in the domain). No catalog validation in v1.
- Q2: `external`/`judge` validated at two tiers (structure + local sanity — timing, votes, criteria-file existence); target-liveness deferred (NG7).
