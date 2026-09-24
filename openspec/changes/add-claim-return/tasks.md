# Tasks: add-claim-return

> Sequencing: start only after `add-base-ref-resolution` is archived — it owns
> `FreshClaimBaseBinding`, `ResumeLawBinding`, and the `tracker-take`
> requirement this change layers on (design, Context).
> Signatures below are the post-`introduce-take-order` ones (rebased 2026-09-24):
> the take chain carries one `TakeOrder`, and the claim identity comes from it.

## 1. Port and contract (FR1, FR2, FR3, FR8)

- [ ] 1.1 Add `ClaimIdentity(holder, epoch)` and `ReturnResult { Returned | Mismatch(currentFacts) }` to `gnomish-plugin-api`, and `Tracker.returnToReady(TaskRef, ClaimIdentity, String reason)` with javadoc stating the fence, the no-op cases, and that `release` is unchanged; verify `:gnomish-plugin-api:compileJava` and the javadoc gate pass (FR1, FR2)
- [ ] 1.2 Implement the verb in `TrackerHealthTracker` (delegating under the health classification) and the plugin `SampleTracker`; verify the plugin sample build and its existing specs pass (FR1)
- [ ] 1.3 TDD `TrackerReturnContract` at the end of the contract chain (extends `TrackerReleaseContract`; both concrete specs repointed): round-trip to `Ready` plus a fresh claim by another instance, stale-holder no-op (after a reap and after a re-claim with a newer epoch), repeated-return no-op, vanished-task no-op, return-versus-removal race ×5 — red against both adapters before 2.x/3.x (FR1–FR3, FR8)

## 2. In-memory adapter (FR1, FR4)

- [ ] 2.1 Add `CorrespondenceEntry.Kind.CLAIM_RETURNED`; generalize `ClaimLeases.removeIfMatches` into one retirement routine taking a `Retirement` parameter (marker kind, comparison: observed footprint | own identity, marker text); `removeStaleClaim` and the new `returnToReady` each call it; verify `InMemoryTrackerContractSpec` green including the 1.3 properties (FR4)
- [ ] 2.2 Teach `TrackedTaskFacts` that `CLAIM_RETURNED` is a boundary entry (tenure ended; `IndexLagging` rule unchanged); verify the round-trip spec of the wire vocabulary covers the new constant and `InMemoryIndexRepairSpec` still passes (FR9)

## 3. GitHub adapter (FR1, FR4, NFR-R2, NFR-O2)

- [ ] 3.1 Add `GithubMarkerKind.CLAIM_RETURNED` with its wire value and human line "returned to ready by <instance>: <reason>"; verify the marker round-trip spec iterates every constant (FR1, NFR-O2)
- [ ] 3.2 Generalize `GithubStaleClaimRemoval` into `GithubClaimRetirement` taking the `Retirement` parameter; `removeStaleClaim` and `returnToReady` build one each; the own-identity comparison reads holder and epoch from the fresh claim comment; verify `GithubStaleClaimRemovalSpec` scenarios pass unchanged and `GithubTrackerContractSpec` is green including 1.3 (FR2, FR4)
- [ ] 3.3 Make the marker reader classify `CLAIM_RETURNED` as a claim boundary for the lease anchor and the `IndexLagging` rule; verify `GithubHistoryFactReaderSpec` and `GithubTaskFetcherSpec` cover a returned thread (FR9)
- [ ] 3.4 Add the return sequence to the GitHub kill-window fault-injection suite and to `TrackerKillWindows`: connection failing after the marker, after the deletion, after the flip; assert `IndexLagging`/`IndexLagging`/`Ready` and reaper convergence twice (NFR-R2)

## 4. Application callers (FR5, FR6, FR7, NFR-R1, NFR-R3, NFR-O1, NFR-S1)

