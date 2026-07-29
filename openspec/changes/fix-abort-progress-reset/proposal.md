# Proposal: fix-abort-progress-reset

Supersedes: add-tracker-port (task 5.3, the abort-counter reset half of FR14)

## Why

`add-tracker-port` FR14 and design D10 require: *"The counter resets on the
first durably persisted round after claim"*, with the `Progress resets the
counter` scenario. Task 5.3 was marked done claiming exactly this, but the
mechanism was never built:

- Core never signals a persisted round — `RevocationCheckingAttemptPersistence`
  (the round-boundary hook) only re-reads the task for revocation.
- No structural "a round persisted" marker exists in either adapter
  (`GithubMarkerKind`, `CorrespondenceEntry.Kind`); `GithubCommentBoundary`
  itself documents the gap.
- Abort-facts reconstruction therefore counts aborts *across* progress
  boundaries: `GithubAbortFactsReader` folds every abort marker on the issue,
  and `TrackedTask.recordAbort` only increments.
- The requirement has zero test coverage (no `Progress resets` spec exists),
  violating the traceability rule.

The consequence is a correctness bug in the backoff/fuse policy: an instance
that made real progress and later hit a transient abort inherits stale aborts
from before the progress, so exponential backoff over-penalizes it and the
K-abort fuse can park a healthy task on `AwaitingHuman(infra)` for early
hiccups that progress already proved recoverable.

The marker stream alone cannot distinguish "reclaimed → made progress → aborted"
(count 1) from "reclaimed → aborted again" (count 2) — both read as
`CLAIM, ABORT, CLAIM, ABORT`. A dedicated durable-progress marker is required;
there is no reconstruction-only fix.

## What Changes

### ADDED
- `Tracker.recordProgress(TaskRef)` — the eleventh port operation: records a
  structural "a round persisted durably" marker so any instance reconstructs
  "aborts since last durable progress" from the tracker alone.
- Core emits `recordProgress` best-effort from the round-boundary hook on the
  **first** durable round after a claim (once per claim), never blocking the
  run on a tracker failure.
- GitHub adapter: a `PROGRESS` structural marker kind and its write; abort-facts
  reconstruction (both the `listReady` feed reader and the `fetchTask` boundary
  reader) anchors the count to the latest `PROGRESS` marker.
- In-memory adapter: `recordProgress` zeroes the abort count and appends a
  `PROGRESS` correspondence-thread entry.

### MODIFIED — **BREAKING**
- The `Tracker` port grows from ten to eleven operations. This corrects the
  "exactly the ten v1 operations" invariant frozen in `add-tracker-port`
  design D1 and its port Javadoc.

## Capabilities

### New Capabilities
<!-- none: this change extends existing capabilities -->

### Modified Capabilities
- `tracker-port`: the port gains `recordProgress`; abort facts are formally
  "aborts since the last durably persisted round", reconstructable by any
  instance.
- `tracker-take`: the take runner emits `recordProgress` at the first durable
  round boundary after claim, best-effort.
- `github-tracker`: a `PROGRESS` structural marker and reconstruction anchored
  to it.

## Impact

- **Port**: `com.github.oinsio.gnomish.app.port.tracker.Tracker` (+1 method),
  `AbortFacts` Javadoc.
- **Core**: `RevocationCheckingAttemptPersistence` (or a sibling round-boundary
  decorator) emits the first-round progress signal; take-runner wiring.
- **Adapters**: `inmemory` (`InMemoryTracker`, `TrackedTask`,
  `CorrespondenceEntry.Kind`), `github` (`GithubMarkerKind`, `GithubStateWrites`,
  `GithubAbortFactsReader`, `GithubCommentBoundary`).
- **Contract suite**: the shared `Tracker` contract gains a
  `recordProgress` round-trip case every adapter must pass.
- **Tests**: new specs for the `Progress resets the counter` scenario across
  core, both adapters, and the contract suite (TDD, 100% mutation gate).
- No new dependencies; no config surface change.

## Goals
- **G1**: Faithfully implement `add-tracker-port` FR14's reset clause — the
  abort counter reflects "aborts since the last durably persisted round".
- **G2**: Keep the reset reconstructable by any instance from the tracker alone
  (statelessness, NFR-R3 of add-tracker-port).

## Non-Goals
- **NG1**: Changing backoff or K-fuse *policy* — only the count fed into them.
- **NG2**: Per-round progress reporting/notes for humans (NFR-O1 surface); the
  progress marker is a machine-readable coordination fact, not a status update.
- **NG3**: Reworking revocation detection (FR15) beyond sharing the hook point.

## Requirements

### Functional
- **FR1**: The `Tracker` port SHALL expose `recordProgress(TaskRef)` that
  persists a structural durable-progress marker without changing the task's
  logical state or claim holder.
- **FR2**: On the first durable round after a claim, the factory SHALL call
  `recordProgress` exactly once per claim, best-effort (a tracker failure is
  logged and never aborts or blocks the run).
- **FR3**: Every adapter SHALL reconstruct `AbortFacts.count` as the number of
  abort markers recorded strictly after the latest durable-progress marker on
  the task; markers at or before it SHALL NOT be counted.
- **FR4**: A `PROGRESS` marker SHALL round-trip across instances — after
  `recordProgress`, a fresh `fetchTask`/`listReady` from a different instance
  observes the reset `AbortFacts`.

### Non-Functional — Reliability
- **NFR-R1**: `recordProgress` failures SHALL be isolated: the durable round is
  already committed before the call, so a failed marker only risks an
  over-count on a later abort, never lost work or a blocked run.
- **NFR-R2**: The reset SHALL be idempotent under repeated emission — a second
  `PROGRESS` marker within the same claim is harmless (the reconstruction
  anchors to the latest).

### Non-Functional — Observability
- **NFR-O1**: A `recordProgress` failure SHALL be logged at WARN with the task
  ref, so an unexpected over-count is diagnosable from logs.

### Non-Functional — Cost
- **NFR-C1**: The correct count SHALL restore the intended exponential backoff:
  progress before an abort resets the delay to the base, avoiding
  over-penalizing a recovered environment (add-tracker-port NFR-C1).

## Operator Experience Criteria
- **UX1**: The tracker thread SHALL remain human-readable: the progress marker
  renders invisibly (GitHub HTML comment) or as a terse machine line, never as
  noise a human must scroll past on every round.

## Success Metrics
- **M1**: The `Progress resets the counter` scenario passes against every
  adapter via the shared contract suite (zero exemptions).
- **M2**: 100% mutation score on the new production code (project gate), or an
  explicitly justified ≥95% for the best-effort wiring boundary.

## Open Questions
- **Q1**: Emit `recordProgress` on *every* durable round (idempotent, simpler
  wiring) or strictly the first after claim (fewer tracker writes)? Leaning
  first-only; resolved in design.
- **Q2**: Does the GitHub `PROGRESS` marker also serve as a claim/abort boundary
  in `GithubCommentBoundary.latestBoundaryIndex`, or only as the abort-count
  anchor? Resolved in design.
