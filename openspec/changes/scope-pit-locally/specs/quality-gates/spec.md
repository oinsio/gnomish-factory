# Spec Delta: quality-gates

## MODIFIED Requirements

### Requirement: Scoped mutation target
Each module's `pitest` SHALL target a mutation scope the build computes from one
owner. With no `pitScope` property the scope is the branch's changes: the
module's production classes whose source files differ, in the working tree
(committed, staged, unstaged and untracked) against the scope base. `-PpitScope=all`
or requesting `pitestAll` selects the module's whole production tree; an explicit
comma-separated glob list keeps the subset the module owns. A module whose
resolved scope is empty skips its gate cleanly — its mutation task, its verdict
task and its report finalizer together, so a report an earlier run left in the
module's build directory is never judged. The scope is obtained through a
configuration-cache-validated read, so an edit between two builds re-resolves it;
its computation runs `git` only — never the test suite, never class files.
<!-- implements FR1, FR4, FR5, NFR-P1, NFR-R2, NFR-R3, NFR-R4, UX1 of scope-pit-locally -->
<!-- implements FR1, FR5 of scope-pit-to-changed-files -->
<!-- implements FR11 of split-into-modules -->

#### Scenario: Default run mutates the branch's changed classes
- **WHEN** `./gradlew check` runs with no `pitScope` property on a branch that
  changed production files in some modules
- **THEN** each of those modules' PIT run targets exactly the classes derived
  from its changed files, uncommitted edits included
- **AND** every module the branch did not touch skips its mutation task
- **AND** the 100% mutation-score gate and `pitestVerifyAllKilled` apply to the
  scoped set

#### Scenario: Whole tree on request
- **WHEN** `./gradlew check -PpitScope=all` or `./gradlew pitestAll` runs
- **THEN** every module mutates its full production package tree
- **AND** the union of module scopes equals the full production tree

#### Scenario: Property narrows the mutation scope
- **WHEN** a module's `check` runs with `pitScope` set to one or more class globs
- **THEN** PIT mutates only the classes matching those globs that the module owns
- **AND** the module skips its gate when none of them is mutable there

#### Scenario: No resolvable base preserves full-project mutation
- **WHEN** `./gradlew check` runs without the `pitScope` property and no scope
  base can be resolved (no default-branch ref, a root commit, not a git checkout)
- **THEN** every module mutates its full production package tree — the fallback
  is the whole tree, never an empty scope

#### Scenario: Empty scope is a clean pass
- **WHEN** a module's resolved scope holds no mutable production class
- **THEN** that module's mutation task is skipped and the build succeeds
- **AND** the build does NOT fail with PIT's "No mutations found" error
- **AND** a `mutations.xml` left in that module's build directory by an earlier
  run is not re-judged: the verdict task and the report finalizer skip with the
  mutation task

#### Scenario: An edit between two builds re-resolves the scope
- **WHEN** `./gradlew check` runs twice with no edit in between, then a
  production file is edited and it runs a third time, with the configuration
  cache on
- **THEN** the second build reuses the cached configuration and the same scope
- **AND** the third build re-obtains the scope and the edited class is in it

#### Scenario: Scope computation is git-only
- **WHEN** the scope is computed in branch mode
- **THEN** at most three `git` processes run and no test task or class file is
  read; in whole-tree mode via the property, none runs

#### Scenario: The operator's git configuration cannot narrow the scope
- **WHEN** the operator's global git configuration ignores a path pattern that
  matches an untracked production class, or quotes non-ASCII paths
- **THEN** the untracked class is still scoped and the non-ASCII path is mapped
  to its class
- **AND** the build's `git` processes take no optional index lock, so a
  concurrent `git status` on the same checkout is not refused

### Requirement: Continuous integration
A CI workflow SHALL run `./gradlew check` on every branch push and pull request
and SHALL publish JaCoCo and PIT reports and a Gradle profile report as build
artifacts. A tag push SHALL NOT start the check workflow: the release workflow
judges the run the tagged commit already has on `main`, and a second run for the
same commit would waste the budget and shadow that verdict. The workflow SHALL
pass no mutation scope: the build's own scope owner narrows the gate to the
branch's changes exactly as a local run does, and the whole-tree gate is the
scheduled run's. The CI build job SHALL NOT declare a per-job timeout: it runs
to completion under GitHub's 6-hour default, and superseded in-flight runs for
the same ref SHALL be cancelled by workflow concurrency. Every other CI workflow
job that supports a per-job timeout SHALL set `timeout-minutes: 30`; a job whose
reusable-workflow form forbids a per-job timeout is exempt.
<!-- implements FR5, FR7 of scope-pit-locally -->
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
- **WHEN** the check workflow runs on a branch push
- **THEN** its Gradle invocation carries no `pitScope` value and no step
  resolves a diff base or a changed-file list
- **AND** the mutation gate's scope equals what `./gradlew check` on that tree
  resolves locally

#### Scenario: Diff with no production changes still passes
- **WHEN** a branch changes only docs or CI configuration
- **THEN** the CI mutation run targets no classes and passes without a "No
  mutations found" failure

