## Why

The project has grown well past its initial skeleton, and the full `./gradlew check`
run (Spock + JaCoCo + PIT + Spotless + static analysis) on the GitHub Actions runner
now risks bumping into the 10-minute job cap set in `ci.yml`. That cap was chosen when
the codebase was small; it is now a false negative waiting to happen — a green build
aborted purely because it ran a few minutes long. We raise the CI time budget while
keeping a per-job timeout so a genuinely hung build still cannot burn the GitHub
default of 6 hours of Actions minutes.

## What Changes

- **MODIFIED**: `ci.yml` build job `timeout-minutes` raised from `10` to `30`.
- **MODIFIED**: `codeql.yml` and `gitleaks.yml` job timeouts synchronized to the same
  `30`-minute budget, so no workflow job caps below the new build budget.
- **MODIFIED**: the `quality-gates` capability's Continuous-integration requirement —
  the CI time budget is re-stated as 30 minutes per job (was an implicit 10), keeping
  the runaway-protection intent while relaxing the value.
- Not removing the timeout: `timeout-minutes` stays present on every job it can be set
  on, so runaway protection is preserved.
- `osv-scan.yml` is unchanged: GitHub does not allow `timeout-minutes` on reusable-
  workflow jobs (already documented in that file), so it has no per-job cap to raise.

## Capabilities

### New Capabilities

<!-- none: this change modifies an existing capability's requirement only -->

### Modified Capabilities

- `quality-gates`: the Continuous-integration requirement's CI time budget is raised
  from an implicit 10 minutes to an explicit 30 minutes per workflow job.

## Goals

- **G1**: A passing build is never aborted by the job timeout as the codebase grows.
- **G2**: Runaway or hung jobs are still killed well below GitHub's 6-hour default.
- **G3**: One uniform, easy-to-reason-about time budget across CI workflow jobs.

## Non-Goals

- **NG1**: Not removing job timeouts entirely (would expose CI to 6-hour runaways).
- **NG2**: Not changing which gates run or their scope (no change to `./gradlew check`
  or the PIT diff-scoping logic).
- **NG3**: Not adding a timeout to `osv-scan.yml` — GitHub forbids it on reusable-
  workflow jobs.

## Users & Scenarios

- **U1**: A contributor pushes a branch; CI runs the full quality gate. As the project
  grows the run takes ~12 minutes and now completes green instead of being cancelled at
  the old 10-minute cap.
- **U2**: A misbehaving change introduces a hung test; the job is killed at 30 minutes
  with a timeout, not left running for hours.

## Requirements

### Functional

- **FR1**: The `ci.yml` build job SHALL set `timeout-minutes: 30`.
- **FR2**: The `codeql.yml` and `gitleaks.yml` jobs SHALL set `timeout-minutes: 30`, so
  no CI workflow job caps below the build budget.

### Non-Functional Cost

- **NFR-C1**: Every CI workflow job that can carry a per-job timeout SHALL keep one set,
  bounding a hung run to 30 minutes rather than the GitHub 6-hour default.

### Non-Functional Observability

- **NFR-O1**: Each `timeout-minutes` line SHALL carry a comment stating the budget and
  its runaway-protection rationale, so the chosen value is self-documenting.

## Operator Experience Criteria

- **UX1**: A CI job that exceeds 30 minutes fails with GitHub's standard timeout error,
  making the cause of the cancellation unambiguous in the run log.

## Success Metrics

- **M1**: Zero CI runs cancelled by the job timeout while the actual `./gradlew check`
  wall time stays under 30 minutes.
- **M2**: All CI workflow jobs that support `timeout-minutes` carry the value `30`.

## Open Questions

<!-- none -->
