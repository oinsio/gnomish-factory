# Tasks

Sequenced after `make-checkpoint-gate-durable` (archived 2026-10-09) and `tighten-spec-contract`
(archived 2026-10-14): the files they shared with this change (`TakeLoadedBranchRoutes`, the
`run` completed arms, `ParkKillPoints`) and the three MODIFIED requirements are in their landed
form. Root `./gradlew check` green after §2 and §4.

## 1. The tenure boundary as a port fact

- [ ] 1.1 `TrackerTask` (`gnomish-plugin-api`) gains `@Nullable BoundaryKind tenureBoundary`
      (design D1, FR1): the last PARK / FINISH / ABORT / STALE_CLAIM_REMOVED marker on the thread
      in posting order, whatever claim markers follow it; `Tracker.fetchTask` javadoc names it and
      says it is not the claim-scoped `TrackerFacts.latestBoundary`. GitHub adapter: derive it in
      the issue-detail read from the parsed marker thread already fetched, beside
      `GithubHistoryFactReader`'s whole-thread `returned`/`finished`, no extra call (NFR-C1) —
      not through `GithubTrackerFacts.latestBoundary` or `GithubCommentBoundary.latestBoundaryIndex`.
      In-memory adapter: `InMemoryTracker.fetchTask` reads the last such entry of
      `TrackedTask.thread()`; no new recording (every write op already records one). Verify the
      port contract suite gains the features of `tracker-port` "Tenure boundary survives a claim
      on every adapter" (park / return / claim, then a second return and claim) and "Every tenure
      boundary kind is reported on every adapter", run against both adapters (WireMock for
      GitHub).
- [ ] 1.2 Adapter author guide (`docs/guides/adapter-author-guide.md`): the new fact, how to
      derive it structurally, and why it is not the claim-scoped boundary of the shape facts.
- [ ] 1.3 `docs/glossary.md`: entry **Tenure boundary** — the thread's last boundary marker,
      *Not:* the claim-scoped boundary the shape facts carry for the reaper (design D1).

## 2. Receipt probes read history

- [ ] 2.1 `GuardedPark.observeAtTarget` (FR2): LANDED iff `tenureBoundary == PARK`, ABSENT iff
      `STALE_CLAIM_REMOVED` or null, UNDETERMINED on a tracker failure (unchanged WARN); one DEBUG
      line when a park is confirmed from history (NFR-O1). Delete the
      `TrackerTaskState.AwaitingHuman` read. Rewrite the `TakeReconcile` class javadoc paragraph
      that claims the marker alone distinguishes the two cases. Verify `GuardedParkSpec` data
      table over the four boundary values; `TakeLoadedBranchRoutesSpec`: a `Parked`+marker branch
      whose tracker boundary is `PARK` clears the marker and continues with the reply.
- [ ] 2.2 `FinishEffect.observeAtTarget` (FR3): LANDED iff `FINISH` or `finished()`. Verify
      `FinishEffectSpec`.
- [ ] 2.3 `ReceiptProbeGateSpec` (`:bootstrap`; design D5): `TrackerTaskState.AwaitingHuman` and
      `latestBoundary` absent from `take/GuardedPark.java` and `take/FinishEffect.java` (M2).
      Gate: `./gradlew check` green.

## 3. Delivered cleanup converges without a claim

