# pipeline-config — delta for add-command-executor

## MODIFIED Requirements

### Requirement: Local sanity validation of mechanism and check configs
The loader SHALL apply catalog-free sanity rules that do not require a live target. The
set of accepted executor types SHALL be `api`, `agent-cli`, and `command`. The executor
`model` SHALL be present and non-blank for the agent executor types (`api`,
`agent-cli`) — the model is pinned in the stage manifest so any instance reproduces the
stage identically, never left to an executor default — and SHALL be a located error
when present on a `command` executor, which has no model. A `command` executor SHALL
declare a non-blank `executor.command`; a missing or blank `command` SHALL be a located
error, and `executor.command` present on an agent executor type SHALL be a located
error. `settings` SHALL be carried as an opaque, well-formed mapping (not validated by
key, value, or range). An `external` check SHALL have a positive `interval`, a positive
`timeout`, `interval ≤ timeout`, and a non-blank check identifier. An `external` check
SHALL also carry a `provider`, defaulting to `github` when absent; a `provider` absent
from the discovered check-provider registry SHALL be a located error naming the
provider and the discovered set — provider existence is in-process knowledge, not
target liveness; the check's provider-specific `params` SHALL be validated at the seam
by that provider's `CheckParamsValidator`, whose problems are aggregated as located
`ConfigError` data like every other validation problem. A `judge` check SHALL have
`votes ≥ 1` and an odd `votes`, and its `model` SHALL remain required and non-blank. The
loader SHALL NOT validate target liveness — whether a CI-check name exists, whether a
`model` is real, or whether `judge` criteria are gradeable.
<!-- implements FR11 of load-pipeline-config -->
<!-- implements FR6 of add-plugin-architecture -->
<!-- implements FR13 of add-plugin-architecture -->
<!-- implements UX1 of add-plugin-architecture -->
<!-- implements FR1, FR2, UX2 of add-command-executor -->

#### Scenario: Missing model is rejected
- **WHEN** an `api` or `agent-cli` stage's `model` is absent or blank
- **THEN** validation reports a located error
- **AND** `settings` present as a mapping is accepted without inspecting its keys or values

#### Scenario: Model on a command stage is rejected
- **WHEN** a `command` stage declares an `executor.model`
- **THEN** validation reports a located error naming the stage and the forbidden field

#### Scenario: Command stage requires its command
- **WHEN** a `command` stage omits `executor.command` or declares it blank
- **THEN** validation reports a located error naming the stage and the missing field

#### Scenario: Well-formed command stage is accepted
- **WHEN** a stage declares `executor: {type: command, command: "./gradlew generateSources"}`
  and no `model`
- **THEN** validation passes and the typed model carries the command

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
