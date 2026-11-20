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
surface — port interfaces, the tracker SPI factory with its validator and
credential-declaration members, `SecretsProvider`, the tracker's SPI validator
interfaces, and the `FindingsSanitizer` contract utility — and nothing from
`application` or `bootstrap` internals. It SHALL carry no check SPI: a verdict
check is an executor program, authored against the executor contract.
**Implements**: FR4 of split-into-modules; FR5, FR12, FR15, FR17 of add-plugin-architecture; FR1, FR2 of close-plugin-api-compilability-gap; FR3, FR8 of split-logtext-leaves; FR4 of type-untrusted-text; FR14 of migrate-verify-to-executor-contract
**Notes**: The untrusted-text primitives the findings sanitizer delegates to stay in the JDK-only `untrustedtext` leaf, which this module declares itself and resolves alongside its own artifact, so the single declared dependency contract is unchanged.

#### Scenario: api artifact excludes application and bootstrap internals
- **WHEN** dependency-analysis inspects the `gnomish-plugin-api` artifact
- **THEN** it contains zero imports from `application` or `bootstrap` internals
- **AND** it exposes the port interfaces, the tracker SPI factory,
  `SecretsProvider`, the tracker's validator SPIs and `FindingsSanitizer`, and
  no check SPI factory, check validator, pin-contribution hook or
  `AttemptCommitWorkspace`

#### Scenario: A third party compiles against a single declared dependency
- **WHEN** a third-party adapter is compiled with `gnomish-plugin-api` as its
  only declared dependency (the `domain` and `untrustedtext` types the ports
  and the sanitizer reference arrive transitively, as does the `operatorevent`
  jar the domain's own emitters use and the `pendingkey` jar whose key the
  domain's pending results carry)
- **THEN** it can implement the tracker port and its SPI factory without
  needing `application` or `bootstrap`
- **AND** a verdict program reads the attempt commit under verification as
  `HEAD` of the working copy its executor request names, with no factory
  dependency at all
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
- **THEN** the tracker SPI factory bears `type()`, `subsectionValidator()`,
  the connection-aware credential declaration, and `endpoint()`, which names the
  subsection key holding its HTTP endpoint and its vendor default host
- **AND** the contract holds no other SPI factory: a check's settings schema,
  credentials and pin contribution are declared by the executor's describe

### Requirement: The published jar graph is the compatibility baseline
The published project jar graph of `gnomish-plugin-api` — and with it the
committed compatibility baseline — SHALL be `gnomish-plugin-api`, `domain`,
the `untrustedtext` leaf this module declares, and every JDK-only leaf
`domain` reaches (today `operatorevent` and `pendingkey`); a change to that set SHALL be
re-baselined in the same change even when no signature moves, and the build
SHALL fail while the committed baseline differs from that set.
**Implements**: FR4 of split-into-modules; FR5, FR12, FR15, FR17 of add-plugin-architecture; FR1, FR2 of close-plugin-api-compilability-gap; FR3, FR8 of split-logtext-leaves; FR4 of type-untrusted-text; FR4 of tighten-spec-contract; FR2 of migrate-verify-to-executor-contract
**Notes**: The version bump that goes with a set change is a review obligation: nothing compares the version across changes, so a re-baseline at an unchanged version passes the build.

#### Scenario: The baseline covers the published jar set
- **WHEN** the compatibility baseline of `gnomish-plugin-api` is generated
- **THEN** it covers the `gnomish-plugin-api`, `domain`, `untrustedtext`,
  `operatorevent`, and `pendingkey` jars

#### Scenario: A jar-set change is re-baselined with a version bump
- **WHEN** a jar joins or leaves the published set and no signature moves
- **THEN** the build fails until the baseline is regenerated in the same
  change; the api version is bumped beside it, which review checks — a
  re-baseline at an unchanged version passes the build

## REMOVED Requirements

### Requirement: Sample plugin proves the check authoring path
**Reason**: The check SPI leaves the api; there is no Java check factory to compile.
**Migration**: the sample verdict program and `gnomish executor verify` replace it.

### Requirement: Attempt-commit workspace readable through the contract
**Reason**: A verdict program runs in a working copy materialized at the attempt commit; no
Java workspace type is needed by a program.
**Migration**: read `HEAD` of the working copy the request's `workspace.path` names
(`git rev-parse HEAD`).
