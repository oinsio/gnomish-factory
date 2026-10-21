# Spec Delta

Layered on `build-system/quality-gates` as modified by `narrow-branch-mutation-scope`
(sequenced before this change): the MODIFIED requirement below is written over that delta's
text of "Scheduled whole-tree mutation run" and keeps its scenario "A weakened test or fixture
is caught within a day". If the sync order is ever inverted, that scenario and the
`G3, NFR-C1 of narrow-branch-mutation-scope` part of the Implements line must survive the merge.

## ADDED Requirements

### Requirement: Mutation modules are enumerated by the build
The build SHALL expose one task that prints, as a JSON array and nothing else on standard
output, the project paths of every module that applies the mutation conventions, in a stable
order. A workflow that runs the mutation gate per module SHALL take its module list from that
output and SHALL hold no module list of its own.
**Implements**: FR1, FR2, NFR-R3, NFR-S2, UX3 of parallelize-nightly-mutation-run

#### Scenario: The list is the modules that mutate
- **WHEN** the enumeration task runs on a build whose modules apply the mutation conventions
- **THEN** its output is a JSON array holding exactly those modules' project paths and no other
  project's, printable in quiet mode with nothing before or after it

#### Scenario: A new module joins without a workflow edit
- **WHEN** a module is added to the build and applies the mutation conventions
- **THEN** the next run of the enumeration task lists it
- **AND** the nightly workflow's matrix is read from that output, so the module is mutated on
  the next scheduled run with no change to the workflow file

#### Scenario: The output is stable under the configuration cache
- **WHEN** the enumeration task runs twice on an unchanged tree with the configuration cache on
- **THEN** both runs print the same array

## MODIFIED Requirements

### Requirement: Scheduled whole-tree mutation run
A scheduled CI workflow SHALL run the whole-tree mutation gate on `main` nightly and on
manual dispatch as one job per mutation module, each uploading its module's PIT report,
SHALL write the verified marker only when every module's job succeeded, and on any failure
SHALL create one tracker issue — or comment once on the still-open issue from a previous run
— naming the failing module tasks and linking the run and each failed module's report. It SHALL
skip when `main` is unchanged since the previous successful scheduled run, through a mechanism
that reads no other run's artifacts. Its token SHALL carry only `contents: read` and
`issues: write`.
**Implements**: FR6, NFR-S1, NFR-C1, UX4 of scope-pit-locally; G3, NFR-C1 of narrow-branch-mutation-scope; FR2, FR3, FR4, FR5, FR6, NFR-R1, NFR-R2, NFR-O2, NFR-S1 of parallelize-nightly-mutation-run
**Notes**: The per-module jobs run the mutation tasks only; the push run of the merge commit already ran every module's tests.

#### Scenario: Cross-file mutation rot is caught within a day
- **WHEN** a mutant in an unchanged class stops dying because of a change
  merged elsewhere
- **THEN** the next scheduled run fails on that module's job and an issue names
  that module's task with a link to the run and to that module's PIT report
  artifact

#### Scenario: A weakened test or fixture is caught within a day
- **WHEN** a merged branch changed a test source or a shared test fixture so
  that a mutant in a production class the branch did not change now survives
- **THEN** the branch's own gate did not mutate that class, and the next
  scheduled run fails on that class's module and reports it as the previous
  scenario does

#### Scenario: A repeat failure updates the open issue
- **WHEN** the scheduled run fails while the issue from a previous failure is
  still open
- **THEN** a comment is added to that issue instead of a second issue being
  opened

#### Scenario: An unchanged main is not re-mutated
- **WHEN** the scheduled run starts and `main` is at the commit the previous
  successful scheduled run verified
- **THEN** the run skips the gate and reports success

#### Scenario: Two red modules make one report
- **WHEN** two module jobs of one scheduled run fail
- **THEN** the run's one failure report names both modules' failed tasks, each with its first
  failure line and its report artifact
- **AND** exactly one issue is created, or one comment added, for the run

#### Scenario: A red module does not stop the others
- **WHEN** one module's job fails while the others are still running
- **THEN** every other module's job runs to its own verdict and uploads its report
- **AND** the verified marker is not written for that commit

#### Scenario: A cancelled or skipped module writes no marker
- **WHEN** the run ends with any module job cancelled or skipped rather than succeeded
- **THEN** the verified marker is not written, so the next scheduled run mutates the commit again

#### Scenario: A module with nothing to mutate is green
- **WHEN** a module's whole-tree scope leaves no mutable class, so its mutation task skips
- **THEN** that module's job succeeds and contributes no failed task to the report
