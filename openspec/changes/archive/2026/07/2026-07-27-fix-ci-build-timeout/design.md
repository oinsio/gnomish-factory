# Design: fix-ci-build-timeout

## Context

Driven by FR1, FR2, NFR-C1 and NFR-O1 from `proposal.md`. The `ci.yml` build job
carries `timeout-minutes: 10`, a value chosen for the initial skeleton. The full
`./gradlew check` (Spock + JaCoCo + PIT + Spotless + static analysis) now approaches
that cap as the codebase grows, risking a green build cancelled purely on wall time.
The original 10-minute figure came from `scope-pit-to-changed-files` (NFR-C1), whose
change is archived and immutable — so the value must be re-stated by this change, not
edited in place.

## Decisions

**D1 — Raise the timeout, do not remove it.** Set `timeout-minutes: 30` rather than
deleting the line. *Rationale:* deleting the line falls back to GitHub's 360-minute
default, so a hung job (e.g. a deadlocked test) could burn 6 hours of Actions minutes
before being killed (NFR-C1). A finite cap keeps runaway protection; 30 minutes gives
roughly 3× the current headroom, comfortably above the observed `check` wall time while
staying far below the default. *Alternative rejected:* removing `timeout-minutes`
entirely — simplest edit, but drops the cost guardrail the requirement exists to give.

**D2 — One uniform budget across all jobs that support a timeout.** Apply `30` to
`ci.yml`, `codeql.yml`, and `gitleaks.yml` alike. *Rationale:* a single number is
easier to reason about and audit (M2) than per-workflow tuning; each remaining cap is
still an effective runaway kill-switch well under the 6-hour default, even where the job
(gitleaks, codeql) rarely needs the headroom. *Alternative rejected:* per-workflow
values proportional to each job's typical runtime — marginally tighter, but adds values
to maintain for no real cost saving, since the cap only fires on a hang.

**D3 — Leave `osv-scan.yml` untouched.** *Rationale:* GitHub forbids `timeout-minutes`
on reusable-workflow jobs — already documented in that file — so there is no per-job cap
to raise (NG3). *Alternative rejected:* wrapping OSV in a non-reusable job just to add a
timeout — needless churn for a scanner that is not the growth concern.

## Risks / Trade-offs

- A genuinely hung build now runs up to 30 minutes before the timeout fires (was 10) →
  accepted: still bounded far below the 6-hour default, and the failure mode is a
  developer's hung test, caught the same run.
- The `check` wall time could one day exceed 30 minutes as the project keeps growing →
  mitigation: M1 tracks cancellations; if they recur for legitimately long runs, revisit
  the budget (or re-scope the gate) in a follow-up change rather than lifting the cap.

## Open Questions

<!-- none -->
