# Design: introduce-take-order

## Context

See `proposal.md` — Why; the decisions below implement FR1–FR6 and are held to NFR-R1. The measurement that drove this change: 38 signatures in
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

**D1 — Two records split by mode, not one split by depth.** *Driven by* FR1, FR2, FR5. `RunOrder` carries the five
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
record a bag by the criterion in D3. *Alternative rejected:* reusing the existing
`RunArguments` record (`application/.../app/RunArguments.java`) as the shared order,
since it already carries four of the five fields (`dir`, `base`, `interactiveMode`,
`discardWork`). `RunArguments` is the parsed command line of one `gnomish run` invocation:
it also holds `mode`, `resume`, `taskSource`, `taskId` and `fromStage`, which no runner
reads, and it holds no `PipelineDefinition`, which every runner needs and which is loaded
from `.gnomish/` only after parsing. It is one tier earlier than an order — flags before
validation, not a resolved instruction to a runner — and the take path has its own parsed
record, `TakeArguments` (`dir`, `refs`, `interactiveMode`, `base`, `discardWork`,
`takeover`), so sharing `RunArguments` would give `take` five dead fields to fill with
nulls. `RunOrder` is therefore the resolved form every entry point produces from its own
parsed record plus the loaded definition: `ManualRunDrive.driveResume` / `driveGit`
(`:bootstrap`) from `RunArguments`, `TakeDispatcher` from `TakeArguments`, and
`ServeAssembly` from the serve arguments when it constructs `TakeSlotRunner`. The parsed
records and the order are not a duplicate pair: the first are inputs the parsers own, the
second is a value the runners own, and those three sites are the only places one becomes
the other.

**D2 — `TakeOrder` owns task identity; no consumer re-derives it.** *Driven by* FR3. `ref()` and `taskId()`
are methods on `TakeOrder`; the expression `trackerTask.snapshot().id()` — today repeated
in `TakeFreshClaim.claim`, `TakeContainerFreshClaim.claim` and their callers — is deleted.
*Rationale:* this is the behavior that makes the record a parameter object rather than an
argument bag (D3), and it removes a derivation the host/container twins each perform
separately. *Alternative rejected:* leaving identity derivation at the call sites — it
keeps two copies of a one-line rule inside a declared sync pair, which is exactly what
`manual-sync-pairs.md` bans.

**D3 — Records, and the criterion that makes the record exemption honest.** *Driven by* FR1, FR2 and NG3. Both types are
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

**D4 — Whole-object passing, including where a consumer uses a subset.** *Driven by* FR4.
`ResumeMechanics.resumeWithoutDecision` needs `cloneDir`, `interactiveMode`, `discardWork`,
`tracker`, `ref`, `instanceId` but not `definition`; it takes the whole `TakeOrder`.
*Rationale:* Fowler's Preserve Whole Object — the alternative is a per-consumer subset type
for each of the 38 sites, which reintroduces the clump one level down. The port interface
`ResumeMechanics` is the one place this is a real trade-off (it widens what a seam sees);
accepted because both implementations are in-module and the seam already receives the
tracker and the ref. *Alternative rejected:* narrowing views (`ResumeOrder`, `ClaimOrder`,
...) — three more types, each a subset of the same five fields, and every call site then
chooses which to build. *Boundary with `add-claim-return`:* the terminal chain
(`TakeOutcomeDispatch` down to `GuardedPark`) carries only `tracker, ref, instanceId`, which
is the claim's identity, and `add-claim-return` types the tracker-side half of it as
`ClaimIdentity(holder, epoch)` for the release verb. The two do not overlap: `ClaimIdentity`
is what the tracker adapter needs to retire a tenure (holder and epoch, no ref, no tracker);
`TakeOrder` is what the take chain needs to act on a task. The chain takes `TakeOrder` here,
and when `add-claim-return` is rebased (task 6.2) it derives `ClaimIdentity` from the order
at the release sites rather than adding a second identity parameter beside it.

**D5 — Manual-run paths adopt `RunOrder` in this change.** *Driven by* FR5, answering Q1. *Rationale:* they
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
| `GitResumeContinuation` / `ContainerResumeOutcomes` | both `resumeFromRecordedPosition`/`resumePaused` take `RunOrder` in place of `definition`, `interactiveMode`, `discardWork` (and `cloneDir` on the container end) |
| `HostResumeMechanics` / `ContainerResumeMechanics` — *not a declared pair*: no `Kept in sync with` marker on either end; they are the two implementations of the shared abstraction `ResumeMechanics<B>` | the interface's methods take `TakeOrder` (D4); both implementations follow by the compiler |

