# Design: fix-terminal-receipt-convergence

## Context

See `proposal.md` — Why. Facts the approach rests on (verified in code on 2026-10-06, re-verified
2026-10-14):

- `GuardedPark.observeAtTarget` (`take/GuardedPark.java:133-145`) answers LANDED iff
  `fetchTask(ref).state() instanceof AwaitingHuman`; `FinishEffect.observeAtTarget`
  (`take/FinishEffect.java:64-78`) iff `finished()` or state `Finished`. Both are
  `TerminalEffect` implementations driven by `TerminalEffectDrive`, and both deliver under
  `ClaimGuard.stillOurs` (`TakeFinishReport.java:57`).
- Every `take` pickup runs after `tracker.claim` succeeded (`TakeReconcile` javadoc, lines
  36-50), so the state is `Working(this)` at probe time; the javadoc claims the branch's pending
  marker distinguishes "never landed" from "landed" — which fails in the window after the
  tracker write and before `confirmTerminalWrite`.
- `TakeDisposition.dispose` (`:71-85`): a `Ready` task with `finished()` is declined
  (`refuseFinished`), a `Finished` task is `Skipped` — no path reaches a `CompletedUncleaned`
  branch once the finish landed. `TakeDisposition` holds no git collaborator; the clone is
  reachable as `order.run().cloneDir()`. `TakeReconcileFinish.finishUncleaned` (`:90-99`) is
  only reached from a claimed pickup, and receives its cleanup as a `Runnable` built in
  `TakeLoadedBranchRoutes` (`:96-97`, `mechanics.finishCleanup(cloneDir, branch)`).
- `GitResumeContinuation.reportCompleted` (`:137-142`) and
  `ContainerResumeOutcomes.reportCompleted` (`:143-148`) print and return; neither cleans.
- The cleanup commit has one writer per store (`TaskLifecycleStore.finishCleanup`, pushed by
  `PushBestEffortTaskLifecycleStore`) but five callers in `application/src/main` and
  `bootstrap/src/main`: `TakeEngineExecution:183` (fresh finish), `HostResumeMechanics:73` and
  `ContainerResumeMechanics:51` → `ContainerRunTermination:84` (the `ResumeMechanics.finishCleanup`
  strategies behind `TakeLoadedBranchRoutes`), `GitOutcomeRecorder:77` and
  `ContainerTerminalDrive:78` (manual `run`, no tracker). `FinishTransition.cleanup()` is the
  Runnable each take path hands in.
- `GithubHistoryFactReader` derives `returned`/`finished` from the **whole** marker thread
  (`hasKind`, `:45-54`). The boundary fact that exists, `TrackerFacts.latestBoundary`, is
  **claim-scoped on purpose** (`TrackerFacts.java:13-16`): `GithubTrackerFacts.latestBoundary`
  (`:76-91`) and `TrackedTaskFacts.latestBoundary` (`:61-72`) return null when a CLAIM follows
  the boundary — which is exactly the state at probe time. `GithubCommentBoundary.latestBoundaryIndex`
  (`:46`) is the index of the latest CLAIM-or-ABORT marker, not a park/finish boundary.
  `GithubCommentUpsert` makes every factory comment a find-then-upsert on a
  `GithubCommentIdentity` — a re-drive is idempotent at the comment level, so the harm of 4c is
  the *state* change (re-parking a returned task), not a duplicate comment.
- `TrackerTask` (`gnomish-plugin-api`) carries `ref, snapshot, state, abortFacts, finished,
  designators` — no boundary. Both adapters pass the shared port contract suite.
- Kill-point pickups: `ParkKillPoints.pickup` (`:114-128`) and `FinishKillPoints.pickup`
  (`:187-201`) drive the effect with `world.takeOrder()` — the seeding instance's claim, tracker
  still `Working(kp0001)`/`AwaitingHuman`; no return, no second claim. `TakeKillPointWorld`
  (used by `ResumedKillPoints`, `GateKillPoints`, `ApprovalKillPoints`) already has a
  production-route `pickup()` (`routes.call(takeOrder())`, `:51-57`, behind a precheck on
  `AwaitingHuman` + the pending marker) and `returnedAndReclaimed()` (`:64-70`: return to
  `Ready`, reclaim by the **same** instance, epoch issued).
