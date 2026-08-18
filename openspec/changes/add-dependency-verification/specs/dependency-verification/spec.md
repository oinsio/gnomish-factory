## ADDED Requirements

### Requirement: Every resolved artifact is pinned
`gradle/verification-metadata.xml` SHALL pin every artifact resolved by any
resolvable configuration — libraries, Gradle plugins, and `build-logic`
dependencies — by checksum, with trusted PGP keys where the publisher signs.
An artifact absent from the metadata SHALL fail the build.
<!-- implements FR1 of add-dependency-verification -->

#### Scenario: Unlisted artifact fails the build
- **WHEN** a build resolves an artifact that has no entry in the verification
  metadata
- **THEN** the build fails naming the artifact and the regeneration command

#### Scenario: Build-logic and plugin artifacts are covered
- **WHEN** the verification metadata is regenerated
- **THEN** it contains entries for Gradle plugins and `build-logic`
  dependencies, not only production/test libraries

### Requirement: Tampered artifacts are refused with an actionable error
A build resolving an artifact whose bytes mismatch the recorded checksum (or
whose signature does not match a trusted key) SHALL fail before any code from
that artifact executes, naming the artifact, expected vs actual, and the fix.
<!-- implements FR2, NFR-O1 of add-dependency-verification -->

#### Scenario: Checksum mismatch is fail-closed
- **WHEN** a repository serves different bytes for a pinned artifact version
- **THEN** the build fails naming the artifact and both checksums
- **AND** no code from the artifact executes

### Requirement: Regeneration is a single idempotent command
Updating the metadata SHALL be one documented command producing a
deterministic, reviewable diff; running it twice with no dependency change
SHALL produce no diff; contributors SHALL NOT hand-edit checksums.
<!-- implements FR3, NFR-R1 of add-dependency-verification -->

#### Scenario: Idempotent regeneration
- **WHEN** the regeneration command runs twice with no dependency change
- **THEN** the second run produces an empty diff

#### Scenario: Dependency bump produces a reviewable diff
- **WHEN** a dependency version is bumped and the command is run
- **THEN** the metadata diff contains only the entries for the changed
  artifacts

### Requirement: Dependency-update flow stays green
The Dependabot flow SHALL be documented end-to-end: a version-bump PR gets its
metadata update through a defined step (reviewer-run or automated), so update
PRs do not stay red for lack of metadata.
<!-- implements FR4, UX1 of add-dependency-verification -->

#### Scenario: Dependabot PR lands through the documented flow
- **WHEN** Dependabot opens a version-bump PR
- **THEN** the documented flow produces the matching metadata update and CI
  passes on the combined result

### Requirement: CI enforces verification with no bypass
CI builds SHALL run with dependency verification active and SHALL NOT carry a
bypass flag; verification failures fail CI.
<!-- implements FR5 of add-dependency-verification -->

#### Scenario: CI has no bypass
- **WHEN** the CI workflow definitions are inspected
- **THEN** no step disables or relaxes dependency verification