No pair is collapsed and no new parallel implementation is added: this change makes the
existing pairs **thinner** — after it, the mirrored signatures differ in no order field, so
a divergence in that part of the contract becomes a compile error. Each pair's
`Kept in sync with` sentence is re-read and updated where it names a parameter that no
longer exists; the registry rows in `.claude/rules/manual-sync-pairs.md` are updated in the
same change. The last row is not one of the seven: it is the one case where the shared
abstraction already exists (`ResumeMechanics<B>`), so it carries no markers, and this change
strengthens it rather than adding a pair. Fourteen markers therefore belong to the chain —
two per declared pair.

**Single-owner mechanisms.**

| Owner | Value (type) | Consumers | Old way removed | Enforced by |
|-------|--------------|-----------|-----------------|-------------|
| `RunOrder` | `RunOrder` (record) | `GitModeRunner.run:109`, `ContainerGitModeRunner.run:70`, `GitResumeRunner.run:103`, `GitResumeRunner.continueFrom:149`, `ContainerResumeRunner.run:86`, `ContainerResumeOutcomes.resumeFromRecordedPosition:48`, `ContainerResumeOutcomes.resumePaused:122`, their declared twins `GitResumeContinuation.resumeFromRecordedPosition:76`, `GitResumeContinuation.resumePaused:129`, `ContainerTerminalDrive.run:29`, `RunAssembler.assemble:68` (`:bootstrap`), the manual-mode assembly point `ManualRunDrive.driveResume` and `driveGit` (`:bootstrap`, the only place a `RunArguments` becomes a `RunOrder`, D1), the take-side assembly points `TakeDispatcher.runBare:163` (from `TakeArguments`) and `ServeAssembly:63` (from the serve arguments, constructing `TakeSlotRunner`), the three pre-claim sites `TakeBareAuto.run:126`, `BareTakeClaimWalk.resolve:47` and the `TakeSlotRunner` constructor:99 (these run before any task is chosen, so no `TrackerTask` exists there; `tracker` and `instanceId` stay separate parameters until `introduce-slot-wiring` groups them), and every `TakeOrder` consumer below through `order.run()` | the hand-listed quintuple `cloneDir, base, definition, interactiveMode, discardWork` in each of those signatures; no exemptions | the parameter type — each listed signature loses the five parameters, so a caller that still has them does not compile |
| `TakeOrder` | `TakeOrder` (record) | `TakeDisposition.dispose:128`, `TakeDispositionResume.resumeExisting:45` / `routeByShape:71`, `TakeClaimAndWork.claimAndWork:108` / `dispatchAfterClaim:173`, `TakeWorkRouter.locateAndWork:37` / `freshClaim:78` / `resume:132`, `TakeFreshClaim.claim:62` / `claimAt:114`, `TakeContainerFreshClaim.claim:44` / `claimAt:96`, `TakeResumeRunner.resumeWithoutDecision:122` / `resumeDecided:172`, `TakeContainerResumeRunner.resumeWithoutDecision:91` / `resumeDecided:138`, `HostResumeMechanics.resumeWithoutDecision:75` / `resumeDecided:95`, `ContainerResumeMechanics.resumeWithoutDecision:53` / `resumeDecided:77`, `TakeEngineExecution.run:120`, `TakeContainerEngineExecution.run:89`, `TakeTakeover.take:68`, `TakeDecisionResume.resume:65` / `ackAndResume:108`, `TakeLoadedBranchRoutes.route:42`, `TakeReconcileFinish.deliverCompleted:60`, `TakeCrashAbort.onCrash:79`, the terminal chain that carries the order from the engine result to the park guard — `TakeOutcomeDispatch.dispatch:55`, `TakeEscalationExit.exit:110`, `TakeReconcile.deliverPark:94` — and its ends `TakeFinishReport.finish:140`, `TakePauseExit.finish:119`, `GuardedPark` ctor:64 and `attempt:109`; the two identity consumers that today read the id off a `TrackerTask` they receive directly, `TaskTierLaw.bind:103` and `TakeQuarantinePark.onQuarantine:52` | the hand-listed `trackerTask, tracker, instanceId` triple and the `cloneDir/base/definition/interactiveMode/discardWork` group in each; no exemptions | the parameter type. **Assembly points:** a `TakeOrder` is built in exactly two places, each right after the claimed task is fetched — `BareTakeClaimWalk.resolve:80` (after `tracker.fetchTask(candidate.ref())`, before `dispatchAfterClaim`) and `TakeSlotRunner.run:183` (after `tracker.fetchTask(claimed)`). No site earlier in the chain can build one without a null task, which FR2 and D1 forbid |
| `TakeOrder` (identity) | `TaskRef` / `String` via `ref()`, `taskId()` | the five sites the sweep finds today (2026-09-23): `TakeWorkRouter:48`, `TakeFreshClaim:78`, `TakeContainerFreshClaim:61` (D2) and `TaskTierLaw:132`, `TakeQuarantinePark:59` — the last two take the order in task 3.9 and read `order.taskId()`; no site keeps a `TrackerTask` parameter for the id alone | `trackerTask.snapshot().id()` at the call site — deleted everywhere; sweep `grep -rn "snapshot().id()" application/src/main bootstrap/src/main` must return only `TakeOrder`'s own body | the grep in task 5.1, run as part of the change; the method on the record is the only remaining source |

