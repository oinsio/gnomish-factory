# Change: add-external-check-github-actions

## Why

The `external` check type of the stage Quality Control contract
(stage-description.md §6) has no production third party: the only shipped
`ExternalCheckClient` is the interactive console client, where a human plays
the platform. Two consequences:

- The QC leg "an independent platform confirms the result" is unbuilt as a
  class, even though the factory's trust story leans on it.
- `command` checks run without their compensating control. Their weak trust
  cells — the gnome edits the tests it is judged by, and the exit code is
  born inside a gnome-touched box — are meant to be backstopped by a verdict
  authored outside the gnome's authority. No adapter, no backstop.

add-sandbox-core narrows its FR16 to the pin-check guard and states the
adapter contract ("platform-authored verdicts only") for future adapters.
This change delivers the first real adapter: GitHub Actions, polling
workflow runs of the pushed attempt commit. It also switches on step 0 of
the Docker-inside ladder (NG5 / task 9.5 of add-sandbox-core):
Testcontainers-class suites run in CI instead of inside the box.

Depends on add-sandbox-core artifacts: unified findings funnel (FR15),
pin-check guard (FR16), attempt-commit verification semantics (FR21/D15),
`SecretsProvider` (FR18). Implement after those land. The base branch is
assumed reviewed-clean — the trust anchor stated in add-sandbox-core.

## What Changes

- **ADDED**: GitHub Actions adapter for the `ExternalCheckClient` port —
  polls workflow runs of exactly the attempt commit, consumes
  platform-authored conclusions only, feeds failures through the findings
  funnel.
- **ADDED**: shared GitHub HTTP plumbing package: HTTP client, retry,
  conditional-request cache lifted from the tracker adapter's internals;
  tracker behavior unchanged.
- **ADDED**: E2E against a live platform: Testcontainers Gitea with an
  Actions runner as the GitHub-compatible stand-in, preceded by an
  API-parity spike.
- **MODIFIED**: external poll timeout classification in the stage engine
  becomes per-check configurable — default stays a quality failure; a
  check may declare its timeout an infrastructure failure. Realizes the
  configurability promised by stage-description.md §7 and deferred by
  add-stage-engine NG6 "until a consumer exists": the CI check is that
  consumer (a starved runner queue must not burn quality attempts).

## Capabilities

### New Capabilities
- `github-external-check` — CI verification of a stage via GitHub Actions
  workflow runs.

### Modified Capabilities
- `stage-engine` — the external poll timeout classifies per the check's
  declared timeout class (default unchanged: quality failure).
- `pipeline-config` — the external check declaration accepts an optional
  timeout-class field.
- `github-tracker` — no spec change: internals move into the shared
  plumbing package with no behavior change.

## Impact

- New packages: `adapter/github` (shared plumbing), `adapter/check/github`
  (the adapter). `adapter/tracker/github` shrinks to tracker-only code.
- Ports unchanged: `ExternalCheckClient` and `PollStatus` stay as they
  are. The engine's poll loop changes only at the deadline (classification
  reads the check's declared timeout class); the external check
  declaration gains one optional manifest field.
- New E2E dependency: Gitea Actions runner container alongside the existing
  Gitea E2E remote.

## Goals

- G1: a stage can declare a CI check verified by a real platform end-to-end
  with no human in the loop.
- G2: the factory's verdict for an external check can only originate from
  the platform itself — nothing creatable with a repo-scoped token can
  produce a green.
- G3: a failing CI run gives the gnome actionable feedback (failed jobs +
  log tails) within funnel caps.
- G4: the Docker ladder's step 0 is usable: a stage can delegate its
  Testcontainers-class suite to CI.

## Non-Goals

- NG1: no webhooks — the factory has no inbound HTTP; polling only
  (stage-description.md).
- NG2: no other platforms (SonarQube, GitLab CI) — the contract must not
  preclude them; their adapters arrive in their own changes.
- NG3: no workflow dispatch or submission — the branch push is the only
  trigger (NG8 of add-stage-engine).
- NG4: no named reusable check definitions in pipeline config (declare
  once, reference from stages) — a separate pipeline-config feature (Q2).
- NG5: no automatic parsing of local `uses:` references into the pin set —
  the user declares extra paths; parsing is future hardening.
- NG6: no policing of non-definition workflows: a gnome-modified workflow
  outside any check's pin set still executes on the platform on push; that
  threat belongs to repository CI hygiene (operator docs), not to this
  adapter.

## Users & Scenarios

