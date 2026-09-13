# Design: fix-claim-epoch-fence

## Context

See proposal.md — Why. What shapes the approach:

- The one comparison of two epochs in the codebase is `ClaimEpoch.isStaleAgainst`, called from
  `BranchShapeClassifier.isStale` on `BranchTipFacts(tipEpoch, liveEpoch)`. The live epoch reaches
  it only through `GitTaskBranches.shapeAt` (`epochs.epochFor(taskId)`); the claimless readers
  (`TipEnvelopeReader`, used by `status`, `usage`, the board) pass `null`, so the fence was inert
  everywhere except the take path. Nothing walks history epochs; `GitShowTip.tipEpoch()` reads the
  top commit only.
- Every tracker adapter mints and stamps the epoch and never compares it. `add-claim-return`
  (active) uses the epoch as the holder's identity for a fenced tracker operation — the
  write-side comparison the canon prescribes — and reads it through `ClaimEpochBook.epochFor`, an
  API this change does not touch.
- Three zombie fences already exist and are specified: the fast-forward-only push
  (`ZombieFenceSpec`, FR7 of add-claim-heartbeat), the round-boundary revocation check
  (`RevocationCheckingAttemptPersistence`), and heartbeat self-fencing (`ClaimLossFlag`).
- The tenure record is filled by `EpochRecordingTracker`, applied by `EpochRecordingTrackerFactory`
  over every discovered provider in the Spring bean
  `TrackerAdapterConfiguration.trackerAdapterRegistry`. `ManualRunConfiguration.taskGit` builds the
  git writers over the same `ClaimEpochBook` bean. The two are joined only by Spring: any assembly
  that bypasses the beans — every end-to-end fixture does — gets a git layer over
  `ClaimEpochSource.NONE` and a tracker that records into a fresh, unread book
  (`TakeCommandSeams.DEFAULTS`). `TakeCommandSeams.withEpochs` exists to close that gap and has
  no caller.
- Precedents this design reuses: `BaseHeadDefaultBoundarySpec` (an allowlisted whole-tree scan in
  `:bootstrap`), `TakeLifecycleEscalateResumeSpecBase` over `TwoInstanceTakeFixture` (two
  instances, one origin, one tracker), and the kill-point matrix (`TransitionKillPointSpec` over
  `KillPointWorlds`).

## Goals / Non-Goals

Design-level boundaries beyond the proposal's:

- The change deletes a mechanism and re-homes a wiring; it adds no new port method, no new commit
  kind, no new operator event, and no new network call.
- The epoch stays a value the writers stamp and the tracker records. Its only read-side use after
  this change is the repair log line (the live epoch, for correlation) and `add-claim-return`'s
  identity check at the tracker.
- Not in scope: any diagnosis derived from comparing epochs on the branch (proposal NG2, NG3).

## Decisions

**D1 — Remove the read-side fence rather than invert or narrow it** (FR1, FR2).
`BranchShape.StaleEpoch` leaves the sealed set; `BranchTipFacts` loses `liveEpoch` (and
`tipEpoch`, unless a status or inspect renderer is found to consume it — the task checks by grep);
`BranchShapeClassifier` applies delivery, envelope, progression in that order; `ClaimEpoch
.isStaleAgainst` is deleted and `Comparable` kept only if a consumer remains; `TakeDispositionResume
.afterReconciliation` and the `StaleEpoch` arms of every exhaustive switch go. The recovery table
in ADR 0003 loses its `StaleEpoch` row, and `RecoveryDisposition.DISCARD` is removed if that row
was its only user. *Rationale:* the canon is unambiguous that older epochs in a log are history and
that staleness belongs to an arriving write compared with the highest token the medium accepted;
git's fast-forward rule is that comparison for the branch, and it already exists. *Alternative
rejected — invert the rule (`tip > live` means "I was superseded"):* correct in principle (Raft's
step-down), but unreachable at pickup, since no claim newer than the one just issued can exist; as
a shape it would be dead code and an unkillable mutant under the 100% gate, and at write time the
remote's rejection already carries the fact. *Alternative rejected — keep the shape and make the
reconciler re-stamp the tip:* a commit written only to satisfy a classifier is a second writer of
tenure boundaries with no consumer, and it hides rather than removes the wrong comparison.

