# pipeline-config — delta for define-executor-contract

## ADDED Requirements

### Requirement: Named executor declarations
The law MAY declare an `executors:` section mapping a name to: `type` (`program` or a
built-in id), `program` (argv array, `program` type only), `settings`, `credentials` (variable
names), `egress` (hosts). A stage's `executor.name` SHALL reference a declared executor; an
unknown name SHALL be a located error. Declarations SHALL come from the law commit only.
<!-- implements FR9, NFR-S3 of define-executor-contract -->

#### Scenario: Unknown executor name is a located error
- **WHEN** a stage names `executor: {name: acme}` and no `executors.acme` exists
- **THEN** loading reports a located error naming the stage and `acme`

#### Scenario: Executor declared in operator config is ignored
- **WHEN** operator configuration carries an `executors` key
- **THEN** loading reports an unknown-key error; executors come from `.gnomish/` only

### Requirement: Settings validated against the executor's declared schema
At law load the loader SHALL obtain each declared executor's describe output and validate its
`settings` against the declared JSON Schema; each violation SHALL be a located error naming the
executor, the settings path and the schema rule. Describe failures SHALL be located errors, not
mid-stage failures.
<!-- implements FR9, FR4, UX1 of define-executor-contract -->

#### Scenario: Settings typo is caught at load
- **WHEN** `executors.acme.settings.maxTurn` is declared and the schema knows `maxTurns`
- **THEN** loading reports a located error at that path naming the unknown property

#### Scenario: Describe that cannot run is a load error
- **WHEN** a declared program is absent from the image or exits non-zero on describe
- **THEN** loading reports a located error naming the executor and the describe failure

### Requirement: Credentials and egress are checked against describe
A declared `credentials` or `egress` entry absent from the executor's describe SHALL be a
located error; a describe-declared credential the law does not grant SHALL be a located error
naming the missing grant, so no executor runs without its declared inputs.
<!-- implements FR4, NFR-S1 of define-executor-contract -->

#### Scenario: Missing credential grant
- **WHEN** describe declares `ACME_API_KEY` and `executors.acme.credentials` omits it
- **THEN** loading reports a located error naming the executor and the variable

## MODIFIED Requirements

### Requirement: Local sanity validation of mechanism and check configs
The loader SHALL apply catalog-free sanity rules that do not require a live target. The set of
accepted executor types SHALL be `api`, `agent-cli`, and `program`. The executor `model` SHALL
be present and non-blank for the agent executor types (`api`, `agent-cli`) — the model is pinned
in the stage manifest so any instance reproduces the stage identically, never left to an
executor default; for a `program` executor the model SHALL be optional and, when present,
carried to the executor as a setting. A `program` executor SHALL declare `executor.name`
referencing a declared executor. `settings` SHALL be carried as an opaque, well-formed mapping
(not validated by key, value, or range) except for `program` executors, whose settings are
validated against the declared schema. An `external` check SHALL have a positive `interval`, a
positive `timeout`, `interval ≤ timeout`, and a non-blank check identifier. An `external` check
SHALL also carry a `provider`, defaulting to `github` when absent; a `provider` absent from the
discovered check-provider registry SHALL be a located error naming the provider and the
discovered set — provider existence is in-process knowledge, not target liveness; the check's
provider-specific `params` SHALL be validated at the seam by that provider's
`CheckParamsValidator`, whose problems are aggregated as located `ConfigError` data like every
other validation problem. A `judge` check SHALL have `votes ≥ 1` and an odd `votes`, and its
`model` SHALL remain required and non-blank. The loader SHALL NOT validate target liveness —
whether a CI-check name exists, whether a `model` is real, or whether `judge` criteria are
gradeable.
<!-- implements FR11 of load-pipeline-config -->
<!-- implements FR6 of add-plugin-architecture -->
<!-- implements FR13 of add-plugin-architecture -->
<!-- implements UX1 of add-plugin-architecture -->
<!-- implements FR9, FR10 of define-executor-contract -->

#### Scenario: Missing model is rejected
- **WHEN** an `api` or `agent-cli` stage's `model` is absent or blank
- **THEN** validation reports a located error
- **AND** `settings` present as a mapping is accepted without inspecting its keys or values

#### Scenario: Program stage names a declared executor
- **WHEN** a stage declares `executor: {type: program, name: acme}` and `executors.acme` exists
- **THEN** validation passes and the typed model carries the executor reference

#### Scenario: Program stage without a name is rejected
- **WHEN** a `program` stage omits `executor.name`
- **THEN** validation reports a located error naming the stage and the missing field

#### Scenario: Program stage may omit the model
- **WHEN** a `program` stage declares no `model`
- **THEN** validation passes; a present `model` is carried to the executor as a setting

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
