# Proposal: introduce-take-order

## Why

A `/check-issue` verification on 2026-09-12 confirmed the reported parameter-count
violations and found them to be a codebase-wide pattern, not five files: **63 signatures
in `src/main` exceed the seven-parameter limit** of `.claude/rules/process-invariants.md`
(records and `@Override` excluded by construction), 47 of them in `:application`.

A frequency count of parameter names across those signatures shows the cause is not 63
independent lapses but **one missing domain concept, re-enumerated by hand across 42 signatures**: the
description of the work a single invocation performs — where the clone is, which base to
start from, which pipeline, which console mode, whether to discard leftovers, and (for
tracker-driven modes) which task, through which tracker, under which instance identity.
Every layer of the take/serve chain re-lists those fields and passes them down unchanged.

This is Fowler's *data clump* in its textbook form: the same group appears in many
signatures, it has a name in the project's own language (the order a gnome works to), and
behavior belongs to it. Naming it removes 21 of the 65 violations counted by the fresh 2026-09-23 scan (task 0.2) outright — and the clump from 42
signatures — without changing any behavior, and — more importantly — it removes the surface on which the seven declared
host/container sync pairs must be kept identical by hand.

Now, rather than after the change queue: `add-claim-return` and `add-pipeline-routing`
both edit these same signatures, and every change that lands first adds new hand-listed
copies of the clump.

## What Changes

- **ADDED**: `RunOrder` — the mode-independent description of one invocation's work
  (clone directory, base override, pipeline definition, interactive mode, discard-work
  flag). Carried by both manual `run` and tracker-driven `take`.
- **ADDED**: `TakeOrder` — the tracker-driven order: a `RunOrder` plus the claimed task,
  the tracker port and this instance's identity. Owns the identity derivations every path
  currently repeats (`ref()`, `taskId()`).
- **MODIFIED**: every signature in the take/resume/serve chain that today enumerates four
  or more of those fields takes the order instead — the 53 signatures of the design's
  consumer table,
  including both ends of all seven declared sync pairs in the chain.
- **MODIFIED**: `.claude/rules/manual-sync-pairs.md` registry rows for the affected pairs,
  where the synchronized invariant text names parameters that no longer exist.
- No behavior change. No new module edge, no new port, no change to any spec requirement.

## Goals

- **G1** — Remove the order clump from every signature the design's consumer table names:
  53 signatures (42 measured, eleven added during apply 2026-09-23 and 2026-09-24 — see the design's
  single-owner table), 0 remaining after the change.
- **G2** — Bring the 21 parameter-limit violations the order alone resolves under the
  limit, measured by the scan task 0.2 describes (65-violation baseline, 2026-09-23).
- **G3** — Reduce what the seven declared host/container pairs must keep identical by
  hand: after the change, the mirrored signatures differ in no order field.
- **G4** — Leave the behavior of every take, serve and run path bit-identical.

## Non-Goals

- **NG1** — The per-slot wiring clump (`assembly`, `git`, `worktreesRoot`, `abortHandler`,
  ...). That is `introduce-slot-wiring`, which lands next and depends on this one.
- **NG2** — Composition roots (`ManualRunRunner` and the `*Assembly` classes). That is
  `collapse-composition-roots`.
- **NG3** — The build gate enforcing the limit. That is `add-parameter-count-gate`, which
  lands last, deliberately: a count gate cannot distinguish a real parameter object from
  an argument bag, so enabling it before the refactor would reward bags.
- **NG4** — Any behavior change, spec change, or new capability. A diff that alters what
  the factory does is out of scope by definition here.
- **NG5** — Splitting `RunOrder`/`TakeOrder` further into per-stage sub-orders.

## Users & Scenarios

- **U1** — A developer adding a parameter to the take chain today edits between 6 and 12
  signatures across both execution media and both entry points, and a missed one compiles
  fine if the types line up. After this change the field is added in one record.
- **U2** — A developer implementing one of the seven host/container pairs must reproduce
  the twin's parameter list exactly. After this change the twins take the same single
  order value, so a divergence is a compile error rather than a review obligation.
- **U3** — An `/opsx:apply` sub-agent receives a task naming a signature to change. Today
  the task must enumerate the parameter list it is threading; after this change it names
  one value.

## Requirements

### Functional

- **FR1** — `RunOrder` carries `cloneDir`, `base` (nullable `--base` override),
  `definition`, `interactiveMode` and `discardWork`, and nothing else.
- **FR2** — `TakeOrder` carries one `RunOrder`, the claimed `TrackerTask`, the `Tracker`
  port and the `InstanceId`, and nothing else.
- **FR3** — `TakeOrder` is the only place the task identity is derived from the claimed
  task: `ref()` and `taskId()` are its methods, and no consumer re-derives either from
  `trackerTask.snapshot()`.
- **FR4** — Every signature listed in the design's consumer table takes the order value
  whole; none re-lists a field the order already carries.
- **FR5** — Manual `run` paths, which have no tracker, take `RunOrder` and never a
  `TakeOrder`; the tracker-driven paths take `TakeOrder` and reach the shared fields
  through it.
- **FR6** — Both ends of each declared sync pair in the chain change together, and each
  pair's `Kept in sync with` text is updated where it names a parameter that is gone.

### Non-Functional — Reliability

