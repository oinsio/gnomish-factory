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

#### Scenario: Structural errors are caught at law load
- **WHEN** `executors.acme` has an unknown `type`, or a `program` declaration has no argv
- **THEN** loading reports a located error naming the declaration and the field, and no process is spawned

### Requirement: Settings validated against the executor's declared schema
Before an executor's first round — once the task's execution environment is live and before the
engine runs — the factory SHALL obtain each declared executor's describe output and validate its
`settings` against the declared JSON Schema; each violation SHALL be a located error naming the
executor, the settings path and the schema rule, parking the task as an invalid law does.
**Implements**: FR9, FR4, FR13, UX1 of define-executor-contract

#### Scenario: Settings typo is caught before the first round
- **WHEN** `executors.acme.settings.maxTurn` is declared and the schema knows `maxTurns`
- **THEN** the describe preflight reports a located error at that path naming the unknown property, and no round runs

#### Scenario: Describe runs in the task's own environment
- **WHEN** a `program` stage's task is claimed in container mode
- **THEN** describe runs inside the task's box after it is live and before the engine starts, through the execution environment seam

### Requirement: A program that cannot be described parks its task
A declared program that is absent from the environment, cannot be started, exits non-zero on
describe, does not answer within the bound, or writes a malformed, oversized or missing answer
SHALL park the task as an invalid law does, with a located error naming the executor and the
failure; it SHALL never be a mid-stage failure, an operator precondition or a daemon condition.
**Implements**: FR4, FR13, UX1 of define-executor-contract
**Notes**: The law names the program, so the failure is the task's (ADR 0019, decision 5); an image lacking what the image contract guarantees stays the operator's.

#### Scenario: Describe that cannot run parks the task as a configuration error
- **WHEN** a declared program is absent from the image or exits non-zero on describe
- **THEN** the task parks as an invalid law does, with a located error naming the executor and the describe failure, no round runs, no attempt is burned and no daemon condition opens

#### Scenario: Describe that cannot start parks the task as an external failure
- **WHEN** the environment refuses to start the declared program, or describe does not exit within the bound
- **THEN** the task parks the same way, with a located error naming the executor and the failure as external, no round runs, no attempt is burned and no daemon condition opens

### Requirement: Credentials and egress are checked against describe
In the describe preflight, a declared `credentials` or `egress` entry absent from the executor's
describe SHALL be a located error; a describe-declared credential the law does not grant SHALL be
a located error naming the missing grant, so no executor runs without its declared inputs.
**Implements**: FR4, NFR-S1 of define-executor-contract

#### Scenario: Missing credential grant
- **WHEN** describe declares `ACME_API_KEY` and `executors.acme.credentials` omits it
- **THEN** the describe preflight reports a located error naming the executor and the variable, before the first round

## MODIFIED Requirements

Layered on `Local sanity validation of mechanism and check configs` as modified by
`tighten-spec-contract` (archived; its text is the stable text): that delta cut the body and added the
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
