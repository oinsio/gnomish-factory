# pipeline/pipeline-config — delta for define-executor-contract

## ADDED Requirements

### Requirement: Named executor declarations
The law MAY declare an `executors:` section mapping a name to: `type` (`program` or a
built-in id), `program` (argv array, `program` type only), `settings`, `credentials` (variable
names), `egress` (hosts). A stage's `executor.name` SHALL reference a declared executor; an
unknown name SHALL be a located error. Declarations SHALL come from the law commit only.
**Implements**: FR9, NFR-S3 of define-executor-contract

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
**Implements**: FR9, FR4, UX1 of define-executor-contract

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
**Implements**: FR4, NFR-S1 of define-executor-contract

#### Scenario: Missing credential grant
- **WHEN** describe declares `ACME_API_KEY` and `executors.acme.credentials` omits it
- **THEN** loading reports a located error naming the executor and the variable

## MODIFIED Requirements

Layered on `Local sanity validation of mechanism and check configs` as modified by
`tighten-spec-contract` (sequenced before this change): that delta cut the body and added the
scenarios "Agent settings are checked against the executor's schema", "Settings that are not a
mapping are rejected" and "External check without an identifier is rejected"; this text adds the
`program` type, the model rule's agent-type scope and the scenarios "Program stage names a
declared executor", "Program stage without a name is rejected" and "Program stage may omit the
model", and both must survive the later sync. `add-command-executor` is layered on this text.

### Requirement: Local sanity validation of mechanism and check configs
The loader SHALL apply catalog-free sanity rules, needing no live target, to the executor types `api`, `agent-cli` and `program`. The executor `model` SHALL be present and non-blank for the agent executor types (`api`, `agent-cli`), and `settings` SHALL be a well-formed mapping whose keys and values only the executor's schema checks. The loader SHALL NOT validate target liveness — whether a CI-check name exists, whether a `model` is real, or whether `judge` criteria are gradeable.
**Implements**: FR11 of load-pipeline-config; FR6 of add-plugin-architecture; FR13 of add-plugin-architecture; UX1 of add-plugin-architecture; FR9, FR10 of define-executor-contract
**Notes**: The model is pinned in the stage manifest so any instance reproduces the stage identically, never left to an executor default.
**Notes**: Provider existence is in-process knowledge (the discovered check-provider registry), not target liveness.

#### Scenario: Missing model is rejected
- **WHEN** an `api` or `agent-cli` stage's `model` is absent or blank
- **THEN** validation reports a located error
- **AND** the sanity rule accepts `settings` present as a mapping without inspecting its keys or values

#### Scenario: Agent settings are checked against the executor's schema
- **WHEN** an `agent-cli` stage or a `judge` check declares a `settings` key
  other than `allowedTools`, `disallowedTools`, `maxTurns` or `roundTimeout`,
  or a malformed value for one of them
- **THEN** the load fails with a located error naming the stage or check and
  the key

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

#### Scenario: Settings that are not a mapping are rejected
- **WHEN** a stage's `settings` is present but is not a mapping
- **THEN** validation reports a located error

#### Scenario: External check without an identifier is rejected
- **WHEN** an `external` check's check identifier is absent or blank
- **THEN** validation reports a located error identifying the check