- **NFR-R1** — The change is behavior-preserving. Spec files necessarily change at their
  **call sites** — the 48 spec files in `:application` and `:bootstrap` that construct or
  invoke a signature in the design's consumer table are edited to build and pass an order —
  and nowhere else: no `then:` block, no `where:` table, no asserted log line, no
  `LogCaptureSupport` attachment and no expected console or tracker output is edited. A
  spec whose *expectation* must change is evidence the refactor altered behavior and stops
  the task. "Call-site-only edits" in `tasks.md` means exactly this.

### Non-Functional — Observability

- **NFR-O1** — Log messages, MDC keys and operator event codes are untouched. The
  build-wide log-expectation gate — the root task `checkLogExpectationGate`, which no
  module's own `check` runs — passes, and no spec's log capture attachment or asserted log
  line is edited (there is no separate expectation file: a spec's `LogCaptureSupport`
  attachment is the expectation).

### Non-Functional — Security

- **NFR-S1** — `credentialEnvVarsToScrub` stays out of both orders (it belongs to the slot
  wiring, NG1), so no credential name gains a new carrier or a new `toString` surface.
  Neither order type may expose a generated `toString` that prints a credential-bearing
  field; this is checked by the consumer sweep, not assumed.

## Operator Experience Criteria

- **UX1** — No operator-visible change whatsoever: identical console output, identical
  log lines, identical tracker writes. The operator cannot tell this change happened.

## Success Metrics

- **M1** — Signatures in the design's consumer table still enumerating order fields: 53
  before (42 measured, eleven added during apply 2026-09-23 and 2026-09-24), 0 after.
- **M2** — Parameter-limit violations in `src/main`: 65 before, 44 after — the 2026-09-23
  baseline (task 0.2) minus the 21 consumer-table signatures that drop to seven or fewer.
  Six consumer-table signatures stay over the limit and are not subtracted:
  `TakeOutcomeDispatch.dispatch` (11 → 9), the `TakeSlotRunner` constructor (16 → 15), and
  the four fresh-claim methods `TakeFreshClaim.claim`/`claimAt` (15 → 9 each) and
  `TakeContainerFreshClaim.claim` (16 → 10) / `claimAt` (15 → 9). Past the dispatch, the
  rest is slot wiring (NG1). Corrected 2026-09-24: the plan counted the fresh-claim four as
  dropping, but seven order fields folding into one leaves nine. (`introduce-slot-wiring`, `collapse-composition-roots` and
  `add-parameter-count-gate` each take their own step down to 0.)
- **M3** — Test suite: unchanged pass count, zero spec expectation edits (NFR-R1).
- **M4** — Mutation score stays at the module gate for every touched module.

## Open Questions

- **Q1** — Do the five manual-`run` signatures that carry only the `RunOrder` half
  (`GitModeRunner.run`, `ContainerGitModeRunner.run`, `GitResumeRunner.run/continueFrom`,
  `ContainerResumeRunner.run`) adopt `RunOrder` in this change, or stay untouched until
  `collapse-composition-roots`? Proposed answer: adopt now — they are the reason `RunOrder`
  is split out of `TakeOrder` at all, and leaving them would create a fourth hand-listed
  copy of the same fields.

## Impact

- **Modules**: `:application` (the `app`, `app.take`, `app.serve` packages), `:bootstrap`
  (the manual-run entry points). No other module is touched; no module gains a dependency.
- **Declared sync pairs touched** (all seven, both ends each): `TakeFreshClaim` /
  `TakeContainerFreshClaim`, `TakeResumeRunner` / `TakeContainerResumeRunner`,
  `TakeResumeBootstrap` / `TakeContainerResumeBootstrap`, `TakeEngineExecution` /
  `TakeContainerEngineExecution`, `GitResumeRunner` / `ContainerResumeRunner`,
  `GitModeRunner` / `ContainerGitModeRunner`, `GitResumeContinuation` /
  `ContainerResumeOutcomes`. The `ResumeMechanics<B>` implementations `HostResumeMechanics` /
  `ContainerResumeMechanics` also change, but they are a shared abstraction, not a declared
  pair (no markers).
- **Rules**: `.claude/rules/manual-sync-pairs.md` registry text only.
- **Glossary**: two new domain terms (`order`, and the `run order` / `take order`
  distinction) in `docs/glossary.md`, added in this change per `process-invariants.md`.
- **Dependencies**: none added.
- **Sequencing**: both predecessors have landed — `add-base-ref-resolution` (archived
  2026-09-13) and `fix-claim-epoch-fence` (archived 2026-09-14; decided 2026-09-13 as a
  predecessor because it deleted `TakeDispositionResume.afterReconciliation`, one of this
  change's consumers, and made `TaskGit.epochs()` the single owner of the tenure book that
  `introduce-slot-wiring` would otherwise duplicate). This change lands before
  `introduce-slot-wiring`. The remaining queued changes — `add-claim-return`,
  `add-pipeline-routing` — have their task text rebased onto the new signatures afterwards
  (task 6.2).
- **Baseline freshness**: the Why section's 63 / 47 come from the 2026-09-12 scan, taken
  before `fix-claim-epoch-fence` landed; task 0.2 re-took it on 2026-09-23 (65), and G2 and
  M2 are stated against the fresh scan.
