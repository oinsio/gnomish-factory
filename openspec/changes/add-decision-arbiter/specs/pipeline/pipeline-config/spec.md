# pipeline/pipeline-config

## ADDED Requirements

### Requirement: Stage manifest declares the arbiter
`stage.yaml` MAY carry a `decisions:` section: `executor` (required, the
block a stage declares, parsed and validated by the same owner), `rulesFile`
(required, resolved inside `.gnomish/`) and `maxDecisions` (positive,
defaulted when absent). Validation SHALL reject a missing or invalid
`executor` block, a `rulesFile` resolving outside `.gnomish/`, an unknown
key or a non-positive cap as located config errors at load time.
**Implements**: FR1 of add-decision-arbiter

#### Scenario: Valid section loads
- **WHEN** a stage declares an `executor` block with a model, rulesFile
  inside `.gnomish/`, and maxDecisions 3
- **THEN** the pipeline loads and the stage definition carries the arbiter
  configuration

#### Scenario: Arbiter without an executor block is rejected
- **WHEN** a `decisions:` section declares no `executor` block, or an agent
  block with no model, or `model` or `settings` beside the block
- **THEN** loading fails with the located ConfigError a stage's block gets

#### Scenario: Escaping rulesFile rejected
- **WHEN** rulesFile resolves outside `.gnomish/` (including via symlink)
- **THEN** loading fails with a located ConfigError

### Requirement: Decision rules join the law freeze
The pipeline law freeze SHALL include every configured decision-rules file,
read once at invocation start from the law source root, keyed like other
control files; the frozen content SHALL be the only rules content any
consult of the invocation sees.
**Implements**: FR2 of add-decision-arbiter

#### Scenario: Rules frozen at invocation start
- **WHEN** an invocation starts on a stage with an arbiter
- **THEN** the frozen law carries the rules content, and later working-copy
  edits are invisible to consults
