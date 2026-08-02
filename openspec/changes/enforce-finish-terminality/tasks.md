# Tasks: enforce-finish-terminality

## 1. Port shape and contract suite (TDD red)

- [ ] 1.1 Add the `finished` fact to `ReadyTask` and `TrackerTask` (FR1, design D2); every existing construction site passes an explicit `false` placeholder so the build stays green while the contract suite goes red
- [ ] 1.2 Add the decline operation to the `Tracker` port interface with javadoc pinning the contract: restore terminal status first, then post the core-supplied explanation; state-level no-op when already terminal — posting no explanation; explanation must not feed the returned/finished derivations (FR4, NFR-R1, design D3, D5)
- [ ] 1.3 Extend the contract chain with a finish-reopen link (new file — respect the per-file line cap): a `reopenFinished` seam (test-only simulation of the human moving a Finished task back to Ready, history intact, mirroring `returnToReady`), plus properties: finish-reopen → `returned == false` and `finished == true`; never-finished → `finished == false`; decline round-trip (terminal restored, gone from `listReady`, explanation visible); decline of an already-terminal task is a silent no-op (status and thread unchanged, no explanation posted); decline explanation is derivation-neutral (FR2, FR6) — EXPECTED RED until tasks 2.x/3.x

## 2. In-memory adapter

- [ ] 2.1 Derive `finished` from a `FINISH` correspondence entry in `listReady` and `fetchTask`; keep `returned` on PARK/STALE_CLAIM_REMOVED only (FR1, FR2)
- [ ] 2.2 Implement the decline operation: state → `Finished`, then a `NOTE` correspondence entry with the explanation — posted only when the state actually transitioned; an already-`Finished` task is a silent no-op with no NOTE (FR4, NFR-R1, design D3, D5)
- [ ] 2.3 Supply the `reopenFinished` seam in the in-memory harness/contract subclass; contract suite green on the in-memory adapter

## 3. GitHub adapter

- [ ] 3.1 Split the marker vocabulary (design D1): add `PARK` and `FINISH` to `GithubMarkerKind`, remove `REPORT`; `GithubStateWrites.park` writes PARK (still carrying `reason` as payload), `finish` writes FINISH; update `GithubMarkerSpec`/parser fixtures — a stale `"report"` wire value must fail loudly in `fromWireValue`
- [ ] 3.2 Re-point every REPORT consumer to the new kinds: the session-boundary lists in `GithubClaimComment.isBoundary` and `GithubClaimLease.latestBoundaryIndex` include PARK and FINISH (prefer one shared boundary predicate; same behavior — both end a session), `GithubParkReason` matches on kind PARK alone (no reason-presence inference), `FixtureSeeder` seeds PARK for parked fixtures; refresh the doc-only REPORT-convention mentions in the `GithubCommentBoundary` and `GithubTaskFetcher` javadocs; existing claim-lease boundary specs stay green on the new kinds
- [ ] 3.3 Derive the facts structurally (FR1, FR2): `returned` = PARK or STALE_CLAIM_REMOVED present, `finished` = FINISH present, in `GithubReturnedFactReader` (or a sibling reader); unit specs over parsed-marker fixtures including the finish-then-reopen and park-then-reopen threads
- [ ] 3.4 Wire the `finished` fact into `GithubFeedQuery` enrichment and the `fetchTask` path from the SAME comments fetch already performed — assert in a WireMock spec that no additional API call is made (NFR-P1)
- [ ] 3.5 Implement the GitHub decline write: label transition ready → delivered first, NOTE-kind marker comment only after a successful transition; WireMock specs for the happy path, the already-delivered no-op, and the transition-failed-no-comment ordering (FR4, NFR-R1, design D5)
- [ ] 3.6 Supply the `reopenFinished` seam in `GithubTrackerFixtureAdapter`; contract suite green on the GitHub adapter — the finish-reopen properties now prove adapter parity (FR6, M1)

## 4. Core policy, take, and serve

- [ ] 4.1 `FeedPolicy.selectClaimCandidates` excludes `finished` entries defensively (neither returned-priority nor fresh, no WIP interaction); spec with a mixed feed (FR3, design D4)
- [ ] 4.2 Feed cycle (serve and bare auto `take`): decline each observed `finished` entry before candidate selection; failures logged and left for the next poll cycle; specs cover decline-within-one-poll, decline-failure convergence, and the finished-entry-does-not-distort-WIP case (FR3, FR4, NFR-R2, NFR-R3, NFR-O1)
- [ ] 4.3 Explicit `take <ref>`: when `fetchTask` reports `finished == true` on a `Ready` task, refuse the mandate — run the decline, report a clear non-success CLI outcome; spec for the refusal and the exit outcome (FR5)
- [ ] 4.4 Compose the decline explanation text in core (one place): states the task is already finished and directs the human to open a new task or bug referencing it (UX1)

## 5. Verification and docs

- [ ] 5.1 Full test run; PIT over the changed production classes meets the project bar (M3)
- [ ] 5.2 Traceability grep: every FR1–FR6, NFR-R1–R3, NFR-P1, NFR-O1, UX1–UX2 of enforce-finish-terminality has at least one implementing entity in code or tests
- [ ] 5.3 Update the operator guide: what happens when a finished task is moved back to ready (the decline comment, the restored status, the new-task/bug guidance) and that rework always means a new task (UX1, UX2)
