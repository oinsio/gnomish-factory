# Design: introduce-slot-wiring

## Context

See `proposal.md` — Why. Two measurements shape this design.

First, the clump: twenty signatures carry four or more of eleven collaborators, and four
of them (`TakeClaimAndWorkFactory.forSlot`, `TakeDisposition`, `TakeSlotRunner`,
`TakeBareAuto`) carry all eleven — they are the clump and almost nothing else.

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

- Equipment becomes fields, held once per `take` invocation or per `serve` daemon; the job
  stays a parameter.
- Two cohesive sub-groups get their own names rather than being flattened into one
  eleven-member record.
- The rule that produced the pattern is amended in the same change, so the repair is not
  undone by the next file-size split.

**Non-Goals:**

- Facade Service extraction for composition roots. `SlotWiring` is a value handed *to*
  components; a composition root is the code that *builds* collaborators, and grouping its
  arguments into a record would be the "deodorant" the literature warns against. That work
  is `collapse-composition-roots`.

## Decisions

**D1 — `SlotWiring` is a record with nine components, two of them named sub-groups.** Driven
by FR1, FR2 and FR3. The eleven collaborators become `RunAssembly assembly`, `TaskGit git`, `Path worktreesRoot`,
`String taskIdMdcKey`, `AbortFuse abort`, `List<String> credentialEnvVarsToScrub`,
`ContainerTakeSupport containerTakeSupport`, `ClaimTenure tenure`, `TrustedBaseContext
trustedBase`. *Rationale:* `AbortFuse` and `ClaimTenure` are not arbitrary partitions —
`TakeCrashAbort` already takes exactly the abort pair, and the two tenure members are the
claim lifecycle that `TakeClaimAndWork` starts and ends together. `ClaimTenure` is derived
from `TakeHeartbeat`, which already carries the pair among its five components, rather than
built beside it; `SlotWiring` does not take `TakeHeartbeat` whole, because the slot uses only
these two and the other three (progress listener, standing reaper, liveness oracle) belong
to the run's composition. The name follows the glossary's existing use of *tenure* — one
holding of a claim, identified by its claim epoch — and the entry added for it says that
this type is the tenure's liveness view (beat and loss signal) and deliberately does not
carry the epoch that identifies it (D5). `ClaimTenure` holds two
members rather than three because the epoch book is not this change's to carry: see D5. Grouping by an existing
consumer is what distinguishes a real sub-type from a tidy-looking split. *Alternative
rejected:* one flat eleven-component record — it satisfies the record exemption but names
no concept below the top level, and the abort pair would stay a two-parameter transposition
hazard (`process-invariants.md`, adjacent same-type parameters) at its consumers.

**D2 — The static recipes become objects; this is the change's core, not a side effect.**
Driven by FR5 and FR6.
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

*`TakeWorkRouter` specifically.* Since `introduce-take-order` its three static methods take
`TakeClaimAndWork w` whole (`locateAndWork:31`, `freshClaim:62`, `resume:96`, and the
private `plan:113`) and read eleven
of its fields: the nine `SlotWiring` members plus `resumeRunner` and `containerResumeRunner`,
which are not wiring. So their parameter counts are already small (two, two, three), and the
defect is the back-reference, not the count: the router is a static helper reaching into
its caller's fields, which that change's design recorded as stamp coupling left for this one
to settle. It becomes an instance that `TakeClaimAndWork` constructs once and holds, from
`(SlotWiring, TakeResumeRunner, TakeContainerResumeRunner)`; it builds its own
`TakeFreshClaim` and `TakeContainerFreshClaim` instances from the wiring, and its three
methods take the order (plus the shape for `resume`) and nothing of `TakeClaimAndWork`.
*Alternative rejected:* leaving it a static holder that receives `TakeClaimAndWork` — it
satisfies the limit today and keeps the halves reaching into each other's fields, the split
`process-invariants.md` already forbids.

