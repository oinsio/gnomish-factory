# plugin-api-contract

## Purpose

Defines `gnomish-plugin-api` — the thin, independently versioned contract module third-party adapters compile against — its exact surface, its semver rules, and the requirement that secrets are reached only through the `SecretsProvider` port it exposes.

## Requirements

### Requirement: Thin plugin-api contract module
A `gnomish-plugin-api` module SHALL contain exactly the third-party contract
surface — port interfaces, the tracker SPI factory (bearing `type()`,
`subsectionValidator()`, and the connection-aware credential declaration), the
check SPI factory `CheckClientFactory` (with `CheckParamsValidator`, the
`ExternalCheckPinContributor` hook, its subsection validator, and both halves
of its credential declaration — from the configured connection and, for a
provider whose targets are per-check, from a check's own params),
`SecretsProvider`, SPI validator interfaces, the `AttemptCommitWorkspace`
workspace-capability interface, and the `FindingsSanitizer` contract utility —
and nothing from `application` or `bootstrap` internals. Domain value and
config types referenced by these ports stay in `domain` and are exposed through
a transitive `api` dependency.
<!-- implements FR4 of split-into-modules -->
<!-- implements FR5, FR12, FR15, FR17 of add-plugin-architecture -->
<!-- implements FR1, FR2 of close-plugin-api-compilability-gap -->

#### Scenario: api artifact excludes application and bootstrap internals
- **WHEN** dependency-analysis inspects the `gnomish-plugin-api` artifact
- **THEN** it contains zero imports from `application` or `bootstrap` internals
- **AND** it exposes the port interfaces, the tracker and check SPI factories,
  `SecretsProvider`, validator SPIs, `AttemptCommitWorkspace`, and
  `FindingsSanitizer`

#### Scenario: A third party compiles against a single declared dependency
- **WHEN** a third-party adapter is compiled with `gnomish-plugin-api` as its
  only declared dependency (the `domain` types the ports reference arrive
  transitively)
- **THEN** it can implement any exposed port and its SPI factory — tracker or
  check — without needing `application` or `bootstrap`
- **AND** an external-check implementation can read the attempt-commit sha of
  the round under verification through `AttemptCommitWorkspace` and sanitize
  its findings through `FindingsSanitizer` using only that dependency
<!-- implements UX3 of split-into-modules -->
<!-- implements G1, FR1, FR2, FR4 of close-plugin-api-compilability-gap -->

#### Scenario: The first-party vendor bundle satisfies the same constraint
- **WHEN** the production dependency declarations of the github vendor bundle
  (`api` / `implementation` — the configurations the layering gate walks) are
  inspected
- **THEN** no dependency on `application` (or `bootstrap`) appears — the
  bundle compiles against the published contract exactly as a third-party
  bundle would
- **AND** its own specs name no `application` type either: they reach the
  attempt-commit workspace through the shared test fixture, which hands back
  the api interface, so `:application` stays an unreferenced transitive of
  `:test-fixtures` on the test classpath
<!-- implements FR3, M1 of close-plugin-api-compilability-gap -->

### Requirement: Independent semantic versioning of the api
`gnomish-plugin-api` SHALL be independently versioned by semver; the semver
surface is the api module plus the `domain` types it exposes transitively.
Changes to `application` internals or to `domain` types not exposed through the
api SHALL NOT require an api version bump. The japicmp check SHALL run as a
failing gate — flipped from report-only now that the first external consumer
exists — armed against the baseline this change ships (the surface including
the check SPI and the tracker SPI additions), breaking the build on an
incompatible api change.
<!-- implements FR5 of split-into-modules -->
<!-- implements FR14 of add-plugin-architecture -->

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
<!-- implements FR4, NFR-S1 of split-into-modules -->

#### Scenario: Adapter obtains secrets only via SecretsProvider
- **WHEN** an adapter needs a credential
- **THEN** it resolves it through the `SecretsProvider` port from
  `gnomish-plugin-api`
- **AND** no credential value is present in any module's build metadata

### Requirement: Attempt-commit workspace readable through the contract
The workspace object the engine hands to sandboxed-mode check runners SHALL be
readable through the api's `AttemptCommitWorkspace` interface: a check
implementation SHALL be able to obtain the attempt-commit sha of the round
under verification by narrowing the received `Workspace` to that api type,
without referencing any `application` type. Reading the sha before a snapshot
was recorded SHALL fail with a protocol error, as today.
<!-- implements FR1 of close-plugin-api-compilability-gap -->

#### Scenario: External check learns the commit it verifies via the api type
- **WHEN** the engine invokes a check client with the run's workspace in
  sandboxed mode
- **THEN** the client can narrow the `Workspace` to the api's
  `AttemptCommitWorkspace` and read the current round's attempt-commit sha
- **AND** the sha it reads is the one the engine's snapshot step recorded for
  that round

#### Scenario: Reading the sha before any snapshot is a protocol error
- **WHEN** a check client reads the attempt-commit sha before the engine has
  recorded a snapshot commit
- **THEN** the read fails with an explicit protocol-violation error rather than
  returning an absent or stale value

### Requirement: Findings sanitization available to every plugin
The api SHALL provide the findings-sanitization utility (control-character /
ANSI strip and tail cap) so any check plugin can apply the same
pre-publication hygiene as first-party adapters; first-party call sites SHALL
keep enforcing it after the relocation.
<!-- implements FR2, NFR-S1 of close-plugin-api-compilability-gap -->

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

### Requirement: Sample plugin proves the check authoring path
The stand-in third-party module SHALL include an external-check SPI factory
implementation — declared through the same `META-INF/services` mechanism as any
provider — that reads the attempt-commit sha through the api interface,
compiled with `gnomish-plugin-api` as its only declared dependency, so the
"compiles against the api alone" promise covers check authoring by
construction and regresses as a compile failure.
<!-- implements FR4, G1 of close-plugin-api-compilability-gap -->

#### Scenario: Sample check factory compiles against the api alone
- **WHEN** the sample module is built
- **THEN** its check SPI factory compiles with `gnomish-plugin-api` as the only
  declared dependency, and the module declares no test source set — the
  successful compilation is the assertion
- **AND** its `layering` declaration still permits only `:domain` and
  `:gnomish-plugin-api`, so any leak of an `application` type into the check
  authoring path fails the build here

### Requirement: Surface growth is additive and re-baselined
The api additions SHALL be backward-compatible for every pre-existing
`gnomish-plugin-api` type, and the compatibility gate SHALL pass against a
deliberately regenerated baseline that includes the new surface.
<!-- implements FR5 of close-plugin-api-compilability-gap -->

#### Scenario: Compatibility gate passes on the grown surface
- **WHEN** the api compatibility check runs after the baseline regeneration
- **THEN** it passes, and the diff against the previous baseline shows
  additions only for pre-existing types
