# Design: fix-terminal-receipt-convergence

## Context

See `proposal.md` — Why. Facts the approach rests on (verified in code on 2026-10-06):

- `GuardedPark.observeAtTarget` (`take/GuardedPark.java:133-145`) answers LANDED iff
  `fetchTask(ref).state() instanceof AwaitingHuman`; `FinishEffect.observeAtTarget`
  (`take/FinishEffect.java:64-78`) iff `finished()` or state `Finished`. Both are
  `TerminalEffect` implementations driven by `TerminalEffectDrive`.
- Every `take` pickup runs after `tracker.claim` succeeded (`TakeReconcile` javadoc, lines
  36-50), so the state is `Working(this)` at probe time; the javadoc claims the branch's pending
  marker distinguishes "never landed" from "landed" — which fails in the window after the
  tracker write and before `confirmTerminalWrite`.
- `TakeDisposition.dispose` (`:70-85`): a `Ready` task with `finished()` is declined
  (`refuseFinished`), a `Finished` task is `Skipped` — no path reaches a `CompletedUncleaned`
  branch once the finish landed. `TakeReconcileFinish.finishUncleaned` (`:71-97`) is only reached
  from a claimed pickup.
- `GitResumeContinuation.reportCompleted` (`:148-154`) and `ContainerResumeOutcomes`'s completed
  arm print and return; neither calls `finishCleanup`/dispose.
- `GithubHistoryFactReader` already derives `returned`/`finished` structurally from PARK/FINISH
  markers; `TrackerFacts.latestBoundary` (`BoundaryKind`: ABORT, PARK, FINISH,
  STALE_CLAIM_REMOVED) exists for the feed/reaper path; `GithubCommentUpsert` makes every
  factory comment a find-then-upsert on a `GithubCommentIdentity` — a re-drive is idempotent at
  the comment level, so the harm of 4c is the *state* change (re-parking a returned task), not
  a duplicate comment.
- `TrackerTask` (`gnomish-plugin-api`) carries `ref, snapshot, state, abortFacts, finished,
  designators` — no boundary. Both adapters pass the shared port contract suite.
- Kill-point pickups: `ParkKillPoints.pickup` (`:113-127`) and `FinishKillPoints.pickup`
  (`:186-200`) drive the effect with `world.takeOrder()` — the seeding instance's claim, tracker
  still `Working(kp0001)`/`AwaitingHuman`; no return, no second claim.
- `WorktreeJanitor` (`serve`) is the per-clone hygiene thread (worktrees by age, hourly).
- Active changes on the same regions: `make-checkpoint-gate-durable` (sequenced before: rewrites
  `TakeLoadedBranchRoutes`, `ParkKillPoints` rows, `run` completed/paused arms),
  `add-claim-return` (tracker-take return verb; not touched here), `add-stage-iteration`.

## Goals / Non-Goals

**Goals:** a lost receipt never undoes a human's return (G1); `CompletedUncleaned` always
converges (G2); the kill-point matrix picks up like production (G3).

**Non-Goals:** new tracker writes; the fenced return; the pre-park windows.

## Decisions

**D1 — The receipt is confirmed from tenure history.** `TrackerTask` gains
`@Nullable BoundaryKind latestBoundary` — the latest PARK / FINISH / ABORT / STALE_CLAIM_REMOVED
marker on the task's thread, or null when none — derived structurally by each adapter (GitHub:
from the comment thread `fetchTask` already reads, through `GithubCommentBoundary`; in-memory:
recorded by the write ops). `GuardedPark.observeAtTarget` answers LANDED iff
`latestBoundary == PARK`, ABSENT iff `STALE_CLAIM_REMOVED` or null, UNDETERMINED on a tracker
failure as today; `FinishEffect.observeAtTarget` answers LANDED iff `FINISH` or `finished()`.
*Rationale:* the state is overwritten by the pickup's own claim; the boundary is not — it is the
same fact GitLab derives its `blocked` status from and the GitHub adapter already computes for
the feed. The boundary after a landed park is PARK whether the human returned the task or the
reaper did not need to; after a lost park the dead claim is reaped, leaving STALE_CLAIM_REMOVED —
exactly the two cases 4c conflates. *Alternative rejected — stamp the claim epoch into the park
comment and compare:* a second structural marker and a parse on the hot path for a fact the
boundary already states.

