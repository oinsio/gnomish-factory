# Design: fix-abort-progress-reset

## Context

Driven by FR1–FR4 of this change, correcting the never-built reset half of
`add-tracker-port` FR14 / design D10. Current state:

- `RevocationCheckingAttemptPersistence.persist` is the only round-boundary hook
  in core; it re-reads the task for revocation but signals nothing durable.
- Neither adapter has a "a round persisted" marker; `GithubCommentBoundary`
  documents the gap in its own Javadoc.
- `GithubAbortFactsReader` folds every abort marker (feed) and
  `TrackedTask.recordAbort` only increments (in-memory) — the count never
  resets on progress.

Constraint carried from `add-tracker-port`: abort facts must be reconstructable
by any instance from the tracker alone (statelessness). The marker stream
`CLAIM, ABORT, CLAIM, ABORT` is ambiguous between "progress then abort" and
"immediate re-abort", so a dedicated durable-progress marker is unavoidable —
no reconstruction-only fix exists.

## Goals / Non-Goals

**Goals:**
- Record durable progress as a tracker fact so "aborts since last durable
  progress" is honored by every adapter (G1, G2).
- Keep the emission best-effort and off the run's critical path (NFR-R1).

**Non-Goals:**
- Changing backoff/fuse policy math (NG1), human-facing progress notes (NG2),
  or revocation detection (NG3).

## Decisions

**D1 — `recordProgress(TaskRef)` as the eleventh port operation.** (FR1) The
`Tracker` port grows one operation that persists a structural durable-progress
marker and leaves logical state and claim holder untouched. *Rationale:* the
reset is a first-class coordination fact, reconstructable by any instance; a
dedicated operation keeps the adapter contract explicit and testable via the
shared suite. This knowingly retires the "exactly ten operations" invariant
frozen in `add-tracker-port` design D1 — the correct count is eleven.
*Rejected:* piggybacking on `postNote` — the adapter cannot distinguish a
progress note from a revocation salvage note (both are `note` markers), so the
reconstruction would reset on the wrong event; a reconstruction-only heuristic
(reset on same-instance reclaim) — deviates from "first persisted round" and
still mis-counts a reclaim that aborts before any round persists.

**D2 — Emit from the round-boundary hook, first durable round per claim,
best-effort.** (FR2, NFR-R1, Q1) `RevocationCheckingAttemptPersistence` already
runs strictly after the delegate's durable `persist`; it gains a once-per-run
guard and calls `tracker.recordProgress(ref)` on the first successful persist,
before the revocation check. The decorator is constructed fresh per
`engine.run(...)` (one instance per claim/run), so a plain boolean field is a
correct per-claim guard. A `recordProgress` throw is caught, logged WARN, and
swallowed: the round is already durable, so the only risk is a later over-count
(NFR-R1). *Rationale:* the durable persist is exactly the event the marker
attests to; reusing the existing hook avoids a second round-boundary decorator.
*Rejected:* emitting on every round — one invisible comment per round bloats the
GitHub issue and polling economy (UX1, conditional-request cost) for no gain,
since the reset is idempotent and one marker suffices; a new sibling decorator —
duplicates the round-boundary plumbing `add-tracker-port` already centralized
here.

**D3 — GitHub: a `PROGRESS` marker that anchors abort counting but is NOT a
claim boundary.** (FR3, FR4, Q2) `GithubMarkerKind` gains `PROGRESS`;
`GithubStateWrites` posts it as a structural comment (hidden HTML + JSON, same
shape as the other markers). Abort-facts reconstruction — both
`GithubAbortFactsReader` (feed) and `GithubCommentBoundary`
(`fetchTask`) — counts only ABORT markers strictly after the latest `PROGRESS`
marker; with no `PROGRESS` marker present, the existing claim-streak logic is
the fallback. `PROGRESS` is deliberately excluded from
`GithubCommentBoundary.latestBoundaryIndex` (claim-holder resolution stays over
CLAIM/ABORT only): a progress marker sits inside an active claim and must not
read as "no active claim". *Rationale:* one anchor rule ("aborts after the
latest progress") expresses FR3 uniformly and subsumes the old
`abort-since-claim` behavior when no progress exists. *Rejected:* making
`PROGRESS` a general boundary — breaks `activeClaim` detection, which needs the
CLAIM to remain the latest boundary through the run.

**D4 — In-memory: `recordProgress` zeroes the abort tally.** (FR3) On
`recordProgress`, `TrackedTask` sets `abortCount = 0` and `lastAbortAt = null`
and appends a `PROGRESS` `CorrespondenceEntry`. The reference adapter models
"aborts since last durable progress" directly as live state (it does not
reconstruct from a comment stream), so a zeroing write is the faithful
in-memory equivalent of the GitHub anchor. *Rationale:* keeps the reference
adapter the simplest possible thing that passes the contract suite.
*Rejected:* storing a progress timestamp and filtering — needless for an
adapter that already holds authoritative live state.

## Risks / Trade-offs

- [Best-effort emit: the single first-round `recordProgress` write fails →
  the run keeps the pre-progress count, so a later abort over-counts] →
  accepted per NFR-R1 (never lost work, only a harsher backoff/earlier park);
  logged WARN (NFR-O1) for diagnosis. Not mitigated by per-round retries by
  choice (D2 cost trade-off).
- [`PROGRESS` marker interacts with `GithubCommentBoundary`'s claim/streak
  logic] → contained by D3: `PROGRESS` is an abort-count anchor only, never a
  claim boundary; covered by explicit reconstruction scenarios in the spec.
- [The `Tracker` port contract changes — every adapter (incl. future Jira) must
  implement `recordProgress`] → the shared contract suite gains the round-trip
  case, so a non-implementing adapter fails at build time, not in production.

## Migration Plan

- No data migration: tasks with no `PROGRESS` marker reconstruct exactly as
  today (the anchor rule falls back to the claim-streak logic). A task already
  in flight simply starts resetting on its next durable round.
- Rollback is a code revert; no persisted schema to unwind (the marker is an
  additive comment kind older adapters ignore as an unknown marker).

## Open Questions

- None outstanding; Q1 and Q2 from the proposal are resolved by D2 and D3.