- `WorktreeJanitor` (`serve`) is the per-clone hygiene thread (worktrees by age, hourly); it
  holds no tracker and no git (`:60-65`), and its javadoc (`:36-38`) and the `factory-serve`
  scenario "The cleaner consults no tracker status" fix that policy for the age pass.
- `make-checkpoint-gate-durable` (archived 2026-10-09) and `tighten-spec-contract` (archived
  2026-10-14) rewrote `TakeLoadedBranchRoutes`, the `ParkKillPoints` rows and the `run`
  completed/paused arms; the text this change layers on is the stable spec. Active neighbours:
  `add-claim-return` (tracker-take return verb; not touched here), `add-stage-iteration`.

## Goals / Non-Goals

**Goals:** a lost receipt never undoes a human's return (G1); `CompletedUncleaned` always
converges (G2); the kill-point matrix picks up like production (G3).

**Non-Goals:** new tracker writes; the fenced return; the pre-park windows.

## Decisions

**D1 — The receipt is confirmed from the tenure boundary, a whole-thread fact.** `TrackerTask`
gains `@Nullable BoundaryKind tenureBoundary` — the last PARK / FINISH / ABORT /
STALE_CLAIM_REMOVED marker on the task's thread in posting order, or null when none, **whatever
claim markers follow it**. GitHub derives it from the parsed marker thread `fetchTask` already
reads, beside `GithubHistoryFactReader`'s whole-thread `returned`/`finished` (no extra call,
NFR-C1); the in-memory adapter reads the last such entry of `TrackedTask.thread()` (every write
op already records one; nothing new is recorded). It is **not** `TrackerFacts.latestBoundary`
and is not derived through `GithubTrackerFacts.latestBoundary`, `TrackedTaskFacts.latestBoundary`
or `GithubCommentBoundary.latestBoundaryIndex`: those are claim-scoped by design (the reaper's
view of the current tenure) and null at probe time; reusing them reproduces the defect. The
glossary gets the term (**tenure boundary**, *Not:* the claim-scoped boundary of the shape
facts). `GuardedPark.observeAtTarget` answers LANDED iff `tenureBoundary == PARK`, ABSENT iff
`STALE_CLAIM_REMOVED` or null, UNDETERMINED on a tracker failure as today;
`FinishEffect.observeAtTarget` answers LANDED iff `FINISH` or `finished()`. *Rationale:* the
state is overwritten by the pickup's own claim; the thread's last boundary is not. After a
landed park it is PARK whether the human returned the task or not; after a lost park the dead
claim is reaped, leaving STALE_CLAIM_REMOVED — exactly the two cases 4c conflates. *Alternative
rejected — stamp the claim epoch into the park comment and compare:* a second structural marker
and a parse on the hot path for a fact the boundary already states.

