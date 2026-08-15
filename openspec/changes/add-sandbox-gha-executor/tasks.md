# Tasks: add-sandbox-gha-executor

Order follows the migration plan (design): adapter mechanics against a
mocked GitHub API first, then the workflow template and harvest on a
live profile, then credential verification, crash-safety and docs
last. Requires change A (`add-sandbox-core`) implemented; independent
of changes C and D. Live-profile E2E is gated on a dedicated test
repository (Q5).

## 1. Adapter core (mocked API)

- [ ] 1.1 Factory config surface: `gha` adapter id, repository/workflow reference, poll intervals, API budget shared with the tracker port (FR1, FR9)
- [ ] 1.2 Dispatch/poll exec: `workflow_dispatch` with command + branch inputs, conclusion → exit code mapping, incremental log delivery; conclusions only from the workflow-run API (FR1, FR7, D1, D8)
- [ ] 1.3 Run bookkeeping: factory metadata stamped on runs, re-associate/cancel on restart, dispose cancels in-flight, startup cancels orphans (FR9, D6)
- [ ] 1.4 Passport: fresh-per-exec, Docker yes, best-effort egress, direct registries, no snapshots, GitHub infrastructure; reconciliation refusal specs (FR3, FR8, D2, D5)
- [ ] 1.5 Findings funnel applied to run logs/outputs (size caps, sanitization, fenced publication) (FR7)
- [ ] 1.6 Contract suite green with fresh-per-exec passport semantics (WireMock GitHub API) (M1)

## 2. Workflow template and harvest

- [ ] 2.1 Shipped workflow template: minimal `permissions` (contents: read), checkout of the task branch as data, command step, bundle-artifact upload step (FR2, FR4, FR6, D4)
- [ ] 2.2 Bundle harvest: artifact download, fixed-refspec fast-forward apply, idempotent re-apply, factory-side push (FR5, D3)
- [ ] 2.3 Decide exec granularity from quota/latency measurements; record in design notes (Q2, D1)
- [ ] 2.4 Live-profile E2E: Docker-needing check completes on a runner; branch arrives via bundle; no push credential on the runner (M2, Q5)
- [ ] 2.5 Live-profile E2E: gnome-branch workflow modification has no effect and is pin-check-flagged (M3)

## 3. Credential floor

- [ ] 3.1 Repository checklist docs: Actions read-only default token, no privileged secrets on workflows reachable from `gnomish/*` branches, protected GitHub Environment (default-branch-only deployment policy) for any OIDC credential, template installation; each step verifiable (FR11, UX1)
- [ ] 3.2 E2E assertion: run token is read-only, no privileged secret access, OIDC credentials expire and are mintable only via the protected environment (M4, NFR-S1)
- [ ] 3.3 harden-runner decision: template default vs docs-only opt-in; record verdict (Q4, D5)

## 4. Robustness, cost, docs

- [ ] 4.1 Crash test: kill with run in flight → re-associate or cancel+re-dispatch, no double apply (M5, NFR-R2)
- [ ] 4.2 API quota discipline under parallel tasks: rate limits, backoff, poll-starvation guard (FR9, NFR-P1)
- [ ] 4.3 Cost reporting: run counts, minutes, URLs in the task report (NFR-C1, NFR-O1, UX3)
- [ ] 4.4 Docs: honest positioning (check-heavy stages, weakest egress, what a compromised run can/cannot reach), streaming-fidelity note, binding guidance (UX4, Q3)