- [ ] 3.1 `DeliveredCleanup` (`:application`; design D2, FR4): given a clone, a task id and a
      `CompletedUncleaned` tip whose tracker reports finished, run the cleanup commit through
      `TaskLifecycleStore.finishCleanup` (pushed by the store's decorator) and the medium's
      disposal; a `Delivered` tip is a no-op; a ref update that loses a race logs DEBUG and
      returns; one INFO line with the task id on a performed cleanup (NFR-O1). Not a
      `TerminalEffect`, no `ClaimGuard`. Delete `ResumeMechanics.finishCleanup` with its
      `HostResumeMechanics` and `ContainerResumeMechanics` implementations; `TakeLoadedBranchRoutes`
      hands `DeliveredCleanup` to `TakeReconcileFinish.finishUncleaned` as the cleanup `Runnable`,
      and `TakeEngineExecution`'s `FinishTransition.Fresh` cleanup is built from it.
- [ ] 3.2 Consumers, by file (FR4): `TakeDisposition` — gains `DeliveredCleanup` and the git
      bundle as fields; the `Finished` arm and the `Ready+finished` arm run it when the clone at
      `order.run().cloneDir()` holds the task branch, then report as today;
      `GitResumeContinuation.reportCompleted` and `ContainerResumeOutcomes.reportCompleted` run it
      before printing; `WorktreeJanitor.tick` gains a branch pass (the janitor gains the bound
      tracker, a branch lister over the clone and `DeliveredCleanup`): list `gnomish/*` branches
      of the clone, for each `CompletedUncleaned` tip one `fetchTask` and `DeliveredCleanup` when
      finished (proposal Q1: every tick); the javadoc's "no tracker check" policy narrows to the
      age pass. Old-way sweep: `grep -rn "finishCleanup(" application/src/main bootstrap/src/main`
      shows calls only in `DeliveredCleanup`, `GitOutcomeRecorder`, `ContainerTerminalDrive` and
      `ContainerRunTermination` (M3) — record the result. Verify `TakeDispositionSpec`,
      `GitResumeRoutingSpec` / `ContainerResumeRoutingSpec` (completed arm cleans when the
      envelope is present, writes nothing when it is not), `WorktreeJanitorSpec` (branch pass;
      the age pass still reads no tracker — `factory-serve` "The cleaner consults no tracker
      status" and "A finished task's branch is cleaned on the branch pass").
- [ ] 3.3 `DeliveredCleanupIdentitySpec` (`:bootstrap`, bare origin, host and container): for each
      of the six consumers, a `CompletedUncleaned` tip with a finished tracker converges to
      `Delivered` with the workspace gone, origin carries the cleanup, and a second run writes
      nothing (NFR-R1); two instances racing on one branch land one cleanup commit.
      `ReceiptProbeGateSpec` gains the `finishCleanup(` allowlist of design D5, every file
      asserted reached.

## 4. Kill-point pickups through the production route

- [ ] 4.1 `TakeKillPointWorld` gains `claimAsSecondInstance()` and `reap()` (design D3): a second
      `InstanceId` with its own `ClaimEpochBook`, the real `tracker.claim`, and a `TakeOrder` for
      it; `returnedAndReclaimed()` stays for the same-instance rows; the `pickup()` precheck on
      `AwaitingHuman` + the pending marker is removed and the `Resumed`/`Gate`/`Approval` rows
      that expect an empty pickup say so per row. The `ParkKillPoints` and `FinishKillPoints`
      rows move onto `TakeKillPointWorld` (`TakeKillPointWorlds`): their pickup returns as the
      human after the tracker park landed, reaps when it did not, claims as the second instance,
      and routes through `TakeLoadedBranchRoutes.route` / `TakeDisposition.dispose` under that
      order. Assertions added per row: after the tracker park, the human's return is not undone
      (tracker stays `Working(second)`, one park comment); after the tracker finish, the
      finished-task disposition lands the cleanup. Record in this task that the pre-change code
      fails both (M1).
- [ ] 4.2 Gate: `./gradlew check` green; PIT 100% on the touched modules (NFR-R2).

## 5. Durable record

- [ ] 5.1 `docs/adr/0003-crash-consistency.md`, mechanism 2 ("intent → effect → receipt"): the
      probe reads the target's durable history, never the state a later step rewrote; one
      sentence naming this change.
- [ ] 5.2 Operator guides: a finished task's branch is cleaned by the next `take <ref>`,
      `run --resume`, or `serve` pass — no manual cleanup.

## Follow-ups from tighten-spec-contract

- F67: move the marker of `GitResumeOutcomeSpec` "run() with outcome completed reports and exits without further touching the worktree or branch" (a lost-cleanup tip) to `Completed task with a lost cleanup converges`, asserting the cleanup, and write a test of the narrowed `Completed task resumes to a no-op` on a cleaned-up branch.