**D2 — One owner of the claimless delivered cleanup, over the media's existing writers.**
`DeliveredCleanup` (`:application`): given a clone, a task id and a tip, when the tip is
`CompletedUncleaned` and the tracker reports the task finished (`finished()` or boundary
`FINISH`), it runs the cleanup commit through `TaskLifecycleStore.finishCleanup` (pushed
best-effort by the store's decorator, as today) and then the medium's disposal (host: the
worktree, as `GitOutcomeRecorder.disposeWorkspace` does; container: the sandbox support's
disposal); a `Delivered` tip is a no-op; a ref update that loses a race logs DEBUG and returns.
It is **not** a `TerminalEffect`: it writes no tracker effect, so there is nothing to probe and
no `ClaimGuard` — no claim is written or checked. It replaces the per-medium
`ResumeMechanics.finishCleanup` strategies (`HostResumeMechanics`, `ContainerResumeMechanics`),
which are deleted, and the cleanup half of `TakeEngineExecution`'s fresh-finish lambda. Consumers
by file: `TakeLoadedBranchRoutes` (the `Runnable` handed to `TakeReconcileFinish.finishUncleaned`),
`TakeEngineExecution` (`FinishTransition.Fresh`'s cleanup), `TakeDisposition`'s `Finished` and
`Ready+finished` arms (after the decline/skip message, when the clone at `order.run().cloneDir()`
holds the task branch; `TakeDisposition` gains `DeliveredCleanup` and the git bundle as
constructor fields), `GitResumeContinuation.reportCompleted` /
`ContainerResumeOutcomes.reportCompleted` (before printing), and `WorktreeJanitor`'s tick — a
new branch pass over the clone's `gnomish/*` branches on every tick (proposal Q1: every tick, a
local listing), one `fetchTask` per `CompletedUncleaned` tip; the janitor gains the bound
tracker, a branch lister over the clone and `DeliveredCleanup`, and its "no tracker check"
policy narrows to the age pass (javadoc and the `factory-serve` delta say so). Exemptions, with
reason: `GitOutcomeRecorder.recordAndCleanUp` and `ContainerTerminalDrive` — manual `run` has no
tracker, so the cleanup follows its intent immediately (FR10 of harden-task-branch-contract) and
there is no "finished" to check; they keep calling the store directly. *Rationale:*
`crash-consistency.md` item 3 — the shape has writers today, but no route reaches them after the
finish lands; three new entry points need one owner, not three copies. *Alternative rejected —
re-claim finished tasks for cleanup:* contradicts `enforce-finish-terminality` (a finished task is
never claimed) and would re-open the task in the tracker for a git-only step.

**D3 — Kill-point pickups go through the production route, on the world that already has it.**
`TakeKillPointWorld` gains `claimAsSecondInstance()` (a new `InstanceId` with its own
`ClaimEpochBook`, the real `tracker.claim`, and a `TakeOrder` for it) and `reap()` (the reaper's
`removeStaleClaim`); `returnedAndReclaimed()` stays for the same-instance rows. The
`ParkKillPoints` and `FinishKillPoints` rows move onto `TakeKillPointWorld`
(`TakeKillPointWorlds`), and their pickup is the row's own sequence — return as the human (park
landed) or reap (park lost), claim as the second instance, then the real
`TakeLoadedBranchRoutes.route` / `TakeDisposition.dispose` under that order — instead of driving
the effect directly. `TakeKillPointWorld.pickup()`'s precheck on `AwaitingHuman` + the pending
marker (the heuristic this change removes) goes; a row that wants an empty pickup says so. The
two windows this change fixes become assertions: after the park's tracker write the
human-returned pickup must not re-park; after the finish's tracker write the finished-task
disposition must land the cleanup. *Rationale:* the matrix passed while production failed
because the pickup skipped the step that changes the state the probe reads (`testing.md`,
"Fixtures assemble through production owners"); a second world with its own production-route
pickup would be two fixtures of one rule. *Alternative rejected — keep direct effect drives and
add a tracker-state stub:* tests the stub.

**D4 — Sync surfaces.** No declared pair is touched (`GuardedPark`/`FinishEffect` are two
`TerminalEffect` implementations over different facts, not two implementations of one rule).
`ResumeMechanics<B>` is the shared abstraction of the two resume media, not a pair: removing its
`finishCleanup` from both strategies in favour of `DeliveredCleanup` keeps it one abstraction with
one method fewer. The new fact is a port contract: both adapters pass the extended suite, which
is the mechanism `tracker-port` already uses instead of a pair. The kill-point harness keeps one
production-route pickup (`TakeKillPointWorld`), not two.

**D5 — Single-owner mechanisms.**