#### Scenario: Reports are downloadable
- **WHEN** a CI run completes (pass or fail)
- **THEN** JaCoCo and PIT reports and the Gradle profile report are attached as
  artifacts

#### Scenario: A long build run is not cancelled
- **WHEN** the CI build job runs longer than 30 minutes
- **THEN** it keeps running and reports its real pass/fail verdict rather than being cancelled by a per-job timeout

#### Scenario: A superseded build run is cancelled
- **WHEN** a new commit is pushed to a ref whose CI build job is still running
- **THEN** the in-flight run for that ref is cancelled by workflow concurrency

#### Scenario: A hung job is bounded by the timeout
- **WHEN** a CI job other than the build job, one that supports a per-job timeout, runs longer than 30 minutes
- **THEN** GitHub cancels the job with its standard timeout error rather than letting it run to the 6-hour default

## ADDED Requirements

### Requirement: Mutation scope base
The scope base SHALL be the merge base of `HEAD` with the default branch
(`main`, else `origin/main`). When that merge base is `HEAD` itself, the base
SHALL be `HEAD`'s first parent. When no base can be resolved — no default-branch
ref, a root commit, not a git checkout — the scope SHALL be the whole tree.
Resolution failures SHALL never narrow the scope.
<!-- implements FR2, NFR-R1 of scope-pit-locally -->

#### Scenario: Feature branch diffs against its merge base
- **WHEN** the build runs on a branch that forked from `main` and `main` has
  since moved
- **THEN** the scope base is the merge base, so classes changed only on `main`
  are not scoped in

#### Scenario: The default branch diffs against its first parent
- **WHEN** the build runs on `main` (merge base equals `HEAD`)
- **THEN** the scope base is `HEAD^`, so a squash or merge commit's own changes
  are mutated

#### Scenario: No resolvable base widens to the whole tree
- **WHEN** neither `main` nor `origin/main` exists, or `HEAD` has no parent, or
  the project directory is not a git checkout
- **THEN** every module mutates its whole production tree
- **AND** the scope line names the fallback reason

### Requirement: Test changes widen the mutation scope
A changed file under a module's test source tree SHALL widen that module's scope
to its whole production tree. A changed file under the shared test-fixtures
module's sources SHALL widen every module's scope to its whole production tree.
A deleted or renamed-away production file SHALL contribute no class.
<!-- implements FR3 of scope-pit-locally -->

#### Scenario: A weakened spec is caught
- **WHEN** a branch changes only a test file in one module (for example deletes
  an assertion from a killing spec)
- **THEN** that module's whole production tree is mutated and the mutant the
  assertion killed survives, failing the gate

#### Scenario: A shared fixture change mutates everything
- **WHEN** a branch changes a file under the shared test-fixtures module
- **THEN** every module mutates its whole production tree

#### Scenario: A deleted class contributes nothing
- **WHEN** a branch deletes a production source file and changes nothing else in
  that module
- **THEN** that module's mutation task is skipped

### Requirement: Mutation scope is announced
The build SHALL log one lifecycle line per build naming the scope mode
(`branch`, `all`, `explicit`), the resolved base commit, the changed-class
count per module, and any widening or fallback reason. The line SHALL be logged
even when the scope leaves every module's mutation task skipped.
<!-- implements NFR-O1, UX2 of scope-pit-locally -->

#### Scenario: A run that mutates nothing says so
- **WHEN** a scoped run resolves its base and no module has a changed class
- **THEN** the log still carries the scope line, naming the base and that every
  module was skipped

#### Scenario: A branch run explains itself
- **WHEN** a scoped run resolves its base
- **THEN** the log carries the base commit, the per-module class counts, and the
  widening reason for any module widened by a test change

#### Scenario: A fallback is visible
- **WHEN** the base could not be resolved
- **THEN** the log line says the whole tree is mutated and why

### Requirement: Scheduled whole-tree mutation run
A scheduled CI workflow SHALL run the whole-tree gate on `main` nightly and on
manual dispatch, SHALL upload the PIT reports, and on failure SHALL create a
tracker issue — or comment on the still-open issue from a previous run — naming
the failing modules and linking the run. It SHALL skip when `main` is unchanged
since the previous successful scheduled run, through a mechanism that reads no
other run's artifacts. Its token SHALL carry only `contents: read` and
`issues: write`.
<!-- implements FR6, NFR-S1, NFR-C1, UX4 of scope-pit-locally -->

#### Scenario: Cross-file mutation rot is caught within a day
- **WHEN** a mutant in an unchanged class stops dying because of a change
  merged elsewhere
- **THEN** the next scheduled run fails on that module and an issue names it
  with a link to the run and its PIT report artifact

#### Scenario: A repeat failure updates the open issue
- **WHEN** the scheduled run fails while the issue from a previous failure is
  still open
- **THEN** a comment is added to that issue instead of a second issue being
  opened

#### Scenario: An unchanged main is not re-mutated
- **WHEN** the scheduled run starts and `main` is at the commit the previous
  successful scheduled run verified
- **THEN** the run skips the gate and reports success
