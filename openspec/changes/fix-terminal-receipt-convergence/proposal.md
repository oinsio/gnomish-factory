# Proposal: fix-terminal-receipt-convergence

Sequenced after `make-checkpoint-gate-durable` (archived 2026-10-09; it rewrote the resume
routes and the kill-point rows this change touches) and after `tighten-spec-contract` (archived
2026-10-14; it re-cut the three MODIFIED requirements below): both are in `openspec/specs/` now,
and every MODIFIED block restates that stable text. Alongside `add-claim-return` (which edits the
tracker-take return protocol; this change touches neither its requirement nor its verb).

## Why

Two terminal transitions of a `take` run end with a receipt the branch records only after the
tracker effect landed: a park (outcome commit → tracker park → marker cleared) and a finish
(outcome commit → tracker finish → cleanup commit). The 2026-10-06 audit found that both
recoveries decide from the **tracker's current state** — and by the time any pickup runs, a
fresh claim has already changed that state to `Working`. So a park killed after its tracker
write landed but before the marker cleared is probed as "absent" and re-driven: the task the
human just returned is parked again, and the human must return it a second time. A finish
killed after its tracker write landed is never picked up at all: a finished task is refused by
every disposition, so its `CompletedUncleaned` branch keeps the `.gnomish-task/` envelope
forever and its worktree is never disposed. `gnomish run --resume` of a completed task has the
same gap on its own medium: it reports and exits, never cleaning. The kill-point matrix did not
catch any of this because its pickups skip the claim — they probe a tracker still saying
`AwaitingHuman`, which production never sees.

The fact that survives a claim is not the state but the **tenure boundary**: the last
PARK/FINISH/ABORT/STALE_CLAIM_REMOVED marker on the task's thread, whatever claims follow it. The
GitHub adapter already derives `returned` and `finished` structurally from the whole thread the
same way. What the feed's shape facts carry is a different, claim-scoped fact — the boundary
*after the newest claim*, null by the time a pickup probes — so the receipt probes cannot borrow
it; they need the thread's last boundary, which no adapter reports today.

## What Changes

- **MODIFIED**: the tracker port's `fetchTask` carries the latest tenure boundary (park,
  finish, abort, stale-claim removal) beside the current state, on every adapter, under the port
  contract suite.
- **MODIFIED**: the park and finish receipt probes read the boundary, not the state: a landed
  park whose receipt was lost is confirmed, never re-driven; the human's return stands.
- **ADDED**: a delivered task whose cleanup never landed converges without a claim — through an
  explicit `take <ref>`, through `run --resume`, and through the `serve` worktree cleaner's
  branch pass — with one owner of the claimless route over the media's existing cleanup writers.
- **MODIFIED**: the `serve` worktree cleaner gains a branch pass over the clone's
  `CompletedUncleaned` branches; its age pass still consults no tracker.
- **MODIFIED**: the kill-point harness picks up through the production route — a human return
  (or the reaper) to `Ready`, then a claim by a *second* instance — so the two windows this
  change fixes are red before it and green after.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `tracker-port`: "Task facts from fetchTask" (latest tenure boundary), "Port contract spec
  suite binds every adapter" (the new fact is in the suite).
- `tracker-take`: "A Completed-without-cleanup tip is finished, never re-executed"
  (convergence without a claim), new requirement "Receipts are confirmed from tenure history,
  not from the current state".
- `manual-run`: "Resume invocation" — completed → finish the cleanup if it is owed, then report.
- `factory-serve`: "Worktree cleaner disposes aged task environments" — a branch pass over
  `CompletedUncleaned` branches; the age pass keeps consulting no tracker.

## Goals

- G1: no human returns a task twice because the factory lost a receipt.
- G2: every `CompletedUncleaned` branch converges to `Delivered` without anyone claiming a
  finished task.
- G3: the kill-point matrix reproduces production pickups.

## Non-Goals

- NG1: the pre-park windows and the stale `outcome` field — `make-checkpoint-gate-durable`.
- NG2: the fenced return itself — `add-claim-return`.
- NG3: any new tracker write; this change adds a read fact and reorders nothing.

## Users & Scenarios

- U1: **A reviewer** answers a question and moves the task back to ready; the factory crashed
  right after posting the question. The next pickup continues with their answer instead of
  re-asking.
- U2: **A maintainer** merging a delivered branch finds no `.gnomish-task/` directory in it even
  though the delivering instance died between the finish and the cleanup.
- U3: **A pipeline author** reading the kill-point table trusts that "second pickup is a no-op"
  was asserted the way production picks up.