**D3 — The file-size rule is amended in this change, not in the gate change.** Driven
by FR7 and G3. A clause is
added to `process-invariants.md`'s file-size section: a split that converts fields into
parameters is not a responsibility split, and the correct transformation for an oversized
collaborator-holding class is to give the extracted half the collaborators as fields.
*Rationale:* the repair and the rule that prevents its recurrence belong together; deferring
the rule to `add-parameter-count-gate` leaves a window in which the next split recreates the
pattern. *Alternative rejected:* an ADR instead of a rule amendment — the file-size limit
already lives in `process-invariants.md`, and splitting one rule across two documents is
how the conflict went unnoticed.

**D4 — `segments` stays a parameter** (proposal Q1; driven by NFR-R1 and G4). *Rationale:* folding the container
segment plan into `ContainerTakeSupport` changes what that seam owns, which is a design
decision with behavior adjacency; this change is behavior-preserving by construction.
*Alternative rejected:* folding it now — it would put a non-mechanical change inside a
38-plus-19-signature mechanical edit, where NFR-R1's "a red spec stops the task" rule could
no longer distinguish a mistake from an intended change.

**D5 — The epoch book is not a member; `fix-claim-epoch-fence` lands first.** Driven by
FR1 and FR3. That change's
own single-owner table makes `TaskGit.epochs()` the sole source of the tenure book and
deletes the separate book parameter of every claiming signature. In `:bootstrap`,
`ContainerRunSupport`/`ContainerRunSupportFactory` keep a separate `ClaimEpochSource epochs`
parameter, but it is fed only from `TaskGit.epochs()` (`ManualRunRunner` passes
`git.epochs()` into both container-support lambdas), so it is not a second owner. Carrying
`epochs` in `ClaimTenure` would therefore stand up a second owner of one value while the
first is still being established. *Rationale:* two owners for one value is the defect
`implementation.md` exists to prevent, and an artifact-stage conflict is the cheapest place
to resolve it. *Consequence:* this change is sequenced after `fix-claim-epoch-fence`, the
only place the agreed "refactor ahead of the queue" order yields, and `SlotWiring` reads the
book through `git` where it needs it. *Alternative rejected:* taking the `epochs`-into-
`TaskGit` move into this change and leaving `fix-claim-epoch-fence` only its fences — it
would put an ownership transfer that touches tracker wiring inside a change whose whole
contract is that behavior does not change.

**D6 — The serve slot's outage decoration is applied at the assembly point, not by the
slot.** Driven by FR4 and NFR-R1; decided 2026-09-25 in an architecture session. Today
`TakeSlotRunner` ctor:120 takes a raw `TaskGit` plus the `RemoteOutageGate` and wraps the git's
base-ref port in `RemoteOutageSignalingBaseRefGit` itself, before handing it down. With the
constructor taking a `SlotWiring` whole, that wrap has to move: the wiring's `git` is
decorated in `ServeRuntimeAssembly.assemble`, through one public owner,
`RemoteOutageGates.signaling(TaskGit, RemoteOutageGate)` (the record stays package-private
in `app.serve`), and `TakeSlotRunner` stops taking the gate. *Rationale:* a decorator is
composition-root work — Seemann's Interception pattern — and a component that decorates its
own injected dependency is his Control Freak anti-pattern: it keeps a decision about which
adapter chain is in effect that the root is supposed to own. The codebase already follows
that rule everywhere else (`RunAssembly.withHostGitPush`, `withExtraListener`,
`withPipelineSource` are all applied from `TakeCommand`, `ServeCommand` and
`ManualRunRunner`); `TakeSlotRunner` was the one consumer wrapping its own dependency. The
difference between the two modes is real and stays — a one-shot `take` has no one to keep
claiming after an outage, a daemon does — but it is one extra assembly step on the serve
side, not a property of the slot. *Alternative rejected, with its case made first:*
`SlotWiring.withGit(TaskGit)`, so the slot keeps wrapping inside its constructor. The form is
idiomatic here (`TaskGit.withBaseRefs`, `RunOrder.withDefinition`; JEP 468's native `with`
expressions have not shipped through Java 25, so hand-written withers stay the right
idiom), and the diff is smallest. It breaks on three points: the knowledge that the
decorator exists lands in two places (the wither and the consumer that calls it); the
equipment stops being a value assembled once and becomes something a consumer edits before
use — the direction in which a wiring record drifts toward a service locator; and the
method's only reason to exist would be sparing three test fixtures, which is production
surface shaped by tests rather than by a need. *Fixture impact, checked:* three specs
construct `TakeSlotRunner` — `TakeSlotRunnerSpec`, `ServeShutdownWiringSpec`,
`TakeSlotRunnerContainerConcurrencySpec`. Two pass an inert gate over `BaseRefGit.UNWIRED`
and never read it: they drop the argument. `TakeSlotRunnerSpec` has one scenario asserting
the gate saw a real refresh; its `newSlotRunner()` decorates through the same owner the root
uses. No expectation is edited (NFR-R1). `RemoteOutageServeEndToEndSpec` and
`OutageWarnFanOutSpec` do not construct the slot runner and are untouched.

