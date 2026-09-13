# Proposal: introduce-slot-wiring

## Why

`introduce-take-order` names what one invocation works to. This change names the other
half: **what it works with**. Twenty-two signatures across the take and serve chain enumerate
four or more of the same twelve collaborators by hand — the run assembly, the task-git
capability set, the worktrees root, the MDC key, the abort handler and its threshold, the
credential names to scrub, the container support seam, the heartbeat, the claim-loss flag and
the trusted base tier. (The epoch book was a twelfth until
`fix-claim-epoch-fence` made `TaskGit.epochs()` its single owner; it now
travels inside `git` and is never a member here — see the dependency below.) `TakeClaimAndWorkFactory.forSlot` lists all
twelve and nothing else; `TakeDisposition`, `TakeBareAuto` and `TakeSlotRunner` each list
twelve of their fifteen-to-seventeen parameters from the same group.

These twelve are not an invocation's data — they are a **slot's equipment**, fixed for as
long as the slot exists. That difference in lifetime is what makes the fix real rather than
cosmetic: the invocation's order stays a parameter (one, after the previous change), while
the equipment becomes fields of the components that use it, constructed once per slot
instead of re-listed at every call.

The same lifetime confusion is what produced the violations in the first place. Twenty-four
offender files say in their own javadoc that they were "split purely to respect the
file-size guidance": a class holding twelve collaborators as fields was cut into static
helper methods, and every field became a parameter, multiplied by every step of the chain.
`process-invariants.md` already forbids a split that does not split a responsibility; it
does not yet say that turning fields into parameters is such a split. This change states it
and repairs the instances it produced in the take chain.

## What Changes

- **ADDED**: `SlotWiring` — the equipment one take slot works with, with two cohesive
  sub-groups named as their own types: `AbortPolicy` (handler + threshold, already consumed
  as a pair by `TakeCrashAbort`) and `ClaimTenure` (heartbeat + claim-loss flag).
- **MODIFIED**: the twenty signatures that enumerate four or more of those collaborators
  take `SlotWiring` instead.
- **MODIFIED**: the take chain's static "recipes" become objects holding the wiring as
  fields rather than receiving it as parameters — `TakeFreshClaim`,
  `TakeContainerFreshClaim`, `TakeWorkRouter`, `TakeClaimAndWorkFactory`. This is the
  transformation that repairs the file-size-driven splits rather than papering over them.
- **MODIFIED**: `.claude/rules/process-invariants.md` — the file-size section gains the
  rule that a split must not convert fields into parameters, with the correct transformation
  named.
- **MODIFIED**: `.claude/rules/manual-sync-pairs.md` rows whose invariant text names a
  parameter list this change removes.
- No behavior change, no new module edge, no spec requirement changed.

## Goals

- **G1** — Remove the wiring clump from all twenty-two signatures the design's consumer
  table names: 22 before, 0 after.
- **G2** — Bring the parameter-limit violation count in `src/main` from the post-order
  figure of 44 to **30**, leaving only composition roots and out-of-chain cases for the two
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
  to twelve signatures on both media; afterwards it is one component of `SlotWiring`.
- **U2** — A developer who must split a 210-line class today reaches for static helpers,
  because that is the only shape the rules describe; afterwards the rule names the
  field-holding transformation and the parameter limit is not broken by obeying the size
  limit.
- **U3** — A reviewer reading `TakeFreshClaim` today must reconstruct which of fifteen
  parameters are equipment and which are the job; afterwards the signature says it.

## Requirements

### Functional

- **FR1** — `SlotWiring` carries exactly: `RunAssembly assembly`, `TaskGit git`, `Path
  worktreesRoot`, `String taskIdMdcKey`, `AbortPolicy abort`, `List<String>
  credentialEnvVarsToScrub`, `ContainerTakeSupport containerTakeSupport`, `ClaimTenure
  tenure`, `TrustedBaseContext trustedBase` — no epoch book (FR3).
- **FR2** — `AbortPolicy` carries the abort handler and its positive threshold, and is the
  only pairing of those two that reaches `TakeCrashAbort`.
- **FR3** — `ClaimTenure` carries the claim beat and the claim-loss flag. It does **not**
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

- **M1** — Signatures in the design's consumer table still enumerating wiring members: 22
  before, 0 after; of these, 14 were over the parameter limit.
- **M2** — Parameter-limit violations in `src/main`: 44 before, **30** after — the 20
  composition sites of `collapse-composition-roots` plus the 10 of
  `add-parameter-count-gate`.
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

- **Modules**: `:application` (`app`, `app.take`, `app.serve`), `:bootstrap` (the take and
  serve command wiring). No new module edge.
- **Declared sync pairs touched**: `TakeFreshClaim` / `TakeContainerFreshClaim`,
  `TakeResumeRunner` / `TakeContainerResumeRunner`, `GitResumeRunner` /
  `ContainerResumeRunner`.
- **Rules**: `.claude/rules/process-invariants.md` (file-size section, FR7) and
  `.claude/rules/manual-sync-pairs.md` (affected rows).
- **Glossary**: `slot wiring`, `abort policy`, `claim tenure` added to `docs/glossary.md`.
- **Depends on**: `introduce-take-order` — the same signatures are edited, and doing them in
  the other order means editing each twice. Also **depends on `fix-claim-epoch-fence`**,
  which must land first: it makes `TaskGit.epochs()` the single owner of the epoch book and
  deletes the separate-parameter form (including at `ContainerRunSupport`/
  `ContainerRunSupportFactory`, two of this change's consumers). Landing this change first
  would create a second owner of that book. Decided 2026-09-13; it is the one place the
  agreed "refactor ahead of the queue" order yields.
