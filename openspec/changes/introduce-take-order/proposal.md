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
behavior belongs to it. Naming it removes 19 of the 63 violations outright — and the clump from 42
signatures — without changing any behavior, and — more importantly — it removes the surface on which the seven declared
host/container sync pairs must be kept identical by hand.

Now, rather than after the change queue: `add-claim-return`, `fix-claim-epoch-fence` and
`add-pipeline-routing` all edit these same signatures, and every change that lands first
adds new hand-listed copies of the clump.

## What Changes

- **ADDED**: `RunOrder` — the mode-independent description of one invocation's work
  (clone directory, base override, pipeline definition, interactive mode, discard-work
  flag). Carried by both manual `run` and tracker-driven `take`.
- **ADDED**: `TakeOrder` — the tracker-driven order: a `RunOrder` plus the claimed task,
  the tracker port and this instance's identity. Owns the identity derivations every path
  currently repeats (`ref()`, `taskId()`).
- **MODIFIED**: every signature in the take/resume/serve chain that today enumerates four
  or more of those fields takes the order instead — 38 signatures across 25 files,
  including both ends of all seven declared sync pairs in the chain.
- **MODIFIED**: `.claude/rules/manual-sync-pairs.md` registry rows for the affected pairs,
  where the synchronized invariant text names parameters that no longer exist.
- No behavior change. No new module edge, no new port, no change to any spec requirement.

## Goals

- **G1** — Remove the order clump from every signature the design's consumer table names:
  42 signatures, 0 remaining after the change.
- **G2** — Bring the 19 parameter-limit violations the order alone resolves under the
  limit, measured by the same scan that produced the 63-violation baseline.
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

- **NFR-R1** — The change is behavior-preserving: the existing suite passes unchanged,
  with no spec's expectations edited to accommodate the refactor. A spec that must change
  is evidence the refactor altered behavior and stops the task.

### Non-Functional — Observability

- **NFR-O1** — Log messages, MDC keys and operator event codes are untouched; the
  log-expectation gate passes with no expectation file edited.

### Non-Functional — Security

- **NFR-S1** — `credentialEnvVarsToScrub` stays out of both orders (it belongs to the slot
  wiring, NG1), so no credential name gains a new carrier or a new `toString` surface.
  Neither order type may expose a generated `toString` that prints a credential-bearing
  field; this is checked by the consumer sweep, not assumed.

## Operator Experience Criteria

- **UX1** — No operator-visible change whatsoever: identical console output, identical
  log lines, identical tracker writes. The operator cannot tell this change happened.

## Success Metrics

- **M1** — Signatures in the design's consumer table still enumerating order fields: 42
  before, 0 after.
- **M2** — Parameter-limit violations in `src/main`: 63 before, **44** after — the 19 this
  change resolves. (`introduce-slot-wiring` takes it to 30, `collapse-composition-roots` to
  10, `add-parameter-count-gate` to 0.)
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
  `GitModeRunner` / `ContainerGitModeRunner`, plus the `ResumeMechanics` implementations
  `HostResumeMechanics` / `ContainerResumeMechanics`.
- **Rules**: `.claude/rules/manual-sync-pairs.md` registry text only.
- **Glossary**: two new domain terms (`order`, and the `run order` / `take order`
  distinction) in `docs/glossary.md`, added in this change per `process-invariants.md`.
- **Dependencies**: none added.
- **Sequencing**: lands after `add-base-ref-resolution` is committed **and after
  `fix-claim-epoch-fence`** (decided 2026-09-13: that change deletes
  `TakeDispositionResume.afterReconciliation`, one of this change's consumers, and makes
  `TaskGit.epochs()` the single owner of the tenure book that `introduce-slot-wiring` would
  otherwise duplicate). It lands before `introduce-slot-wiring`. The remaining queued
  changes — `add-claim-return`, `add-pipeline-routing` — have their task text rebased onto
  the new signatures afterwards (task 6.2).
- **Baseline freshness**: every count in this proposal comes from the 2026-09-12 scan, taken
  before `fix-claim-epoch-fence` landed; task 1.0 re-takes it.