**Sync surfaces.** This change touches **two declared pairs**, both ends of each:

| Pair | What this change does to it |
|------|------------------------------|
| `TakeFreshClaim` / `TakeContainerFreshClaim` | both become instances holding `SlotWiring` (D2); the recipe steps stay mirrored, and the parameter list each must reproduce shrinks to the order plus `segments` on the container side |
| `TakeResumeRunner` / `TakeContainerResumeRunner` | both constructors take `SlotWiring` in place of their seven-to-eight equipment parameters |

A third declared pair, `GitResumeRunner` / `ContainerResumeRunner`, is deliberately **not**
touched, and no mirrored change is needed on either end. Both are constructed only by
`ManualRunRunner` — the `gnomish run --resume` path, which holds no claim and has no slot —
from `(assembly, git, worktreesRoot, TASK_ID_KEY)` plus, on the container end, the sandbox
and factory properties and the `MANUAL` container support seam. That path has no abort
fuse, no heartbeat, no claim-loss flag, no trusted base tier and no per-slot credential
list, so a `SlotWiring` for it could only be built from placeholders — the claimless-path
objection that also excludes `ContainerRunSupport` below. Neither constructor is over the
limit (four and six parameters), so leaving them costs M2 nothing.

No pair is collapsed and no new parallel implementation is created — as in the previous
change, the pairs get thinner. Each pair's `Kept in sync with` sentence is re-read and
updated where it names a parameter list that no longer exists. Neither pair has a row in
`.claude/rules/manual-sync-pairs.md` — both ends already carry the marker — so the registry
is not edited.

**Single-owner mechanisms.**

