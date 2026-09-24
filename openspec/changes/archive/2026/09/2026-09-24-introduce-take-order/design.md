# Design: introduce-take-order

## Context

See `proposal.md` — Why; the decisions below implement FR1–FR6 and are held to NFR-R1. The measurement that drove this change: 38 signatures in
`:application` + `:bootstrap` enumerate four or more fields of one group (the first pass; the
consumer table below holds 53 after re-verification and apply), and the group is
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
fields and none of the tracker three, while the 33 tracker-driven signatures of that first pass carry
both groups. Nesting keeps each record small (5 and 4 components) and keeps a null tracker off
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
than a "bag of everything": the group must recur across many signatures (53 in the consumer table), carry a
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
for each of the 53 sites, which reintroduces the clump one level down. The port interface
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

**D6 — The one place the order's definition is rebound: `withDefinition` at law binding.**
*Driven by* FR4 and NFR-R1; added 2026-09-24 during apply. A fresh claim does not run under
the definition the order was built with: once the base commit is resolved it reads the task's
own law there (`TaskTierLaw`, FR13 of `add-base-ref-resolution`), and the engine and
`RunAssembly.assemble` receive that task definition. `TakeOrder.withDefinition` (delegating to
`RunOrder.withDefinition`) returns the order re-bound to the task's law; each fresh-claim
`claimAt` builds it once, right after `TaskTierLaw.bind` returns `Bound`, and passes only that
copy downstream. It is the only wither on either record. *Rationale:* downstream keeps one
source for the definition. This is Preserve Whole Object applied to a value that really does
change at one phase boundary. *Alternative rejected:* an explicit `PipelineDefinition`
parameter beside the order, which puts two definitions in scope with no rule for which one
wins. *Alternative rejected:* phase types (a separate bound order type, the way Gradle
separates a configuration from its resolved form). The compiler would then prove that the
engine gets a bound definition, but resume would have to "bind" by fiat, and with one rebinding
site that is a type spent on one line. It becomes the escalation path if a second rebinding
site or a second phase-dependent field appears. *Alternative rejected:* removing `definition`
from the order, which returns the parameter to most of the chain. *Guards:* in `claimAt` the
re-bound copy gets its own name and the pre-bind order is not used after it; the javadoc of
`withDefinition` names `claimAt` as its only caller; and task 3.4 adds a unit spec per medium
where the startup and task definitions differ and the engine must run the task's stage.
Existing fixtures use one pipeline for both, so they cannot tell the two apart. Resume
continues to run under the order's startup definition: that is the current behavior (NG4), and
whether resume should rebind from the pinned base is a question outside this change.

**Sync surfaces.** This change touches **seven declared pairs**, both ends of each:

