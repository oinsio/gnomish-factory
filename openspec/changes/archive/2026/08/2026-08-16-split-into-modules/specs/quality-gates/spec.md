## MODIFIED Requirements

### Requirement: Single verification command
The build SHALL expose one command — `./gradlew check` — that compiles
production and test code, runs the full Spock suite, produces the JaCoCo
report, and runs PIT mutation testing. With the module tree in place, root
`check` SHALL aggregate every module's `check`, each running that module's PIT.
<!-- implements FR11 of split-into-modules -->

#### Scenario: One command runs everything
- **WHEN** a developer runs `./gradlew check` on a fresh clone with JDK 25
- **THEN** compilation, Spock tests, JaCoCo, and PIT all execute across every
  module
- **AND** the command exits 0 only if every gate in every module passes

#### Scenario: A single module's check is self-contained
- **WHEN** a developer runs `check` for one module
- **THEN** that module's tests, coverage, and PIT mutation testing run
- **AND** no other module's production classes are mutated

### Requirement: Scoped mutation target
Each module's build SHALL accept the optional scoped-target property that
narrows PIT's target classes to an explicit list within that module; when the
property is absent each module SHALL mutate its full production package tree,
so root `check` covers the union — the whole production tree — exactly as the
monolith did.
<!-- implements FR1, FR5 of scope-pit-to-changed-files -->
<!-- implements FR11 of split-into-modules -->

#### Scenario: Property narrows the mutation scope
- **WHEN** a module's `check` runs with the scoped-target property set to one or
  more class globs under that module's production packages
- **THEN** PIT mutates only classes matching those globs
- **AND** the 100% mutation-score gate and `pitestVerifyAllKilled` apply to that
  scoped set

#### Scenario: Absent property preserves full-project mutation
- **WHEN** `./gradlew check` runs without the scoped-target property (e.g. a
  local developer run)
- **THEN** every module mutates its full production package tree
- **AND** the union of module scopes equals the full production tree, as before
  the split

#### Scenario: Empty scope is a clean pass
- **WHEN** the scoped-target property is set but resolves to no production
  classes in a module
- **THEN** that module's mutation task is skipped and the build succeeds
- **AND** the build does NOT fail with PIT's "No mutations found" error

### Requirement: Continuous integration
A CI workflow SHALL run `./gradlew check` on every push and pull request and
SHALL publish JaCoCo and PIT reports as build artifacts. On pull-request and
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
<!-- implements FR1, FR2, NFR-C1 of remove-ci-build-timeout -->
<!-- implements FR2, FR3, FR4, NFR-C1 of scope-pit-to-changed-files -->
<!-- implements FR11 of split-into-modules -->

#### Scenario: CI enforces the gates
- **WHEN** a commit is pushed with a failing test or a surviving mutant in a
  changed production class
- **THEN** the CI run fails

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
- **THEN** it keeps running and reports its real pass/fail verdict rather than
  being cancelled by a per-job timeout

#### Scenario: A superseded build run is cancelled
- **WHEN** a new commit is pushed to a ref whose CI build job is still running
- **THEN** the in-flight run for that ref is cancelled by workflow concurrency

#### Scenario: A hung job is bounded by the timeout
- **WHEN** a CI job other than the build job, one that supports a per-job
  timeout, runs longer than 30 minutes
- **THEN** GitHub cancels the job with its standard timeout error rather than
  letting it run to the 6-hour default