| Owner | Value (type) | Consumers | Old way removed | Enforced by |
|-------|--------------|-----------|-----------------|-------------|
| `SlotWiring` | `SlotWiring` (record) | `TakeClaimAndWorkFactory.forSlot:33`, `TakeClaimAndWork` ctor:66, `TakeDisposition` ctor:76, `TakeBareAuto` ctor:73, `TakeSlotRunner` ctor:101, `ServeAssembly.slotRunner:46`, the `TakeDispatcher` record:34 and its `runExplicit:46`, `runOneRef:77`, `runBare:133` and `runBatch:200`, `TakeBatch.dispatch:116`, `TakeRefDispatch.run:25`, `TakeFreshClaim.claim:59`/`claimAt:99`, `TakeContainerFreshClaim.claim:42`/`claimAt:82`, `TakeResumeRunner` ctor:63, `TakeContainerResumeRunner` ctor:48, `TakeResumeExecution` record:26 | the hand-listed members of the clump in each signature, and the per-ref `TakeDispatcher.newAbortHandler:226` (the take assembly point builds the one handler). Exemptions: the constructors and factories upstream of tracker provisioning — `TakeCommand` ctor:107, `TakeCommandFactory.of:24` and `:53`, `ServeCommand` ctor:94, `SubcommandDispatchFactory.of:30`, and `ServeRuntimeAssembly.assemble:50` as a signature — are **not reduced** by this change: they hold only five of the members (`assembly`, `git`, `worktreesRoot`, `taskIdMdcKey`, `containerTakeSupport`), because the other four (abort fuse, tenure, credential list, trusted base) come into existence only after the tracker is provisioned, so no `SlotWiring` can exist where they are. They are composition roots and belong to `collapse-composition-roots`. `TakeDispatcher.runOneRef` (11 → 8), `TakeBatch.dispatch` (11 → 8) and `TakeRefDispatch.run` (12 → 9) lose the three wiring members they relay but stay over the limit on per-invocation values (the parsed arguments, definition, tracker config, tracker, instance id, adapter factory); they are handed to `collapse-composition-roots` with the residual list (task 6.2). `ContainerRunSupport.create:130` and `ContainerRunSupportFactory.create:61` (`:bootstrap`) keep their `credentialEnvVarsToScrub` parameter — they are not consumers of the wiring (see below) and are handed to `collapse-composition-roots`. `GitResumeRunner` and `ContainerResumeRunner`, and the `ManualRunRunner` that builds them, keep their constructors — they serve only the claimless `gnomish run --resume` path (see Sync surfaces). **Leaf consumers, decided 2026-09-25 (task 5.2 sweep, architecture review):** `TakeEngineExecution` (5 members + the per-run `lawBinding`) and `TakeContainerEngineExecution` (3 members + `lawBinding`) are the chain's end — they call `assembly.assemble`, `git.store()` and the fuse rather than relaying them — and are built per run, not per slot, at their four construction sites (`TakeFreshClaim`, `TakeResumeExecution`, `TakeContainerFreshClaim`, `TakeContainerResumeRunner`) from explicit `wiring.*` reads. They keep their exact member lists: handing them the whole wiring would make the host-mode engine depend on the container seam, the MDC key and the trusted base it never touches, and force `TakeContainerEngineExecutionSpec` to build a nine-member wiring from placeholders — the same objection that excludes `ContainerRunSupport`. The rule is recorded in `process-invariants.md` ("the parameter object stops at the last relay"). Two triggers revisit this: a leaf needing more than seven members gets a facade over its cohesive cluster (Seemann's Facade Service) exposed by `SlotWiring` as one accessor; construction duplicated across more than three callers with *divergent* member sets collapses into one factory. Both are records, outside the parameter-count scan, so M1/M2 are unaffected | the parameter type — every listed signature loses the members, so a caller still holding them does not compile. **Assembly points:** see "Where a `SlotWiring` is built" below — exactly two, checked by `grep -rn "new SlotWiring(" */src/main` |
| `AbortFuse` (existing record in `app.take`, glossary *abort fuse*; corrected 2026-09-25 from a proposed new `AbortPolicy`, which would have been a second type for the same pair) | `AbortFuse` (record) | `TakeCrashAbort` ctor, `TakeEngineExecution` and `TakeContainerEngineExecution` (already), `TakeOutcomeDispatch.dispatch` (added by the task 5.1 sweep: both engine executions unpacked the fuse into an adjacent `abortHandler, abortThreshold` pair for it; it now takes the fuse, 9 → 8 parameters, still over the limit and in the scan's residual list), and every consumer above that passed `abortHandler, abortThreshold` adjacently | the adjacent `(AbortHandler, int)` pair — a transposition hazard by `process-invariants.md`; sweep `grep -rn "abortThreshold" application/src/main bootstrap/src/main` must show no signature taking it beside a bare `AbortHandler` | the parameter type |
| `ClaimTenure` | `ClaimTenure` (record), built only by `TakeHeartbeat.tenure()` | `TakeClaimAndWork` ctor, `TakeSlotRunner` ctor, `TakeDisposition` ctor, `TakeBareAuto` ctor, `TakeClaimAndWorkFactory.forSlot` | the hand-listed `heartbeat, claimLossFlag` pair, read today as `heartbeat.instance()` / `heartbeat.flag()` at `TakeDispatcher:120,124,154,155` and `ServeAssembly:76,77`. `TakeHeartbeat` stays the owner of both values — its javadoc requires the flag to be the SAME instance wired as the beat's lost-claim sink — and `ClaimTenure` is a narrower view of it, not a second source: a `ClaimTenure` assembled from any other flag would silently detach the round-boundary consult from the beat. Specs may build one from `ClaimBeat.NONE` and a fresh flag, as they pass that pair today. The epoch book is **not** included and is not this change's to own (D5): it reaches its consumers through `TaskGit.epochs()`, established by `fix-claim-epoch-fence` | the parameter type |
| `RemoteOutageGates.signaling(TaskGit, RemoteOutageGate)` (D6) | the `TaskGit` a serve slot reads its base refs through — the real one with every claim-time base read reported to the gate | `ServeRuntimeAssembly.assemble`, the serve assembly point, which fills `SlotWiring.git` with its result; the take assembly point has no gate and fills the member with the raw git | `TakeSlotRunner` ctor:120's own `git.withBaseRefs(new RemoteOutageSignalingBaseRefGit(git.baseRefs(), remoteOutageGate))` and its `RemoteOutageGate remoteOutageGate` parameter; sweep `grep -rn "RemoteOutageSignalingBaseRefGit(" */src/main` must return only the record's own declaration and `RemoteOutageGates` | the parameter type — the constructor takes a `SlotWiring` and no gate, so no consumer can re-decorate; the grep gate above for the record's construction |
| `process-invariants.md` file-size clause (FR7) | rule text | every future split of an oversized class | the unstated assumption that static-helper extraction is an acceptable split | review, plus `add-parameter-count-gate`'s gate catching the symptom afterwards; stated here because the rule change cannot be mechanically enforced on its own |

The consumer list was re-verified 2026-09-13 against the baseline scan and corrected
2026-09-24 and 2026-09-25: 20 signatures take the wiring — 18 methods and constructors,
plus the `TakeResumeExecution` and `TakeDispatcher` records, which the limit exempts. All 18
list more than seven parameters, but only 14 are in the parameter-count scan: the four
`TakeDispatcher` methods are record members, which the scanner exempts. The second 2026-09-25 correction replaced the four
command-side sites (`TakeCommand`, both `TakeCommandFactory.of`, `ServeCommand`) and the two
composition roots (`ServeRuntimeAssembly.assemble`, `SubcommandDispatchFactory.of`) — none of
which can hold a `SlotWiring`, see the exemption row — with the take dispatch chain that
actually relays the members today (`TakeDispatcher` and its four entry points,
`TakeBatch.dispatch`, `TakeRefDispatch.run`) and with `ServeAssembly.slotRunner`, which
receives the serve wiring. The 2026-09-13 pass had added two `:bootstrap` sites, `ContainerRunSupport.create`
and `ContainerRunSupportFactory.create` (nine parameters each); they are removed again. Both
are the container-bundle builder reached only through the `ContainerSupportFactory` lambda
`ManualRunRunner.containerSupportFactory` returns, and that lambda serves plain `gnomish run`
(`OwnershipMode.MANUAL`) as well as take/serve — a path with no slot, no claim, no abort
fuse and no trusted base. Of the nine `SlotWiring` members they carry one,
`credentialEnvVarsToScrub`, arriving as a lambda argument; taking `SlotWiring` would not
shorten them, and would force the claimless path to build a slot's wiring. They are
assembly code of the composition root and belong to `collapse-composition-roots`, which
takes them into its site table. The 2026-09-25 correction removed `GitResumeRunner` ctor:78
for the same reason: it is built only by `ManualRunRunner` for `gnomish run --resume`, a
claimless path with no slot (see Sync surfaces); at four parameters it was never among the
twelve over the limit.

**Measured effect on the scan** (2026-09-25, the scanner of `introduce-take-order` task
0.2: 44 violations in `src/main`). Of the 14 scanned consumer-table signatures:

- **Drop to seven or fewer (12):** `TakeClaimAndWorkFactory.forSlot` (11), `TakeClaimAndWork`
  ctor (12), `TakeDisposition` ctor (14), `TakeBareAuto` ctor (16), `TakeSlotRunner` ctor
  (15), `ServeAssembly.slotRunner` (15), `TakeFreshClaim.claim`/`claimAt` (9/9),
  `TakeContainerFreshClaim.claim`/`claimAt` (10/9), `TakeResumeRunner` ctor (8),
  `TakeContainerResumeRunner` ctor (8).
- **Reduced but still over (2):** `TakeBatch.dispatch` (11 → 8), `TakeRefDispatch.run`
  (12 → 9). `TakeDispatcher.runOneRef` (11 → 8) also stays over by hand count but is a
  record member, outside the scan.
- **Not touched (exemption row):** `TakeCommand` ctor (16), `TakeCommandFactory.of:24` (11)
  and `:53` (12), `ServeCommand` ctor (16), `ServeRuntimeAssembly.assemble` (19),
  `SubcommandDispatchFactory.of` (19), `ContainerRunSupport.create` and
  `ContainerRunSupportFactory.create` (9 each).

Expected after this change: 44 − 12 = **32** (proposal M2), all of them handed on — 22 to
`collapse-composition-roots`, whose starting table carries these figures, and 10 to
`add-parameter-count-gate`.

**Where a `SlotWiring` is built.** Four of the nine members — the abort fuse (its handler
wraps the tracker), the tenure (from the run's `TakeHeartbeat`), the credential list (from the
resolved adapter factory) and the trusted base (from the startup law) — exist only after the
tracker is provisioned, and the assembly member is the listener-augmented `RunAssembly` built
beside the heartbeat. So a `SlotWiring` is built in exactly two places, each right after its
run's heartbeat and augmented assembly exist, and in no constructor — once per `take`
invocation, and once per `serve` daemon, where the single `TakeSlotRunner` all slots share
holds it:

- `TakeCommand.run`, once per `gnomish take` invocation, right after `takeAssembly`:201 and
  before `new TakeDispatcher`:203. It is shared by explicit, bare and batch mode, as the
  heartbeat already is. This replaces the per-ref `TakeDispatcher.newAbortHandler:226`: one
  `AbortHandler` per invocation instead of one per ref is behavior-preserving, because
  `AbortHandler` is a stateless record over `(tracker, clock)` and the tracker is the same
  for every ref of the invocation.
- `ServeRuntimeAssembly.assemble`, once per daemon, right after `serveAssembly`:81, handed to
  `ServeAssembly.slotRunner`:100. The handler built today at `ServeAssembly.slotRunner:62`
  moves here with it; every slot already shares one. Its `git` member is the decorated one —
  `RemoteOutageGates.signaling(git, remoteOutageGate)` — so the slot reads its base refs
  through the gate-signaling port without knowing it (D6).

**Who owns the worktrees path.** `SlotWiring.worktreesRoot` is a carrier, not an owner. In
this change both assembly points fill it from the `Path worktreesRoot` their enclosing
command already receives. `collapse-composition-roots` then introduces `FactoryPaths` as the
single owner of that path; from then on the two assembly points fill the component from
`FactoryPaths.worktreesRoot()`, and that change's `FactoryPaths` row lists this component as
a fed-from exemption. `SlotWiring` does not carry `FactoryPaths` whole: the slot never uses
`homeDir`.

No row claims two values are one by construction, so no identity spec is required.

## Risks / Trade-offs

- **Converting static recipes to instances changes construction sites, not just
  signatures.** → Each conversion is its own task with its own spec run, and NFR-R1 holds:
  a spec that goes red stops the task instead of being edited.
- **`SlotWiring` could become the bag the literature warns about** as future changes add
  members. → D1's criterion is the defence: a new member must belong to a named sub-group
  or be an equipment-lifetime collaborator of the slot; `add-parameter-count-gate` records
  this criterion in the rules so it survives the change's archival.
- **The command constructors, their factories and the two composition roots stay over the
  limit, and three dispatch signatures stay just over it.** → Recorded as exemptions in the
  single-owner table with the reason (no `SlotWiring` can exist before the tracker is
  provisioned) and the follow-up named, rather than silently left;
  `collapse-composition-roots` carries them, from the residual list of task 6.2.
- **`credentialEnvVarsToScrub` gains a record `toString` (NFR-S1).** → Swept for: no
  `SlotWiring` is logged whole. The value carried is a list of variable *names*, as today.

## Migration Plan

Not applicable — source-only, no durable state or wire format. Rollback is a revert.
Sequenced strictly after `introduce-take-order`; running them in the other order means
editing the same signatures twice.
