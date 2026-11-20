# pipeline/pipeline-config — delta for migrate-verify-to-executor-contract

## ADDED Requirements

### Requirement: Check tokens map to executor names
The loader SHALL map `type: builtin` to executor `files-exist`, `type: command` to `shell`, and
`type: external` to the executor named by `provider` (default `github-actions`), and SHALL
accept `executor: <name>` directly; the named executor SHALL declare role `verdict` or the
check is a located error.
**Implements**: FR1, UX2 of migrate-verify-to-executor-contract

#### Scenario: Default external provider maps
- **WHEN** an `external` check omits `provider`
- **THEN** the model names executor `github-actions`

#### Scenario: Explicit executor form
- **WHEN** a check declares `executor: sonarqube` and the law declares `executors.sonarqube`
- **THEN** validation passes and the check's settings validate against that executor's schema

### Requirement: SCM status checks declare expected contexts and a threshold
An `scm-status` check SHALL declare a non-empty `expected` list of context names; any verdict
check MAY declare `threshold` (`error` default, `warning`, `note`) used by the engine's verdict
derivation. A missing `expected` list on `scm-status` SHALL be a located error.
**Implements**: FR4, FR7 of migrate-verify-to-executor-contract

#### Scenario: Missing expected list
- **WHEN** an `scm-status` check declares no `expected`
- **THEN** validation reports a located error naming the check

### Requirement: Credentialed checks must run in a fresh box
A check whose executor describe declares credentials SHALL be a located error when it declares
`verify-in: same-box`; the default for such checks is `fresh-box`.
**Implements**: NFR-S1 of migrate-verify-to-executor-contract

#### Scenario: Same-box with a secret is refused
- **WHEN** a check names an executor declaring `SONAR_TOKEN` and `verify-in: same-box`
- **THEN** validation reports a located error naming the check and the credential

## MODIFIED Requirements

### Requirement: Local sanity validation of mechanism and check configs
The loader SHALL apply catalog-free sanity rules, needing no live target, to the executor types `api`, `agent-cli`, `program` and `command`. The executor `model` SHALL be present and non-blank for the agent executor types (`api`, `agent-cli`), and `settings` SHALL be a well-formed mapping whose keys and values only the executor's schema checks. The loader SHALL NOT validate target liveness — whether a CI-check name exists, whether a `model` is real, or whether `judge` criteria are gradeable.
**Implements**: FR11 of load-pipeline-config; FR6 of add-plugin-architecture; FR13 of add-plugin-architecture; UX1 of add-plugin-architecture; FR9, FR10 of define-executor-contract; FR1, FR2, UX2 of add-command-executor; FR1, FR13 of migrate-verify-to-executor-contract
**Notes**: The model is pinned in the stage manifest so any instance reproduces the stage identically, never left to an executor default.
**Notes**: Executor existence is in-process knowledge (the executor registry: law declarations plus core's built-ins), not target liveness.

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

#### Scenario: Model on a command stage is rejected
- **WHEN** a `command` stage declares an `executor.model`
- **THEN** validation reports a located error naming the stage and the forbidden field

#### Scenario: Command stage requires its command
- **WHEN** a `command` stage omits `executor.command` or declares it blank
- **THEN** validation reports a located error naming the stage and the missing field

#### Scenario: Command field on another executor type is rejected
- **WHEN** an `api`, `agent-cli` or `program` stage declares `executor.command`
- **THEN** validation reports a located error naming the stage and the forbidden field

#### Scenario: Well-formed command stage is accepted
- **WHEN** a stage declares `executor: {type: command, command: "./gradlew generateSources"}`
  and no `model`
- **THEN** validation passes and the typed model carries the command

#### Scenario: External check timing is sane
- **WHEN** an `external` check has a non-positive `interval` or `timeout`, or `interval > timeout`
- **THEN** validation reports a located error identifying the check
- **AND** a check with positive `interval`, positive `timeout`, and `interval ≤ timeout` is accepted

#### Scenario: Unknown provider is a located load error
- **WHEN** an `external` check declares a `provider` that names no executor of
  the registry (no built-in and no law declaration)
- **THEN** validation reports a located error naming the unknown executor and
  the registry's names, before any stage runs

#### Scenario: Defaulted github provider absent from the registry is a located error
- **WHEN** an `external` check omits `provider` and the law declares an executor
  that shadows or misdeclares `github-actions`
- **THEN** validation reports a located error naming `github-actions` as a
  built-in's name; the built-in is core's own and cannot be absent by removing
  the github vendor bundle, whose removal disables the tracker only

#### Scenario: External check provider defaults to github and validates its params
- **WHEN** an `external` check omits `provider` but declares provider-specific `params`
- **THEN** the loader records executor `github-actions` and validates the `params` against that built-in's describe-declared settings schema
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

#### Scenario: Settings key with no value is rejected
- **WHEN** a stage's executor `settings`, a `judge` check's `settings`, a check's `params` or an
  executor declaration's `settings` declares a key with no value
- **THEN** the load fails with a located error naming the file and `<where>.<key>`, before any
  stage runs

#### Scenario: External check without an identifier is rejected
- **WHEN** an `external` check's check identifier is absent or blank
- **THEN** validation reports a located error identifying the check

### Requirement: Stage model mirrors the stage contract and all verify-check types
Each `StageDefinition` SHALL represent the stage-contract sections (purpose, input, output, control, mechanism/executor, verify, failure/escalation limits, advancement) and SHALL model the verify checks as two typed variants: an executor check, which every `builtin`, `command`, `external` or `executor:` check maps to and which carries its executor name, its token, its `settings` and the engine-common fields, and the `judge` check.
**Implements**: FR2 of load-pipeline-config; FR7 of add-plugin-architecture; FR1 of migrate-verify-to-executor-contract

#### Scenario: All four check types are modeled distinctly
- **WHEN** a stage's `verify` list contains a `builtin`, a `command`, an `external`, and a `judge` check
- **THEN** the model exposes the first three as executor checks naming `files-exist`, `shell` and the `external` check's executor, each keeping the token it was declared with and its type-specific settings (e.g. `command` carries the executable), and the `judge` as its own variant carrying the acceptance-criteria path and vote count
- **AND** the ordered position of each check within the stage is preserved

#### Scenario: External check carries provider and opaque params
- **WHEN** an `external` check declares a `provider` and provider-specific `params`
- **THEN** the model carries the executor name mapped from `provider` and the `params` as the executor's settings alongside the engine-common `interval`, `timeout`, `timeoutClass`, and `pinPaths`
- **AND** no new verify-check sealed variant is added

#### Scenario: Unknown check type is rejected
- **WHEN** a `verify` entry declares a check `type` that is not one of the four known types
- **THEN** validation reports a located error naming the unknown type
