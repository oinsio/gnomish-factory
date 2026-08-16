# plugin-api-contract

## Purpose

Defines `gnomish-plugin-api` — the thin, independently versioned contract module third-party adapters compile against — its exact surface, its semver rules, and the requirement that secrets are reached only through the `SecretsProvider` port it exposes.

## Requirements

### Requirement: Thin plugin-api contract module
A `gnomish-plugin-api` module SHALL contain exactly the third-party contract
surface — port interfaces, the existing tracker SPI factory interface,
`SecretsProvider`, and SPI validator interfaces — and nothing from `application`
or `bootstrap` internals. Domain value and config types referenced by these
ports stay in `domain` and are exposed through a transitive `api` dependency;
the check SPI factory is introduced by change B, not this change.
<!-- implements FR4 of split-into-modules -->

#### Scenario: api artifact excludes application and bootstrap internals
- **WHEN** dependency-analysis inspects the `gnomish-plugin-api` artifact
- **THEN** it contains zero imports from `application` or `bootstrap` internals
- **AND** it exposes the port interfaces, the tracker SPI factory,
  `SecretsProvider`, and validator SPIs

#### Scenario: A third party compiles against a single declared dependency
- **WHEN** a third-party adapter is compiled with `gnomish-plugin-api` as its
  only declared dependency (the `domain` types the ports reference arrive
  transitively)
- **THEN** it can implement any exposed port and its SPI factory without needing
  `application` or `bootstrap`
<!-- implements UX3 of split-into-modules -->

### Requirement: Independent semantic versioning of the api
`gnomish-plugin-api` SHALL be independently versioned by semver; the semver
surface is the api module plus the `domain` types it exposes transitively.
Changes to `application` internals or to `domain` types not exposed through the
api SHALL NOT require an api version bump; in this change japicmp tracks the
surface in report-only mode.
<!-- implements FR5 of split-into-modules -->

#### Scenario: Internal refactor leaves the api version untouched
- **WHEN** an `application` or unexposed `domain` internal is refactored without
  changing any type exposed by `gnomish-plugin-api`
- **THEN** the `gnomish-plugin-api` version is unchanged

#### Scenario: An exposed domain type change surfaces in the japicmp report
- **WHEN** a `domain` type exposed through the api's ports changes incompatibly
- **THEN** the japicmp report records the change against the baseline (the
  failing gate arrives in change B)

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