- [ ] 4.1 Add three `OperatorEvent` codes (return landed INFO, return fenced DEBUG, return failed ERROR) and a `ClaimReturn` value in `application`, built by `ClaimReturn.of(TakeOrder, ClaimEpochBook)` from the order's tracker, ref and `InstanceId` and `TaskGit.epochs()`; it exposes `tracker()` and `ref()` for the park path and resolves `ClaimIdentity` when the return runs (not when built), calls the verb best-effort, and logs by outcome; verify a unit spec for each branch, including the sanitized reason and a re-claim between build and return (NFR-O1, NFR-S1, design D2)
- [ ] 4.2 Replace the `(TaskRef ref, Tracker tracker)` pair in `FreshClaimBaseBinding.resolve` and `ResumeLawBinding.resolve` with one `ClaimReturn` (design D2), built from the order at `TakeFreshClaim.claim`, `TakeContainerFreshClaim.claim`, `TakeResumeRunner.resumeWithoutDecision`/`resumeDecided` (through `TakeResumeExecution.run`) and `TakeContainerResumeRunner`; switch both helpers from `release` to the return with reason "base refresh: origin unreachable"; message becomes "Task X returned to Ready: origin did not answer …"; verify `FreshClaimBaseBindingSpec`, `ResumeLawBindingSpec`, `OutageWarnFanOutSpec` updated and green; remove `harness.returnToReady` from `RemoteOutageServeEndToEndSpec` so the real return carries the scenario (FR5, M1, UX2)
- [ ] 4.3 Switch `TakeClaimAndWork.releaseBestEffort` to the return through a `ClaimReturn` built in `dispatchAfterClaim(TakeOrder)` from the order and `git.epochs()`, with the usage error's sanitized summary; verify the bail-out spec asserts `Ready` and the marker text (FR7)
- [ ] 4.4 Type the claim-loss cause: `ClaimLossFlag` records `ClaimLoss(cause, reason)`; `ServeShutdown` marks `SHUTDOWN`, the heartbeat marks `LOST`; `RevocationDetectedException` carries the cause; verify `ClaimLossFlagSpec` and `RevocationCheckingAttemptPersistenceSpec` cover both (FR6)
- [ ] 4.5 Branch both ends of the declared revocation pair on the cause — `RevocationHandler.handle` (host) and the revocation arm of `TakeContainerEngineExecution.run` (container): SHUTDOWN → return, no note; LOST → note + `release` unchanged; rewrite the `Kept in sync with` sentence on both ends to the new invariant; verify a spec per arm per end with a tracker fake asserting exactly which verb ran (FR6, NG1, design Sync surfaces)
- [ ] 4.6 Shutdown lifecycle: verify `ServeShutdownDrainRaceSpec`/the serve lifecycle spec asserts every drained slot's task is `Ready` immediately after the drain on the in-memory tracker, and a GitHub WireMock variant sees the marker, the deletion, and the label flip; the same `Ready` assertion holds for a container-mode slot; assert a failing tracker never delays the kill past grace (FR6, NFR-R3, M2)
- [ ] 4.7 `ReleaseCallSiteBoundarySpec` in `:bootstrap`: scan `application/src/main` for `release(` on a tracker receiver (`tracker.` / `tracker().`) and fail outside the two allowlisted revocation files (`RevocationHandler`, `TakeContainerEngineExecution`), asserting the scan reached both; fail on any string comparison against `SHUTDOWN_REASON`; verify it is red before 4.2–4.5 land and green after, and that `SlotLedger`, `TakeBatch`, `FeedCycle` and `SnapshotWriter` do not trip it (M3, design single-owner table)

## 5. Crash consistency and durable documentation (NFR-R2, FR9, UX1)

- [ ] 5.1 Add the tracker-side return row to the kill-point matrix (`TrackerKillWindows`) with the three windows of D5 and the second-pass no-op; verify `TransitionKillPointSpec`/the GitHub kill-window suite pass twice (NFR-R2)
- [ ] 5.2 Write `docs/adr/0008-two-paths-back-to-the-queue.md` (principle, the two verbs, the fence, expiry as the invariant, the surveyed precedents); reference it from design D8 and from `docs/adr/0002`'s consequences (D8)
- [ ] 5.3 Glossary: add *return*, amend *release* and *reaper*; adapter author guide: port-table row for `returnToReady`, the retirement-routine guidance for adapter authors; verify `grep` finds no banned synonym (D8)
- [ ] 5.4 Operator guides: `operator-guide-serve.md` SIGTERM diagram and prose back to "return → Ready (immediate)", the outage section's "claimable again as soon as the gate closes"; exit-code 16 row in `operator-guide.md` says "returned"; verify the Mermaid renders and no "claim TTL" caveat remains for the deliberate cases (UX1)

## 6. Verification

- [ ] 6.1 Traceability sweep: every FR/NFR/UX/M of the proposal has an implementing spec or code reference; `openspec validate add-claim-return --strict` passes
- [ ] 6.2 Full build green: `./gradlew check` with PIT at 100% in every touched module (`gnomish-plugin-api`, `adapters`, `adapters/github`, `application`, `bootstrap`, `test-fixtures`); the contract suite's new properties pass on both adapters and the plugin sample (M4)
