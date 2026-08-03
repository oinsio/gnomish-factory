# Tasks: add-external-check-github-actions

Prerequisites: add-sandbox-core is implemented through its sections 5
(attempt-commit round protocol) and 8 (findings funnel, pin-check guard);
the base-branch trust anchor is documented there.

## 1. Spike: platform parity for E2E

- [ ] 1.1 Spike Gitea Actions REST parity: runs by head SHA, run
  conclusion, job list, job logs; record the go/fallback decision for
  section 7 under Q1 (M1)

## 2. Shared GitHub plumbing

- [ ] 2.1 Extract GithubHttpClient, retry config, conditional-request cache
  and auth handling into the shared GitHub plumbing package; the tracker
  adapter consumes it with behavior unchanged — existing tracker specs stay
  green (FR7)
- [ ] 2.2 Move/extend plumbing specs with the extraction; no duplicated
  retry/ETag tests remain (FR7)

## 3. GHA external check adapter

- [ ] 3.1 Run query by head SHA + checkId workflow with latest-attempt
  selection; WireMock specs incl. unrelated workflows and re-run
  supersession (FR1, FR5)
- [ ] 3.2 Verdict mapping: success → Pass, other/unknown conclusion → Fail
  (fail-closed), absent run or no conclusion → Running; WireMock specs
  (FR2)
- [ ] 3.3 Platform-authored-only proof: forged token-created status
  alongside a red conclusion yields Fail; status endpoints are never
  queried (FR3, M2)
- [ ] 3.4 CannotVerify classification for network errors, 5xx and rate
  limit, with reason; Resilience4j on the poll call (NFR-R1)
- [ ] 3.5 Token via SecretsProvider; read scopes documented; token absent
  from logs, findings and CannotVerify details (FR8, NFR-S1)

## 4. Findings and observability

- [ ] 4.1 On Fail: failed jobs/steps + log tails within funnel caps, routed
  through the funnel; truncation noted (FR6, NFR-C1)
- [ ] 4.2 Poll outcome logging with run id/URL; run link carried into the
  tracker report (NFR-O1, UX1)

## 5. Pin and contract

- [ ] 5.1 Adapter contributes the checkId workflow file to the pin set,
  unioned with law-declared paths; spec: early substitution caught at the
  point of use (FR4)
- [ ] 5.2 The adapter passes ExternalCheckClientContract; stateless
  re-poll/takeover case (NFR-R2)

## 6. Per-check timeout class (engine + config)

- [ ] 6.1 Pipeline-config: optional timeout-class field on the external
  check declaration (`quality` default | `infrastructure`); unknown value
  is a located validation error; loader specs (FR9)
- [ ] 6.2 Engine: at the poll deadline classify per the declared class —
  `quality` → Fail with timeout finding (unchanged), `infrastructure` →
  CannotVerify naming the elapsed timeout, escalation without burning an
  attempt; specs for both branches and for the undeclared default (FR9)

## 7. E2E on a live platform (per 1.1 outcome)

- [ ] 7.1 Testcontainers Gitea + Actions runner fixture: repo with the
  reference pipeline, a workflow, push of the attempt commit triggers a run
  (M1)
- [ ] 7.2 E2E: a stage with the CI check passes green and fails red
  end-to-end, findings land in the report; zero manual steps (M1, G1, G4)
- [ ] 7.3 Fallback if 1.1 finds no parity: scripted WireMock platform E2E;
  gap recorded under Q1

## 8. Gates and docs

- [ ] 8.1 Traceability grep: every FR/NFR/UX of this change has an
  implementing entity in code or specs
- [ ] 8.2 Coverage and mutation per testing.md on new Java production code
  (M3)
- [ ] 8.3 Operator docs: token scope, base URL config, CI hygiene for the
  residual threat (minimal CI token permissions, no privileged secrets
  reachable from gnome branches), linked to add-sandbox-core task 9.5
  guidance; when to class a check's timeout as `infrastructure` (slow
  runner queues) and the escalation trade-off (UX2, NG6, FR9)
