# quality-gates

## ADDED Requirements

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

## MODIFIED Requirements

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
