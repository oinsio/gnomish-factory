# Design: introduce-take-order

## Context

See `proposal.md` — Why. The measurement that drove this change: 38 signatures in
`:application` + `:bootstrap` enumerate four or more fields of one group, and the group is
the same at every level of the chain — from `TakeDisposition.dispose` at the entry point
down to `ResumeMechanics.resumeWithoutDecision` at the medium seam.

Two constraints shape the approach:

1. **The chain spans two execution media and two entry modes.** Manual `run` has no
   tracker, no claimed task and no instance identity; tracker-driven `take`/`serve` have
   all three. A single order type would force a null tracker onto every manual path — the
   escape hatch `implementation.md` exists to close.
2. **Seven declared sync pairs live inside the chain** (`manual-sync-pairs.md`). Each pair
   is two implementations of one recipe over host and container media, and today the pair
   contract includes "both take the same parameter list". That part of the contract is what
   this change deletes.

## Goals / Non-Goals

**Goals:**

- One value per lifetime: the invocation's description travels as one parameter, whatever
  depth of the chain it is at.
- The order types are records, so they are exempt from the parameter limit by construction
  — the same exemption Error Prone and Sonar both apply — and that exemption is legitimate
  only because these are named concepts with behavior, not bags (D3).

**Non-Goals:**

- Grouping collaborators (ports, adapters, policies). Those have slot lifetime, not
  invocation lifetime, and are `introduce-slot-wiring`'s subject.
- Introducing any interface, port or module edge. This change adds two records to
  `:application` and nothing else.

## Decisions

**D1 — Two records split by mode, not one split by depth.** `RunOrder` carries the five
fields both modes share (`cloneDir`, `@Nullable String base`, `PipelineDefinition
definition`, `InteractiveMode interactiveMode`, `boolean discardWork`); `TakeOrder` carries
a `RunOrder` plus `TrackerTask trackerTask`, `Tracker tracker`, `InstanceId instanceId`.
*Rationale:* the measured data has exactly this shape — the five manual-run signatures
(`GitModeRunner.run`, `ContainerGitModeRunner.run`, `GitResumeRunner.run`,
`GitResumeRunner.continueFrom`, `ContainerResumeRunner.run`) carry four of the five shared
fields and none of the tracker three, while the 33 tracker-driven signatures carry both
groups. Nesting keeps each record small (5 and 4 components) and keeps a null tracker off
every manual path. *Alternative rejected:* one nine-component `TakeOrder` with nullable
tracker fields — it makes "is this a manual run?" a null check at every consumer, which is
precisely the escape hatch `implementation.md` requires closing, and it would make the
record a bag by the criterion in D3.

**D2 — `TakeOrder` owns task identity; no consumer re-derives it.** `ref()` and `taskId()`
are methods on `TakeOrder`; the expression `trackerTask.snapshot().id()` — today repeated
in `TakeFreshClaim.claim`, `TakeContainerFreshClaim.claim` and their callers — is deleted.
*Rationale:* this is the behavior that makes the record a parameter object rather than an
argument bag (D3), and it removes a derivation the host/container twins each perform
separately. *Alternative rejected:* leaving identity derivation at the call sites — it
keeps two copies of a one-line rule inside a declared sync pair, which is exactly what
`manual-sync-pairs.md` bans.

**D3 — Records, and the criterion that makes the record exemption honest.** Both types are
`record`s, which the parameter-limit rule exempts by construction. That exemption is only
defensible when the record meets the literature's criterion for a parameter object rather
than a "bag of everything": the group must recur across many signatures (38 here), carry a
domain name (the *order* a gnome works to, entered in `docs/glossary.md` by this change),
and absorb behavior (D2). *Rationale:* recorded here because `add-parameter-count-gate`
will make the record exemption load-bearing, and a count gate cannot tell the two apart —
the criterion has to live in a design and in the rules, not in the gate. *Alternative
rejected:* a non-record class, so the limit applies to its constructor — it would fail the
seven-parameter rule at nine components and force an artificial sub-grouping that names no
real concept.

