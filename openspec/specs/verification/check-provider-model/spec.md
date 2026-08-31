# check-provider-model

## Purpose

Bring the external-check port to parity with the tracker port: a `provider` discriminator on the `CheckClientFactory` SPI, a `ServiceLoader`-populated registry, per-provider params and operator-subsection validators returning located `ConfigError` data, SPI-declared credential names feeding the child-environment scrub and never-allowlist sets, per-check provider selection inside a stage manifest, `provider`+`checkId` findings identity, and the adapter pin-path contribution hook — so github is one provider among many rather than a hardwired special case.

## Requirements

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

### Requirement: Each check provider validates its operator subsection
Every check provider SHALL expose, through its SPI factory, a validator for its
`factory.check.<provider>` operator subsection — symmetric to the tracker
subsection validator — covering the connection form (exactly one of inline
connection keys or a `connection: <name>` profile reference) and the provider's
own keys. Problems SHALL be reported as located `ConfigError` data aggregated
with other load errors, never thrown.
<!-- implements FR4 of add-plugin-architecture -->
<!-- implements FR5 of add-plugin-architecture -->

#### Scenario: Malformed operator subsection is a located load error
- **WHEN** `factory.check.github` declares both inline connection keys and a
  `connection:` profile reference, or omits a key the provider requires
- **THEN** loading reports a located `ConfigError` from the github subsection
  validator, aggregated with every other validation problem

### Requirement: Check providers declare their credentials through the SPI
Every check provider SHALL declare its credential environment-variable names
through the check SPI, resolved from its configured connection — inline
subsection or named profile — and the factory SHALL derive the
child-environment scrub and never-allowlist set for check credentials from
these declarations; core SHALL NOT name any vendor credential constant.
<!-- implements FR17 of add-plugin-architecture -->

#### Scenario: A plugin's credential is scrubbed without a core constant
- **WHEN** the github check provider is loaded as a discovered plugin and a
  stage running its `external` check executes via the agent CLI
- **THEN** the child environment is scrubbed of the provider's declared
  credential name, and no core source names that variable

#### Scenario: Declared check credential cannot be allowlisted
- **WHEN** operator config lists a discovered check provider's declared
  credential name as a child-environment passthrough variable
- **THEN** startup fails with a configuration error naming the variable,
  matching the tracker credential treatment

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

### Requirement: Command checks are bounded
A stage's `command` verify check SHALL be bounded by a configured,
installation-level timeout. A check that has not exited when the timeout
expires SHALL be terminated (tree-wide, with the supervision kill discipline)
and SHALL classify as a quality failure whose findings carry the captured
output tail — the command ran and failed to finish, exactly as a red exit code
would have failed it — burning a stage attempt and feeding the tail back as
retry context. The timeout expiry SHALL be logged naming the check id, the
elapsed time, and the configured value.
<!-- implements FR12, FR5, NFR-O1, UX4 of bound-subprocess-commands -->

#### Scenario: A hung check fails instead of hanging the run
- **WHEN** a `command` check enters an infinite loop and its timeout expires
- **THEN** the check resolves as a quality failure within the timeout plus the
  kill margin, its findings carry the output tail captured so far, and the
  stage's ordinary retry loop proceeds

#### Scenario: A check that finishes in time is untouched
- **WHEN** a `command` check exits before the timeout
- **THEN** its exit code, tail capture, and verdict classification are exactly
  as before
