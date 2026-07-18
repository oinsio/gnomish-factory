# Delta spec: pipeline-config

Money budgets are removed from the project (design D4: monetary cost is fictitious under subscription auth and derivable from tokens + model); the autonomy-limits wording drops its money mention.

## MODIFIED Requirements

### Requirement: Autonomy limit resolution
The loader SHALL resolve the stage attempt limit from the `config.yaml` default, with a per-stage override taking precedence. The resolved limit SHALL be an integer ≥ 1. Token budgets are out of scope for this capability version; monetary budgets are out of scope for the project.
<!-- implements FR16 of add-agent-executor -->

#### Scenario: Stage override wins over default
- **WHEN** `config.yaml` sets a default attempt limit and a stage overrides it
- **THEN** the resolved `StageDefinition` carries the stage's overriding value

#### Scenario: Default applies when no override
- **WHEN** a stage declares no override for a limit
- **THEN** the resolved `StageDefinition` carries the `config.yaml` default

#### Scenario: Non-positive attempt limit is rejected
- **WHEN** the resolved attempt limit (default or override) is less than 1
- **THEN** validation reports a located error
