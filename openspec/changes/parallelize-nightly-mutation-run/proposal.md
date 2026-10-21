# Proposal: run the nightly whole-tree mutation gate as one job per module

## Why

The nightly whole-tree mutation run (`pitest-nightly.yml`, D7 of `scope-pit-locally`) runs
`./gradlew check -PpitScope=all` in one job on a 4-core `ubuntu-latest` runner: 3 h 51 min on
2026-10-20. With `narrow-branch-mutation-scope` the nightly becomes the only whole-tree owner, so
its verdict is the one that catches a weakened test or fixture, and a four-hour run that reports
at midday is a slow loop for a morning fix. The run's cost is a sum of unequal parts, and
`MutationEngineLock` serializes the modules on purpose inside one build: on the 14-core reference
machine (2026-10-12) `:adapters:git` took 2.8 min of coverage and 17.5 min of mutation,
`:application` 7.5 min, `:bootstrap` 7 min of coverage alone, and the other fifteen modules 0 s to
4 min each. On the CI runner the sum is the night; the largest term, scaled the same way, is about
an hour.

The repository is public, so standard-runner minutes are free and up to 20 jobs run at once. One
job per module turns the sum into its largest term: the nightly's verdict arrives in roughly a
quarter of the time, with the same gate, the same reports and the same tracker issue.

## What Changes

- **MODIFIED** — the nightly workflow runs the mutation gate as a matrix: one job per module
  that applies the mutation conventions, each running that module's `pitest` and
  `pitestVerifyAllKilled` with `-PpitScope=all`, each uploading its own `pit-report-<module>`
  artifact and its Gradle log as an artifact.
- **ADDED** — the build enumerates its mutation modules: one root task prints, as JSON, the
  project paths of every module whose build applies the mutation conventions. The workflow's
  matrix is that output; no module list is written in YAML.
- **MODIFIED** — the verified marker (`nightly-verified-<sha>`) is written by one final job that
  runs after every matrix job and only when all of them succeeded; the failure report runs once,
  from that final job, over the logs the matrix jobs uploaded, so two failing modules produce one
  issue comment rather than racing to create two issues.
- **MODIFIED** — `scripts/nightly-mutation-issue.sh` accepts the concatenated logs of several
  jobs and names the per-module report artifacts; its body no longer says `./gradlew check
  -PpitScope=all`.