**D2 — The `TaskGit` bundle owns the tenure record; the claiming commands wrap their tracker from
it** (FR4). `TaskGit` gains a `ClaimEpochBook epochs` component beside its five capabilities.
`TakeCommandSupport.resolveTracker` (the one funnel both `TakeCommand` and
`ServeCommand.provisionTracker` resolve through) calls the four-argument
`TrackerAdapterFactory.create(secrets, config, instanceId, book)` and wraps the result in
`EpochRecordingTracker(tracker, book)`, with `book = git.epochs()`. `TrackerAdapterConfiguration
.trackerAdapterRegistry` stops wrapping; `EpochRecordingTrackerFactory` and its spec are deleted;
`TakeCommandSeams.epochs` and `withEpochs` are deleted; `TakeCommand` and `TakeWorkRouter` read the
book from the bundle. *Rationale:* the stamping half and the recording half must be one object, and
the only value both halves already receive is `TaskGit`; carrying the book inside it makes a
mismatched assembly unconstructible (the "escape hatch is gone" item of
`.claude/rules/implementation.md`) instead of merely unusual. *Alternative rejected — keep the
registry-wrapping bean and make every fixture call it:* a convention, and exactly the one the seven
fixtures already violate; nothing would fail when the eighth does too. *Alternative rejected — a
separate `TenureWiring` value passed beside `TaskGit`:* two parameters that must be built from one
book is the transposition hazard `process-invariants.md` names; the bundle already exists for the
purpose of keeping such collaborators together.

**D3 — Fixtures assemble through the owner, and the reclaim cycle joins the real-medium suites**
(FR5, FR6). `TaskGitFixture.real()` builds a fresh `ClaimEpochBook` by default; the claimless
variant is renamed to say so (`realClaimless()`) and used only by `StatusCommand`, `UsageCommand`,
and board fixtures. `TakeCommandFixture`, `TwoInstanceTakeFixture`, `AppAssemblyFixture`,
`ServeObservabilityFixture`, `ContainerSupportFixture`, `ResumeSpecFixtureBase`, and
`KillPointWorlds` take their book from the bundle they build. Two regression specs extend the
two-instance base: the existing escalate-resume base gains the assertions of the spec scenario
"Escalated, returned, reclaimed" (tip epoch before reclaim, new epoch after), and a new
crash-reap-reclaim base realizes "Crashed, reaped, reclaimed" over a salvaged tip. Both are
sequenced as red before D1 lands (M2). *Rationale:* `testing.md` requires the identity a design
claims to be asserted on the real medium; the claim here is "a test assembly stamps like
production". *Alternative rejected — a unit spec of `TakeDispositionResume` with a stamped
`BranchTipFacts`:* it would pass today for the wrong reason (the fake supplies whichever epoch the
author chose) and is exactly the component-spec-green, flow-red shape this defect had.

**D4 — Enforcement and durable record** (FR5, FR7). A `:bootstrap` architecture spec,
`ClaimlessGitBoundarySpec`, scans `application/src`, `bootstrap/src`, and `test-fixtures/src` for
`ClaimEpochSource.NONE` and `new ClaimEpochBook()` and fails on any file outside an allowlist:
the claimless commands' wiring, `TaskGitFixture`, the bean that creates the book, and the unit
specs of the book and the decorator themselves. The ADR 0003 disposition table drops its
`StaleEpoch` row and its "Block-allocated sequence counters" paragraph gains the sentence that
states the principle: *an epoch comparison belongs to the medium at write time, against the
highest epoch it has accepted; a reader that compares history against its own claim has no
fence, only false positives*. The glossary's *Fence* entry names the two real fences and *Claim
epoch* says provenance and tracker identity. `testing.md` gains a short section, "Fixtures
assemble through production owners": a decorator or wiring applied only at the composition root
is not covered by end-to-end specs that assemble by hand; the owner must be a value the command
receives, and an architecture spec must pin the fixture path. *Rationale:* the principle outlives
the change and the checklist prevents the next instance of the same gap; both belong outside the
archived folder. *Alternative rejected — a `.claude/rules/` entry only:* a rule without a build
gate is the "convention" that `implementation.md` item 4 refuses.

