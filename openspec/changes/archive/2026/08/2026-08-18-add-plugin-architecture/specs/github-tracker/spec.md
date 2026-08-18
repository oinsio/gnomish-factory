## MODIFIED Requirements

### Requirement: tracker.github config subsection owned by the adapter
The adapter SHALL declare and validate its `tracker.github` subsection:
`api-url` (mandatory, no code default), `repo` (`owner/name`), and
`labels.{ready,working,needs-human,delivered}` as `{name, color}` objects with
hex color validation. In place of the inline `api-url`, the subsection MAY
reference a named operator-config connection profile as `connection: <name>`,
resolving the endpoint and the token's credential name from the profile (the
vendor-connection-profile capability). A referencing subsection MAY still
declare inline keys the referenced profile does not define — they overlay the
profile — but declaring an inline key the referenced profile also defines is
ambiguous and SHALL be a load error, one per overlapping key. Validation SHALL aggregate errors and fail fast at load,
consistent with pipeline-config error reporting. The token SHALL be resolved
through the `SecretsProvider` port by name — never from yaml and never read
from process env directly; the env/file adapter backs the name with
`GNOMISH_GITHUB_TOKEN`. The token SHALL never reach a task environment or
prompts; the adapter SHALL declare its credential name — through the SPI's
connection-aware credential declaration, resolving the profile-supplied name
when a `connection:` profile is referenced — so the variable can
never be admitted into a child-environment allowlist.
<!-- implements FR17, NFR-S1 of add-tracker-port -->
<!-- implements FR18, NFR-S1 of add-sandbox-core -->
<!-- implements FR16 of add-plugin-architecture -->
<!-- implements FR17 of add-plugin-architecture -->
<!-- implements UX3 of add-plugin-architecture -->

#### Scenario: Missing api-url is a load error
- **WHEN** `tracker.github` lacks `api-url` and declares no `connection:`
  profile reference
- **THEN** loading fails with a located error; no built-in default is applied

#### Scenario: Subsection resolves its connection from a named profile
- **WHEN** `tracker.github` declares `connection: <name>` and the operator
  config defines that profile
- **THEN** the adapter resolves the endpoint and the token's credential name
  from the profile; inline keys the profile does not define overlay it

#### Scenario: Overlapping inline key alongside a profile reference is a load error
- **WHEN** `tracker.github` declares `connection: <name>` and, inline, a key
  the referenced profile also defines
- **THEN** loading fails with a located error per overlapping key, naming both
  the reference and the ambiguous inline key

#### Scenario: Token stays out of the gnome
- **WHEN** a stage executes via the agent CLI while a tracker task is being worked
- **THEN** the task environment's allowlisted env contains no tracker credential

#### Scenario: Backend switch requires no adapter change
- **WHEN** the operator switches the configured `SecretsProvider` adapter
- **THEN** the tracker adapter resolves the same secret name with no code change