- **MODIFIED** — the nightly runs the mutation tasks only, not each module's whole `check`: the
  push run of the merge commit already ran every module's tests, and PIT's coverage pass runs the
  covering specs again anyway. This revisits the answer to Q2 of `scope-pit-locally` ("`check`
  rather than `pitestAll`: one run, one complete artifact set, one place to link") — the one
  place to link is now the run, and the artifact set is one per module.

Permissions (`contents: read`, `issues: write`, nothing job-level), the schedule, the manual
dispatch, the verified-marker skip and the issue-or-comment rule are unchanged.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `build-system/quality-gates`: "Scheduled whole-tree mutation run" is modified — the run is one
  job per mutation module, its verdict and its verified marker come from the aggregate of those
  jobs, and the failure report is one per run; a new requirement "Mutation modules are enumerated
  by the build" states that the set of modules the nightly runs is read from the build, not
  written in the workflow.

## Goals

- **G1** — The nightly's wall time on `ubuntu-latest` falls from 3 h 51 min to about the largest
  module's run: under 90 minutes.
- **G2** — The gate is unchanged: every module that applies the mutation conventions is mutated
  over its whole production tree with the 100 % threshold and `pitestVerifyAllKilled`; a module
  added to the build joins the matrix with no workflow edit.
- **G3** — A red night is still one tracker issue (or one comment), naming every failed module
  task with its reason, with a link to the run and to each failed module's report artifact.
- **G4** — The verified marker is written only for a commit every module passed, and never when
  a job was cancelled or skipped.

## Non-Goals

- **NG1** — No change to `ci.yml`: the push run stays one job running `check` with the branch
  scope. **Deferral with a condition**: the change that re-introduces a whole-tree verdict on a
  branch (the pickup condition of NG1 of `narrow-branch-mutation-scope`) applies this matrix to
  `ci.yml`, or records why the measured per-module time makes that unaffordable there too.
- **NG2** — No grouping of small modules into shared jobs: a hand-kept grouping by cost is a list
  that drifts, and the setup a small module's job pays (a cached git build, a JDK, Gradle) is
  minutes on free runners. Pickup condition: a plan whose concurrent-job limit the matrix exceeds,
  or a measured setup cost above the smallest modules' own run.
- **NG3** — No change to `MutationEngineLock`, `threads`, the heavy-JVM budget or any PIT engine
  setting (NG1 of `adapt-build-load-to-hardware`, NG3 and NG6 of `scope-pit-locally` stand);
  parallelism is between jobs, each still one build with one mutator.
- **NG4** — No change to the mutation scope's modes or to `CiScopeOwnerSpec`'s rule that a
  workflow passes `pitScope` as `all` or not at all.
- **NG5** — No shared remote build cache: each job compiles what its module needs from the
  Gradle user-home cache `setup-gradle` restores; a cold cache costs each job a few minutes of
  compilation once a day. Pickup condition: a measured compile share above a third of a job's
  wall time.
- **NG6** — No retention or token change for artifacts: per-module reports keep the 14-day
  retention; the final job downloads the matrix jobs' artifacts through the run's own upload
  channel, which needs no `actions: read`.

## Users & Scenarios

- **U1 — The human the morning after a red night.** Opens the `nightly-mutation` issue: one
  comment lists `:adapters:git:pitestVerifyAllKilled` with its first failure line and links the
  run; the `pit-report-adapters-git` artifact holds that module's `mutations.xml`. The fix branch
  scopes that class by hand.
- **U2 — The maintainer adding a module.** Applies `test-conventions` in the new module's build
  file; the next nightly lists it in the matrix without a workflow edit, and the matrix spec in
  `:bootstrap` keeps the enumeration honest.
- **U3 — The human dispatching the nightly by hand after a fix.** Sees the matrix, waits for the
  largest module's job, and sees the issue close-able when the final job writes the marker.
- **U4 — The reader of the Actions page.** Sees one job per module named by its path, so the
  slow module is visible at a glance, which the single job never showed.

## Requirements

### Functional

- **FR1** — One root Gradle task prints, as a JSON array on standard output and nothing else,
  the project paths (`:adapters:git`) of every project that applies the mutation conventions, in
  a stable order, with the configuration cache on.
- **FR2** — The nightly workflow has a job that runs that task and exposes its output as the
  matrix of the mutation job; the mutation job runs `./gradlew <module>:pitest
  <module>:pitestVerifyAllKilled -PpitScope=all` for its module with `fail-fast: false`, so one red
  module does not cancel the others.
- **FR3** — Each mutation job uploads its module's PIT report directory as `pit-report-<module
  label>` (the project path without the leading colon, colons replaced by dashes) and its teed
  Gradle log as `nightly-log-<module label>`, both whether the job passed or failed.
- **FR4** — A final job, needing the matrix job and running whatever its result, writes the
  `nightly-verified-<sha>` cache marker only when the matrix job's result is `success`; on any
  other result (`failure`, `cancelled`) it downloads every `nightly-log-*` artifact of this run,
  concatenates them and runs the failure-report script once.
- **FR5** — `scripts/nightly-mutation-issue.sh` lists each failed task from a log that holds
  several Gradle runs, keeps naming each task once with the first failure line, and names the
  report artifact of each failed task's module (`pit-report-<label>`) in the body instead of one
  `pit-report` name; its body names the per-module invocation.
- **FR6** — The `verified` skip job, the schedule, the dispatch trigger, the concurrency group
  and the top-level permissions block are unchanged; no job declares its own permissions.
- **FR7** — `NightlyMutationIssueScriptSpec` and `CiScopeOwnerSpec` pin the new shape: the
  workflow still runs the report through the script, still passes `pitScope` only as `all`, and
  still grants exactly `contents: read` and `issues: write`.

### Non-Functional Performance

- **NFR-P1** — Nightly wall time ≤ 90 min on `ubuntu-latest` (G1), measured on the first three
  green nights after the change.

### Non-Functional Reliability

- **NFR-R1** — The verified marker is never written for a commit with a red, cancelled or
  skipped module job (G4).
- **NFR-R2** — A module whose scope leaves no mutable class (the `pitest` task skips with "the
  mutation scope leaves no mutable class in this module") is a green job, not a red one, exactly
  as it is a green module inside today's single run.
- **NFR-R3** — The enumeration task is deterministic and configuration-cache compatible: two
  runs on one tree print the same JSON, and a module added to `settings.gradle` with the
  conventions changes the output on the next run.

### Non-Functional Observability

- **NFR-O1** — Each matrix job is named by its module path, so the Actions page shows which
  module is slow or red; the final job's summary lists the per-module results.
- **NFR-O2** — The issue body names the failed tasks, their reasons, the run and each failed
  module's report artifact (G3).

### Non-Functional Security

- **NFR-S1** — The token stays `contents: read` plus `issues: write` at the top level; the final
  job's artifact download uses the run's own artifact channel and needs no `actions: read`
  (verified by `CiScopeOwnerSpec`'s job-level-permissions feature staying green).
- **NFR-S2** — The matrix is data from the build's own output; the workflow interpolates it only
  through `fromJSON` into `matrix`, never into a shell line as text.

### Non-Functional Cost

- **NFR-C1** — Total runner minutes rise by the per-job setup (about 18 × 3 min) and fall by the
  tests the single `check` ran twice; on a public repository the standard-runner minutes are not
  billed. The verified-marker skip still avoids a run on an unchanged `main`.

## Operator Experience Criteria

- **UX1** — The Actions page of a nightly run reads as a list of modules with their durations;
  the longest bar is the module to speed up next.
- **UX2** — The issue a red night opens needs no decoding: the failed task's module names the
  artifact to download.
- **UX3** — Adding a module needs no step in the developer guide beyond "apply
  `test-conventions`"; the guide's nightly section says the matrix comes from the build.

## Success Metrics

- **M1** — The first three green nightly runs after the change complete in ≤ 90 min each
  (baseline 3 h 51 min, 2026-10-20).
- **M2** — `./gradlew -q pitestModules` prints exactly the 18 project paths that apply
  `test-conventions` today, and a TestKit build with one extra module prints 19.
- **M3** — A dispatched run with one module made red by hand (a planted survivor on a scratch
  branch, or the script spec's canned two-module log) yields one issue comment naming that
  module's task and artifact, and no marker is written.

## Open Questions

- **Q1** — Whether the final job should also upload one merged `pit-report` artifact for
  readers used to the old name. Proposed answer: no; the issue body names the per-module
  artifact, and a merged copy is a second place for the same bytes.