| Pair | What this change does to it |
|------|------------------------------|
| `TakeFreshClaim` / `TakeContainerFreshClaim` | both `claim`/`claimAt` take `TakeOrder`; the identity derivation each performed (D2) is deleted from both |
| `TakeResumeRunner` / `TakeContainerResumeRunner` | both `resumeWithoutDecision`/`resumeDecided` take `TakeOrder` |
| `TakeResumeBootstrap` / `TakeContainerResumeBootstrap` | **unchanged** (corrected 2026-09-24): the host end is shared with manual `run --resume` (`GitResumeRunner`), where FR5 forbids a `TakeOrder`, and both carry only `cloneDir` and `taskId` of the clump. The pair is held together by the `ResumeMechanics.loadBranch(Path, String)` contract, not by identical helper signatures. The order is unpacked once, in `TakeLoadedBranchRoutes.route`, the only `loadBranch` caller. Passing a whole object to a helper that reads two of its fields is the stamp coupling Preserve Whole Object makes an exception for |
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
| `RunOrder` | `RunOrder` (record) | `GitModeRunner.run:106`, `ContainerGitModeRunner.run:67`, `GitResumeRunner.run:101`, `GitResumeRunner.continueFrom:142`, `ContainerResumeRunner.run:91`, `ContainerResumeOutcomes.resumeFromRecordedPosition:46`, `ContainerResumeOutcomes.resumePaused:113`, their declared twins `GitResumeContinuation.resumeFromRecordedPosition:76`, `GitResumeContinuation.resumePaused:124`, `ContainerTerminalDrive.run:29`, `GitResumeContinuation.resumeEscalated` / `ContainerResumeOutcomes.resumeEscalated` and the private `GitResumeContinuation.runToTerminalBoundary` (added 2026-09-23 during apply, pair consistency), the assembly seam `RunAssembly.assemble` (interface, `:application`) with its implementation `ManualRunAssembly.assemble` and delegate `RunAssembler.assemble:68` (`:bootstrap`) — the order reaches the seam from every caller, `order.run()` on the take side — the manual-mode assembly point `ManualRunDrive.driveResume`, `driveGit` and `driveInPlace`, all through one `ManualRunDrive.order` helper (`:bootstrap`, the only place a `RunArguments` becomes a `RunOrder`, D1), the take-side assembly points `TakeDispatcher.runBare:133` (from `TakeArguments`) and `ServeAssembly:66` (from the serve arguments, constructing `TakeSlotRunner`), the three pre-claim sites `TakeBareAuto.run:125`, `BareTakeClaimWalk.resolve:45` and the `TakeSlotRunner` constructor:101 (these run before any task is chosen, so no `TrackerTask` exists there; `tracker` and `instanceId` stay separate parameters until `introduce-slot-wiring` groups them), and every `TakeOrder` consumer below through `order.run()` | the hand-listed quintuple `cloneDir, base, definition, interactiveMode, discardWork` in each of those signatures; no exemptions | the parameter type — each listed signature loses the order fields it carries today, so a caller that still has them does not compile |
| `TakeOrder` | `TakeOrder` (record) | `TakeDisposition.dispose:119`, `TakeDispositionResume.resumeExisting:42` / `routeByShape:60`, `TakeClaimAndWork.claimAndWork:104` / `dispatchAfterClaim:160`, `TakeWorkRouter.locateAndWork:31` / `freshClaim:62` / `resume:96`, `TakeFreshClaim.claim:59` / `claimAt:99`, `TakeContainerFreshClaim.claim:42` / `claimAt:82`, `TakeResumeRunner.resumeWithoutDecision:114` / `resumeDecided:147`, `TakeContainerResumeRunner.resumeWithoutDecision:88` / `resumeDecided:115`, `HostResumeMechanics.resumeWithoutDecision:72` / `resumeDecided:83`, `ContainerResumeMechanics.resumeWithoutDecision:50` / `resumeDecided:65`, `TakeEngineExecution.run:115`, `TakeContainerEngineExecution.run:99`, `TakeTakeover.take:56`, `TakeDecisionResume.resume:60` / `ackAndResume:85`, `TakeLoadedBranchRoutes.route:41`, `TakeReconcileFinish.deliverCompleted:54` / `finishUncleaned:87` (added 2026-09-24: it calls `TakeFinishReport.finish`, so the order must reach it), `TakeCrashAbort.onCrash:74`, the terminal chain that carries the order from the engine result to the park guard — `TakeOutcomeDispatch.dispatch:51`, `TakeEscalationExit.exit:106`, `TakeReconcile.deliverPark:91` — and its ends `TakeFinishReport.finish:120`, `TakePauseExit.finish:109`, `GuardedPark` ctor:62 and `attempt:102` and its finish-side twin, the `FinishEffect` record (added 2026-09-24; a record, so exempt from the limit either way), together with the shorter convenience overloads of the same three exits (`TakeFinishReport.finish:76` / `:102`, `TakePauseExit.finish:72`, `TakeEscalationExit.exit:71`, added 2026-09-24; only specs call them, and leaving them would keep the triple alive beside the order); the two identity consumers that today read the id off a `TrackerTask` they receive directly, `TaskTierLaw.bind:100` and `TakeQuarantinePark.onQuarantine:51` | the hand-listed `trackerTask, tracker, instanceId` triple and the `cloneDir/base/definition/interactiveMode/discardWork` group in each. One exemption (2026-09-24): the resume bootstraps and `ResumeMechanics.loadBranch` keep `(cloneDir, taskId)`, because the host bootstrap is shared with manual `run --resume` (FR5; see the Sync surfaces row) | the parameter type. **Assembly points:** a `TakeOrder` is built in exactly three places, each right after the claimed task is fetched: `BareTakeClaimWalk.resolve:74` (after `tracker.fetchTask(candidate.ref())`, before `dispatchAfterClaim`), `TakeSlotRunner.run:179` (after `tracker.fetchTask(claimed)`) and `TakeDispatcher.runOneRef:77` (after `tracker.fetchTask(ref)`, the explicit `take <ref>` path; missing from this table until 2026-09-24). No site earlier in the chain can build one without a null task, which FR2 and D1 forbid. A built order is re-bound only through `withDefinition`, at the two fresh-claim `claimAt` sites (D6) |
| `TakeOrder` (identity) | `TaskRef` / `String` via `ref()`, `taskId()` | the five sites the sweep finds today (2026-09-23): `TakeWorkRouter:48`, `TakeFreshClaim:78`, `TakeContainerFreshClaim:61` (D2) and `TaskTierLaw:132`, `TakeQuarantinePark:59` — the last two take the order in task 3.9 and read `order.taskId()`; no site keeps a `TrackerTask` parameter for the id alone | `trackerTask.snapshot().id()` at the call site — deleted everywhere; sweep `grep -rn "snapshot().id()" application/src/main bootstrap/src/main` must return only `TakeOrder`'s own body | the grep in task 5.1, run as part of the change; the method on the record is the only remaining source |

