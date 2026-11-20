# plugin-api-contract

## Purpose

Defines `gnomish-plugin-api` — the thin, independently versioned contract module third-party adapters compile against — its exact surface, its semver rules, and the requirement that secrets are reached only through the `SecretsProvider` port it exposes.

## Requirements

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

### Requirement: Independent semantic versioning of the api
`gnomish-plugin-api` SHALL be independently versioned by semver; the semver
surface is the api module plus the `domain` types it exposes transitively.
Changes to `application` internals or to `domain` types not exposed through the
api SHALL NOT require an api version bump.
**Implements**: FR5 of split-into-modules; FR14 of add-plugin-architecture
**Notes**: The japicmp gate was flipped from report-only to failing once the first external consumer existed, armed against the baseline add-plugin-architecture shipped (the surface including the check SPI and the tracker SPI additions).

#### Scenario: Internal refactor leaves the api version untouched
- **WHEN** an `application` or unexposed `domain` internal is refactored without
  changing any type exposed by `gnomish-plugin-api`
- **THEN** the `gnomish-plugin-api` version is unchanged

#### Scenario: An exposed domain type change surfaces in the japicmp report
- **WHEN** a `domain` type exposed through the api's ports changes incompatibly
- **THEN** the japicmp report records the change against the baseline

#### Scenario: An incompatible api change breaks the build
- **WHEN** a type exposed by `gnomish-plugin-api` — or a `domain` type it
  exposes transitively — changes incompatibly against the japicmp baseline
- **THEN** the build fails at the japicmp gate naming the incompatible change,
  not merely a report entry

### Requirement: Secrets reached only through the api port
Module boundaries SHALL prevent adapters from reaching secrets internals except
through the `SecretsProvider` port exposed by `gnomish-plugin-api`; no credentials
SHALL appear in any module metadata.
**Implements**: FR4, NFR-S1 of split-into-modules

#### Scenario: Adapter obtains secrets only via SecretsProvider
- **WHEN** an adapter needs a credential
- **THEN** it resolves it through the `SecretsProvider` port from
  `gnomish-plugin-api`
- **AND** no credential value is present in any module's build metadata

### Requirement: Findings sanitization available to every plugin
The api SHALL provide the findings-sanitization utility (control-character /
ANSI strip, tail cap, and the log-bound `forLog` composition of the two) so
any check plugin can apply the same
pre-publication hygiene as first-party adapters; first-party call sites SHALL
keep enforcing it after the relocation. The utility is a facade over the
untrusted-text leaf's primitives: it holds no character-class table of its
own, so it cannot drift from the log-line sanitizer.
**Implements**: FR2, NFR-S1 of close-plugin-api-compilability-gap; FR3 of split-logtext-leaves

#### Scenario: A hostile CI log is neutralized with api-only means
- **WHEN** a check implementation depending only on `gnomish-plugin-api`
  processes a multi-gigabyte log containing ANSI escapes and control characters
- **THEN** it can strip and tail-cap the text through the api's sanitizer
  before placing it in a finding

#### Scenario: First-party sanitization is unchanged by the move
- **WHEN** the existing first-party log-sanitization specs run after the
  relocation
- **THEN** they pass unchanged in substance (imports aside): strip and cap
  behavior is identical

#### Scenario: The facade holds no table of its own
- **WHEN** the findings sanitizer's source is scanned for escape-character or
  control-class literals
- **THEN** none is found — every primitive resolves to the untrusted-text
  leaf

### Requirement: Surface growth is additive and re-baselined
The api additions SHALL be backward-compatible for every pre-existing
`gnomish-plugin-api` type, and the compatibility gate SHALL pass against a
deliberately regenerated baseline that includes the new surface. A pre-1.0
breaking move — a re-exposed domain type or the snapshot changing a field's
type — SHALL be a MINOR bump with the baseline regenerated in the same
commit and the break named in the module's build script, so a reviewer sees
the break and the bump together.
**Implements**: FR5 of close-plugin-api-compilability-gap; FR4 of type-untrusted-text

#### Scenario: Compatibility gate passes on the grown surface
- **WHEN** the api compatibility check runs after the baseline regeneration
- **THEN** it passes, and the diff against the previous baseline shows
  additions only for pre-existing types

#### Scenario: A typed-field break is bumped and re-baselined together
- **WHEN** the snapshot's title and body and the abort marker's cause change
  from plain strings to the untrusted-text carrier
- **THEN** the api version takes a MINOR bump, the baseline is regenerated in
  the same commit, and the build script names the break

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

### Requirement: Sample verdict program proves the executor authoring path
The stand-in third-party module SHALL include a sample verdict program — a script with no
factory dependency — that passes the executor conformance kit in role `verdict` with `poll`.
Check authoring is proven by the kit, not by compiling a factory against `gnomish-plugin-api`.
**Implements**: FR8 of migrate-verify-to-executor-contract

#### Scenario: Sample verdict program passes the kit
- **WHEN** the conformance kit runs against the sample verdict program
- **THEN** every verdict and poll case passes and the program declares no factory dependency
