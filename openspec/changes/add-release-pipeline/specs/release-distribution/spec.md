## ADDED Requirements

### Requirement: The product version comes from the release tag
The product version SHALL be the release tag's version without its leading `v` in a release build, and `0.0.0-dev` in every other build. It SHALL be stamped into the boot jar at build time, with no build timestamp beside it, and every place that reports the factory's version — `gnomish --version`, the serve snapshot, the ledger — SHALL read it from there. The plugin-contract artifact SHALL keep the version declared in its own build file and SHALL NOT take the product version.
<!-- implements FR3, FR6, G3 of add-release-pipeline -->

#### Scenario: Release build carries the tag's version
- **WHEN** the release workflow builds tag `v0.2.0`
- **THEN** `gnomish --version` from the built archive prints `0.2.0` and a serve snapshot written by it records `0.2.0`

#### Scenario: Local build is marked as not a release
- **WHEN** a developer builds the distribution locally with no release version given
- **THEN** `gnomish --version` prints `0.0.0-dev`

#### Scenario: The stamp carries no timestamp
- **WHEN** the boot jar is built twice from the same commit with the same version
- **THEN** the version stamp inside both jars is byte-identical

#### Scenario: Plugin contract is untouched
- **WHEN** the release workflow builds tag `v0.2.0`
- **THEN** the plugin-contract jar keeps the version declared in its build file

### Requirement: A distribution archive with a launcher
The build SHALL produce `gnomish-<version>.tar.gz` and `gnomish-<version>.zip`, each unpacking to `gnomish-<version>/` containing `bin/gnomish`, `lib/gnomish-<version>.jar`, `share/examples/`, `share/sandbox-image/` (copied from the repository's reference sandbox-image recipe at build time), `LICENSE`, `NOTICE` and `README.md`. `bin/gnomish` SHALL locate Java through `JAVA_HOME`, else `PATH`; refuse a Java whose feature version is below 25 with a message naming the binary found, its version and how to point at another; add the options in `GNOMISH_JAVA_OPTS`; and pass every argument to the factory unchanged, injecting no factory option. Two builds of the same commit and version SHALL produce byte-identical archives.
<!-- implements FR4, FR5, NFR-R1, NFR-P1, UX2 of add-release-pipeline -->

#### Scenario: Old Java is refused plainly
- **WHEN** `bin/gnomish take` runs with only Java 21 on `PATH` and no `JAVA_HOME`
- **THEN** it exits non-zero printing that Java 25 or newer is required, the path of the `java` it found, and version 21

#### Scenario: Newer Java is accepted
- **WHEN** `bin/gnomish --version` runs with Java 26
- **THEN** the factory starts and prints its version

#### Scenario: Arguments pass through unchanged
- **WHEN** `bin/gnomish status --dir=/srv/widgets 'github:acme/widgets#7'` runs
- **THEN** the factory receives exactly those three arguments, in that order

#### Scenario: Reproducible archive
- **WHEN** the archive is built twice from the same commit with the same version
- **THEN** both builds have the same SHA-256 sum

### Requirement: A pushed version tag publishes a verified release
A pushed tag matching `v*` SHALL start the release workflow. It SHALL fail before building when the tag is not `vMAJOR.MINOR.PATCH` with an optional pre-release suffix, when the tagged commit is not reachable from `main`, or when the commit's CI run did not conclude successfully — each failure naming the check and its fix. It SHALL build the distribution with the tag's version, unpack the archive and fail unless `bin/gnomish --version` prints that version, verify the distribution terms of the archive and its jar, and only then publish a GitHub Release carrying both archives, a `SHA256SUMS` file, a CycloneDX SBOM of the boot jar, and a build provenance attestation per archive. The notes SHALL be `docs/releases/<tag>.md` when present, otherwise generated from merged pull requests. A failed run SHALL publish nothing and SHALL be safe to re-run for the same tag. The workflow SHALL NOT re-run the verification suite.
<!-- implements FR1, FR2, FR7, FR8, NFR-S1, NFR-S2, NFR-R2, NFR-O1, NFR-C1 of add-release-pipeline -->

#### Scenario: Malformed tag stops early
- **WHEN** the maintainer pushes `v0.2`
- **THEN** the workflow fails before building, stating the expected `vMAJOR.MINOR.PATCH` shape, and no release exists

#### Scenario: Red commit is not released
- **WHEN** a tag points at a commit whose CI run failed
- **THEN** the workflow fails before building, naming the failed run, and no release exists

#### Scenario: Version mismatch blocks publishing
- **WHEN** the built archive's `bin/gnomish --version` prints anything other than the tag's version
- **THEN** the workflow fails and publishes nothing

#### Scenario: Hand-written notes win
- **WHEN** `docs/releases/v0.1.0.md` exists at the tagged commit
- **THEN** the release body is that file's content

### Requirement: The factory reports its version
`gnomish --version` SHALL print the product version on one line and exit 0 without resolving a project, reading configuration beyond defaults, or contacting any service.
<!-- implements FR6 of add-release-pipeline -->

#### Scenario: Version outside any project
- **WHEN** `gnomish --version` runs in a directory that is no registered clone
- **THEN** it prints the version and exits 0