**D2 — One owner of the delivered cleanup, reachable without a claim.** `DeliveredCleanup`
(`:application`): given a `CompletedUncleaned` tip whose tracker reports finished (`finished()`
or boundary `FINISH`), commit the cleanup, push best-effort, dispose the workspace — the
destructive half of `FinishTransition`, extracted from `TakeReconcileFinish.finishUncleaned`'s
tail and `GitOutcomeRecorder.recordAndCleanUp`'s Completed arm. Consumers: `TakeDisposition`'s
`Finished` and `Ready+finished` arms (after the decline/skip message, when the branch exists and
is `CompletedUncleaned`), `GitResumeContinuation.reportCompleted` /
`ContainerResumeOutcomes.reportCompleted`, and `WorktreeJanitor`'s tick (a new branch pass over
the clone's `gnomish/*` branches, one `fetchTask` per `CompletedUncleaned` branch). No claim is
written: the finish already ended the tenure, and the bare-object/worktree ref update fails
without force when two instances meet (NFR-R1). *Rationale:* `crash-consistency.md` item 3 — the
shape has an owner today, but no route reaches it after the finish lands; three entry points
need one owner, not three copies. *Alternative rejected — re-claim finished tasks for cleanup:*
contradicts `enforce-finish-terminality` (a finished task is never claimed) and would re-open
the task in the tracker for a git-only step.

**D3 — Kill-point pickups go through the production route.** `KillPointWorld` gains
`returnAsHuman()` (tracker → `Ready`, as the human would), `reap()` (the reaper's
`removeStaleClaim`), and `claimAsSecondInstance()` (a new `InstanceId` with its own
`ClaimEpochBook`, `tracker.claim`); `ParkKillPoints.pickup` and `FinishKillPoints.pickup` call
the real `TakeLoadedBranchRoutes.route` / `TakeDisposition.dispose` under the second instance's
`TakeOrder` instead of driving the effect directly. The two windows this change fixes become
assertions: after the park's tracker write the human-returned pickup must not re-park; after the
finish's tracker write the finished-task disposition must land the cleanup. *Rationale:* the
matrix passed while production failed because the pickup skipped the step that changes the
state the probe reads (`testing.md`, "Fixtures assemble through production owners"). *Alternative
rejected — keep direct effect drives and add a tracker-state stub:* tests the stub.

**D4 — Sync surfaces.** No declared pair is touched (`GuardedPark`/`FinishEffect` are two
`TerminalEffect` implementations over different facts, not two implementations of one rule; the
`take` resume runners' pair is not edited). The new fact is a port contract: both adapters pass
the extended suite, which is the mechanism `tracker-port` already uses instead of a pair.
`DeliveredCleanup` replaces two copies of the cleanup tail (`TakeReconcileFinish`,
`GitOutcomeRecorder` Completed arm) with one owner — a consolidation, not a new pair.

**D5 — Single-owner mechanisms.**

| Owner | Value (type) | Consumers | Old way removed | Enforced by |
|-------|--------------|-----------|-----------------|-------------|
| `TrackerTask.latestBoundary` — the one fact a receipt probe reads | `@Nullable BoundaryKind` | `GuardedPark.observeAtTarget`, `FinishEffect.observeAtTarget`, `DeliveredCleanup` (finished check) | `fetchTask(...).state() instanceof AwaitingHuman` in `GuardedPark` — deleted; `state() instanceof Finished` in `FinishEffect` — replaced | the port contract suite feature (both adapters); grep gate in `ReceiptProbeGateSpec` (`:bootstrap`): `TrackerTaskState.AwaitingHuman` does not appear in `take/GuardedPark.java` or `take/FinishEffect.java` |
| `DeliveredCleanup` — the one cleanup of a delivered branch | the cleanup commit + disposal, as one `TerminalEffect` receipt | `TakeDisposition` (finished arms), `TakeReconcileFinish.finishUncleaned` (its destructive tail), `GitResumeContinuation.reportCompleted`, `ContainerResumeOutcomes.reportCompleted`, `WorktreeJanitor.tick` | `taskRepository.finishCleanup(taskId)` + `disposeWorkspace` spelled in `GitOutcomeRecorder.recordAndCleanUp` and `TakeEngineExecution`/`TakeContainerEngineExecution`'s finish lambdas — routed through the owner; `reportCompleted` printing without cleaning — replaced | grep gate in `ReceiptProbeGateSpec`: `finishCleanup(` in `*/src/main` appears only in `DeliveredCleanup`, the port and the adapters; identity spec `DeliveredCleanupIdentitySpec` (bare origin, both media): after any of the five consumers runs on a `CompletedUncleaned` tip whose tracker is finished, the tip is `Delivered` and the workspace is gone, and a second run writes nothing |

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
- [The janitor's branch pass adds tracker reads] → one `fetchTask` per `CompletedUncleaned`
  branch per tick, bounded by the number of such branches, which this change drives to zero.
- [Two instances cleaning the same branch] → the ref update is compare-and-swap on both media;
  the loser logs DEBUG and moves on.
- [`add-claim-return` edits the tracker-take return protocol concurrently] → this change touches
  neither the return requirement nor `release`; sequencing is independent, sync order noted.

## Migration Plan

1. Port fact + both adapters + contract suite (§1). 2. Probes read it (§2). 3. `DeliveredCleanup`
and its five consumers (§3). 4. Kill-point pickups through the production route; identity and
gate specs (§4). 5. ADR/guide (§5).
