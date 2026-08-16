## MODIFIED Requirements

### Requirement: Thin plugin-api contract module
A `gnomish-plugin-api` module SHALL contain exactly the third-party contract
surface — port interfaces, the tracker SPI factory (bearing `type()`,
`subsectionValidator()`, and the connection-aware credential declaration), the
check SPI factory `CheckClientFactory` (with `CheckParamsValidator`, the
`ExternalCheckPinContributor` hook, its subsection validator, and its
credential declaration), `SecretsProvider`, and SPI validator interfaces — and
nothing from `application` or `bootstrap` internals. Domain value and config
types referenced by these ports stay in `domain` and are exposed through a
transitive `api` dependency.
<!-- implements FR4 of split-into-modules -->
<!-- implements FR5, FR12, FR15, FR17 of add-plugin-architecture -->

#### Scenario: api artifact excludes application and bootstrap internals
- **WHEN** dependency-analysis inspects the `gnomish-plugin-api` artifact
- **THEN** it contains zero imports from `application` or `bootstrap` internals
- **AND** it exposes the port interfaces, the tracker and check SPI factories,
  `SecretsProvider`, and validator SPIs

#### Scenario: A third party compiles against a single declared dependency
- **WHEN** a third-party adapter is compiled with `gnomish-plugin-api` as its
  only declared dependency (the `domain` types the ports reference arrive
  transitively)
- **THEN** it can implement any exposed port and its SPI factory — tracker or
  check — without needing `application` or `bootstrap`
<!-- implements UX3 of split-into-modules -->

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
