## ADDED Requirements

### Requirement: Check port carries a provider discriminator and a discovered registry
The check port SHALL gain a `provider` discriminator on its SPI factory
(`CheckClientFactory`), and check clients SHALL be resolved from a
`Map<provider, CheckClientFactory>` registry populated by `ServiceLoader` —
bringing the check port to parity with the tracker port. GitHub SHALL be one
`provider` among the registry, not a hardwired special case.
<!-- implements FR5 of add-plugin-architecture -->

#### Scenario: A check client is resolved by its provider
- **WHEN** a stage runs an `external` check declaring `provider: github`
- **THEN** the engine resolves the github `CheckClientFactory` from the
  `ServiceLoader` registry and obtains an `ExternalCheckClient` from it
- **AND** no core code names github directly to make that resolution

### Requirement: Each check provider ships a per-provider params validator
Every check provider SHALL supply a `CheckParamsValidator` SPI keyed by its
`provider`, invoked at the config seam to validate that provider's non-secret
`params` before any stage runs. Validation problems SHALL be returned as located
`ConfigError` data, consistent with the loader's aggregation, never thrown.
<!-- implements FR5 of add-plugin-architecture -->

#### Scenario: Provider validates its own params
- **WHEN** an `external` check declares `provider: github` with a malformed
  github-specific param
- **THEN** the github `CheckParamsValidator` reports a located `ConfigError`
  identifying the check and the offending param
- **AND** a provider with no matching validator selection is never asked to
  validate another provider's params

### Requirement: Check provider is selected per-check in the stage manifest
Check provider selection SHALL be per-check within a stage manifest, not
per-project. A single stage's `verify` list MAY hold several `external` checks
naming different providers, and each SHALL be resolved independently from the
registry.
<!-- implements FR6 of add-plugin-architecture -->

#### Scenario: One stage runs external checks from different providers
- **WHEN** a stage's `verify` list contains an `external` check with
  `provider: github` and another with `provider: http`
- **THEN** each check resolves its own provider from the registry and both run in
  the declared order within the one verify chain

### Requirement: Check identity for findings correlation is provider plus checkId
The identity used to correlate a check's findings across attempts SHALL be the
pair `provider` + `checkId`. Two checks sharing a `checkId` under different
providers SHALL be distinct identities.
<!-- implements FR8 of add-plugin-architecture -->

#### Scenario: Same checkId under two providers stays distinct
- **WHEN** two `external` checks share a `checkId` but declare different providers
- **THEN** their findings are correlated under distinct `provider`+`checkId`
  identities, never merged

### Requirement: The check SPI carries the adapter pin-contribution hook
The check SPI factory SHALL expose the adapter pin-path contribution hook
(`ExternalCheckPinContributor`, moved into `gnomish-plugin-api`), so the
pin-check guard can union each provider's contributed paths with the
law-declared `pinPaths` exactly as verification-hardening requires — for a
discovered plugin the same way as for a built-in provider. (The
missing-provider → github default is specified in the pipeline-config
capability.)
<!-- implements FR15 of add-plugin-architecture -->

#### Scenario: A discovered provider's pin contribution reaches the guard
- **WHEN** the github provider is loaded as a discovered plugin and a stage runs
  its `external` check
- **THEN** the pin-check guard unions the provider's contributed paths (the
  `checkId` workflow file) with the law-declared `pinPaths`, unchanged from the
  pre-plugin behavior