| Owner | Value (type) | Consumers | Old way removed | Enforced by |
|-------|--------------|-----------|-----------------|-------------|
| `TrackerTask.tenureBoundary` — the one fact a receipt probe reads | `@Nullable BoundaryKind`, whole-thread | `GuardedPark.observeAtTarget`, `FinishEffect.observeAtTarget`, `DeliveredCleanup` (finished check) | `fetchTask(...).state() instanceof AwaitingHuman` in `GuardedPark` — deleted; `state() instanceof Finished` in `FinishEffect` — replaced; the claim-scoped `TrackerFacts.latestBoundary` stays for the reaper and is never read by a probe | the port contract suite feature (both adapters, including a boundary read after two later claims); grep gate in `ReceiptProbeGateSpec` (`:bootstrap`): `TrackerTaskState.AwaitingHuman` and `latestBoundary` do not appear in `take/GuardedPark.java` or `take/FinishEffect.java` |
| `DeliveredCleanup` — the one claimless cleanup of a delivered branch | the cleanup commit + push + disposal, idempotent, no `TerminalEffect` | `TakeLoadedBranchRoutes` (cleanup `Runnable` for `finishUncleaned`), `TakeEngineExecution` (fresh-finish cleanup), `TakeDisposition` (finished arms), `GitResumeContinuation.reportCompleted`, `ContainerResumeOutcomes.reportCompleted`, `WorktreeJanitor.tick` (branch pass) | `ResumeMechanics.finishCleanup` with `HostResumeMechanics:66-73` and `ContainerResumeMechanics:50-51` — deleted; `taskRepository.finishCleanup` + `disposeWorkspace` in `TakeEngineExecution:183` — routed; `reportCompleted` printing without cleaning — replaced. Exemptions: `GitOutcomeRecorder.recordAndCleanUp`, `ContainerTerminalDrive` → `ContainerRunTermination` (manual `run`, no tracker, FR10) | grep gate in `ReceiptProbeGateSpec`: `finishCleanup(` is called in `application/src/main` and `bootstrap/src/main` only from the allowlist `DeliveredCleanup`, `GitOutcomeRecorder`, `ContainerTerminalDrive`, `ContainerRunTermination`, every allowlisted file asserted reached; identity spec `DeliveredCleanupIdentitySpec` (bare origin, both media): after any of the six consumers runs on a `CompletedUncleaned` tip whose tracker is finished, the tip is `Delivered` and the workspace is gone, and a second run writes nothing |

**D6 — Crash consistency** (`crash-consistency.md`): no new durable step. The park sequence
(outcome+marker → tracker park → receipt) and the finish sequence (outcome+marker → tracker
finish → cleanup) keep their shapes; what changes is the recovery owner's *reading* in the window
after the effect landed: `Parked` with marker set now converges by confirming from history and
clearing the marker (roll forward, no re-drive); `CompletedUncleaned` after a landed finish now
has a reachable owner. Idempotence: `DeliveredCleanup` on a `Delivered` tip is a no-op; the
in-history confirmation writes only the receipt. Kill-point rows: the existing `ParkKillPoints`
and `FinishKillPoints` rows, picked up through D3, assert both.

## Risks / Trade-offs

- [`TrackerTask` is in `gnomish-plugin-api`: a plugin-contract change] → additive field with a
  null default; pre-release; the adapter author guide is updated.
- [Two boundary facts with one word in their names] → the glossary entry and the `latestBoundary`
  ban in the probe files keep them apart; the reaper keeps its claim-scoped fact untouched.
- [The janitor's branch pass adds tracker reads] → one `fetchTask` per `CompletedUncleaned`
  branch per tick, bounded by the number of such branches, which this change drives to zero; the
  age pass still reads no tracker, so the `factory-serve` scenario stays true.
- [Deleting `ResumeMechanics.finishCleanup`] → both resume media lose one strategy method; the
  identity spec runs the take recovery on both media so the replacement is observed, not assumed.
- [Moving the park/finish rows onto `TakeKillPointWorld`] → the `Resumed`/`Gate`/`Approval` rows
  share the world and its `pickup()`; removing the precheck must leave their expected empty
  pickups explicit per row.
- [Two instances cleaning the same branch] → the ref update is compare-and-swap on both media;
  the loser logs DEBUG and moves on.
- [`add-claim-return` edits the tracker-take return protocol concurrently] → this change touches
  neither the return requirement nor `release`; sequencing is independent, sync order noted.

## Migration Plan

1. Port fact + both adapters + contract suite + glossary (§1). 2. Probes read it (§2).
3. `DeliveredCleanup` and its six consumers (§3). 4. Kill-point pickups through the production
route; identity and gate specs (§4). 5. ADR/guides (§5).
