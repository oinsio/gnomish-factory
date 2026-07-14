# quality-gates

## ADDED Requirements

### Requirement: Single verification command
The build SHALL expose one command — `./gradlew check` — that compiles production and test code, runs the full Spock suite, produces the JaCoCo report, and runs PIT mutation testing. <!-- implements FR5, UX1 of add-project-skeleton -->

#### Scenario: One command runs everything
- **WHEN** a developer runs `./gradlew check` on a fresh clone with JDK 25
- **THEN** compilation, Spock tests, JaCoCo, and PIT all execute
- **AND** the command exits 0 only if every gate passes

### Requirement: Mutation testing gate
PIT SHALL mutate Java production classes only (never Groovy test bytecode) and SHALL fail the build when the mutation score is below the threshold (100%; explicitly documented exceptions may lower it to 95% for code unreachable by unit tests). <!-- implements FR6 of add-project-skeleton -->

#### Scenario: Surviving mutant fails the build
- **WHEN** production code contains logic not killed by any test
- **THEN** `./gradlew check` fails
- **AND** the failure output names the mutation threshold and links to the HTML report

#### Scenario: Groovy bytecode is never mutated
- **WHEN** PIT runs
- **THEN** its target classes include only Java production packages

### Requirement: Coverage reporting
JaCoCo SHALL produce XML and HTML coverage reports on every test run. <!-- implements FR6, NFR-O1 of add-project-skeleton -->

#### Scenario: Reports generated
- **WHEN** the test task completes
- **THEN** JaCoCo XML and HTML reports exist under the build directory

### Requirement: Code format gate
The build SHALL enforce a consistent code format via a Spotless check wired into `./gradlew check`; formatting violations SHALL fail the build and be auto-fixable with `spotlessApply`. <!-- implements FR8 of add-project-skeleton -->

#### Scenario: Misformatted code fails the gate
- **WHEN** a source file violates the configured format
- **THEN** `./gradlew check` fails naming the file
- **AND** running `spotlessApply` fixes the violation

### Requirement: Static analysis gate
Compilation SHALL fail on Error Prone bug patterns and NullAway null-safety violations within the production base package; Error Prone's unused-code checks (`UnusedMethod`, `UnusedVariable`) SHALL be treated as errors. <!-- implements FR9 of add-project-skeleton -->

#### Scenario: Null-safety violation fails compilation
- **WHEN** production code dereferences a value that NullAway cannot prove non-null
- **THEN** compilation fails naming the violation

#### Scenario: Dead private code fails compilation
- **WHEN** production code contains an unused private method or variable
- **THEN** compilation fails with the unused-code check named

### Requirement: Dependency hygiene
The build SHALL detect unused and misdeclared dependencies and fail the gate on violations. <!-- implements FR10 of add-project-skeleton -->

#### Scenario: Unused dependency fails the gate
- **WHEN** a declared dependency is not used by any source set
- **THEN** the dependency-analysis task fails naming the dependency

### Requirement: Continuous integration
A CI workflow SHALL run `./gradlew check` on every push and pull request and SHALL publish JaCoCo and PIT reports as build artifacts. <!-- implements FR7, NFR-O1 of add-project-skeleton -->

#### Scenario: CI enforces the gates
- **WHEN** a commit is pushed with a failing test or a surviving mutant
- **THEN** the CI run fails

#### Scenario: Reports are downloadable
- **WHEN** a CI run completes (pass or fail)
- **THEN** JaCoCo and PIT reports are attached as artifacts

### Requirement: Security scanning
CI SHALL run security scanning on every push and pull request: CodeQL analysis of the codebase, OSV-Scanner failing the run on known-vulnerable dependency versions, and Gitleaks failing the run on committed secrets. <!-- implements FR11, NFR-S1 of add-project-skeleton -->

#### Scenario: Vulnerable dependency fails CI
- **WHEN** a dependency version with a known OSV/CVE advisory is present in the build
- **THEN** the OSV-Scanner job fails naming the dependency and advisory

#### Scenario: Committed secret fails CI
- **WHEN** a commit contains a string matching a known secret pattern
- **THEN** the Gitleaks job fails identifying the offending commit and location

### Requirement: Reproducible build
The build SHALL be reproducible: the Gradle version is fixed by the wrapper, the Java toolchain is pinned to 25, and dependency versions are declared in a single location. <!-- implements FR1, NFR-R1 of add-project-skeleton -->

#### Scenario: Wrapper pins the toolchain
- **WHEN** the project is built on a machine with a different default JDK
- **THEN** Gradle uses the pinned Java 25 toolchain or fails with a clear message
