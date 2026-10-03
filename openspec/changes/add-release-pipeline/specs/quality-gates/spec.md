## MODIFIED Requirements

### Requirement: Continuous integration
A CI workflow SHALL run `./gradlew check` on every branch push and pull request
and SHALL publish JaCoCo and PIT reports as build artifacts. A tag push SHALL
NOT start the check workflow: the release workflow judges the run the tagged
commit already has on `main`, and a second run for the same commit would waste
the budget and shadow that verdict. On pull-request and
branch runs the mutation gate SHALL be scoped per module: the production Java
classes changed relative to the merge base with `main` map to their owning
modules, and only those modules' mutation gates run, targeted at the changed
classes; whole-tree mutation coverage is guaranteed by local/manual
`./gradlew check`, not by CI. The CI build job SHALL NOT declare a per-job
timeout: it runs to completion under GitHub's 6-hour default, and superseded
in-flight runs for the same ref SHALL be cancelled by workflow concurrency.
Every other CI workflow job that supports a per-job timeout SHALL set
`timeout-minutes: 30`; a job whose reusable-workflow form forbids a per-job
timeout is exempt.
<!-- implements FR2, NFR-C1 of add-release-pipeline -->
<!-- implements FR1, FR2, NFR-C1 of remove-ci-build-timeout -->
<!-- implements FR2, FR3, FR4, NFR-C1 of scope-pit-to-changed-files -->
<!-- implements FR11 of split-into-modules -->

#### Scenario: CI enforces the gates
- **WHEN** a commit is pushed with a failing test or a surviving mutant in a
  changed production class
- **THEN** the CI run fails

#### Scenario: A tag push starts no check run
- **WHEN** a `v*` tag is pushed on a commit that already has a CI run on `main`
- **THEN** no CI, license-gate, OSV-Scanner or Gitleaks run starts for the tag
  ref, and the release workflow finds exactly the `main` push run for that
  commit

#### Scenario: Mutation gate is scoped to the diff
- **WHEN** a branch changes a subset of production Java files
- **THEN** the CI mutation run targets only the owning modules' gates, with the
  classes derived from those changed files

#### Scenario: Diff with no production changes still passes
- **WHEN** a branch changes only docs, tests, or CI configuration
- **THEN** the CI mutation run targets no classes and passes without a "No
  mutations found" failure

#### Scenario: Reports are downloadable
- **WHEN** a CI run completes (pass or fail)
- **THEN** JaCoCo and PIT reports are attached as artifacts

#### Scenario: A long build run is not cancelled
- **WHEN** the CI build job runs longer than 30 minutes
- **THEN** it keeps running and reports its real pass/fail verdict rather than being cancelled by a per-job timeout

#### Scenario: A superseded build run is cancelled
- **WHEN** a new commit is pushed to a ref whose CI build job is still running
- **THEN** the in-flight run for that ref is cancelled by workflow concurrency

#### Scenario: A hung job is bounded by the timeout
- **WHEN** a CI job other than the build job, one that supports a per-job timeout, runs longer than 30 minutes
- **THEN** GitHub cancels the job with its standard timeout error rather than letting it run to the 6-hour default

### Requirement: Security scanning
CI SHALL run security scanning: OSV-Scanner failing the run on known-vulnerable dependency versions and Gitleaks failing the run on committed secrets on every branch push and pull request, never on a tag push; CodeQL analysis of the codebase on every pull request and on pushes to `main`.

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
<!-- implements FR2, NFR-C1 of add-release-pipeline -->
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

The build's outputs SHALL be reproducible as well as its inputs: every archive task of every module (jars, the boot jar, distribution archives) SHALL write a fixed entry order, no file timestamps and fixed file modes, and the boot jar's build info SHALL carry no build time, so that two builds of the same commit with the same version produce byte-identical archives.
<!-- implements NFR-R1 of add-release-pipeline -->
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

#### Scenario: Archive outputs are byte-identical
- **WHEN** any module's archive task runs twice from the same commit with the
  same version
- **THEN** both outputs have the same SHA-256 sum
