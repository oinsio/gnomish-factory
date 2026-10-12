# pipeline/pipeline-config

## ADDED Requirements

### Requirement: Stage manifest declares iteration
`stage.yaml` MAY carry an `iterate:` section: the checklist source (a
declared stage input artifact), `maxItems`, `maxDiscoveredItems`,
`attemptLimitPerItem` (all positive integers, defaulted when absent), and
`perItemChecks` naming which of the stage's verify checks run per item.
An invalid section SHALL be rejected at load time as a located config
error.
**Implements**: FR1, Q1 of add-stage-iteration

#### Scenario: Valid iterate section loads
- **WHEN** a stage declares iterate over a declared input with two
  `command` checks designated per item
- **THEN** the pipeline loads and the stage definition carries the
  iteration configuration

#### Scenario: Expensive check refused per item
- **WHEN** `perItemChecks` names a `judge` check
- **THEN** loading fails with a located ConfigError naming the check and
  the allowed types

#### Scenario: Undeclared source refused
- **WHEN** the iterate source names an artifact the stage does not consume
- **THEN** loading fails with a located ConfigError

#### Scenario: Unknown or external per-item check refused
- **WHEN** `perItemChecks` names a check the stage does not declare, or a
  check of type `external`
- **THEN** loading fails with a located ConfigError naming the check

#### Scenario: Unknown key or non-positive limit refused
- **WHEN** the iterate section carries an unknown key, or `maxItems`,
  `maxDiscoveredItems` or `attemptLimitPerItem` is zero or negative
- **THEN** loading fails with a located ConfigError naming the key
