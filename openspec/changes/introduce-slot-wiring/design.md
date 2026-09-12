# Design: introduce-slot-wiring

## Context

See `proposal.md` — Why. Two measurements shape this design.

First, the clump: twenty signatures carry four or more of twelve collaborators, and three
of them (`TakeClaimAndWorkFactory.forSlot`, `TakeDisposition`, `TakeSlotRunner`,
`TakeBareAuto`) carry eleven or twelve — they are the clump and almost nothing else.

Second, the provenance: `grep -rl "file-size" */src/main` intersected with the offender
list gives twenty-four files, including `TakeFreshClaim`, `TakeContainerFreshClaim`,
`ContainerResumeOutcomes` and `TakeClaimAndWork` — each states in its own javadoc that it
exists because a class grew past the size limit. The split that produced them turned a
field set into a parameter list and then multiplied it along the chain. This design fixes
both the instances and the rule that invited them.

The chain's shape after `introduce-take-order`: each signature carries one `TakeOrder` (the
job) plus a hand-listed tail of equipment. This change collapses the tail.

## Goals / Non-Goals

**Goals:**

- Equipment becomes fields, held once per slot; the job stays a parameter.
- Two cohesive sub-groups get their own names rather than being flattened into one
  nine-to-twelve-member record.
- The rule that produced the pattern is amended in the same change, so the repair is not
  undone by the next file-size split.

**Non-Goals:**

- Facade Service extraction for composition roots. `SlotWiring` is a value handed *to*
  components; a composition root is the code that *builds* collaborators, and grouping its
  arguments into a record would be the "deodorant" the literature warns against. That work
  is `collapse-composition-roots`.

## Decisions

**D1 — `SlotWiring` is a record with nine components, two of them named sub-groups.** The
twelve collaborators become `RunAssembly assembly`, `TaskGit git`, `Path worktreesRoot`,
`String taskIdMdcKey`, `AbortPolicy abort`, `List<String> credentialEnvVarsToScrub`,
`ContainerTakeSupport containerTakeSupport`, `ClaimTenure tenure`, `TrustedBaseContext
trustedBase`. *Rationale:* `AbortPolicy` and `ClaimTenure` are not arbitrary partitions —
`TakeCrashAbort` already takes exactly the abort pair, and the two tenure members are the
claim lifecycle that `TakeClaimAndWork` starts and ends together. `ClaimTenure` holds two
members rather than three because the epoch book is not this change's to carry: see D6. Grouping by an existing
consumer is what distinguishes a real sub-type from a tidy-looking split. *Alternative
rejected:* one flat twelve-component record — it satisfies the record exemption but names
no concept below the top level, and the abort pair would stay a two-parameter transposition
hazard (`process-invariants.md`, adjacent same-type parameters) at its consumers.

**D2 — The static recipes become objects; this is the change's core, not a side effect.**
`TakeFreshClaim`, `TakeContainerFreshClaim`, `TakeWorkRouter` and `TakeClaimAndWorkFactory`
stop being `private`-constructor holders of static methods and become instances constructed
with `SlotWiring`, their recipe steps becoming methods. *Rationale:* this is Fowler's
Combine Functions into Class applied to its exact shape — a set of functions sharing the
same argument group. It is also what makes the file-size split honest: the halves now own
state and a responsibility instead of forwarding fifteen parameters to each other.
*Alternative rejected:* keeping them static and passing `SlotWiring` as one parameter — the
signatures would pass the limit, but the recipe would still re-receive its equipment on
every call, and the "split for file size" javadoc would still describe a split that
transferred no ownership. That is compliance with the number and not with the rule.

**D3 — The file-size rule is amended in this change, not in the gate change.** A clause is
added to `process-invariants.md`'s file-size section: a split that converts fields into
parameters is not a responsibility split, and the correct transformation for an oversized
collaborator-holding class is to give the extracted half the collaborators as fields.
*Rationale:* the repair and the rule that prevents its recurrence belong together; deferring
the rule to `add-parameter-count-gate` leaves a window in which the next split recreates the
pattern. *Alternative rejected:* an ADR instead of a rule amendment — the file-size limit
already lives in `process-invariants.md`, and splitting one rule across two documents is
how the conflict went unnoticed.

**D4 — `segments` stays a parameter** (proposal Q1). *Rationale:* folding the container
segment plan into `ContainerTakeSupport` changes what that seam owns, which is a design
decision with behavior adjacency; this change is behavior-preserving by construction.
*Alternative rejected:* folding it now — it would put a non-mechanical change inside a
38-plus-20-signature mechanical edit, where NFR-R1's "a red spec stops the task" rule could
no longer distinguish a mistake from an intended change.

**D6 — The epoch book is not a member; `fix-claim-epoch-fence` lands first.** That change's
own single-owner table makes `TaskGit.epochs()` the sole source of the tenure book and
deletes every separate-parameter form of it, naming `ContainerRunSupport`/
`ContainerRunSupportFactory` — two of this change's consumers — explicitly. Carrying
`epochs` in `ClaimTenure` would therefore stand up a second owner of one value while the
first is still being established. *Rationale:* two owners for one value is the defect
`implementation.md` exists to prevent, and an artifact-stage conflict is the cheapest place
to resolve it. *Consequence:* this change is sequenced after `fix-claim-epoch-fence`, the
only place the agreed "refactor ahead of the queue" order yields, and `SlotWiring` reads the
book through `git` where it needs it. *Alternative rejected:* taking the `epochs`-into-
`TaskGit` move into this change and leaving `fix-claim-epoch-fence` only its fences — it
would put an ownership transfer that touches tracker wiring inside a change whose whole
contract is that behavior does not change.

