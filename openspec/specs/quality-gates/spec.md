# quality-gates

## Purpose

Defines the automated quality gates that guard the codebase: a single `./gradlew check` command that runs the test suite, coverage, and mutation testing, plus format, static-analysis, and dependency-hygiene gates, all enforced in CI alongside security scanning, on a reproducible build.

## Requirements

### Requirement: Single verification command
The build SHALL expose one command — `./gradlew check` — that compiles production and test code, runs the full Spock suite, produces the JaCoCo report, and runs PIT mutation testing.

#### Scenario: One command runs everything
- **WHEN** a developer runs `./gradlew check` on a fresh clone with JDK 25
- **THEN** compilation, Spock tests, JaCoCo, and PIT all execute
- **AND** the command exits 0 only if every gate passes

### Requirement: Mutation testing gate
PIT SHALL mutate Java production classes only and SHALL fail the build when the mutation score is below the threshold (100%; explicitly documented exceptions may lower it to 95% for code unreachable by unit tests). The set of mutated classes MAY be narrowed to an explicit scope (see "Scoped mutation target"); the threshold and documented exceptions apply unchanged to whichever classes are in scope.
<!-- implements FR1 of scope-pit-to-changed-files -->

#### Scenario: Surviving mutant fails the build
- **WHEN** a production class in scope contains logic not killed by any test
- **THEN** `./gradlew check` fails
- **AND** the failure output names the mutation threshold and links to the HTML report

#### Scenario: Only Java production code is mutated
- **WHEN** PIT runs
- **THEN** its target classes include only Java production packages

### Requirement: Scoped mutation target
The build SHALL accept an optional property that narrows PIT's target classes to an explicit list; when the property is absent the build SHALL mutate the full production package tree exactly as before.
<!-- implements FR1, FR5 of scope-pit-to-changed-files -->

#### Scenario: Property narrows the mutation scope
- **WHEN** `./gradlew check` runs with the scoped-target property set to one or more class globs under the production base package
- **THEN** PIT mutates only classes matching those globs
- **AND** the 100% mutation-score gate and `pitestVerifyAllKilled` apply to that scoped set

#### Scenario: Absent property preserves full-project mutation
- **WHEN** `./gradlew check` runs without the scoped-target property (e.g. a local developer run)
- **THEN** PIT mutates the full `com.github.oinsio.gnomish.*` tree as it does today

#### Scenario: Empty scope is a clean pass
- **WHEN** the scoped-target property is set but resolves to no production classes
- **THEN** the mutation task is skipped and the build succeeds
- **AND** the build does NOT fail with PIT's "No mutations found" error

### Requirement: Coverage reporting
JaCoCo SHALL produce XML and HTML coverage reports on every test run.

#### Scenario: Reports generated
- **WHEN** the test task completes
- **THEN** JaCoCo XML and HTML reports exist under the build directory

### Requirement: Code format gate
The build SHALL enforce a consistent code format via a Spotless check wired into `./gradlew check`; formatting violations SHALL fail the build and be auto-fixable with `spotlessApply`.

#### Scenario: Misformatted code fails the gate
- **WHEN** a source file violates the configured format
- **THEN** `./gradlew check` fails naming the file
- **AND** running `spotlessApply` fixes the violation

### Requirement: Static analysis gate
Compilation SHALL fail on Error Prone bug patterns and NullAway null-safety violations within the production base package; Error Prone's unused-code checks (`UnusedMethod`, `UnusedVariable`) SHALL be treated as errors.

#### Scenario: Null-safety violation fails compilation
- **WHEN** production code dereferences a value that NullAway cannot prove non-null
- **THEN** compilation fails naming the violation

#### Scenario: Dead private code fails compilation
- **WHEN** production code contains an unused private method or variable
- **THEN** compilation fails with the unused-code check named

### Requirement: Dependency hygiene
The build SHALL detect unused and misdeclared dependencies and fail the gate on violations.

#### Scenario: Unused dependency fails the gate
- **WHEN** a declared dependency is not used by any source set
- **THEN** the dependency-analysis task fails naming the dependency

### Requirement: Continuous integration
A CI workflow SHALL run `./gradlew check` on every push and pull request and SHALL publish JaCoCo and PIT reports as build artifacts. On pull-request and branch runs the mutation gate SHALL be scoped to the production Java classes changed relative to the merge base with `main`, so the job completes within its time budget; whole-project mutation coverage is guaranteed by local/manual `./gradlew check`, not by CI.
<!-- implements FR2, FR3, FR4, NFR-C1 of scope-pit-to-changed-files -->

#### Scenario: CI enforces the gates
- **WHEN** a commit is pushed with a failing test or a surviving mutant in a changed production class
- **THEN** the CI run fails

#### Scenario: Mutation gate is scoped to the diff
- **WHEN** a branch changes a subset of production Java files
- **THEN** the CI mutation run targets only the classes derived from those changed files
- **AND** the job completes within its configured time budget

#### Scenario: Diff with no production changes still passes
- **WHEN** a branch changes only docs, tests, or CI configuration
- **THEN** the CI mutation run targets no classes and passes without a "No mutations found" failure

#### Scenario: Reports are downloadable
- **WHEN** a CI run completes (pass or fail)
- **THEN** JaCoCo and PIT reports are attached as artifacts

### Requirement: Security scanning
CI SHALL run security scanning: OSV-Scanner failing the run on known-vulnerable dependency versions and Gitleaks failing the run on committed secrets on every push and pull request; CodeQL analysis of the codebase on every pull request and on pushes to `main`.

#### Scenario: Vulnerable dependency fails CI
- **WHEN** a dependency version with a known OSV/CVE advisory is present in the build
- **THEN** the OSV-Scanner job fails naming the dependency and advisory

#### Scenario: Committed secret fails CI
- **WHEN** a commit contains a string matching a known secret pattern
- **THEN** the Gitleaks job fails identifying the offending commit and location

### Requirement: Reproducible build
The build SHALL be reproducible: the Gradle version is fixed by the wrapper, the Java toolchain is pinned to 25, and dependency versions are declared in a single location.

#### Scenario: Wrapper pins the toolchain
- **WHEN** the project is built on a machine with a different default JDK
- **THEN** Gradle uses the pinned Java 25 toolchain or fails with a clear message
