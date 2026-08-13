Supersedes: fix-ci-build-timeout (FR1 / NFR-C1 only — the CI build job's time cap)

## Why

The full `./gradlew check` gate (Spock + JaCoCo + PIT + Spotless + static analysis) no
longer fits into the 30-minute cap set on the `ci.yml` build job: green builds are being
cancelled by the timeout rather than by a real failure. Raising the number again only
moves the same false negative a few months out, because the gate's wall time grows with
the codebase. The build job gets no per-job cap at all and falls back to GitHub's 6-hour
default; the fast security workflows keep their 30-minute caps, so a runaway there is
still bounded.

## What Changes

- **REMOVED**: the `timeout-minutes: 30` line on the `ci.yml` `build` job — the job
  inherits GitHub's 6-hour default job timeout.
- **MODIFIED**: the `quality-gates` capability's Continuous-integration requirement —
  the blanket "every job that supports a per-job timeout SHALL set `timeout-minutes: 30`"
  rule now exempts the `ci.yml` build job, which SHALL carry no per-job timeout.
- `codeql.yml` and `gitleaks.yml` are unchanged: they keep `timeout-minutes: 30`.
- `osv-scan.yml` is unchanged: GitHub forbids `timeout-minutes` on reusable-workflow jobs.
- No change to which gates run, to the PIT diff-scoping logic, or to the concurrency
  cancellation that already kills superseded in-flight runs.

## Capabilities

### New Capabilities

<!-- none: this change modifies an existing capability's requirement only -->

### Modified Capabilities

- `quality-gates`: the Continuous-integration requirement's per-job timeout rule is
  narrowed — the `ci.yml` build job is exempt and carries no `timeout-minutes`.

## Goals

- **G1**: A passing build is never cancelled by a per-job timeout, however long the gate
  grows.
- **G2**: The cap on the security-scanning jobs is preserved unchanged.

## Non-Goals

- **NG1**: Not removing timeouts from `codeql.yml` / `gitleaks.yml`.
- **NG2**: Not changing which gates run or their scope (`./gradlew check`, PIT scoping).
- **NG3**: Not optimising the build's wall time — that is separate work.

## Users & Scenarios

- **U1**: A contributor pushes a branch whose full gate takes ~40 minutes; CI now runs to
  completion and reports the real pass/fail verdict instead of a timeout cancellation.
- **U2**: A contributor pushes a follow-up commit to the same branch; the superseded
  in-flight run is still cancelled immediately by the existing `concurrency` group, so an
  uncapped job does not accumulate wasted Actions minutes.

## Requirements

### Functional

- **FR1**: The `ci.yml` build job SHALL NOT declare `timeout-minutes`.
- **FR2**: The `codeql.yml` and `gitleaks.yml` jobs SHALL keep `timeout-minutes: 30`.

### Non-Functional Cost

- **NFR-C1**: Runaway spend on the uncapped build job SHALL stay bounded by the existing
  `concurrency` cancellation of superseded runs and GitHub's 6-hour default job timeout;
  no new cost control is introduced.

## Operator Experience Criteria

- **UX1**: A long build run in the Actions UI shows the gate still executing rather than
  a red "The job running on runner … has exceeded the maximum execution time" error.

## Success Metrics

- **M1**: Zero `ci.yml` runs cancelled by a per-job timeout.
- **M2**: `grep -c timeout-minutes .github/workflows/ci.yml` returns 0, while
  `codeql.yml` and `gitleaks.yml` each still return 1.

## Open Questions

<!-- none -->
