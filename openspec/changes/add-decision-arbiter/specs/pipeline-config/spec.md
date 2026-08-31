# pipeline-config

## ADDED Requirements

### Requirement: Stage manifest declares the arbiter
`stage.yaml` MAY carry a `decisions:` section: `model` (required),
`settings` (bounded by the same agent-cli settings vocabulary as
executors), `rulesFile` (required, resolved inside `.gnomish/` like every
control file), and `maxDecisions` (positive integer, defaulted when
absent). Validation SHALL reject a `rulesFile` resolving outside
`.gnomish/`, an unknown key, or a non-positive cap, as located config
errors at load time — no execution, consistent with loader safety rules.
<!-- implements FR1 of add-decision-arbiter -->

#### Scenario: Valid section loads
- **WHEN** a stage declares model, rulesFile inside `.gnomish/`, and
  maxDecisions 3
- **THEN** the pipeline loads and the stage definition carries the arbiter
  configuration

#### Scenario: Escaping rulesFile rejected
- **WHEN** rulesFile resolves outside `.gnomish/` (including via symlink)
- **THEN** loading fails with a located ConfigError

### Requirement: Decision rules join the law freeze
The pipeline law freeze SHALL include every configured decision-rules file,
read once at invocation start from the law source root, keyed like other
control files; the frozen content SHALL be the only rules content any
consult of the invocation sees.
<!-- implements FR2 of add-decision-arbiter -->

#### Scenario: Rules frozen at invocation start
- **WHEN** an invocation starts on a stage with an arbiter
- **THEN** the frozen law carries the rules content, and later working-copy
  edits are invisible to consults