## Sync surfaces

Sync surfaces: none — this change adds no parallel implementation and touches no declared pair.
The ADR 0003 disposition table and `BranchShape.recoveryOwner()`/`disposition()` are edited
together, and `BranchShapeSpec` ("each shape declares its recovery owner and disposition") keeps
them aligned as before; that is a documented realization, not a hand-synced implementation pair.

## Single-owner mechanisms

| Owner | Value (type) | Consumers | Old way removed | Enforced by |
|-------|--------------|-----------|-----------------|-------------|
| `TaskGit.epochs()` — the process's tenure record, built once in `ManualRunConfiguration.taskGit` (production) and `TaskGitFixture.real()` (specs) | `ClaimEpochBook` | `TakeCommandSupport.resolveTracker` (wraps the tracker for `TakeCommand.run` and `ServeCommand.provisionTracker`); `TakeCommand` → `TakeWorkRouter` (repair log epoch); `TakeClaimAndWork.dispatchAfterClaim` (`epochs.ended`); `GitTaskStore`, `GitTaskBranches`, `GitTaskWorktrees` (constructed from the same book inside the bean); `ContainerRunSupportFactory`/`ContainerRunSupport` (container writers; take the book from the bundle instead of a separate parameter); `ManualRunRunner` (passes the bundle, no longer a separate book) | `TrackerAdapterConfiguration.trackerAdapterRegistry` wrapping and `EpochRecordingTrackerFactory` (deleted); `TakeCommandSeams.epochs`/`withEpochs` (deleted); `ClaimEpochSource.NONE` in every claiming assembly and fixture (replaced by the bundle's book). Exemptions: `status`, `usage`, `board`, plain `run` wiring and their fixtures — claimless by design | the `TaskGit` record component (a claiming command cannot be handed a bundle without a book); `ClaimlessGitBoundarySpec` allowlist scan; the two FR6 flow specs asserting stamped epochs on the real medium |

Identity claimed: "the epoch the tracker recorded for a claim and the epoch stamped on every
commit of that tenure are one value". Identity spec: the escalate-return-reclaim flow spec (D3)
asserts the second tenure's `Gnomish-Claim-Epoch` trailer equals the epoch the in-memory tracker
issued for the second claim, end to end, with no book wired by the test.

## Crash consistency

The change removes a transition (the reconcile-then-reclassify pass) and adds none. The two
reclaim cycles of FR6 join the kill-point matrix as reads of already-enumerated windows: the
salvaged tip after a mid-round kill and the parked tip after an escalation are existing shapes
(`InProgress`, `Parked`/`Answered`) whose owners are unchanged; the specs assert the shape on
pickup and that a second pickup is a no-op (`crash-consistency.md` item 10).

## Risks / Trade-offs

- [A lapsed holder pushes between the reclaimer's read and its first push] → unchanged from
  today: the reclaimer's push is refused as non-fast-forward, the reconciler discards its local
  round under the lease, and the run continues from origin. One round of churn, logged. Q1 in the
  proposal tracks whether a tenure-boundary commit should close it.
- [`TaskGit` grows a sixth component; some port-fake specs construct it by hand] → they pass a
  fresh `ClaimEpochBook`, which the boundary spec allowlists for unit specs of the bundle itself;
  count and list them in the task report.
- [Deleting `RecoveryDisposition.DISCARD` if `StaleEpoch` was its only user removes a documented
  disposition kind] → the ADR's "roll forward / discard" vocabulary keeps *discard* for the
  reconciler's diverged-local rule, which is not a shape disposition; the enum reflects shapes only.
- [`TrackerAdapterFactory.create` three-argument default and the plugin sample] → unchanged: the
  funnel now calls the four-argument form, and the default still routes to it for adapters that
  ignore epochs. `DelegatingDecoratorCompletenessSpec` keeps guarding `EpochRecordingTracker`.

## Migration Plan

No data migration: existing branches stamped by earlier tenures classify by content after the
change, which is the fix. Deploy order is a normal release. The two `gf-tests` tasks already
quarantined are returned by the operator (proposal NG4) and resume without further action once the
release is running. Rollback restores the quarantine on every reclaim and nothing else; no state
written by this version is unreadable by the previous one.
