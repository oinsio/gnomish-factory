## MODIFIED Requirements

### Requirement: Adapter is constructed from factory configuration
The factory SHALL construct `GithubCheckExternalClient` from factory
config — the platform base URL under a dedicated config key and a token
resolved by name through `SecretsProvider` — and inject it into the stage
engine wrapped by the pin-check guard, so an operator enables the GitHub
Actions adapter with configuration alone, no code changes. In place of the
inline base URL and token name, the check subsection MAY reference a named
operator-config connection profile as `connection: <name>` — the same profile
the github tracker may reference, so one vendor keeps a single
connection/credentials block (the vendor-connection-profile capability). A
referencing subsection MAY still declare inline keys the referenced profile
does not define — they overlay the profile — but declaring an inline key the
referenced profile also defines is ambiguous and SHALL be a load error, one
per overlapping key. The env/file
adapter backs the token name with `GNOMISH_GITHUB_ACTIONS_TOKEN`,
replacing the provisional direct env read; the adapter SHALL declare
that name — through the check SPI's connection-aware credential
declaration, resolving the profile-supplied name when a `connection:`
profile is referenced — so it can never be admitted into a
child-environment allowlist, matching the tracker token's treatment.
<!-- implements FR26 of add-sandbox-core -->
<!-- implements FR16 of add-plugin-architecture -->
<!-- implements FR17 of add-plugin-architecture -->
<!-- implements UX3 of add-plugin-architecture -->

#### Scenario: Operator enables the adapter with config alone
- **WHEN** factory config declares the external-check base URL and the
  token secret resolves
- **THEN** stages declaring external checks poll GitHub Actions through
  the constructed adapter, behind the pin-check guard

#### Scenario: Adapter is constructed from a shared connection profile
- **WHEN** the check subsection declares `connection: <name>` and the operator
  config defines that profile
- **THEN** the adapter resolves the base URL and the token's credential name
  from the profile, sharing them with any other port referencing the same
  profile

#### Scenario: Missing token fails closed at wiring time
- **WHEN** the token secret does not resolve
- **THEN** construction fails as a configuration error naming the missing
  secret; no stage runs with an unauthenticated adapter

#### Scenario: External-check token cannot be allowlisted
- **WHEN** operator config lists `GNOMISH_GITHUB_ACTIONS_TOKEN` as a
  child-environment passthrough variable
- **THEN** startup fails with a configuration error naming the variable,
  same as for the tracker token
