# Proposal: introduce-slot-wiring

## Why

`introduce-take-order` names what one invocation works to. This change names the other
half: **what it works with**. Twenty signatures across the take and serve chain enumerate
four or more of the same eleven collaborators by hand — the run assembly, the task-git
capability set, the worktrees root, the MDC key, the abort handler and its threshold, the
credential names to scrub, the container support seam, the heartbeat, the claim-loss flag and
the trusted base tier. (The epoch book was a twelfth until
`fix-claim-epoch-fence` made `TaskGit.epochs()` its single owner; it now
travels inside `git` and is never a member here — see the dependency below.) `TakeClaimAndWorkFactory.forSlot` lists all
eleven and nothing else; `TakeDisposition`, `TakeBareAuto` and `TakeSlotRunner` each list
all eleven among their fourteen-to-sixteen parameters.

These eleven are not an invocation's data — they are a **slot's equipment**, fixed for as
long as the slot exists. That difference in lifetime is what makes the fix real rather than
cosmetic: the invocation's order stays a parameter (one, after the previous change), while
the equipment becomes fields of the components that use it, constructed once — per
`gnomish take` invocation, or once per `serve` daemon and shared by all of its slots — instead
of re-listed at every call.

The same lifetime confusion is what produced the violations in the first place. Twenty-four
offender files say in their own javadoc that they were "split purely to respect the
file-size guidance": a class holding these collaborators (twelve then, eleven now) as fields was cut into static
helper methods, and every field became a parameter, multiplied by every step of the chain.
`process-invariants.md` already forbids a split that does not split a responsibility, and
names "passing 20-parameter bundles" as one symptom; it does not yet name turning fields into
parameters as the transformation that produces that symptom. This change states it
and repairs the instances it produced in the take chain.

## What Changes

- **ADDED**: `SlotWiring` — the equipment one take slot works with, with two cohesive
  sub-groups named as their own types: `AbortFuse` (handler + threshold — the existing
  `app.take` record the engine executions already take, reused rather than duplicated) and `ClaimTenure` (heartbeat + claim-loss flag).
- **MODIFIED**: the twenty signatures that enumerate four or more of those collaborators
  take `SlotWiring` instead.
- **MODIFIED**: the take chain's static "recipes" become objects holding the wiring as
  fields rather than receiving it as parameters — `TakeFreshClaim`,
  `TakeContainerFreshClaim`, `TakeWorkRouter`, `TakeClaimAndWorkFactory`. This is the
  transformation that repairs the file-size-driven splits rather than papering over them.
- **MODIFIED**: `TakeSlotRunner` no longer takes a `RemoteOutageGate` and no longer decorates
  its own `TaskGit`; the outage-signaling decoration is applied at the serve assembly point
  through one owner, `RemoteOutageGates.signaling` (design D6).
- **MODIFIED**: `.claude/rules/process-invariants.md` — the file-size section gains the
  rule that a split must not convert fields into parameters, with the correct transformation
  named.
- **MODIFIED**: the `Kept in sync with` sentences of the two touched sync pairs, where they
  name a parameter list this change removes. Neither pair has a row in
  `.claude/rules/manual-sync-pairs.md` (both ends carry the marker), so the registry is
  not edited.
- No behavior change, no new module edge, no spec requirement changed.

## Goals

- **G1** — Remove the wiring clump from all twenty signatures the design's consumer
  table names: 20 before, 0 after.
- **G2** — Bring the parameter-limit violation count in `src/main` from the post-order
  figure of 44 to **32**, leaving only composition roots and out-of-chain cases for the two
  changes that follow.
- **G3** — Make the rule conflict impossible to repeat: after this change,
  `process-invariants.md` names fields-into-parameters as a forbidden split, so the next
  file-size split takes the other transformation.
- **G4** — Leave behavior bit-identical, as in the previous change.

## Non-Goals

- **NG1** — Composition roots and assemblies (`ManualRunRunner`, `ServeRuntimeAssembly`,
  `SubcommandDispatchFactory`, `ObservabilityAssembly`, `ManualRunAssembly`,
  `FeedAutomaton`). They need Facade Service extraction, a different transformation, and
  they are `collapse-composition-roots`.
- **NG2** — The build gate (`add-parameter-count-gate`).
- **NG3** — Re-litigating the file-size limit itself. The limit stays at 100–120 lines with
  a 200 hard cap; only the definition of an acceptable split is sharpened.
- **NG4** — Any behavior or spec change.
- **NG5** — Merging `SlotWiring` with `RunAssembly`. `RunAssembly` is one of its members,
  not its replacement.

## Users & Scenarios

- **U1** — A developer adding a collaborator to the take chain today threads it through up
  to eleven signatures on both media; afterwards it is one component of `SlotWiring`.
- **U2** — A developer who must split a 210-line class today reaches for static helpers,
  because that is the only shape the rules describe; afterwards the rule names the
  field-holding transformation and the parameter limit is not broken by obeying the size
  limit.
- **U3** — A reviewer reading `TakeFreshClaim` today must reconstruct which of fifteen
  parameters are equipment and which are the job; afterwards the signature says it.

## Requirements

