# pipeline/pipeline-config — delta for extract-agent-round-runner

## ADDED Requirements

### Requirement: A judge check declares the stage's executor block
A `judge` check SHALL declare who judges with the `executor:` block a stage declares, parsed and
validated by the same owner and the same rules. No judge SHALL be defaulted: a `judge` check
with no `executor:` block, an agent block with no `model`, or a block whose executor does not
support the `verdict` role SHALL be a located load error, and the factory SHALL NOT start.
**Implements**: FR13, FR14, UX1 of extract-agent-round-runner
**Notes**: The executor, the judge and the arbiter are one mechanism configured one way; they differ in instructions and role.

#### Scenario: A judge check with an agent block is accepted
- **WHEN** a `judge` check declares `criteriaFile` and `executor: {type: agent-cli, model: m, settings: {maxTurns: 60}}`
- **THEN** validation passes and the typed check carries the same executor type a stage's block maps to

#### Scenario: A judge check without an executor block is rejected
- **WHEN** a `judge` check declares no `executor:` block
- **THEN** the load fails with a located error naming the stage, the check and `executor`

#### Scenario: A judge block without a model is rejected
- **WHEN** a `judge` check's `agent-cli` block declares no `model`
- **THEN** the load fails with the located error a stage's block without a model gets

#### Scenario: A judge executor without the verdict role is rejected
- **WHEN** a `judge` check's block names a `program` whose describe lists no `verdict` role
- **THEN** the load fails with a located error naming the check, the executor and the role

#### Scenario: Removed judge keys are rejected
- **WHEN** a `judge` check declares `votes`, or `model` or `settings` beside its `executor:` block
- **THEN** the load fails with a located error naming the check and the unknown field

#### Scenario: An agent block resolves to the reserved executor name
- **WHEN** a stage or a `judge` check declares `type: agent-cli`
- **THEN** it resolves in the executor registry to the entry named `claude`
- **AND** a law that declares an executor named `claude` is refused at load as a built-in's name

## MODIFIED Requirements

### Requirement: Local sanity validation of mechanism and check configs
The loader SHALL apply catalog-free sanity rules, needing no live target, to the executor types `api`, `agent-cli`, `program` and `command`. The executor `model` SHALL be present and non-blank for the agent executor types (`api`, `agent-cli`), and `settings` SHALL be a well-formed mapping whose keys and values only the executor's schema checks. The loader SHALL NOT validate target liveness — whether a CI-check name exists, whether a `model` is real, or whether `judge` criteria are gradeable.
**Implements**: FR11 of load-pipeline-config; FR6 of add-plugin-architecture; FR13 of add-plugin-architecture; UX1 of add-plugin-architecture; FR9, FR10 of define-executor-contract; FR1, FR2, UX2 of add-command-executor; FR1, FR13 of migrate-verify-to-executor-contract; FR4, FR13 of extract-agent-round-runner
**Notes**: The model is pinned in the stage manifest so any instance reproduces the stage identically, never left to an executor default.
**Notes**: Executor existence is in-process knowledge (the executor registry: law declarations plus core's built-ins), not target liveness.
**Notes**: The scenario "Judge vote count must be positive and odd" keeps its title from before votes were removed; no count is accepted now.

#### Scenario: Missing model is rejected
- **WHEN** an `api` or `agent-cli` stage's `model` is absent or blank
- **THEN** validation reports a located error
- **AND** the sanity rule accepts `settings` present as a mapping without inspecting its keys or values

#### Scenario: Agent settings are checked against the executor's schema
- **WHEN** an `agent-cli` executor block, a stage's or a `judge` check's, declares a `settings` key
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
- **WHEN** a `judge` check declares `votes`, whatever its value
- **THEN** validation reports a located error identifying the check

#### Scenario: Target liveness is not validated
- **WHEN** an `external` check names a CI check, or a `judge`/executor declares a model, that does not exist in any live system
- **THEN** validation does not attempt to confirm its existence and does not fail on that ground

#### Scenario: Settings that are not a mapping are rejected
- **WHEN** a stage's `settings` is present but is not a mapping
- **THEN** validation reports a located error

#### Scenario: Settings key with no value is rejected
- **WHEN** a stage's executor `settings`, a `judge` check's executor `settings`, a check's `params` or an
  executor declaration's `settings` declares a key with no value
- **THEN** the load fails with a located error naming the file and `<where>.<key>`, before any
  stage runs

#### Scenario: External check without an identifier is rejected
- **WHEN** an `external` check's check identifier is absent or blank
- **THEN** validation reports a located error identifying the check
