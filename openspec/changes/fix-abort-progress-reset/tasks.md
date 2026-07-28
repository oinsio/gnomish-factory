# Tasks: fix-abort-progress-reset

TDD throughout (red → green → refactor); every FR gets at least one Spock spec
referencing it. PIT `targetClasses` cover the new Java only; 100% mutation
target (justify any ≥95% exception at the best-effort wiring boundary).

## 1. Port contract

- [ ] 1.1 Add `recordProgress(TaskRef)` to the `Tracker` port with a doc comment
      (Implements FR1 of fix-abort-progress-reset): records a durable-progress
      marker, leaves logical state and claim holder untouched (FR1)
- [ ] 1.2 Update `AbortFacts` Javadoc: count is "aborts strictly after the last
      durable-progress marker for the current claim" (FR3)
- [ ] 1.3 Extend the shared `Tracker` contract spec suite with a
      `recordProgress` round-trip case: record abort(s) → `recordProgress` →
      count resets to zero, observed by a fresh read; every adapter must pass
      it, zero exemptions (FR3, FR4, M1)

## 2. In-memory reference adapter

- [ ] 2.1 `CorrespondenceEntry.Kind`: add `PROGRESS` (FR2, UX1)
- [ ] 2.2 `TrackedTask.recordProgress(...)`: zero `abortCount`, null
      `lastAbortAt`, append a `PROGRESS` thread entry; spec the reset and the
      thread narration (FR3, D4)
- [ ] 2.3 `InMemoryTracker.recordProgress`: delegate under the store lock, no
      state/holder change; run the contract suite green (FR1, FR3)

## 3. GitHub adapter

- [ ] 3.1 `GithubMarkerKind`: add `PROGRESS` (wire value `progress`); parser
      round-trips it; spec unknown-kind tolerance is preserved (FR7 add-tracker,
      FR4)
- [ ] 3.2 `GithubStateWrites.recordProgress`: post the structural progress
      comment (hidden HTML + JSON: kind/instance/time/version), no label/state
      change; WireMock spec (FR1, FR4)
- [ ] 3.3 `GithubCommentBoundary`: count aborts strictly after the latest
      `PROGRESS` marker; fall back to the claim-streak logic when none; keep
      `PROGRESS` out of `latestBoundaryIndex`/`activeClaim` (FR3, D3)
- [ ] 3.4 `GithubAbortFactsReader` (feed): anchor the fold to the latest
      `PROGRESS` marker so `listReady` and `fetchTask` agree (FR3, D3)
- [ ] 3.5 `GithubTracker.recordProgress` wiring; run the contract suite against
      the WireMock-backed adapter, zero exemptions (FR1, M1)

## 4. Core emission at the round boundary

- [ ] 4.1 `RevocationCheckingAttemptPersistence`: once-per-run guard; after the
      delegate's durable `persist` and before the revocation check, call
      `tracker.recordProgress(ref)` on the first round only (FR2, D2)
- [ ] 4.2 Best-effort wrap: catch a `recordProgress` throw, log WARN with the
      ref, swallow it; the run proceeds unchanged (FR2, NFR-R1, NFR-O1)
- [ ] 4.3 Spec: first round emits once; rounds 2..n do not re-emit; a throwing
      `recordProgress` yields the same outcome as success (no abort/park)
      (FR2, NFR-R1)

## 5. End-to-end and verification

- [ ] 5.1 Take-lifecycle spec: two aborts → reclaim → durable round → abort →
      backoff/fuse decision sees count one (`Progress resets the counter`
      end-to-end) (FR3, NFR-C1, M1)
- [ ] 5.2 Grep-verify FR1–FR4/NFR coverage: every requirement has an
      implementing entity in code or tests (traceability rule)
- [ ] 5.3 Full check: `./gradlew check` green — Spotless, Error Prone/NullAway,
      JaCoCo, PIT at the mutation target; justify any documented exception
      (M2)

## 6. Supersession bookkeeping

- [ ] 6.1 Mark `add-tracker-port` task 5.3 as superseded by this change in a
      note (the reset half now lives here); do not edit archived content
- [ ] 6.2 Recommend a commit message referencing fix-abort-progress-reset and
      the FR IDs; human commits (never the agent)