**Sync surfaces.** This change touches **three declared pairs**, both ends of each:

| Pair | What this change does to it |
|------|------------------------------|
| `TakeFreshClaim` / `TakeContainerFreshClaim` | both become instances holding `SlotWiring` (D2); the recipe steps stay mirrored, and the parameter list each must reproduce shrinks to the order plus `segments` on the container side |
| `TakeResumeRunner` / `TakeContainerResumeRunner` | both constructors take `SlotWiring` in place of their seven-to-eight equipment parameters |
| `GitResumeRunner` / `ContainerResumeRunner` | the host end's four-parameter constructor takes `SlotWiring`; the container end follows so the pair stays aligned |

No pair is collapsed and no new parallel implementation is created — as in the previous
change, the pairs get thinner. Each pair's `Kept in sync with` sentence is re-read and
updated where it names a parameter list that no longer exists, and the affected rows of
`.claude/rules/manual-sync-pairs.md` are updated in this change.

**Single-owner mechanisms.**

| Owner | Value (type) | Consumers | Old way removed | Enforced by |
|-------|--------------|-----------|-----------------|-------------|
| `SlotWiring` | `SlotWiring` (record) | `TakeClaimAndWorkFactory.forSlot:36`, `TakeClaimAndWork` ctor:74, `TakeDisposition` ctor:81, `TakeBareAuto` ctor:75, `TakeSlotRunner` ctor:102, `TakeCommand` ctor:112, `TakeCommandFactory.of:24` and `:53`, `ServeCommand` ctor:93, `ServeAssembly.slotRunner:47`, `ServeRuntimeAssembly.assemble:51`, `SubcommandDispatchFactory.of:30`, `TakeFreshClaim.claim:62`/`claimAt:114`, `TakeContainerFreshClaim.claim:44`/`claimAt:96`, `TakeResumeRunner` ctor:66, `TakeContainerResumeRunner` ctor:51, `TakeResumeExecution` ctor:36, `GitResumeRunner` ctor:76, `ContainerRunSupport.create:130` and `ContainerRunSupportFactory.create:60` (`:bootstrap`) | the hand-listed members of the clump in each signature. Exemption: `ServeRuntimeAssembly.assemble` and `SubcommandDispatchFactory.of` keep their remaining parameters — they are composition roots and are only *partially* reduced here, by design (`collapse-composition-roots` owns the rest); each keeps a `// collapse-composition-roots` note naming the follow-up | the parameter type — every listed signature loses the members, so a caller still holding them does not compile |
| `AbortPolicy` | `AbortPolicy` (record) | `TakeCrashAbort` ctor, and every consumer above that passed `abortHandler, abortThreshold` adjacently | the adjacent `(AbortHandler, int)` pair — a transposition hazard by `process-invariants.md`; sweep `grep -rn "abortThreshold" application/src/main bootstrap/src/main` must show no signature taking it beside a bare `AbortHandler` | the parameter type |
| `ClaimTenure` | `ClaimTenure` (record) | `TakeClaimAndWork` ctor, `TakeSlotRunner` ctor, `TakeDisposition` ctor, `TakeBareAuto` ctor, `TakeClaimAndWorkFactory.forSlot` | the hand-listed `heartbeat, claimLossFlag` pair. The epoch book is **not** included and is not this change's to own (D6): it reaches its consumers through `TaskGit.epochs()`, established by `fix-claim-epoch-fence` | the parameter type |
| `process-invariants.md` file-size clause (FR7) | rule text | every future split of an oversized class | the unstated assumption that static-helper extraction is an acceptable split | review, plus `add-parameter-count-gate`'s gate catching the symptom afterwards; stated here because the rule change cannot be mechanically enforced on its own |

The consumer list was re-verified 2026-09-13 against the baseline scan: 22 signatures take
the wiring, of which **14 are today over the seven-parameter limit**. The two `:bootstrap`
sites added on that re-verification (`ContainerRunSupport.create`,
`ContainerRunSupportFactory.create`) carry three wiring members each — below the threshold
the first pass used — and were over the limit and named in no change.

No row claims two values are one by construction, so no identity spec is required.

## Risks / Trade-offs

- **Converting static recipes to instances changes construction sites, not just
  signatures.** → Each conversion is its own task with its own spec run, and NFR-R1 holds:
  a spec that goes red stops the task instead of being edited.
- **`SlotWiring` could become the bag the literature warns about** as future changes add
  members. → D1's criterion is the defence: a new member must belong to a named sub-group
  or be an equipment-lifetime collaborator of the slot; `add-parameter-count-gate` records
  this criterion in the rules so it survives the change's archival.
- **Partially reducing two composition roots leaves them still over the limit.** → Recorded
  as an exemption in the single-owner table with the follow-up named, rather than silently
  left; `collapse-composition-roots` carries them.
- **`credentialEnvVarsToScrub` gains a record `toString` (NFR-S1).** → Swept for: no
  `SlotWiring` is logged whole. The value carried is a list of variable *names*, as today.

## Migration Plan

Not applicable — source-only, no durable state or wire format. Rollback is a revert.
Sequenced strictly after `introduce-take-order`; running them in the other order means
editing the same twenty signatures twice.
