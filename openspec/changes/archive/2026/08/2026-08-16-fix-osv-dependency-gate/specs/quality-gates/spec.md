## MODIFIED Requirements

### Requirement: Security scanning
CI SHALL run security scanning: OSV-Scanner failing the run on known-vulnerable dependency versions and Gitleaks failing the run on committed secrets on every push and pull request; CodeQL analysis of the codebase on every pull request and on pushes to `main`.

The OSV-Scanner gate SHALL evaluate **every** committed Gradle lockfile in the
repository — root and per-module, project and buildscript — against a **single**
repository-root suppression allowlist, independent of the directory each
lockfile lives in. Adding a module that contributes a lockfile SHALL require no
per-module scan configuration.

A suppression in that allowlist SHALL be permitted only for an artifact confined
to test or buildscript classpaths, SHALL state the affected scope and why no
adoptable fix exists, and SHALL carry an expiry date after which the gate fails
again. No advisory affecting a production runtime classpath may be suppressed;
it is fixed by pinning a non-vulnerable version instead.

Security version overrides SHALL be declared in the project's single version
source (`gradle/libs.versions.toml`, or `gradle.properties` for buildscript
classpaths, which cannot read the catalog), each naming the advisory it clears
and the condition for dropping the override; the committed lock state SHALL be
regenerated so the scanned lockfiles reflect them.

The project SHALL document one command that reproduces the CI scan verdict
locally.
<!-- implements FR1, FR2, FR6, FR7, FR8, FR9, NFR-S1, NFR-S2, NFR-S3 of fix-osv-dependency-gate -->

#### Scenario: Vulnerable dependency fails CI
- **WHEN** a dependency version with a known OSV/CVE advisory is present in the build
- **THEN** the OSV-Scanner job fails naming the dependency and advisory

#### Scenario: Committed secret fails CI
- **WHEN** a commit contains a string matching a known secret pattern
- **THEN** the Gitleaks job fails identifying the offending commit and location

#### Scenario: The allowlist governs a module lockfile
- **WHEN** an advisory listed in the repository-root allowlist appears in a
  lockfile inside a module directory rather than at the repository root
- **THEN** the scan treats it as suppressed and does not fail the run
- **AND** no configuration file exists in that module directory to make it so

#### Scenario: A newly added module needs no scan wiring
- **WHEN** a module is added to the build and contributes its own lockfile
- **THEN** the existing allowlist applies to it with no edit to the scan job or
  to any per-module configuration

#### Scenario: A production-scope advisory is fixed, never suppressed
- **WHEN** an advisory affects an artifact resolved onto a production runtime
  classpath
- **THEN** the build pins a non-vulnerable version of that artifact
- **AND** the allowlist contains no entry for that advisory

#### Scenario: A suppression expires
- **WHEN** the current date passes a suppression's recorded expiry date
- **THEN** the OSV-Scanner job fails on that advisory again, forcing a
  re-decision

#### Scenario: A reviewer can judge a suppression in place
- **WHEN** a reviewer opens the allowlist
- **THEN** each entry states the affected classpath scope, why no adoptable fix
  exists, and its expiry date, without consulting git history or an external
  document

#### Scenario: The failure names the responsible module
- **WHEN** the scan fails on a vulnerable artifact
- **THEN** the reported row identifies the package, version, advisory ID, and the
  source lockfile path, so the owning module is identifiable without re-running
  the scan

#### Scenario: A developer reproduces the CI verdict locally
- **WHEN** a developer regenerates the lock state and runs the documented local
  scan command
- **THEN** the verdict matches what CI reports for the same lock state,
  including which advisories are suppressed

### Requirement: Reproducible build
The build SHALL be reproducible: the Gradle version is fixed by the wrapper, the Java toolchain is pinned to 25, and dependency versions are declared in a single location. A security override of a BOM-managed or `strictly`-constrained transitive SHALL be applied on every configuration where the affected artifact resolves, and its effect SHALL be visible in the committed lock state rather than implied by build-script intent.
<!-- implements FR3, FR4, FR5, NFR-R1, NFR-R2 of fix-osv-dependency-gate -->

#### Scenario: Wrapper pins the toolchain
- **WHEN** the project is built on a machine with a different default JDK
- **THEN** Gradle uses the pinned Java 25 toolchain or fails with a clear message

#### Scenario: An override reaches every configuration that resolves the artifact
- **WHEN** a security override pins an artifact that several modules resolve
  transitively
- **THEN** every lockfile listing that artifact records the pinned version
- **AND** a module the override fails to reach is detected as a stale lockfile
  entry rather than passing silently

#### Scenario: The scan reads regenerated lock state
- **WHEN** a version override is changed in the single version source
- **THEN** the lock state is regenerated and committed before the scan is
  considered authoritative
