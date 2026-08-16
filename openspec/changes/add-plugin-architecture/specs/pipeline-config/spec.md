## MODIFIED Requirements

### Requirement: Stage model mirrors the stage contract and all verify-check types
Each `StageDefinition` SHALL represent the stage-contract sections (purpose, input, output, control, mechanism/executor, verify, failure/escalation limits, advancement) and SHALL model the four verify-check types — `builtin`, `command`, `external`, `judge` — as distinct typed variants carrying their own configuration. The `external` variant SHALL additionally carry a `provider` discriminator and an opaque `params` map of flat JDK types (Jackson-free, like `builtin` params); its engine-common fields — `interval`, `timeout`, `timeoutClass`, `pinPaths` — remain modeled as before. No new sealed verify-check variant SHALL be introduced.
<!-- implements FR2 of load-pipeline-config -->
<!-- implements FR7 of add-plugin-architecture -->

#### Scenario: All four check types are modeled distinctly
- **WHEN** a stage's `verify` list contains a `builtin`, a `command`, an `external`, and a `judge` check
- **THEN** the model exposes each as its own typed variant with its type-specific fields (e.g. `command` carries the executable, `judge` carries the acceptance-criteria path and vote count)
- **AND** the ordered position of each check within the stage is preserved

#### Scenario: External check carries provider and opaque params
- **WHEN** an `external` check declares a `provider` and provider-specific `params`
- **THEN** the model carries the `provider` discriminator and the `params` as an opaque flat-typed map alongside the engine-common `interval`, `timeout`, `timeoutClass`, and `pinPaths`
- **AND** no new verify-check sealed variant is added

#### Scenario: Unknown check type is rejected
- **WHEN** a `verify` entry declares a check `type` that is not one of the four known types
- **THEN** validation reports a located error naming the unknown type

### Requirement: Local sanity validation of mechanism and check configs
The loader SHALL apply catalog-free sanity rules that do not require a live target. The executor `model` SHALL be present and non-blank for every executor type — the model is pinned in the stage manifest so any instance reproduces the stage identically, never left to an executor default — and `settings` SHALL be carried as an opaque, well-formed mapping (not validated by key, value, or range). An `external` check SHALL have a positive `interval`, a positive `timeout`, `interval ≤ timeout`, and a non-blank check identifier. An `external` check SHALL also carry a `provider`, defaulting to `github` when absent; a `provider` absent from the discovered check-provider registry SHALL be a located error naming the provider and the discovered set — provider existence is in-process knowledge, not target liveness; the check's provider-specific `params` SHALL be validated at the seam by that provider's `CheckParamsValidator`, whose problems are aggregated as located `ConfigError` data like every other validation problem. A `judge` check SHALL have `votes ≥ 1` and an odd `votes`. The loader SHALL NOT validate target liveness — whether a CI-check name exists, whether a `model` is real, or whether `judge` criteria are gradeable.
<!-- implements FR11 of load-pipeline-config -->
<!-- implements FR6 of add-plugin-architecture -->
<!-- implements FR13 of add-plugin-architecture -->
<!-- implements UX1 of add-plugin-architecture -->

#### Scenario: Missing model is rejected
- **WHEN** a stage's `model` is absent or blank, whatever the executor type
- **THEN** validation reports a located error
- **AND** `settings` present as a mapping is accepted without inspecting its keys or values

#### Scenario: External check timing is sane
- **WHEN** an `external` check has a non-positive `interval` or `timeout`, or `interval > timeout`
- **THEN** validation reports a located error identifying the check
- **AND** a check with positive `interval`, positive `timeout`, and `interval ≤ timeout` is accepted

#### Scenario: Unknown provider is a located load error
- **WHEN** an `external` check declares a `provider` that no discovered check
  provider serves
- **THEN** validation reports a located error naming the unknown provider and
  the discovered provider set, before any stage runs

#### Scenario: Defaulted github provider absent from the registry is a located error
- **WHEN** an `external` check omits `provider` and no discovered provider
  serves `github` (the github plugin jar is absent from the classpath)
- **THEN** validation reports a located error naming the defaulted `github`
  provider and the discovered set, exactly as for an explicitly named unknown
  provider — the factory itself still starts

#### Scenario: External check provider defaults to github and validates its params
- **WHEN** an `external` check omits `provider` but declares provider-specific `params`
- **THEN** the loader records `provider: github` and invokes the github `CheckParamsValidator` on the `params`
- **AND** a param problem is reported as a located `ConfigError` identifying the check

#### Scenario: Judge vote count must be positive and odd
- **WHEN** a `judge` check declares `votes` that is less than 1 or even
- **THEN** validation reports a located error identifying the check

#### Scenario: Target liveness is not validated
- **WHEN** an `external` check names a CI check, or a `judge`/executor declares a model, that does not exist in any live system
- **THEN** validation does not attempt to confirm its existence and does not fail on that ground
