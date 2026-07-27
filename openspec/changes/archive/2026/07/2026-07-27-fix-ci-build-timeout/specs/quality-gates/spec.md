## MODIFIED Requirements

### Requirement: Continuous integration
A CI workflow SHALL run `./gradlew check` on every push and pull request and SHALL publish JaCoCo and PIT reports as build artifacts. On pull-request and branch runs the mutation gate SHALL be scoped to the production Java classes changed relative to the merge base with `main`, so the job completes within its time budget; whole-project mutation coverage is guaranteed by local/manual `./gradlew check`, not by CI. Every CI workflow job that supports a per-job timeout SHALL set `timeout-minutes: 30`, bounding a hung run to 30 minutes rather than the GitHub 6-hour default; a job whose reusable-workflow form forbids a per-job timeout is exempt.
<!-- implements FR1, FR2, NFR-C1 of fix-ci-build-timeout -->
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

#### Scenario: A hung job is bounded by the timeout
- **WHEN** a CI workflow job that supports a per-job timeout runs longer than 30 minutes
- **THEN** GitHub cancels the job with its standard timeout error rather than letting it run to the 6-hour default
