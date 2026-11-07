# verification/github-external-check — delta for migrate-verify-to-executor-contract

## MODIFIED Requirements

### Requirement: Polling is stateless and takeover-safe
A poll SHALL depend only on the check declaration, the attempt commit the engine delivers with
the verdict request and the pending key the engine persisted; no other poll state SHALL be
persisted, so any factory instance can resume polling after a crash or takeover and observe the
same runs. The executor SHALL read the attempt commit from the request the engine hands it,
never from a workspace type of its own.
**Implements**: NFR-R2 of add-external-check-github-actions; FR26 of add-sandbox-core; FR2, FR3, FR6 of migrate-verify-to-executor-contract

#### Scenario: Another instance resumes mid-poll
- **WHEN** the polling instance dies and another instance resumes the task
- **THEN** the new instance polls the same attempt commit with the persisted key and reaches the same verdict with no other state handed over

#### Scenario: The adapter consumes the engine workspace type
- **WHEN** the stage engine invokes the `github-actions` verdict executor with a request carrying the attempt commit of the round under verification
- **THEN** the executor reads the attempt commit from that request and polls runs of exactly that commit

### Requirement: The adapter contributes its workflow file to the pin set
The pin set checked by the pin-check guard SHALL be the union of the
user-declared paths from the stage law and the `checkId` workflow file: the
`github-actions` built-in's describe SHALL declare `checkIdIsPinnedPath`, and
the engine's pin decorator SHALL add the `checkId` for every executor declaring
it, so the workflow stays pinned with no manifest edit and no per-check hook.
**Implements**: FR4 of add-external-check-github-actions; FR6, FR16 of migrate-verify-to-executor-contract

#### Scenario: Early substitution is caught at the point of use
- **WHEN** the gnome modified the declared workflow file during an earlier
  stage and a later stage reaches this check
- **THEN** the pin-check guard fails the check against the base branch
  before any platform contact

### Requirement: Adapter is constructed from factory configuration
The factory SHALL construct the `github-actions` built-in verdict executor from
operator configuration: the platform base URL and a token resolved by name through
`SecretsProvider`, read from the `factory.check.github-actions` subsection,
inline or by a `connection:` profile; the engine resolves it from the executor
registry behind the pin-check guard, so an operator enables it with
configuration alone, no code changes, and removing the vendor bundle does not
remove it.
**Implements**: FR26 of add-sandbox-core; FR16 of add-plugin-architecture; FR17 of add-plugin-architecture; UX3 of add-plugin-architecture; FR13, NFR-S2 of migrate-verify-to-executor-contract
**Notes**: The `connection:` profile is the same one the github tracker may reference, so one vendor keeps a single connection and credentials block (the vendor-connection-profile capability); the `SecretsProvider`-backed token name replaced the provisional direct environment read. The tracker's subsection is never read for the built-in (design D3).

#### Scenario: Operator enables the adapter with config alone
- **WHEN** factory config declares the external-check base URL and the
  token secret resolves
- **THEN** stages declaring external checks poll GitHub Actions through
  the built-in, behind the pin-check guard

#### Scenario: Adapter is constructed from a shared connection profile
- **WHEN** the executor's subsection declares `connection: <name>` and the operator
  config defines that profile
- **THEN** the built-in resolves the base URL and the token's credential name
  from the profile, sharing them with any other port referencing the same
  profile

#### Scenario: Missing token fails closed at wiring time
- **WHEN** the token secret does not resolve
- **THEN** construction fails as a configuration error naming the missing
  secret; no stage runs with an unauthenticated built-in

#### Scenario: External-check token cannot be allowlisted
- **WHEN** operator config lists `GNOMISH_GITHUB_ACTIONS_TOKEN` as a
  child-environment passthrough variable
- **THEN** startup fails with a configuration error naming the variable,
  same as for the tracker token

#### Scenario: An inline key the profile lacks overlays it
- **WHEN** the executor's subsection declares `connection: <name>` and, inline, a
  key the referenced profile does not define
- **THEN** the built-in is constructed from the profile's values with that
  inline key overlaid

#### Scenario: An inline key the profile also defines is a load error
- **WHEN** an executor's subsection referencing a profile declares, inline, keys the
  referenced profile also defines
- **THEN** the configuration is refused before the built-in is constructed, with one
  located error per overlapping key naming it and the two forms declared; in
  operator config the refusal stops startup

#### Scenario: The default token name is the env/file secret
- **WHEN** the executor's subsection references no connection profile
- **THEN** the token resolves through `SecretsProvider` under the name
  `GNOMISH_GITHUB_ACTIONS_TOKEN`, which the env/file adapter backs

#### Scenario: A profile-supplied token name cannot be allowlisted
- **WHEN** the referenced connection profile names the token under another
  variable and operator config lists that variable as a child-environment
  passthrough variable
- **THEN** the built-in declares the profile-supplied name through its describe's
  credential declaration, and startup fails with a configuration error naming
  the variable