`TakeDispositionResume.afterReconciliation:116` was in this table until 2026-09-13 and is
not any more: `fix-claim-epoch-fence` (archived 2026-09-14) deleted that method together
with the `StaleEpoch` arm it served; a 2026-09-23 grep of `application` and `bootstrap`
finds neither name. Every count below therefore comes from the 2026-09-12 scan, taken before
that deletion, and is re-taken at the start of this change (task 0.2).

The consumer lists are the measured ones, re-verified 2026-09-13 against the scan that
produced the baseline: 42 signatures take an order, of which **19 are today over the
seven-parameter limit** and the rest are edited for consistency (a chain where only the
long links take the order would leave the clump alive in the short ones). The five sites
added on that re-verification — `RunAssembler.assemble`, `TakeFinishReport.finish`,
`TakePauseExit.finish` and both `GuardedPark` members — carry three order fields each, below
the threshold the first pass used to build the table, and were over the limit and named in
no change. That gap is the failure `implementation.md` exists for, caught at the artifact
stage. The 2026-09-23 review added five more: the three intermediate callers without which
the order cannot reach those four (`TakeOutcomeDispatch.dispatch`, `TakeEscalationExit.exit`,
`TakeReconcile.deliverPark` — they carry only `tracker, ref, instanceId`, so the first pass
did not count them as order sites), and the two remaining `snapshot().id()` readers
(`TaskTierLaw.bind`, `TakeQuarantinePark.onQuarantine`) that the sweep in task 5.1 would
otherwise have found unlisted. `TakeOutcomeDispatch.dispatch` is the one consumer that stays
over the limit after this change (eleven parameters become nine): its remaining excess is
retry, park, abort and finish policy, not order fields, and is left to
`add-parameter-count-gate`.

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
  in task 6.2: after this lands, `add-claim-return` and `add-pipeline-routing` have their
  task text updated through `/opsx:update`. This is the
  cost of the agreed ordering (refactor before the queue), chosen because the alternative
  grows the clump further.
- **Record `toString` on `TakeOrder` prints a `TrackerTask`.** → NFR-S1: the consumer
  sweep checks that no order type is logged whole and that `TrackerTask`'s own rendering is
  unchanged; nothing in this change adds a log site.

## Migration Plan

Not applicable — no durable state, no wire format and no operator-visible surface changes.
The change is source-only and lands in one branch; rollback is a revert.

**Sequencing.** Both predecessors have landed: `add-base-ref-resolution` (archived
2026-09-13) and `fix-claim-epoch-fence` (archived 2026-09-14), which deleted one of this
table's consumers (`TakeDispositionResume.afterReconciliation`) and established
`TaskGit.epochs()` as the single owner `introduce-slot-wiring` depends on. Nothing in the
queue precedes this change any more; `add-claim-return` and `add-pipeline-routing` are
rebased after it, not before (task 6.2), and it lands before `introduce-slot-wiring`.