`TakeDispositionResume.afterReconciliation:116` was in this table until 2026-09-13 and is
not any more: `fix-claim-epoch-fence` (archived 2026-09-14) deleted that method together
with the `StaleEpoch` arm it served; a 2026-09-23 grep of `application` and `bootstrap`
finds neither name. The over-limit counts below come from the 2026-09-23 re-scan (task 0.2);
the signature lists were re-verified against the tree the same day.

The consumer lists are the measured ones, re-verified 2026-09-13 against the scan that
produced the baseline: 42 signatures take an order, of which **27 are over the
seven-parameter limit** on the 2026-09-23 scan — counting the two `ResumeMechanics` interface
declarations changed by task 3.6, whose implementations are `@Override` and exempt — and 21
of them drop to seven or fewer; the rest are edited for consistency (a chain where only the
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
otherwise have found unlisted. Six consumers stay over the limit after this change:
`TakeOutcomeDispatch.dispatch` (eleven parameters become nine), whose remaining excess is
retry, park, abort and finish policy, not order fields, and is left to
`add-parameter-count-gate`; and the `TakeSlotRunner` constructor (sixteen become fifteen),
which carries only `cloneDir` and `definition` of the order — the rest is slot wiring, left
to `introduce-slot-wiring` (NG1); and the four fresh-claim methods (`TakeFreshClaim.claim` /
`claimAt` 9 each, `TakeContainerFreshClaim.claim` 10 / `claimAt` 9), where seven order fields
become one and the eight or nine left are slot wiring, also left to `introduce-slot-wiring`.
The plan counted those four among the ones that drop, and M2 said 40. The 2026-09-24 scan
after apply showed 44, and M2 was corrected. Passing `TakeClaimAndWork` whole, as
`TakeWorkRouter` does, would have reached 40, but it hands every fresh claim all of the
slot's wiring (stamp coupling) and settles in advance a grouping that `introduce-slot-wiring`
has not designed yet.

Apply on 2026-09-23 added five signatures to the table: `RunAssembly.assemble` and its
`@Override` implementation — `RunAssembler.assemble` could not take the order alone, since
its only caller is that implementation and holds no `cloneDir`, `base` or `discardWork` — and
the two `resumeEscalated` arms plus `runToTerminalBoundary`, for pair consistency. None of the
five is over the limit (the interface has seven parameters, the implementation is `@Override`,
the others five or fewer), so they do not change the violation count.
The table now holds 47 signatures.

Apply on 2026-09-24 added six more, none over the limit before or after:
`TakeReconcileFinish.finishUncleaned`, the four convenience overloads of the three exits and
the `FinishEffect` record (the completion's twin of `GuardedPark`). `ClaimGuard.stillOurs`,
`RevocationCheckingAttemptPersistence`, `RevocationHandler`, `AbortHandler.handle` and
`TakeResumeExecution.run` still take the tracker, the ref or the instance identity as separate
values. They are leaf helpers, the claim-identity boundary that `add-claim-return` types as
`ClaimIdentity` (D4), and each receives its values unpacked from the order at one call site.
The resume bootstraps, which the table never listed but task 3.5 named, stay unchanged, as
recorded above. The table now holds 53 signatures, and M2 is unchanged.

No row claims two values are one by construction, so no identity spec is required by
`testing.md`; the behavior-preservation requirement (NFR-R1) is carried by the existing
suite instead. D6 is the exception: it claims that the definition a fresh claim runs under is
the task's own, and the unit specs of task 3.4 check this with a startup definition that
differs from it.

## Risks / Trade-offs

- **A 53-signature mechanical edit can hide one non-mechanical change.** → NFR-R1 makes it
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
