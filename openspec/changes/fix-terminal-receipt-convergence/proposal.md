# Proposal: fix-terminal-receipt-convergence

Sequenced after `make-checkpoint-gate-durable` (which rewrites the same resume routes and the
same kill-point rows) and alongside `add-claim-return` (which edits the tracker-take return
protocol; this change touches neither its requirement nor its verb).
Also sequenced after `tighten-spec-contract`: every MODIFIED block (tracker-port, tracker-take,
manual-run) is layered on that change's text, which must sync first.

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

The fact that survives a claim is not the state but the **tenure boundary**: the GitHub adapter
already derives `returned` and `finished` structurally from the PARK/FINISH markers on the
thread, and the feed already reads the latest boundary kind. The receipt probes simply never
asked for it.

## What Changes

- **MODIFIED**: the tracker port's `fetchTask` carries the latest tenure boundary (park,
  finish, abort, stale-claim removal) beside the current state, on every adapter, under the port
  contract suite.
- **MODIFIED**: the park and finish receipt probes read the boundary, not the state: a landed
  park whose receipt was lost is confirmed, never re-driven; the human's return stands.
- **ADDED**: a delivered task whose cleanup never landed converges without a claim — through an
  explicit `take <ref>`, through `run --resume`, and through the `serve` janitor's pass — with
  one owner of the cleanup.
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
- `manual-run`: "Resume invocation" — layered on `tighten-spec-contract`'s delta (which carries
  the archived `make-checkpoint-gate-durable` text):
  completed → finish the cleanup if it is owed, then report.

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

- FR1: `fetchTask` SHALL carry the task's latest tenure boundary — `PARK`, `FINISH`, `ABORT`,
  `STALE_CLAIM_REMOVED`, or none — derived from the tracker's durable history, unchanged by a
  later claim; every adapter SHALL pass the contract suite feature for it.
- FR2: the park receipt probe SHALL report "landed" when the latest boundary is `PARK`, and
  "absent" when it is `STALE_CLAIM_REMOVED` or none; it SHALL no longer read the current
  state.
- FR3: the finish receipt probe SHALL report "landed" when the latest boundary is `FINISH` or
  the `finished` fact is set.
- FR4: a `CompletedUncleaned` branch whose tracker reports finished SHALL converge — cleanup
  commit, push, workspace disposal — through one owner, reached by: `take <ref>` on a finished
  task (instead of refusing with nothing done), `run --resume` on a completed task, and the
  `serve` janitor's pass over the clone's task branches. None of these SHALL claim the task.
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
- M3: `grep -rn "finishCleanup(" */src/main` returns the single owner and its three consumers.

## Impact

- `gnomish-plugin-api`: `TrackerTask` (latest boundary), `Tracker` javadoc.
- `adapters/github`: `GithubIssueDetail*` (boundary from `GithubCommentBoundary`),
  `adapters/tracker-inmemory`: boundary tracking in `InMemoryTracker`/harness.
- `:application`: `GuardedPark`, `FinishEffect`, `TakeDisposition`, `TakeReconcileFinish`,
  `GitResumeContinuation` / `ContainerResumeOutcomes` completed arms, new `DeliveredCleanup`,
  `serve/WorktreeJanitor`.
- `:bootstrap`: kill-point worlds and rows, port contract suite feature, `DeliveredCleanup`
  wiring.
- Docs: `docs/adr/0003-crash-consistency.md` (receipt probes read history), tracker adapter
  author guide.

## Open Questions

- Q1: should the janitor's branch pass be bounded by the same age threshold as its worktree
  pass, or run on every tick? Proposed: every tick, it is one `ls-remote`-free local listing.
