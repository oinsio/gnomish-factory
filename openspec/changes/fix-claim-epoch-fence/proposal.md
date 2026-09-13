# fix-claim-epoch-fence

## Why

Every legitimate reclaim of a task branch is quarantined in production. The branch-tip
classifier compares the tip commit's claim epoch with the epoch of the claim the reclaiming
instance was just issued, and names the tip `StaleEpoch` when the tip is older — which it always
is after a tenure ended, because the previous tenure stamped it and the new tenure has written
nothing yet. The replica reconciler, named as that shape's recovery owner, reconciles local against
origin and never touches epochs, so the second classification is stale again and the take stops
with `BranchQuarantineException`. Observed on tasks #56 and #57 of the `gf-tests` project on
2026-09-09: escalated, returned to ready by a human, quarantined on the reclaim. The same path is
taken after a crash (salvage commit stamped, claim reaped, reclaim) and after a release — the
whole roll-forward half of the recovery table in `docs/adr/0003-crash-consistency.md` is
unreachable on the GitHub tracker.

The comparison is the wrong one. In every reference design of fencing tokens (Kleppmann's fencing
tokens, Chubby sequencers, Raft terms, Kafka leader epochs), staleness is a property of an
*arriving write* compared against the *highest token already accepted by the medium*; artifacts
of older epochs are ordinary history. The factory's real branch fence is git's fast-forward-only
push, and its tracker fence is the round-boundary revocation check — both already exist and are
proven by specs. The read-side comparison added nothing to them and made every reclaim a false
positive.

The defect shipped because no end-to-end spec can see it: the book of live epochs is filled by a
decorator applied in a Spring bean in `:bootstrap`, and every end-to-end fixture assembles the
commands by hand with a raw tracker registry and a claimless git layer, so in tests no commit is
ever stamped and no live epoch ever exists.

## What Changes

- **REMOVED**: the read-side epoch fence. The classifier no longer compares the tip epoch with the
  live claim; `StaleEpoch` leaves the closed shape set (eleven shapes become ten); the
  reconcile-then-reclassify route and the `DISCARD` disposition that existed only for it go with
  it. **BREAKING** for readers of the sealed hierarchy — every exhaustive switch is corrected by
  the compiler.
- **MODIFIED**: the meaning of the claim epoch on the branch. Stamping stays exactly as it is:
  every commit of a tenure carries `Gnomish-Claim-Epoch`. The stamp is provenance — which tenure
  wrote this commit — and the identity the tracker-side fenced return (`add-claim-return`) keys on.
  It is no longer a classification input.
- **ADDED**: one owner for the tenure record. The `TaskGit` bundle carries the process's
  `ClaimEpochBook`; the two commands that claim wrap the tracker they resolve with that same book.
  The separate registry-wrapping bean and the `TakeCommandSeams.epochs` seam — the escape hatch
  the fixtures fell through — are removed, so an assembly whose writers stamp one book and whose
  tracker records another can no longer be built.
- **ADDED**: end-to-end fixtures assemble through that owner, and two regression specs on the real
  medium drive the reclaim cycle with stamped tips: escalate → human returns → reclaim by another
  instance; crash mid-tenure → reap → reclaim by another instance. A `:bootstrap` architecture spec
  keeps `ClaimEpochSource.NONE` confined to the claimless commands.
- **MODIFIED**: the durable record. The `task-branch-contract` capability's shape table and
  claim-epoch requirement, the ADR 0003 disposition table and its fencing rationale, and the
  glossary entries *Fence* and *Claim epoch* say what the epoch is for and what it is not.

## Goals

- G1: every branch shape with a roll-forward disposition is reachable from a reclaim in production —
  a returned, reaped, or released task resumes from its branch content.
- G2: the end-to-end suite assembles claim, stamp, and classify through the same wiring production
  uses, so a fence regression turns a spec red instead of reaching an operator.
- G3: the durable documents state the epoch's role truthfully: provenance on the branch, identity
  at the tracker, never a read-side staleness test.

## Non-Goals