**D4 — Whole-object passing, including where a consumer uses a subset.**
`ResumeMechanics.resumeWithoutDecision` needs `cloneDir`, `interactiveMode`, `discardWork`,
`tracker`, `ref`, `instanceId` but not `definition`; it takes the whole `TakeOrder`.
*Rationale:* Fowler's Preserve Whole Object — the alternative is a per-consumer subset type
for each of the 38 sites, which reintroduces the clump one level down. The port interface
`ResumeMechanics` is the one place this is a real trade-off (it widens what a seam sees);
accepted because both implementations are in-module and the seam already receives the
tracker and the ref. *Alternative rejected:* narrowing views (`ResumeOrder`, `ClaimOrder`,
...) — three more types, each a subset of the same five fields, and every call site then
chooses which to build.

**D5 — Manual-run paths adopt `RunOrder` in this change** (proposal Q1). *Rationale:* they
are the reason `RunOrder` exists as a separate type; leaving them out would leave a fourth
hand-listed copy of the same five fields and make the split look arbitrary. *Alternative
rejected:* deferring them to `collapse-composition-roots` — that change is about
constructor injection in composition roots, a different concern, and the manual-run
signatures would have to be edited twice.

**Sync surfaces.** This change touches **seven declared pairs**, both ends of each:

| Pair | What this change does to it |
|------|------------------------------|
| `TakeFreshClaim` / `TakeContainerFreshClaim` | both `claim`/`claimAt` take `TakeOrder`; the identity derivation each performed (D2) is deleted from both |
| `TakeResumeRunner` / `TakeContainerResumeRunner` | both `resumeWithoutDecision`/`resumeDecided` take `TakeOrder` |
| `TakeResumeBootstrap` / `TakeContainerResumeBootstrap` | both take `TakeOrder` where they take clone dir + identity today |
| `TakeEngineExecution` / `TakeContainerEngineExecution` | both `run` take `TakeOrder` |
| `GitResumeRunner` / `ContainerResumeRunner` | both take `RunOrder` (no tracker on these paths) |
| `GitModeRunner` / `ContainerGitModeRunner` | both `run` take `RunOrder` |
| `HostResumeMechanics` / `ContainerResumeMechanics` | the shared `ResumeMechanics` interface's methods take `TakeOrder` (D4); both implementations follow |

No pair is collapsed and no new parallel implementation is added: this change makes the
existing pairs **thinner** — after it, the mirrored signatures differ in no order field, so
a divergence in that part of the contract becomes a compile error. Each pair's
`Kept in sync with` sentence is re-read and updated where it names a parameter that no
longer exists; the registry rows in `.claude/rules/manual-sync-pairs.md` are updated in the
same change. The seventh row is the one case where the shared abstraction already exists
(`ResumeMechanics<B>`), and this change strengthens it rather than adding a pair.

**Single-owner mechanisms.**

| Owner | Value (type) | Consumers | Old way removed | Enforced by |
|-------|--------------|-----------|-----------------|-------------|
| `RunOrder` | `RunOrder` (record) | `GitModeRunner.run:106`, `ContainerGitModeRunner.run:68`, `GitResumeRunner.run:100`, `GitResumeRunner.continueFrom:139`, `ContainerResumeRunner.run:86`, `ContainerResumeOutcomes.resumeFromRecordedPosition:37`, `ContainerResumeOutcomes.resumePaused:111`, `ContainerTerminalDrive.run:29`, `RunAssembler.assemble:68` (`:bootstrap`), and every `TakeOrder` consumer below through `order.run()` | the hand-listed quintuple `cloneDir, base, definition, interactiveMode, discardWork` in each of those signatures; no exemptions | the parameter type — each listed signature loses the five parameters, so a caller that still has them does not compile |
| `TakeOrder` | `TakeOrder` (record) | `TakeDisposition.dispose:132`, `TakeDispositionResume.resumeExisting:45` / `routeByShape:71`, `TakeClaimAndWork.claimAndWork:114` / `dispatchAfterClaim:179`, `TakeWorkRouter.locateAndWork:37` / `freshClaim:78` / `resume:132`, `TakeFreshClaim.claim:62` / `claimAt:114`, `TakeContainerFreshClaim.claim:44` / `claimAt:96`, `TakeResumeRunner.resumeWithoutDecision:119` / `resumeDecided:169`, `TakeContainerResumeRunner.resumeWithoutDecision:91` / `resumeDecided:138`, `HostResumeMechanics.resumeWithoutDecision:85` / `resumeDecided:105`, `ContainerResumeMechanics.resumeWithoutDecision:53` / `resumeDecided:77`, `TakeEngineExecution.run:120`, `TakeContainerEngineExecution.run:78`, `TakeTakeover.take:67`, `TakeDecisionResume.resume:64` / `ackAndResume:107`, `TakeLoadedBranchRoutes.route:42`, `TakeReconcileFinish.deliverCompleted:60`, `TakeCrashAbort.onCrash:78`, `TakeBareAuto.run:129`, `BareTakeClaimWalk.resolve:46`, `TakeSlotRunner` ctor:102, `TakeFinishReport.finish:134`, `TakePauseExit.finish:116`, `GuardedPark` ctor:59 and `attempt:103` | the hand-listed `trackerTask, tracker, instanceId` triple and the `cloneDir/base/definition/interactiveMode/discardWork` group in each; no exemptions | the parameter type |
| `TakeOrder` (identity) | `TaskRef` / `String` via `ref()`, `taskId()` | every consumer above that needs the task id | `trackerTask.snapshot().id()` at the call site — deleted everywhere; sweep `grep -rn "snapshot().id()" application/src/main bootstrap/src/main` must return only `TakeOrder`'s own body | the grep in task 5.1, run as part of the change; the method on the record is the only remaining source |

