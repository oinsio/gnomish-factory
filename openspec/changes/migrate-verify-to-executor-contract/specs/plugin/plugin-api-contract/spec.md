# plugin/plugin-api-contract — delta for migrate-verify-to-executor-contract

## ADDED Requirements

### Requirement: Sample verdict program proves the executor authoring path
The stand-in third-party module SHALL include a sample verdict program — a script with no
factory dependency — that passes the executor conformance kit in role `verdict` with `poll`.
Check authoring is proven by the kit, not by compiling a factory against `gnomish-plugin-api`.
**Implements**: FR8 of migrate-verify-to-executor-contract

#### Scenario: Sample verdict program passes the kit
- **WHEN** the conformance kit runs against the sample verdict program
- **THEN** every verdict and poll case passes and the program declares no factory dependency

## MODIFIED Requirements

### Requirement: Thin plugin-api contract module
A `gnomish-plugin-api` module SHALL contain exactly the third-party contract
surface — port interfaces, the tracker and check SPI factories with their
validator, credential-declaration and pin-contribution members,
`SecretsProvider`, SPI validator interfaces, the `AttemptCommitWorkspace`
workspace-capability interface, and the `FindingsSanitizer` contract utility —
and nothing from `application` or `bootstrap` internals.
**Implements**: FR4 of split-into-modules; FR5, FR12, FR15, FR17 of add-plugin-architecture; FR1, FR2 of close-plugin-api-compilability-gap; FR3, FR8 of split-logtext-leaves; FR4 of type-untrusted-text; FR14 of migrate-verify-to-executor-contract
**Notes**: The untrusted-text primitives the findings sanitizer delegates to stay in the JDK-only `untrustedtext` leaf, which this module declares itself and resolves alongside its own artifact, so the single declared dependency contract is unchanged.

#### Scenario: api artifact excludes application and bootstrap internals
- **WHEN** dependency-analysis inspects the `gnomish-plugin-api` artifact
- **THEN** it contains zero imports from `application` or `bootstrap` internals
- **AND** it exposes the port interfaces, the tracker and check SPI factories,
  `SecretsProvider`, validator SPIs, `AttemptCommitWorkspace`, and
  `FindingsSanitizer`

#### Scenario: A third party compiles against a single declared dependency
- **WHEN** a third-party adapter is compiled with `gnomish-plugin-api` as its
  only declared dependency (the `domain` and `untrustedtext` types the ports
  and the sanitizer reference arrive transitively, as does the `operatorevent`
  jar the domain's own emitters use)
- **THEN** it can implement the tracker port and its SPI factory without
  needing `application` or `bootstrap`
- **AND** an external-check implementation can read the attempt-commit sha of
  the round under verification through `AttemptCommitWorkspace` and sanitize
  its findings through `FindingsSanitizer` using only that dependency
- **AND** a tracker implementation mints the snapshot's title and body as
  tracker-provenance untrusted text using only that dependency

#### Scenario: The first-party vendor bundle satisfies the same constraint
- **WHEN** the production dependency declarations of the github vendor bundle
  (`api` / `implementation` — the configurations the layering gate walks) are
  inspected
- **THEN** no dependency on `application` (or `bootstrap`) appears — the
  bundle compiles against the published contract plus the first-party GitHub
  library (the one module holding the GitHub HTTP core, which the bundle's
  tracker shares with core's verdict built-ins); a third-party bundle has the
  contract alone

#### Scenario: The SPI factories bear their declared members
- **WHEN** the contract's SPI factories are inspected
- **THEN** the tracker SPI factory bears `type()`, `subsectionValidator()`, and
  the connection-aware credential declaration
- **AND** the check SPI factory `CheckClientFactory` bears `CheckParamsValidator`,
  the `ExternalCheckPinContributor` hook, its subsection validator, and both
  halves of its credential declaration — from the configured connection and,
  for a provider whose targets are per-check, from a check's own params

## REMOVED Requirements

### Requirement: Sample plugin proves the check authoring path
**Reason**: The check SPI leaves the api; there is no Java check factory to compile.
**Migration**: the sample verdict program and `gnomish executor verify` replace it.

### Requirement: Attempt-commit workspace readable through the contract
**Reason**: The attempt commit travels in the executor request; no Java workspace type is
needed by a program.
**Migration**: read `workspace.attemptCommit` from the request on stdin.