- NG1: a tenure-boundary marker commit on reclaim (Raft's no-op entry). The window it closes —
  a lapsed holder's push landing between the reclaimer's read and its first push — already ends in
  a fast-forward rejection and one discarded round; whether that churn is worth a commit per
  reclaim is a separate decision (Q1).
- NG2: an epoch-named diagnosis on push rejection. The round-boundary revocation check already
  names the new holder from the tracker; a second diagnosis from the branch would be a second
  owner of the same message.
- NG3: fencing tracker-side writes (comments, labels) by epoch, or a history-walking epoch
  regression audit. Both remain future work (Q2, Q3).
- NG4: unblocking `gf-tests` #56/#57. That is an operator step on the target project's branches
  (amend the tip's trailer away and return the task); this change makes it unnecessary for the
  next task, not for those two.
- NG5: the two side findings of the same report — `openspec` missing from the verification
  subprocess's `PATH`, and duplicated `"type"` keys in the committed JSON envelopes.

## Users & Scenarios

- U1: an operator returns an escalated task to ready. The next instance to claim it resumes it as
  `Parked` or `Answered` and continues; nothing on the branch needs a human hand.
- U2: a `serve` instance dies mid-round. The reaper returns the task, another instance claims it,
  classifies the salvaged tip as `InProgress`, and resumes at the recorded position.
- U3: a developer writes a two-instance end-to-end spec. Both instances stamp and record epochs
  exactly as production does, without wiring a book by hand.

## Requirements

### Functional

- FR1: the branch-shape classifier SHALL classify a tip from its file set and envelope versions
  only. It SHALL NOT take a live claim epoch as input, and the shape set SHALL contain no
  `StaleEpoch`.
- FR2: a reclaim of a branch whose tip carries an epoch older than the new claim SHALL route the
  branch by its content shape — `Parked`, `Answered`, `InProgress`, `CompletedUncleaned`,
  `Created`, `Delivered` — exactly as a tip carrying no epoch would.
- FR3: every commit made under a claim SHALL still carry that claim's epoch trailer, and the repair
  log line written on a non-clean pickup SHALL still report the live epoch (unchanged behaviour,
  restated because FR1 removes the only reader that compared it).
- FR4: the tenure record SHALL have one owner. The `TaskGit` bundle SHALL carry the
  `ClaimEpochBook` its writers stamp from; `take` and `serve` SHALL wrap the tracker they resolve
  with that book; no other component SHALL construct a book or wrap a tracker for it. The registry
  wrapper in `:bootstrap` and the `TakeCommandSeams.epochs` seam SHALL be removed.
- FR5: a git layer built with `ClaimEpochSource.NONE` SHALL exist only in the claimless commands
  (`status`, `usage`, `board`, plain `run`); every other assembly, test fixtures included, SHALL
  build through the owner of FR4. An architecture spec in `:bootstrap` SHALL enforce the allowlist.
- FR6: two regression specs on a real bare origin with two independently assembled instances SHALL
  assert, with the tip stamped by the first tenure: (a) escalate → human reply and return → reclaim
  resumes and delivers; (b) crash mid-round → salvage → reap → reclaim resumes at the recorded
  position. Each SHALL assert the reclaiming tenure's commits carry the new epoch.
- FR7: the `task-branch-contract` delta, `docs/adr/0003-crash-consistency.md`, `docs/glossary.md`,
  and the testing rule SHALL be updated in this change (see design D4).

### Non-Functional Reliability

- NFR-R1: recovery of every remaining shape is unchanged: idempotent, convergent, one owner per
  shape. The kill-point matrix gains the two reclaim cycles of FR6 with a second pickup asserted as
  a no-op.
- NFR-R2: no new durable step, commit, push, or tracker write is introduced; a reclaim performs
  the same writes as before minus the reconcile-then-reclassify pass.

### Non-Functional Observability

- NFR-O1: the repair log line on a non-clean pickup keeps its fields (task, shape, live epoch,
  action, recovery count). No new operator event is introduced; the two `StaleEpoch`-specific
  phrases in the repair-action and diagnosis renderers are removed.

### Non-Functional Security

- NFR-S1: epoch stamps keep carrying only counters — no paths, hostnames, or credentials
  (unchanged).

### Non-Functional Cost

- NFR-C1: no additional git network calls on any path; the removed reconcile pass saves one fetch
  per formerly quarantined reclaim.

## Operator Experience Criteria

- UX1: returning an escalated task to ready is the whole recovery: the operator sees the task go
  `Working` again and the stage resume, never a "Branch quarantined" comment for a healthy branch.
- UX2: the quarantine comment's closing hint ("inspect the task branch's `.gnomish-task/` files")
  is true for every shape that can still produce it — the three envelope diagnoses.

## Success Metrics

- M1: `grep -rn "StaleEpoch" --include='*.java' --include='*.groovy' --include='*.md'` over
  `src/`, `docs/`, and `openspec/specs/` returns zero hits after archive.
- M2: both FR6 regression specs are green and are red when the FR1 fence is reintroduced
  locally (verified once during implementation and recorded in the task report).
- M3: the FR5 architecture spec passes and lists every `ClaimEpochSource.NONE` site by file; the
  list contains only the claimless commands and their fixtures.
- M4: PIT stays at the module gates with no new exemption; the removed `isStaleAgainst` and the
  dead reconcile route leave no surviving mutant behind.

## Open Questions

- Q1: should a reclaim write a tenure-boundary commit before its first round, to turn the
  "lapsed holder pushes first" window from a discarded round into a cheap re-read? Deferred; decide
  on observed churn (NG1).
- Q2: should `usage` or `status` report an epoch regression along the branch history (a commit
  stamped lower than its parent) as an audit finding? Deferred (NG3).
- Q3: should the sweeper ignore tracker comments stamped with an epoch lower than the current
  claim's? Deferred (NG3).

## Capabilities

### New Capabilities

- none

### Modified Capabilities

- `lifecycle/task-branch-contract`: "Total branch-shape classification" loses `StaleEpoch` and the
  live-epoch input; "Claim-epoch fencing" is rewritten as provenance plus the two real fences; a
  new requirement pins the single-owned tenure record.

## Impact

- **Sequencing (decided 2026-09-13)**: this change lands **before** the
  parameter-limit family — `introduce-take-order`, `introduce-slot-wiring`,
  `collapse-composition-roots`, `add-parameter-count-gate`. Two reasons, both
  from this change's own single-owner table: it makes `TaskGit.epochs()` the
  sole source of the tenure book, which `introduce-slot-wiring` would otherwise
  duplicate inside `SlotWiring`; and it deletes
  `TakeDispositionResume.afterReconciliation`, a consumer
  `introduce-take-order` would otherwise edit first and lose second. No change
  to this proposal's own scope follows — only its position in the queue.
- `:domain` — `BranchShape` (shape removed), `BranchShapeClassifier` (fence rule removed),
  `BranchTipFacts` (live-epoch field removed), `ClaimEpoch` (`isStaleAgainst` removed; `Comparable`
  stays only if a consumer remains).
- `:adapters:git` — `BranchTipFactsReader`, `TipEnvelopeReader`, `GitTaskBranches.shapeAt` (no
  live epoch threaded to the classifier; the book is still needed for stamping).
- `:application` — `TakeDispositionResume` (route and `afterReconciliation` removed),
  `TakeLoadedBranchRoutes`, `BranchRepairAction`, `BranchShapeDiagnosis`, `TakeCommandSeams`
  (seam removed), `TakeCommandSupport.resolveTracker` and `ServeCommand.provisionTracker`
  (wrap with the book), `TaskGit` (carries the book), `EpochRecordingTracker` (unchanged class,
  new application site).
- `:bootstrap` — `TrackerAdapterConfiguration.trackerAdapterRegistry` (wrapping removed),
  `EpochRecordingTrackerFactory` and its spec (deleted), `ManualRunConfiguration.taskGit`
  (book into the bundle), fixtures listed in design D3, one new architecture spec, two regression
  specs, kill-point matrix rows.
- `:test-fixtures` — `TaskGitFixture.real()` builds a book by default.
- Docs — `openspec/specs/lifecycle/task-branch-contract` (via delta), `docs/adr/0003`,
  `docs/glossary.md`, `.claude/rules/testing.md`, `.claude/rules/manual-sync-pairs.md` is not
  touched (no pair affected).
- Sequencing — independent of `add-claim-return` (which reads the book through the same
  `ClaimEpochBook` API and is unaffected by where the wrapper is applied) and of
  `signal-outage-gate-on-origin-contact` (no push or fetch path is modified here).