`TakeDispositionResume.afterReconciliation:116` was in this table until 2026-09-13 and is
not any more: `fix-claim-epoch-fence`, which lands first (see the Migration Plan), deletes
that method together with the `StaleEpoch` arm it served. Every count below therefore comes
from the 2026-09-12 scan and is re-taken at the start of this change, once that deletion has
landed.

The consumer lists are the measured ones, re-verified 2026-09-13 against the scan that
produced the baseline: 42 signatures take an order, of which **19 are today over the
seven-parameter limit** and the rest are edited for consistency (a chain where only the
long links take the order would leave the clump alive in the short ones). The five sites
added on that re-verification — `RunAssembler.assemble`, `TakeFinishReport.finish`,
`TakePauseExit.finish` and both `GuardedPark` members — carry three order fields each, below
the threshold the first pass used to build the table, and were over the limit and named in
no change. That gap is the failure `implementation.md` exists for, caught at the artifact
stage.

No row claims two values are one by construction, so no identity spec is required by
`testing.md`; the behavior-preservation requirement (NFR-R1) is carried by the existing
suite instead.

## Risks / Trade-offs

- **A 38-signature mechanical edit can hide one non-mechanical change.** → NFR-R1 makes it
  checkable: no spec expectation may be edited. Any spec that goes red is treated as
  evidence the refactor changed behavior, and the task stops rather than adjusting the
  spec.
- **`ResumeMechanics` sees more than it needs (D4).** → Accepted and recorded; the
  alternative (subset types) reintroduces the clump. Revisit only if a third
  implementation of the seam appears.
- **The queued take-path changes must be rebased.** → Named in the proposal's Impact and
  in task 6.2: after this lands, `add-claim-return`, `fix-claim-epoch-fence` and
  `add-pipeline-routing` have their task text updated through `/opsx:update`. This is the
  cost of the agreed ordering (refactor before the queue), chosen because the alternative
  grows the clump further.
- **Record `toString` on `TakeOrder` prints a `TrackerTask`.** → NFR-S1: the consumer
  sweep checks that no order type is logged whole and that `TrackerTask`'s own rendering is
  unchanged; nothing in this change adds a log site.

## Migration Plan

Not applicable — no durable state, no wire format and no operator-visible surface changes.
The change is source-only and lands in one branch; rollback is a revert.

**Sequencing.** This change lands after `add-base-ref-resolution` is committed and after
`fix-claim-epoch-fence`, which deletes one of this table's consumers
(`TakeDispositionResume.afterReconciliation`) and establishes `TaskGit.epochs()` as the
single owner `introduce-slot-wiring` then depends on. Everything else in the queue —
`add-claim-return`, `add-pipeline-routing` — is rebased after this change, not before
(task 6.2).