- U1: a project user declares an external check on a stage (`checkId` =
  workflow file path, interval, timeout, extra pin paths); a task passes
  the stage only when that workflow's run on the attempt commit concludes
  green.
- U2: the gnome breaks the build; the stage fails with failed-job names and
  log tails as findings; the retry works from that feedback.
- U3: the operator reading the tracker sees which platform produced the
  verdict and a link to the run.
- U4: an operator whose CI runner queue is slow declares the check's
  timeout class `infrastructure`: a run that never finished in time
  escalates as "cannot verify" instead of burning stage attempts on
  feedback-free retries.

## Requirements

### Functional

- FR1: the adapter SHALL implement `ExternalCheckClient` by querying
  workflow runs whose head commit is exactly the attempt commit and whose
  workflow matches the check's `checkId` (workflow file path); runs of
  other workflows or other commits SHALL be ignored.
- FR2: verdict mapping SHALL be: platform conclusion `success` → Pass; any
  other or unknown conclusion → Fail (fail-closed); no matching run, or a
  run without a conclusion → Running (the engine's timeout then
  classifies per the check's declared timeout class — FR9).
- FR3: the adapter SHALL derive verdicts exclusively from workflow-run
  conclusions authored by the platform; it SHALL never read check-run or
  commit statuses or any state creatable with a repo-scoped token (the
  adapter contract stated by add-sandbox-core FR16).
- FR4: the adapter SHALL contribute the `checkId` workflow file to the
  check's pin set; user-declared pin paths from the stage law are unioned
  in by the pin-check guard (add-sandbox-core FR16).
- FR5: when multiple runs match the same workflow and attempt commit, the
  latest run attempt SHALL win.
- FR6: on Fail the adapter SHALL produce findings naming the failed jobs
  and steps plus the tail of each failed job's log, routed through the
  unified findings funnel (add-sandbox-core FR15).
- FR7: the GitHub HTTP plumbing (client, retry, conditional-request cache,
  auth handling) SHALL be shared between the tracker adapter and this
  adapter from one package; tracker behavior SHALL be unchanged by the
  move.
- FR8: the adapter's token SHALL be resolved through `SecretsProvider`
  (add-sandbox-core FR18) and SHALL never appear in logs or findings.
- FR9: an external check declaration MAY declare its timeout failure
  class — `quality` (default: unchanged behavior, a timeout burns a stage
  attempt) or `infrastructure` (a timeout classifies as CannotVerify: no
  attempt burned, escalation with a "cannot verify" report per
  stage-description.md); the engine SHALL classify a poll timeout per the
  declared class, and an unknown class value SHALL be a located
  configuration error.

### Non-Functional

- NFR-R1: network errors, 5xx and rate-limit responses SHALL classify as
  CannotVerify — an infrastructure failure of the check that burns no
  stage attempt (stage-description.md).
- NFR-R2: polling SHALL be stateless and idempotent: after a crash or
  takeover any instance re-polls the same attempt commit and observes the
  same run set; no poll state is persisted.
- NFR-O1: every poll SHALL log its outcome with the run identifier;
  Pass/Fail records SHALL carry the platform run URL for the tracker
  report.
- NFR-C1: log retrieval SHALL respect the funnel size caps
  (add-sandbox-core NFR-C1), and polling SHALL use conditional requests
  where the API allows, to conserve rate limit.
- NFR-S1: the token SHALL need read scope only for run and log queries;
  scope requirements are documented for the operator.

## Operator Experience Criteria

- UX1: a CI failure in the tracker reads as: check name, failed
  jobs/steps, fenced log tails marked as untrusted machine output, and a
  link to the run.
- UX2: enabling the adapter needs no factory configuration beyond the
  token and (for GitHub Enterprise / Gitea) a base URL; everything else
  lives in the stage declaration.

## Success Metrics

- M1: the E2E reference pipeline with a CI-verified stage completes
  against a live platform (Gitea Actions container) with zero manual
  steps.
- M2: the contract suite proves G2: a forged token-created status
  alongside a red run conclusion yields Fail.
- M3: coverage and mutation targets per testing.md hold on all new Java
  production code.

## Open Questions

- Q1: Gitea Actions REST parity for "runs by head SHA" and job logs —
  settled by the spike (task 1.1); on insufficient parity the live E2E is
  deferred, the E2E falls back to a WireMock-scripted platform, and the
  gap is recorded here.
- Q2: named reusable check definitions in pipeline config — worth a
  separate change?
- Q3: which platform is next (SonarQube quality gate?) — informs how much
  of this adapter generalizes into shared external-check plumbing.