### Functional

- **FR1** — `SlotWiring` carries exactly: `RunAssembly assembly`, `TaskGit git`, `Path
  worktreesRoot`, `String taskIdMdcKey`, `AbortFuse abort`, `List<String>
  credentialEnvVarsToScrub`, `ContainerTakeSupport containerTakeSupport`, `ClaimTenure
  tenure`, `TrustedBaseContext trustedBase` — no epoch book (FR3).
- **FR2** — `AbortFuse` (the existing record, glossary *abort fuse*) carries the abort handler
  and its positive threshold, and is the only pairing of those two that reaches
  `TakeCrashAbort`; no second type for the same pair is introduced.
- **FR3** — `ClaimTenure` carries the claim beat and the claim-loss flag, and is built only
  from the run's `TakeHeartbeat` (its `tenure()` accessor), which stays their owner. It does **not**
  carry the epoch book: `fix-claim-epoch-fence` makes `TaskGit.epochs()` that value's single
  owner, and a second carrier here would be exactly the defect `implementation.md` forbids.
- **FR4** — Every signature in the design's consumer table takes `SlotWiring` whole and
  re-lists none of its members.
- **FR5** — The take chain's static recipes become instances holding `SlotWiring` as a
  field: `TakeFreshClaim`, `TakeContainerFreshClaim`, `TakeWorkRouter` and
  `TakeClaimAndWorkFactory` no longer expose a static method that receives the wiring.
- **FR6** — Both ends of every declared sync pair this change touches move together.
- **FR7** — `process-invariants.md`'s file-size section states that a split converting
  fields into parameters is not a responsibility split, and names the field-holding
  transformation as the correct one.

### Non-Functional — Reliability

- **NFR-R1** — Behavior-preserving: the existing suite passes with no spec expectation
  edited. A red spec stops the task rather than being adjusted.

### Non-Functional — Observability

- **NFR-O1** — `taskIdMdcKey` keeps its exact value and the MDC key set by each resume
  bootstrap is unchanged; the log-expectation gate passes with no expectation file edited.

### Non-Functional — Security

- **NFR-S1** — `credentialEnvVarsToScrub` is a member of `SlotWiring`, so it gains a
  record `toString`. No `SlotWiring` value may be logged whole, and no credential *value*
  is carried — only declared variable names, as today. Checked by sweep, not assumed.

## Operator Experience Criteria

- **UX1** — No operator-visible change: identical console output, log lines and tracker
  writes.

## Success Metrics

- **M1** — Signatures in the design's consumer table still enumerating wiring members: 20
  before, 0 after. Of the 18 methods and constructors among them, 14 are in the
  parameter-count scan (the scanner of `introduce-take-order` task 0.2, which exempts record
  members and `@Override` methods); the other four are `TakeDispatcher` record methods, over
  seven by hand count but exempt from the scan.
- **M2** — Parameter-limit violations in `src/main` by that scanner: 44 before, **32**
  after — 12 consumer-table signatures drop to seven or fewer, and the 32 left are the 22
  composition sites of `collapse-composition-roots` plus the 10 of
  `add-parameter-count-gate` (measured 2026-09-25; the per-signature list is in the design).
- **M3** — Files whose javadoc cites the file-size guidance as the reason for a split, and
  which still hold a static method taking four or more collaborators: 0 in the take chain.
- **M4** — Test suite unchanged, zero spec expectation edits; mutation score unchanged.

## Open Questions

- **Q1** — Does `segments` (the container segment plan, carried by
  `TakeContainerFreshClaim`) belong in `ContainerTakeSupport` rather than as a separate
  parameter? Proposed answer: leave it a parameter in this change and note it for
  `collapse-composition-roots`, since moving it changes what the container support seam
  owns — a behavior-adjacent decision that does not belong in a behavior-preserving
  refactor.

## Impact

- **Modules**: `:application` (`app`, `app.take`, `app.serve` — which gains one public static
  method, `RemoteOutageGates.signaling`, design D6), `:bootstrap` (the take and serve command
  wiring). No new module edge.
- **Declared sync pairs touched**: `TakeFreshClaim` / `TakeContainerFreshClaim`,
  `TakeResumeRunner` / `TakeContainerResumeRunner`. `GitResumeRunner` /
  `ContainerResumeRunner` is not touched: it serves only the claimless
  `gnomish run --resume` path (design, Sync surfaces).
- **Rules**: `.claude/rules/process-invariants.md` (file-size section, FR7).
- **Glossary**: `slot wiring`, `claim tenure` added to `docs/glossary.md`; the existing
  *abort fuse* entry already names the abort pair.
- **Depends on**: `introduce-take-order` — the same signatures are edited, and doing them in
  the other order means editing each twice. Also **depends on `fix-claim-epoch-fence`**,
  which must land first: it makes `TaskGit.epochs()` the single owner of the epoch book and
  deletes the separate book parameter of every claiming signature (the container-bundle
  builders in `:bootstrap` keep theirs, fed only from `TaskGit.epochs()`). Landing this change first
  would create a second owner of that book. Decided 2026-09-13; it is the one place the
  agreed "refactor ahead of the queue" order yields.