## Requirements

### Functional

- FR1: `fetchTask` SHALL carry the task's **tenure boundary** — the last `PARK`, `FINISH`,
  `ABORT` or `STALE_CLAIM_REMOVED` marker on the thread, or none — derived from the tracker's
  durable history and unchanged by any later claim; it is distinct from the claim-scoped
  boundary the shape facts carry. Every adapter SHALL pass the contract suite feature for it.
- FR2: the park receipt probe SHALL report "landed" when the latest boundary is `PARK`, and
  "absent" when it is `STALE_CLAIM_REMOVED` or none; it SHALL no longer read the current
  state.
- FR3: the finish receipt probe SHALL report "landed" when the latest boundary is `FINISH` or
  the `finished` fact is set.
- FR4: a `CompletedUncleaned` branch whose tracker reports finished SHALL converge — cleanup
  commit, push, workspace disposal — through one owner, reached by: `take <ref>` on a finished
  task (instead of refusing with nothing done), `run --resume` on a completed task, and the
  `serve` worktree cleaner's branch pass over the clone's task branches. None of these SHALL
  claim the task; the owner writes no tracker effect and runs under no claim guard, over the
  media's existing cleanup writers.
- FR5: the kill-point harness SHALL pick up every park and finish row through the production
  route: the tracker returned to `Ready` (by the harness as the human, or by the reaper for a
  lost park), then a claim by a second instance with its own tenure record, then the real
  routing; the rows for the two windows above SHALL assert the human's return is not undone
  and the cleanup lands.

### Non-Functional Reliability

- NFR-R1: the cleanup of a finished task SHALL be idempotent across instances: two instances
  converging the same branch produce one cleanup commit; the loser's write fails without force.
- NFR-R2: PIT stays at 100% in every touched module.

### Non-Functional Observability

- NFR-O1: a receipt confirmed from history logs one DEBUG line; a cleanup performed without a
  claim logs one INFO line with the task id.

### Non-Functional Cost

- NFR-C1: the boundary fact SHALL cost no extra tracker call: it is derived from the comment
  thread `fetchTask` already reads.

## Operator Experience Criteria

- UX1: a returned task is never re-parked with the same report; the thread shows one park
  comment per park.
- UX2: a delivered branch never carries `.gnomish-task/` once the factory has been run against
  the project again.

## Success Metrics

- M1: `ParkKillPoints` and `FinishKillPoints` rows pass with claim-through pickups; the
  pre-change code fails their new assertions (recorded in the task).
- M2: `grep -rn "TrackerTaskState.AwaitingHuman" application/src/main/java/com/github/oinsio/gnomish/app/take/GuardedPark.java`
  returns nothing.
- M3: `grep -rn "finishCleanup(" application/src/main bootstrap/src/main` shows calls in exactly
  three files: the owner `DeliveredCleanup` and the two manual-run exemptions
  (`GitOutcomeRecorder`, `ContainerTerminalDrive` via `ContainerRunTermination`); the port
  declarations and the git adapters are outside the count.

## Impact

- `gnomish-plugin-api`: `TrackerTask` (latest boundary), `Tracker` javadoc.
- `adapters/github`: `GithubTaskFetcher` / `GithubIssueDetail*` (boundary derived from the
  whole marker thread, beside `GithubHistoryFactReader`); `:adapters`
  (`adapter/tracker/inmemory`): `InMemoryTracker.fetchTask` reads the boundary from the
  task's thread.
- `:application`: `GuardedPark`, `FinishEffect`, `TakeDisposition`, `TakeLoadedBranchRoutes`
  (the cleanup handed to `TakeReconcileFinish`), `TakeEngineExecution` (fresh-finish cleanup),
  `ResumeMechanics` and its host/container implementations (`finishCleanup` removed),
  `GitResumeContinuation` / `ContainerResumeOutcomes` completed arms, new `DeliveredCleanup`,
  `serve/WorktreeJanitor`.
- `:bootstrap`: `TakeKillPointWorld` and the kill-point rows, port contract suite feature,
  `DeliveredCleanup` wiring, `ReceiptProbeGateSpec`.
- Docs: `docs/adr/0003-crash-consistency.md` (receipt probes read history), `docs/glossary.md`
  (**tenure boundary**), tracker adapter author guide, operator guides.

## Open Questions

- Q1: should the janitor's branch pass be bounded by the same age threshold as its worktree
  pass, or run on every tick? Proposed: every tick, it is one `ls-remote`-free local listing.
