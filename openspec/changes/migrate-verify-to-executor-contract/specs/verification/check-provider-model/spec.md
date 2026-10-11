# verification/check-provider-model — delta for migrate-verify-to-executor-contract

## REMOVED Requirements

### Requirement: Check port carries a provider discriminator and a discovered registry
**Reason**: Providers become named executors in the executor registry of
`define-executor-contract`; discovery by `ServiceLoader` is replaced by built-in registration
and law-declared external programs.
**Migration**: `provider: github` → executor `github-actions`; `provider: http` → executor
`http`; a third-party provider becomes an external verdict program.

### Requirement: Each check provider ships a per-provider params validator
**Reason**: Params are validated against the executor's describe-declared JSON Schema.
**Migration**: move the validator's rules into the executor's settings schema.

### Requirement: Each check provider validates its operator subsection
**Reason**: Connection data moves to the executor declaration's settings and credentials,
validated by the same describe schema.
**Migration**: `factory.check.<provider>` keys move under the law's `executors.<name>` or the
operator's vendor connection profile.

### Requirement: Check providers declare their credentials through the SPI
**Reason**: Credentials are declared by the executor's describe and cross-checked against the
law, the same mechanism stages use.
**Migration**: declare the variable names in describe; grant them in the law declaration.

### Requirement: The check SPI carries the adapter pin-contribution hook
**Reason**: Pin contribution becomes a describe field (`pinPaths` contributed per check).
**Migration**: the `github-actions` built-in contributes the workflow file through describe.

### Requirement: Check provider is selected per-check in the stage manifest
**Reason**: Selection becomes executor-name based (sugar tokens or `executor:`), specified in
`verification/verdict-executors`.
**Migration**: `provider: <name>` keeps working as the executor name for `external` checks.

### Requirement: Check identity for findings correlation is provider plus checkId
**Reason**: Identity becomes executor name plus `checkId`, specified below.
**Migration**: none for manifests; the ledger token `provider=` becomes `executor=`.

## ADDED Requirements

### Requirement: Check identity for findings correlation is executor plus checkId
Findings SHALL be correlated by executor name plus `checkId`, so two checks on the same
executor remain distinguishable in the attempt record and the tracker.
<!-- implements FR1 of migrate-verify-to-executor-contract -->

#### Scenario: Same executor, two checks
- **WHEN** two `http` checks with different `checkId`s run in one stage
- **THEN** their findings carry distinct identities
