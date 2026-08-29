# pipeline-config

## ADDED Requirements

### Requirement: Stage manifest declares iteration
`stage.yaml` MAY carry an `iterate:` section: the checklist source (a
declared stage input artifact), `maxItems`, `maxDiscoveredItems`,
`attemptLimitPerItem` (all positive integers, defaulted when absent), and
`perItemChecks` naming which of the stage's verify checks run per item.
Validation SHALL reject at load time, as located config errors: a source
that is not a declared input of the stage, a `perItemChecks` entry naming
an unknown check or a check of type `external` or `judge`, an unknown
key, or a non-positive limit.
<!-- implements FR1 of add-stage-iteration -->

#### Scenario: Valid iterate section loads
- **WHEN** a stage declares iterate over a declared input with two
  `command` checks designated per item
- **THEN** the pipeline loads and the stage definition carries the
  iteration configuration

#### Scenario: Expensive check refused per item
- **WHEN** `perItemChecks` names a `judge` check
- **THEN** loading fails with a located ConfigError naming the check and
  the allowed types
<!-- implements Q1 resolution of add-stage-iteration -->

#### Scenario: Undeclared source refused
- **WHEN** the iterate source names an artifact the stage does not consume
- **THEN** loading fails with a located ConfigError
