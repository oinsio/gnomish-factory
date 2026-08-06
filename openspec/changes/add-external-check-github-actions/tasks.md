# Tasks: add-external-check-github-actions

Prerequisites: add-sandbox-core is proposed but **not yet implemented**
(its sections 5 — attempt-commit round protocol — and 8 — findings funnel,
pin-check guard — exist only as `openspec/changes/add-sandbox-core`
artifacts, not in `src/`). This change proceeds ahead of that landing,
using adapter-local, explicitly-marked stand-ins for the four pieces it
would otherwise depend on: token resolution (5.5), the findings funnel cap
(4.1), the attempt-commit-carrying workspace (5.2), and the pin-check guard
(5.1, adapter side only, not exercised end-to-end). Each stand-in is
documented as provisional in its class javadoc and in the delta specs
under `specs/github-external-check/spec.md`, and is expected to be
replaced, not re-verified, once add-sandbox-core lands. The base-branch
trust anchor remains only a documented assumption until add-sandbox-core's
guard exists to enforce it.

## 1. Spike: platform parity for E2E

- [x] 1.1 Spike Gitea Actions REST parity: runs by head SHA, run
  conclusion, job list, job logs; record the go/fallback decision for
  section 7 under Q1 (M1)

## 2. Shared GitHub plumbing

- [x] 2.1 Extract GithubHttpClient, retry config, conditional-request cache
  and auth handling into the shared GitHub plumbing package; the tracker
  adapter consumes it with behavior unchanged — existing tracker specs stay
  green (FR7)
- [x] 2.2 Move/extend plumbing specs with the extraction; no duplicated
  retry/ETag tests remain (FR7)

## 3. GHA external check adapter

- [x] 3.1 Run query by head SHA + checkId workflow with latest-attempt
  selection; WireMock specs incl. unrelated workflows and re-run
  supersession (FR1, FR5)
- [x] 3.2 Verdict mapping: success → Pass, other/unknown conclusion → Fail
  (fail-closed), absent run or no conclusion → Running; WireMock specs
  (FR2)
- [x] 3.3 Platform-authored-only proof: forged token-created status
  alongside a red conclusion yields Fail; status endpoints are never
  queried (FR3, M2)
- [x] 3.4 CannotVerify classification for network errors, 5xx and rate
  limit, with reason; Resilience4j on the poll call (NFR-R1)
- [x] 3.5 Token via SecretsProvider; read scopes documented; token absent
  from logs, findings and CannotVerify details (FR8, NFR-S1)
- [x] 3.6 Fail-fast CannotVerify for client-side rejections that no retry
  resolves — 401/403/404 and other non-2xx — with a status-specific,
  actionable reason; error body never parsed as an empty runs listing
  (NFR-R3)

## 4. Findings and observability

- [x] 4.1 On Fail: failed jobs/steps + log tails within funnel caps, routed
  through the funnel; truncation noted (FR6, NFR-C1)
- [x] 4.2 Poll outcome logging with run id/URL; run link carried into the
  tracker report (NFR-O1, UX1) — **partial**: only the Fail leg carries
  the URL into the tracker report (via finding details); the Pass leg has
  no field to carry it (`PollStatus.Pass` is a bare marker) and only logs
  it. Follow-up tracked as proposal.md Q4 / add-sandbox-core Q4.

## 5. Pin and contract

- [x] 5.1 Adapter contributes the checkId workflow file to the pin set,
  unioned with law-declared paths; spec: early substitution caught at the
  point of use (FR4) — adapter side only: the pin-check guard that unions
  and enforces this (add-sandbox-core FR16/D10) is not implemented yet, so
  the union and the "caught at the point of use" scenario are documented
  as provisional in GithubCheckPinPaths' javadoc, not tested end-to-end
- [x] 5.2 The adapter passes ExternalCheckClientContract; stateless
  re-poll/takeover case (NFR-R2)

## 6. Per-check timeout class (engine + config)

- [x] 6.1 Pipeline-config: optional timeout-class field on the external
  check declaration (`quality` default | `infrastructure`); unknown value
  is a located validation error; loader specs (FR9)
- [x] 6.2 Engine: at the poll deadline classify per the declared class —
  `quality` → Fail with timeout finding (unchanged), `infrastructure` →
  CannotVerify naming the elapsed timeout, escalation without burning an
  attempt; specs for both branches and for the undeclared default (FR9)

## 7. E2E on a live platform (per 1.1 outcome)

- [x] 7.1 Testcontainers Gitea + Actions runner fixture: repo with the
  reference pipeline, a workflow, push of the attempt commit triggers a run
  (M1)
- [x] 7.2 E2E: a stage with the CI check passes green and fails red
  end-to-end, findings land in the report; zero manual steps (M1, G1, G4)
- [x] 7.3 Fallback if 1.1 finds no parity: scripted WireMock platform E2E;
  gap recorded under Q1 — **not applicable**: 1.1 found full parity (GO),
  no fallback needed

## 8. Gates and docs

- [x] 8.1 Traceability grep: every FR/NFR/UX of this change has an
  implementing entity in code or specs
- [x] 8.2 Coverage and mutation per testing.md on new Java production code
  (M3)
- [x] 8.3 Operator docs: token scope, base URL config, CI hygiene for the
  residual threat (minimal CI token permissions, no privileged secrets
  reachable from gnome branches), linked to add-sandbox-core task 9.5
  guidance; when to class a check's timeout as `infrastructure` (slow
  runner queues) and the escalation trade-off (UX2, NG6, FR9) — docs
  written, but UX2 itself is unmet: no factory wiring exists for the base
  URL config the docs describe. See 8.4.
- [ ] 8.4 **Follow-up, not done here**: wire the adapter into the factory —
  a `GithubCheckExternalClient` construction entry point (analog of
  `GithubTrackerAdapterFactory`: a YAML key for the base URL, resolving
  the token via `SecretsProvider`) that injects the client into the stage
  engine. Today no configuration enables this adapter (UX2 unmet, G1 only
  proven at the E2E-harness level). **Deferred to add-sandbox-core**
  (tracked there as Q5 / task 8.4's guard-wiring work), since that change
  already wires the pin-check guard around every `ExternalCheckClient`
  assembly.
