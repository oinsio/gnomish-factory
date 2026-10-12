# Tasks

Sequenced after `make-checkpoint-gate-durable` (shared files: `TakeLoadedBranchRoutes`, the
`run` completed arms, `ParkKillPoints`). Root `./gradlew check` green after §2 and §4.

## 1. The tenure boundary as a port fact

- [ ] 1.1 `TrackerTask` (`gnomish-plugin-api`) gains `@Nullable BoundaryKind latestBoundary`
      (design D1, FR1); `Tracker.fetchTask` javadoc names it. GitHub adapter: derive it in the
      issue-detail read from the comment thread already fetched (`GithubCommentBoundary`), no
      extra call (NFR-C1). In-memory adapter: record the boundary on `park`, `finish`,
      `recordAbort`, `removeStaleClaim`; a claim leaves it unchanged. Verify the port contract
      suite gains the features of `tracker-port` "Tenure boundary survives a claim on every
      adapter" (park / finish / abort / stale-claim removal / persistence across a claim), run
      against both adapters (WireMock for GitHub).
- [ ] 1.2 Adapter author guide (`docs/guides/` tracker adapter guide): the new fact and how to
      derive it structurally.

## 2. Receipt probes read history

- [ ] 2.1 `GuardedPark.observeAtTarget` (FR2): LANDED iff `latestBoundary == PARK`, ABSENT iff
      `STALE_CLAIM_REMOVED` or null, UNDETERMINED on a tracker failure (unchanged WARN). Delete
      the `TrackerTaskState.AwaitingHuman` read. Rewrite the `TakeReconcile` class javadoc
      paragraph that claims the marker alone distinguishes the two cases. Verify `GuardedParkSpec`
      data table over the four boundary values; `TakeLoadedBranchRoutesSpec`: a `Parked`+marker
      branch whose tracker boundary is `PARK` clears the marker and continues with the reply.
- [ ] 2.2 `FinishEffect.observeAtTarget` (FR3): LANDED iff `FINISH` or `finished()`. Verify
      `FinishEffectSpec`.
- [ ] 2.3 `ReceiptProbeGateSpec` (`:bootstrap`; design D5): `TrackerTaskState.AwaitingHuman`
      absent from `take/GuardedPark.java` and `take/FinishEffect.java` (M2). Gate:
      `./gradlew check` green.

## 3. Delivered cleanup converges without a claim

- [ ] 3.1 `DeliveredCleanup` (`:application`; design D2, FR4): given a `CompletedUncleaned` tip
      and a tracker reporting finished, run the cleanup commit, the best-effort push and the
      workspace disposal through the medium's existing writers; a `Delivered` tip is a no-op; a
      ref update that loses a race logs DEBUG and returns. One INFO line with the task id on a
      performed cleanup (NFR-O1). Extract from `TakeReconcileFinish.finishUncleaned`'s tail and
      `GitOutcomeRecorder.recordAndCleanUp`'s Completed arm; route both through it.
- [ ] 3.2 Consumers, by file (FR4): `TakeDisposition` — the `Finished` arm and the
      `Ready+finished` arm run `DeliveredCleanup` when the clone holds the task branch, then
      report as today; `GitResumeContinuation.reportCompleted` and
      `ContainerResumeOutcomes.reportCompleted` run it before printing; `WorktreeJanitor.tick`
      gains a branch pass: list `gnomish/*` branches of the clone, for each `CompletedUncleaned`
      one `fetchTask` and `DeliveredCleanup` when finished (proposal Q1: every tick). Old-way
      sweep: `grep -rn "finishCleanup(" */src/main` returns `DeliveredCleanup`, the port, the two
      repositories and the decorator only (M3) — record the result. Verify `TakeDispositionSpec`,
      `GitResumeRoutingSpec` / `ContainerResumeRoutingSpec` (completed arm cleans when the
      envelope is present, writes nothing when it is not), `WorktreeJanitorSpec` (branch pass).
- [ ] 3.3 `DeliveredCleanupIdentitySpec` (`:bootstrap`, bare origin, host and container): for each
      of the five consumers, a `CompletedUncleaned` tip with a finished tracker converges to
      `Delivered` with the workspace gone, origin carries the cleanup, and a second run writes
      nothing (NFR-R1); two instances racing on one branch land one cleanup commit.

## 4. Kill-point pickups through the production route

- [ ] 4.1 `KillPointWorld` gains `returnAsHuman()`, `reap()` and `claimAsSecondInstance()`
      (design D3): a second `InstanceId` with its own `ClaimEpochBook`, the real
      `tracker.claim`, and a `TakeOrder` for it. `ParkKillPoints.pickup` and
      `FinishKillPoints.pickup` route through `TakeLoadedBranchRoutes.route` /
      `TakeDisposition.dispose` under that order; the park rows return as the human after the
      tracker park landed and reap when it did not. Assertions added per row: after the tracker
      park, the human's return is not undone (tracker stays `Working(second)`, one park comment);
      after the tracker finish, the finished-task disposition lands the cleanup. Record in this
      task that the pre-change code fails both (M1).
- [ ] 4.2 Gate: `./gradlew check` green; PIT 100% on the touched modules (NFR-R2).

## 5. Durable record

- [ ] 5.1 `docs/adr/0003-crash-consistency.md`, mechanism 2 ("intent → effect → receipt"): the
      probe reads the target's durable history, never the state a later step rewrote; one
      sentence naming this change.
- [ ] 5.2 Operator guides: a finished task's branch is cleaned by the next `take <ref>`,
      `run --resume`, or `serve` pass — no manual cleanup.

## Follow-ups from tighten-spec-contract

- F67: move the marker of `GitResumeOutcomeSpec` "run() with outcome completed reports and exits without further touching the worktree or branch" (a lost-cleanup tip) to `Completed task with a lost cleanup converges`, asserting the cleanup, and write a test of the narrowed `Completed task resumes to a no-op` on a cleaned-up branch.
