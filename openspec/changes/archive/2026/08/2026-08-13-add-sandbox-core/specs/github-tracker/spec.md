# github-tracker (delta)

## MODIFIED Requirements

### Requirement: tracker.github config subsection owned by the adapter
The adapter SHALL declare and validate its `tracker.github` subsection: `api-url` (mandatory, no code default), `repo` (`owner/name`), and `labels.{ready,working,needs-human,delivered}` as `{name, color}` objects with hex color validation. Validation SHALL aggregate errors and fail fast at load, consistent with pipeline-config error reporting. The token SHALL be resolved through the `SecretsProvider` port by name — never from yaml and never read from process env directly; the env/file adapter backs the name with `GNOMISH_GITHUB_TOKEN`. The token SHALL never reach a task environment or prompts; the adapter SHALL declare its credential name so the variable can never be admitted into a child-environment allowlist.
<!-- implements FR17, NFR-S1 of add-tracker-port -->
<!-- implements FR18, NFR-S1 of add-sandbox-core -->

#### Scenario: Missing api-url is a load error
- **WHEN** `tracker.github` lacks `api-url`
- **THEN** loading fails with a located error; no built-in default is applied

#### Scenario: Token stays out of the gnome
- **WHEN** a stage executes via the agent CLI while a tracker task is being worked
- **THEN** the task environment's allowlisted env contains no tracker credential

#### Scenario: Backend switch requires no adapter change
- **WHEN** the operator switches the configured `SecretsProvider` adapter
- **THEN** the tracker adapter resolves the same secret name with no code change
