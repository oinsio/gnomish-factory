# Design: remove-ci-build-timeout

## Context

Driven by FR1, FR2 and NFR-C1 from proposal.md. Today all four workflows carry a
`timeout-minutes: 30` cap except `osv-scan.yml`, whose reusable-workflow job form forbids
one. The cap was introduced by the archived `fix-ci-build-timeout` change as uniform
runaway protection; the build job has since outgrown it, so the uniformity is what has to
give.

## Decisions

**D1 — Remove the cap on the build job instead of raising it.** `ci.yml`'s `build` job
declares no `timeout-minutes` and inherits GitHub's 6-hour default.
*Rationale:* the gate's wall time grows with the codebase, so any finite number is a
future false negative that costs another change to fix; the 6-hour default plus the
existing `concurrency` cancellation already bound the realistic runaway cases (FR1,
NFR-C1).
*Alternative rejected:* raise the cap to 60/120 minutes — keeps the uniform-budget story
but re-introduces exactly the failure mode this change exists to remove.

**D2 — Keep `codeql.yml` and `gitleaks.yml` at 30 minutes.** Only the build job is
exempted; the security scans stay capped.
*Rationale:* those jobs are minutes-long and their runtime does not track the codebase
size, so 30 minutes remains generous there and preserves runaway protection where it is
free (FR2, NG1).
*Alternative rejected:* remove every cap for consistency — trades real protection on two
fast jobs for a cosmetic symmetry.

**D3 — Delete the line outright, with no replacement comment in `ci.yml`.** The rationale
lives in this change and in the `quality-gates` spec, not in the workflow file.
*Rationale:* the workflow already carries a dense comment header; a paragraph explaining a
setting that is not there adds noise at the point of use, and the spec is the place a
reader checks for the CI timeout rule.
*Alternative rejected:* keep an explanatory comment on the job — makes the absence
self-documenting, but duplicates the spec and dates quickly.

## Risks / Trade-offs

- A genuinely hung build burns up to 6 hours of Actions minutes instead of 30 →
  the `concurrency` group cancels it as soon as the next push lands on the same ref; a
  hang on an idle ref is caught by the run showing as still-running in the Actions UI.
- The spec's per-job timeout rule becomes conditional rather than blanket, so a future
  workflow job could be added with no cap by mistake → the requirement states the exemption
  explicitly as "the CI build job" only, and M2 gives a grep-checkable invariant.
- The active `split-into-modules` change carries its own delta of the same `quality-gates`
  requirement with the old blanket wording → whichever change archives second must be
  re-synced against the first; called out as a task.
