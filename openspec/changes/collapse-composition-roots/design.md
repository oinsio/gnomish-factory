# Design: collapse-composition-roots

## Context

See `proposal.md` — Why. After `introduce-take-order` and `introduce-slot-wiring`, thirty-two
signatures in `src/main` remain over the limit; twenty-two of them are in `:application` and
`:bootstrap` and are composition code. This design covers those twenty-two. The other ten
(`sandbox/docker` environment builders, agent round executions, `GithubMarkerJson`,
`PipelineModelBuilder.mapAndValidate`) are not composition and are deliberately left to
`add-parameter-count-gate`, which must reach zero. Confirmed 2026-09-26 by re-running the
same scanner over the working tree: 32 = 22 + 10, at the file:line positions of
`introduce-slot-wiring`'s residual list (task 6.2), which every table and task below cites.

The measured starting shape, after the two preceding changes (re-measured 2026-09-25 with
the scanner of `introduce-take-order` task 0.2, which exempts record members and `@Override`
methods; the "now" column is the tree after `introduce-take-order`, the "after" column is
what `introduce-slot-wiring`'s design says it leaves):

| Site                                                                             | Params now → after slot wiring                                                                              |
|----------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------|
| `ManualRunRunner` ctor (`:bootstrap`)                                            | 28 → 28                                                                                                     |
| `ObservabilityAssembly.assemble`                                                 | 16 → 16                                                                                                     |
| `SubcommandDispatchFactory.of`                                                   | 19 → 19                                                                                                     |
| `ObservabilityAssembly.assembleSnapshot`                                         | 14 → 14                                                                                                     |
| `ServeRuntimeAssembly.assemble`                                                  | 19 → 19                                                                                                     |
| `ManualRunAssembly` ctors (two, `:bootstrap`)                                    | 14, 10 → 14, 10                                                                                             |
| `TakeCommand` ctor                                                               | 16 → 16                                                                                                     |
| `FeedAutomaton` ctors (three)                                                    | 13, 12, 11 → 13, 12, 11                                                                                     |
| `ServeCommand` ctor                                                              | 16 → 16                                                                                                     |
| `ObservabilityWiring` ctor                                                       | 9 → 9                                                                                                       |
| `ServeAssembly.feedAutomaton`                                                    | 10 → 10                                                                                                     |
| `TakeRefDispatch.run`                                                            | 12 → 9                                                                                                      |
| `TakeCommandFactory.of` (two, :24 and :53)                                       | 11, 12 → 11, 12                                                                                             |
| `TakeOutcomeDispatch.dispatch`                                                   | 9 → 8 (`introduce-slot-wiring` task 5.1: takes `AbortFuse` in place of the adjacent handler/threshold pair) |
| `TakeBatch.dispatch`                                                             | 11 → 8                                                                                                      |
| `InstanceHeartbeat` ctor                                                         | 8 → 8                                                                                                       |
| `ContainerRunSupport.create`, `ContainerRunSupportFactory.create` (`:bootstrap`) | 9 → 9 each                                                                                                  |

`ServeAssembly.slotRunner` (15 before `introduce-slot-wiring`) is not in the table: `introduce-slot-wiring` brings it
to seven or fewer. The command constructors, both `TakeCommandFactory.of` and the two
composition roots keep their full signatures there, because no `SlotWiring` can exist before
the tracker is provisioned (that change's exemption row).

One candidate outside the parameter count was handed over by `introduce-slot-wiring` on
2026-09-25, from its architecture review of why `take` and `serve` differ: the slot body is
already one — `new TakeOrder(run, tracker.fetchTask(ref), tracker, instanceId)` followed by
`claimAndWork.dispatchAfterClaim(order)` — but it is spelled twice, at
`BareTakeClaimWalk:74-75` and in `TakeSlotRunner.run`, and `introduce-take-order` lists both
among the "three places a take order is assembled". When this change reworks the take
dispatch chain (`TakeDispatcher.runOneRef`, `TakeBatch.dispatch`, `TakeRefDispatch.run`), fold
the pair into one `TakeClaimAndWork.workClaimed(RunOrder, TaskRef)` so the order for an
already-claimed task is assembled in one place. Behavior-preserving; what stays different
around it (who claims, who sets the MDC key, who prints the summary) is the two modes' own
responsibility and is not to be merged. This is FR6, task 3.7, and the `workClaimed` row of
the single-owner table below (added 2026-09-26 — the hand-over was recorded here without a
requirement, a task or an owner row, which `implementation.md` treats as an unlisted
consumer).

The last row was handed over by `introduce-slot-wiring` on 2026-09-24: the two static
builders assemble the container bundle behind the `ContainerSupportFactory` lambda that
`ManualRunRunner.containerSupportFactory` returns, for plain `run` (`OwnershipMode.MANUAL`)
as well as take/serve, so they are composition code of the manual-run root rather than slot
wiring. Their `ClaimEpochSource epochs` parameter is fed only from `TaskGit.epochs()`.

**Architecture review of 2026-09-26 (sections 3–4 re-planned).** With sections 1–2 applied,
twenty sites were still over the limit and the tasks of section 3 said only "bring under the
limit with the facades of section 2", which the section-2 facades do not do. A code audit and
a literature check (Seemann, *Composition Root* and *Abstract Factory or Service Locator?*;
Kelly, *Encapsulate Context*; Henney, *Context Encapsulation*; Fowler, *Replace Function with
Command*, *Dependency Composition*; Metz, *The Wrong Abstraction*) reduced the twenty to four
root causes, each a decision below: a second composition root hidden inside a component (D6),
static recipes that turned fields into parameters (D7), an unnamed per-invocation cluster
(D8), and the equipment the two tracker-driven commands share (D9), plus the container-support
builders (D10). The review also refuted D5's count hypothesis (see D5).

## Goals / Non-Goals

**Goals:**

- Apply the transformation the literature prescribes for this shape — Facade Service — and
  refuse the one it warns against (a parameter object over a composition root's arguments).
- Make each extraction defensible individually, with a stated criterion, rather than as a
  sweep that hits a number.

**Non-Goals:**

- Reaching zero violations repository-wide. Ten sites are out of scope by NG1 and are named
  for the next change rather than swept in here.

## Decisions

**D1 — Facade Service, not parameter object, for composition sites.** Each cluster is
extracted as a type that owns the cluster's *aggregate behavior*; the composition root then
depends on the behavior, not on the parts. *Rationale:* Seemann's remedy for constructor
over-injection, and the distinction he draws is exactly the one this change must not blur:
a facade hides behavior behind an abstraction, a parameter object only re-packages
arguments. The preceding two changes were legitimately parameter objects because their
groups recur across dozens of signatures; a composition root's argument list recurs nowhere,
so the same medicine there would be the deodorant. *Alternative rejected:*
`ManualRunRunnerArgs`-style records — they would satisfy the limit today and leave 28
responsibilities in one class, and the record exemption would be doing the work instead of
the design.

**D2 — The extraction criterion, applied per cluster and recorded per cluster** (promoted to
`docs/adr/0010-facade-over-parameter-object.md` on 2026-09-26, together with the pass-through
and credential-seam rules the `TrackerWiring` row applied; the ADR is the durable statement,
this section its origin). A cluster
becomes a facade only if (a) its members are used together by the root rather than merely
arriving together, (b) the facade carries at least one method beyond its accessors (FR4),
and (c) the facade names something a reader already recognizes. A cluster failing any of
the three is left flat. The verdict for every cluster — extracted, or rejected with the
failing criterion — is written into one place, the single-owner table below (FR4); a task
report cites the row and repeats nothing. *Rationale:* the
research found no operational test distinguishing a real facade from an argument bag; since
no gate can make that call, the criterion has to be explicit and per-cluster, checked in
review. *Alternative rejected:* "group anything above seven" — that is the failure mode
the whole family of changes exists to avoid.

**D3 — `ManualRunRunner` takes the assembly, not its ingredients (FR2).** Ten of the 28
arguments are exactly `ManualRunAssembly`'s ten package-private constructor parameters
(`ManualRunAssembly.java:125`); the runner rebuilds the assembly inline today
(`ManualRunRunner.java:191`). It takes the assembly as a bean instead. Measured 2026-09-26:
only three of the ten exist in the runner solely to be handed on — `filesExistCheckRunner`,
`shellCommandCheckRunner`, `threadSleeper` — so D3 alone takes the constructor from 28 to 25.
The other seven (`systemConsoleIO`, `errorConsoleIO`, `checkClientRegistry`,
`secretsProvider`, `systemClock`, `factoryProperties`, `sandboxProperties`) the runner also
uses outside the assembly: as its own fields, in `GitModeRunner`, `ContainerGitModeRunner`,
`CheckProviderSeam`, `containerSupportFactory` and `SubcommandDispatchFactory.of`. They stay
the runner's own parameters after D3 and leave only with the cluster each belongs to —
`systemClock` with the time-sources cluster (task 2.3), `secretsProvider` with the
tracker-wiring cluster (task 2.4), `checkClientRegistry` / `factoryProperties` /
`sandboxProperties` with the container-support cluster (task 3.6), the two consoles with
whatever section 2 decides for them — so the runner reaches the limit by the sum of D3, D4
and section 2, and task 5.2 reports that sum, not D3. The bean method that now builds the
assembly inherits the ten ingredients and comes under the limit together with the assembly's
own constructors in task 3.5. *Rationale:* the largest single reduction in the change that
needs no new type — the facade already exists and is simply not being used as one.
*Alternative rejected:* the runner reading the seven back through the assembly's
package-private fields, which would make the facade a parameter object (D1) and re-couple the
runner to the assembly's internals. *Alternative rejected:* leaving it, because the runner
also builds a second, listener-carrying copy — that copy is derived from the first by
`withExtraListener`, which works just as well from an injected assembly.

**D4 — `FactoryPaths` for the two roots (FR3, FR5).** `Path worktreesRoot` and `Path
homeDir` are adjacent same-type parameters that Spring today distinguishes by parameter
name — the `-parameters` compiler flag in `java-conventions.gradle` exists for this. They
become one value with two named accessors, produced by one bean. *Rationale:*
`process-invariants.md` calls adjacent same-type parameters a transposition hazard at any
count; and resolving an injection point by parameter name is a silent-failure mode — a
rename in an unrelated refactor rewires the factory. *Alternative rejected:* Spring
qualifiers — they fix the injection ambiguity but leave the transposition hazard at every
plain-Java call site.

**D5 — `FeedAutomaton` is inspected before it is refactored** (proposal Q1). If its three
constructors hold no behavioral cluster, the honest outcome is a recorded SRP finding and a
follow-up change, not an invented facade. *Rationale:* D2's criterion applied to itself; a
class with three constructors of 10–12 arguments is as likely to need splitting as
grouping. *Alternative rejected:* deciding now without reading it — the cluster table above
is measured, but whether a cluster is *behavioral* cannot be measured from the signature.

*Hypothesis to verify in task 4.1 (read 2026-09-26, `FeedAutomaton.java:65-176`):* the
class already names one behavioral cluster. Its 13-parameter constructor builds `new
IdleTiming(idlePollInterval, backoffBase, backoffCap, random)` (`:160`) from four of its
parameters — the three adjacent `Duration`s and the `Random` — and `IdleTiming` carries two
methods beyond accessors (`jittered()`, `idleState(...)`), so it passes D2 as it stands;
`backoffBase` / `backoffCap` / `random` also feed `FeedSelection` (`:171`), so the
extraction is "take `IdleTiming` as a parameter", not a new type. The other two constructors
are a test seam, not a responsibility: no production code calls them (`ServeAssembly.
feedAutomaton:72` calls the 13-parameter one; every other `new FeedAutomaton(` is in a spec),
and the 12-parameter one supplies `RemoteOutageGates.system(BaseRefGit.UNWIRED, Path.of("."),
idlePollInterval)` (`:130`) — a production `system()` factory wired for tests from `src/main`,
which `checkTestTimeInjection` scans only `src/test` for and therefore cannot see. The
expected outcome is one constructor at seven or fewer, with the two defaulting constructors
moved to a test fixture in `application/src/test`, so the real-time gate reaches the call.
Task 4.1 confirms or refutes this against the code; the verdict enters the single-owner
table as the `FeedAutomaton` row either way.

*Refuted at the count level, 2026-09-26 (architecture review):* `IdleTiming` replaces four of
the thirteen, but `FeedSelection` (`:171`) is built from `backoffBase`, `backoffCap`,
`wipLimit` and `random` as well, and `wipLimit`, `sleeper`, `clock`, `dirtyNotifier` and
`remoteOutageGate` stay — one constructor at ten. The constructor is a composition root inside
the class: it builds `FeedTracker`, `FeedOutageRetry`, `FeedResilience`, `FeedCycle` and
`FeedViewTracker` from its parameters. So the second branch is the expected one: one
constructor taking `IdleTiming` (13 → 10), the two defaulting constructors moved to a test
fixture so `RemoteOutageGates.system(...)` leaves `src/main`, the finding recorded in the
table's `FeedAutomaton` row, and the split named for a follow-up change
(`split-feed-automaton-composition`: the automaton takes its cycle and view tracker built,
the way `ServeRuntimeAssembly` builds everything else). M1 becomes 22 → 1 and M4 11, both
amended in the proposal.

**D6 — The assembly leaves `ManualRunRunner` (FR7).** The runner is today three things: the
`ApplicationRunner` entry point, the composition root of the manual-run graph (four runners,
persistence, parser, synthesizer — read back by `ManualRunDrive` through fourteen
`runner.*` field reads), and the composition root of the tracker-driven commands. Seemann's
Composition Root rule is that application code "is never composed": the graph is assembled
only at the entry point, which for Spring is the `@Configuration` set, and a sibling reading a
component's fields is a service locator by another route. Measured 2026-09-26: with the
container cluster (D10) and a `SubcommandDispatch` bean the runner would still hold about
sixteen, because the manual-run half is a root of its own. So the assembly moves to `@Bean`
methods of `ManualRunConfiguration`: `ManualRunDrive` becomes an instance holding its own
runners, parser, startup, synthesizer, persistence, console and binding settings (the
"extracted half gets the collaborators as fields" transformation of `process-invariants.md`);
`SubcommandDispatch` becomes a bean built from the same ingredients `SubcommandDispatchFactory`
takes today; the runner takes `GitVersionCheck`, `SubcommandDispatch`, `ManualRunDrive` and
the error console. The `dockerProbe` test seam, a package-private field the runner exposes for
`ManualRunContainerDispatchSpec`'s `.@` write, moves to `ContainerSupports` (D10) as a named
test constructor, closing one of the two `.@` debts `testing.md` lists. *Steelman of the
alternative — keep the runner as root and extract a `ManualRunRunners` facade over the four
runners:* smallest diff, no Spring change, `ManualRunDrive` untouched; it breaks on D1 (a
parameter object over a root's arguments) and leaves the field-read locator in place. Borrowed
from it: nothing structural; Fowler's *Dependency Composition* pattern of override seams at the
root is what the named test constructor implements. Recorded on NFR-R2: the context gains two
beans, both in the single-owner table; every existing bean keeps its identity.

**D7 — Static recipes become instances, and get no spec of their own.** `ServeRuntimeAssembly.
assemble` (18), `SubcommandDispatchFactory.of` (13), `TakeCommandFactory.of` (9, 10),
`TakeRefDispatch.run` (9), `TakeBatch.dispatch` (8) and `ContainerRunSupportFactory.create`
(9) each say in their javadoc that they were extracted for file size, and each takes its
origin's fields as parameters — the anti-split `process-invariants.md` names. The refactoring
is Fowler's *Replace Function with Command* with *Extract Class* for the fixed equipment: the
object is built once with what the origin held (`ServeRuntimeAssembly` inside `ServeCommand`,
from the command's equipment; `ContainerRunSupportFactory` over the fixed part the
`containerSupportFactory` lambda closes over today), and its method takes only the
per-call job (`assemble(serveArguments, boundTracker, effectiveSlots)`). No new type where the
class already exists. Testing: such an object has no behavior beyond construction, so a spec
of its own would assert "method calls method" (Seemann, *Integration Testing composed
functions*: unit tests "don't tell you how they integrate"); it is exercised through the effect
on the real flow (`ServeRuntimeWiringSpec`) and listed in `pitest { excludedClasses }` with the
covering suite named — the arid-wiring category of `testing.md`. Where D8 alone brings a
static site under the limit (`TakeRefDispatch`, `TakeBatch`, `TakeDispatcher.runOneRef`), it
stays static: a Command with no state is added complexity (Fowler's own caveat).

*Added 2026-09-27 (architecture review of task 3.1):* `ServeAssembly` is the same shape and was
missing from the list. Its four builders take the daemon's fixed equipment — `factoryProperties`,
`serveProperties`, `feedClock`, the fields `ServeCommand` holds — as parameters on every call, and
`feedAutomaton` (10) stays at eight with `BoundTracker`, because only three of its ten are members
of the bound tracker. The refactoring is Fowler's *Combine Functions into Class*: `ServeAssembly`
becomes an instance over those three, held by the `ServeRuntimeAssembly` instance, and each builder
takes only the per-call job with the exact bound-tracker members it uses (`feedAutomaton` 10 → 7,
`slotRunner` 5). Unlike the other instances of this decision, it keeps its existing specs
(`ServeAssemblySpec`, `ServeAssemblyBuildersSpec` assert effects — the WIP limit carried, the
caller's own tracker consulted — not "method calls method") and is **not** listed in
`excludedClasses`; the specs change only their construction line.

**D8 — `BoundTracker`, a parameter object with a closed membership (FR8).** The dispatch chain
re-lists `definition`, `trackerConfig`, `factory`, `tracker`, `instanceId` (and `trustedBase`
beside them) in every signature between the command and `TakeDispatcher.runOneRef`; `take` and
`serve` each build the six in the same sequence after the tracker is provisioned. This is
Kelly's *Encapsulate Context*: data available to divergent parts of the flow, passed as one
value. It is a parameter object, not a facade — the code below the command *uses* it, and it
recurs across eight signatures — so it is a record named by the domain, **bound tracker**:
"what this invocation bound". Kelly's documented failure modes are the design constraints:
(i) the kitchen-sink context, answered by a membership rule in the javadoc — *only what exists
once the tracker is provisioned and stays fixed for the invocation* — so a later member must
pass that sentence; (ii) the context that constructs collaborators and drifts toward a locator,
answered by allowing exactly one derived method, `credentialEnvVars()` (`factory.
credentialEnvVars(trackerConfig)`, spelled twice today), and no construction — the draft's
`abortFuse(clock)` was rejected on this ground and moved to D9; (iii) Henney's
role-partitioning, already the "parameter object stops at the last relay" rule: the leaf,
`TakeDispatcher.runOneRef`, uses every member, so it takes the value whole. *Steelman of the
alternative — thread the existing `RunOrder`/`TakeOrder` instead:* no new term; it breaks
because a `TakeOrder` needs the fetched task and the dispatch chain runs before the fetch.
Borrowed: `TakeDispatcher` derives its `RunOrder` from `bound.definition()` — reads, not
recomputation.

*Amended 2026-09-27 (architecture review of task 3.1, with a literature check — Kelly, Henney's
Role-Partitioned Context, GoF Decorator consequences, Seemann's Interception, Page-Jones's
connascence of value).* Two corrections. **(a) `ServeAssembly.feedAutomaton` and `slotRunner` are
leaves, not relays**: each builds its consumer directly and uses three of the six members, so by
"the parameter object stops at the last relay" (`process-invariants.md`) each declares exactly the
members it uses; `ServeRuntimeAssembly.assemble` is the one serve-side relay and reads them out.
**(b) The serve root derives its bound tracker over the decorated tracker.** Every consumer of the
daemon works with the `TrackerHealthTracker` wrap, not the tracker the adapter returned, and the wrap
is applied in `assemble` beside the outage-gate decoration of git (design D6 of
`introduce-slot-wiring`: decorators are applied at the root and handed down as values). So the
record carries one wither, `withTracker(Tracker)` — a copy, not a construction, so rule (ii) holds —
and `assemble` makes `BoundTracker served = bound.withTracker(trackerHealth)` its first use of the
value; nothing below `assemble` receives the raw one. *Steelman of the alternative — wrap in
`ServeCommand.run` before binding, so one bound tracker per invocation exists by construction (the
literature's preference: the decorated instance is the component of the graph):* it breaks here
because `TrackerHealthTracker` is also the health source `SnapshotSources` reads through a typed
reference, so `assemble` would take the bound tracker and the typed decorator side by side — two
arguments that must agree, the connascence-of-value smell this amendment removes from
`SlotWiringFactory` (D9); splitting the decorator from the health source is a change to
`gnomish-plugin-api`, out of scope by NG2. Borrowed from it: the rule that below the root exactly one
bound tracker exists and its tracker is the one the daemon works with, pinned by the sweep in the
table (`bound.tracker()` and a second `BoundTracker` local never appear in `ServeCommand`).

**D9 — `SlotWiringFactory`: the equipment the tracker-driven commands share, named as what it
is.** `TakeCommand` and `ServeCommand` hold the same collaborators and, once the tracker is
provisioned, build the same `SlotWiring` — `assembly.withExtraListener(heartbeat.progress())
.withPipelineSource(...)`, `git`, `worktreesRoot`, the MDC key, `new AbortFuse(new
AbortHandler(tracker, clock), trackerConfig.abortThreshold())`, the credential names, the
container support, `heartbeat.tenure()`, `trustedBase` — spelled twice (`TakeCommand.run`,
`ServeRuntimeAssembly.assemble`). By Seemann's test ("an Abstract Factory is a generic type with
a non-generic Create method") an object with fixed fields and one method returning one type is
an Abstract Factory, which is a legitimate facade shape provided it is named as one — so
`SlotWiringFactory`, not a "harness"/"toolkit"/"kit" (the literature has no such term, and an
invented name is the tell that criterion (c) failed). Members: exactly what `slotWiring` uses
— `assembly`, `git`, `worktreesRoot`, `taskIdMdcKey`, `clock`, `containerTakeSupport`, the
`pipelineSource` read from `TrackerWiring`; `serve`'s extras (the engine clock, `ServeProperties`,
the starter, the console) stay flat in `ServeCommand`, and no member is read back out through
an accessor (ADR 0010). Method: `slotWiring(BoundTracker, TaskGit, TakeHeartbeat)` — `serve`
passes its bound tracker derived over the health-wrapped tracker (D8, amendment b) and its
outage-decorated git, which are substituted collaborators, not a flag. *Amended 2026-09-27:* the
draft signature carried a separate `Tracker` beside the bound tracker for serve's wrap; with the
derived bound it was a second way to obtain a value the first parameter already carries —
connascence of value between two arguments, Bloch's transposition hazard by another route — and
it is dropped. What that closes by construction: the abort handler inside the wiring writes to the
same tracker the slot claims through (`bound.tracker()`), which today holds in both commands only
by convention. `TaskGit` stays a parameter legitimately: git is equipment, not a member of the
bound tracker, and serve substitutes the decorated one. **Metz test, written into the method's javadoc:** the moment a
boolean, a mode or an `if (serve)` appears inside, the abstraction is wrong — split into
`takeSlotWiring`/`serveSlotWiring` or inline back into both callers. **The tracker-bind
sequence is deliberately not consolidated**, although ADR 0010 consolidates duplicated
sequences: `serve` wraps `bindStartupLaw` and `resolveTracker` each in its own `catch` with its
own console sentence and exit code, `take` wraps neither; one shared method would either take a
flag or change which failure maps to which sentence (NFR-O1). The five lines stay in each
command; the rejection is in the table.

**D10 — `ContainerSupports`, and the container-run builders as an instance.** `ContainerRunSupport.
create` and `ContainerRunSupportFactory.create` (9 each) are the D7 shape — the fixed part
(`sandboxProperties`, `factoryProperties`, the check credentials, the check-client registry,
the ownership mode, `epochs` from `TaskGit.epochs()`) is what `ManualRunRunner.
containerSupportFactory` already closes over; it becomes the fields of a
`ContainerRunSupportFactory` instance whose `create` takes the per-run job (`cloneDir`,
`taskId`, `segments`, the definition, the credentials to scrub). That also removes the adjacent
`List<String>, List<String>` pair (`checkCredentialEnvVars`, `credentialEnvVarsToScrub`), a
transposition hazard `process-invariants.md` names. Above it, `ContainerSupports` is the facade
over the cluster D3 assigned to task 3.6 (`checkClientRegistry`, `factoryProperties`,
`sandboxProperties`, `bindingProperties`, `bindingRegistry`, the Docker probe, `epochs`): its
behavior is building the `manual` support factory for `run` and the `ContainerTakeSupport`
for `take`/`serve` — the two constructions that differ only in the ownership label, today two
calls in the runner's constructor. Criterion (c): "container support" is the name the code
already uses (`ContainerSupportFactory`, `ContainerRunSupport`, `ContainerTakeSupport`). It
carries the `dockerProbe` seam (D6) with a named test constructor; the book still arrives only
from `TaskGit.epochs()`.

**D11 — `@Bean` methods obey the limit; the root is split into factories and facades, not
exempted (2026-09-27 architecture review of tasks 3.5–3.6).** Moving the assembly into
`ManualRunConfiguration` (D6) produces `@Bean` methods of ten to twelve parameters
(`manualRunDrive`, `subcommandDispatch`, `manualRunAssembly`). Two options were weighed with a
literature check. *(b) Exempt `@Bean` methods,* on the ground that the composition root is where
"everything is coupled" (Seemann, *Composition Root Reuse*) and a long list there is the honest
inventory of the graph: the steelman is that every intermediate bean is one more name the reader
must pass through to see the same list. It breaks on two points: the only remedy Seemann himself
gives for "my root became large" is factories and builders wired at the root, or a convention-based
container — never an exemption; and Checkstyle's own `ParameterNumber` exemptions
(`ignoreOverriddenMethods`, `ignoreAnnotatedBy`) exist for signatures the author does *not*
control, which a `@Bean` method is not. No source found says DI registration code is exempt from
size heuristics (the phrase "the root may be the messiest part" was checked and is unsourced).
*(a) More beans,* each at seven or fewer, is what Facade Services cascading "closer and closer to
the application boundary" (Seemann) and Spring's "split configuration by concern, inject by
parameter" describe. **Decision: (a), with the minimal set** — six beans join the context beyond
D6's two: `SlotWiringFactory`, `TakeCommand`, `ServeCommand`, `ServeRuntimeAssembly`,
`ManualRunners` and `CheckEquipment`; `SandboxLifecyclePass` and `ServeAssembly` (three inputs
each) are built inside the bean method that needs them rather than registered. *Corrected
2026-09-27 (task 3.6, agreed with the user):* the count was wrong — with both built inline,
`serveRuntimeAssembly` has nine inputs, not seven. Both are registered as beans (three inputs
each), which also keeps the one `SandboxLifecyclePass` instance `take` and `serve` shared before;
`serveRuntimeAssembly` is then exactly its constructor, seven. Borrowed from (b):
no bean is added for a number alone — each row below names its behavior. `TakeCommand` and
`ServeCommand` lose the "not a Spring component" sentence of their javadoc; they are constructed
by `@Bean` methods, not discovered (NG3 holds: no component scanning). NFR-R2's list grows
accordingly; the context-start spec stays unedited. The rule lands in ADR 0010 (task 5.5).

**D12 — Criterion (a) admits the deletion test (2026-09-27, task 3.9 literature check).** D2's
"(a) used together, not merely arriving together" is stricter than any source it rests on:
Fowler's Data Clumps says outright "don't worry about data clumps that use only some of the
fields of the new object", and his test is deletion — "if you deleted one value, would the others
make sense?"; Evans/Vernon's is the same from the value-object side — "taken apart, each attribute
fails to provide a cohesive meaning". So (a) is amended: *used together by the consumer, **or**
meaningless apart* (the deletion test). What stays strict is (b): a type that passes the deletion
test but only holds two accessors is Fowler's "structure without behavior migration" and is
rejected — the invariant that makes the pair one thing must live in the type. Applied to
`InstanceHeartbeat`: `lostDetection` is `(k−1)·interval` and means nothing without `interval`
(passes the deletion test), the two are adjacent `Duration`s (Bloch's identical-type transposition
hazard, `process-invariants.md`), so **`BeatTiming(interval, lostDetection)` is extracted** with
the ordering invariant in its compact constructor and `rollUp()` moved from
`InstanceHeartbeat.rollUpFor`; `LeaseThresholds` stays the one derivation site and gains
`beatTiming(config)` as its builder, so no second place derives the pair. ADR 0010's criterion is
amended in task 5.5.

**Sync surfaces: none — this change adds no parallel implementation and touches no declared
pair.** Verified by `grep -rn "Kept in sync with" */src/main` against the twenty-two sites: no
composition root or assembly in the list carries a marker or is named by one. If the
`FeedAutomaton` inspection (D5) produces a second implementation of anything, that outcome
is out of this change's scope by NG2 and moves to the follow-up it recommends.

**Single-owner mechanisms.**

| Owner                                                                                                                                                                               | Value (type)                                                                                                                                        | Consumers                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | Old way removed                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            | Enforced by                                                                                                                                                                                                                                                                                                                |
|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `FactoryPaths`                                                                                                                                                                      | `FactoryPaths` (record, accessors `worktreesRoot()` / `homeDir()`)                                                                                  | Three kinds of consumer, split 2026-09-26 because `process-invariants.md` ("the parameter object stops at the last relay") gives each a different shape. **Relays, which take the value whole** — the four sites that today take both paths adjacently: `ManualRunRunner` ctor:152 (`:169-170`), `SubcommandDispatchFactory.of:30` (`:33-34`), `ServeCommand` ctor:94 (`:97-98`), `ServeRuntimeAssembly.assemble:52` (`:54-55`). **Plain-Java single-path leaves, which take one `Path` read from the value at their relay** — `TakeCommand` ctor:109 (`worktreesRoot` only, `:112`), `TakeCommandFactory.of:24` and `:53` (`worktreesRoot` only, `:27` / `:56`; `:24` was missing from this row), fed `FactoryPaths.worktreesRoot()` by `SubcommandDispatchFactory.of:50`; `ObservabilityAssembly.assemble:103` (`homeDir` only, `:107`), fed `FactoryPaths.homeDir()` by `ServeRuntimeAssembly.assemble:142`. A leaf that needs one path does not receive both. **Spring-fed leaves, which take the value whole and read one accessor** — `StatusCommand` ctor:65 (`Path worktreesRoot`) and `DashboardCommand` ctor:60 (`Path homeDir`), the two `@Component`s Spring feeds from the `Path` beans by parameter name (added 2026-09-26: they were missing from this row and `StatusCommand` was wrongly listed as an exemption, while the Migration Plan deletes the beans they resolve); no relay exists to pre-read for them, so they are the one place a single-path consumer takes the whole value — and the Spring configuration that declares both `Path` beans | the two bare `Path` beans resolved by parameter name, and every `(Path, Path)` adjacency. **Verdict (D4; recorded 2026-09-27, task 5.1): a typed value, not a D2 cluster** — it exists for the transposition hazard, and it passes the deletion test (D12) because the two paths are one installation layout; (b) beyond accessors: `underHome(home)`, the layout derivation (worktrees under `<home>/.gnomish/worktrees`) that the two `Path` beans spelled separately, now the one production construction (`ManualRunConfiguration`), pinned by `FactoryPathsSpec`; sweep: `grep -rn "Path worktreesRoot\|Path homeDir"` over the files of the four relays and the two Spring-fed leaves must return nothing; over each plain-Java single-path leaf it must return exactly its one declaration; `grep -rln "@Component" application/src/main bootstrap/src/main \| xargs grep -ln "Path worktreesRoot\|Path homeDir"` must return nothing (no Spring-fed leaf survives on the deleted beans); and across `application/src/main bootstrap/src/main` no signature may take the two adjacently. Exemption: downstream carriers of a single path are fed from `FactoryPaths`, not owners of it — the `SlotWiring` component `worktreesRoot` (introduced by `introduce-slot-wiring`), filled from `FactoryPaths.worktreesRoot()` at its two assembly points, `TakeCommand.run:210` and `ServeRuntimeAssembly.assemble:106`; and the plain-Java leaf components that take one worktrees path (`TaskWorktreeGit`, `WorktreeJanitor`, `ServeAssembly.worktreeJanitor:110`, `HostResumeMechanics`, `GitResumeRunner` and the like), which receive it from those two values and are never Spring-injected                         | the parameter type at the relays and the Spring-fed leaves — a bare `Path` no longer satisfies those signatures, so a transposed or misnamed injection does not compile; at the plain-Java single-path leaves, the absence of a second `Path` to transpose with, pinned by the sweep                                       |
| `ManualRunAssembly`                                                                                                                                                                 | `ManualRunAssembly`                                                                                                                                 | `ManualRunRunner` ctor:152                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              | the three ingredient parameters the runner holds only for the assembly (`filesExistCheckRunner`, `shellCommandCheckRunner`, `threadSleeper`) and the inline `new ManualRunAssembly(...)` in the runner's constructor body (D3; the other seven ingredients stay until their own clusters are decided); sweep `grep -rn "new ManualRunAssembly(" bootstrap/src/main` must return only the bean method                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | the parameter type                                                                                                                                                                                                                                                                                                         |
| `ReportCommands`                                                                                                                                                                    | `ReportCommands` (facade over status / usage / board / dashboard)                                                                                   | `ManualRunRunner` ctor:152, `SubcommandDispatchFactory.of:30`, and `SubcommandDispatch`, which held the four as record components                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | the four hand-listed command parameters at both sites and the four `if` routes in `SubcommandDispatch.dispatchNonRun`. **Verdict 2026-09-26 (task 2.1): extracted.** (a) the four are used together — dispatched as one group ahead of `take`/`serve`, and they share the property that makes them a group: none claims, writes to the tracker or drives the pipeline; (b) method beyond accessors: `run(Subcommand, ApplicationArguments)`, the report routing moved out of `SubcommandDispatch` (it has no accessors at all), pinned in `:application` by `ReportCommandsSpec`; (c) "report commands" is the name the proposal and U1 already use. A Spring `@Component` beside the four it holds, so the context gains this one facade bean and nothing else changes identity (FR5). Runner 24 → 21, `SubcommandDispatchFactory.of` 17 → 14                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | the parameter type                                                                                                                                                                                                                                                                                                         |
| `TakeClaimAndWork.workClaimed(RunOrder, TaskRef, Tracker, InstanceId)` (the tracker and instance id added 2026-09-26 in task 3.7: the fetch needs them and the class holds neither) | the post-claim `TakeOrder` and its dispatch (FR6)                                                                                                   | `BareTakeClaimWalk.resolve:74-75`, `TakeSlotRunner.run:142-143` — the two places that today spell `new TakeOrder(run, tracker.fetchTask(ref), tracker, instanceId)` followed by `claimAndWork.dispatchAfterClaim(order)` for a task this caller has already claimed; the MDC key, the anchor log and the summary stay with the callers                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  | the inline fetch-then-dispatch pair at both sites; sweep `grep -rn "new TakeOrder(" application/src/main bootstrap/src/main` must return exactly three hits: `TakeOrder.withDefinition`, `workClaimed`'s own body, and the one exemption. Exemption: `TakeDispatcher.runOneRef:89` builds its order *before* the claim — `TakeDisposition.dispose` claims or takes over afterwards — so it is not an already-claimed order and must not route through `workClaimed`. This supersedes the "exactly three places" statement of `introduce-take-order`'s table: the count is one owner plus one pre-claim site                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                | the sweep in task 3.7, and `dispatchAfterClaim` narrowed so that `workClaimed` is the only public entry that takes a `TaskRef` for a claimed task                                                                                                                                                                          |
| `SnapshotSources` (proposed as `VitalSources`)                                                                                                                                      | `SnapshotSources` (facade over the live sources of the status snapshot)                                                                             | `ObservabilityAssembly.assemble:103`, `ObservabilityAssembly.assembleSnapshot:175` — the two sites that share the feeds; `ObservabilityWiring` ctor:47 was listed here until 2026-09-26 but shares none of them (see the next row)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      | the ten parameters the two sites share (`automaton`, `slotLedger`, `slotCapacity`, `progress`, `trackerHealth`, `heartbeat`, `standingReaper`, `worktreeJanitor`, `sweepTickLog`, `remoteOutageGate`; corrected 2026-09-26 from "eight"), of which the four `VitalsSnapshotAssembler.assemble` consumes (`heartbeat`, `standingReaper`, `worktreeJanitor`, `sweepTickLog`) are the vital feeds proper — whether the facade covers the four or the ten is D2's call in task 2.2. Conditional on D2 as above **Verdict 2026-09-26 (task 2.2): extracted over all ten, under the name `SnapshotSources`.** The ten are the live collaborators every section of the status snapshot but the lifecycle one is read from — `vitals` is one of six sections, so `VitalSources` would have misnamed the cluster (criterion c) and the four vital feeds alone would have carried no behavior of their own beyond forwarding to `VitalsSnapshotAssembler`. (a) all ten are read together on every snapshot write and built together by `ServeRuntimeAssembly`; (b) method beyond accessors: `snapshot(InstanceInfo, LifecycleStateTracker, Instant, Duration)`, the body of the former `assembleSnapshot:175`, which is deleted rather than shrunk; pinned by `ObservabilityAssemblySpec` (the written snapshot's capacity, identity, host); (c) "the snapshot's sources". One member is read back through its accessor: `assemble` hands `sources.slotLedger()` to the task-outcome ledger writer, which records against the same ledger the snapshot reads. `assemble` 16 → 7                                                                                                                                                      | the parameter type                                                                                                                                                                                                                                                                                                         |
| `LedgerWriters`                                                                                                                                                                     | `LedgerWriters` (facade over the ledger write points one instance owns)                                                                             | `ObservabilityWiring` ctor:47 — its nine parameters are `lifecycleTracker`, `snapshotWriter`, the four `*LedgerWriter`s (`lifecycle`, `taskOutcome`, `sweep`, `remoteOutage`), `ledgerAppender`, `instance`, `clock`; none is a snapshot feed, so no `VitalSources` extraction moves it (added 2026-09-26: the constructor had no cluster and no task of its own)                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       | the four `*LedgerWriter` parameters and `ledgerAppender`, which `assemble:103` builds together over one `RotatingLedgerAppender` and which the wiring hands out through `taskOutcomeLedgerWriter()`, `sweepLedgerWriter()`, `remoteOutageLedgerWriter()` and `newRunSummaryLedgerWriter()`. Conditional on D2 in task 2.5: the candidate method beyond accessors is `newRunSummaryLedgerWriter()` (a writer built over the shared appender) — if that is all the cluster does together, the row records the rejection **Verdict 2026-09-26 (task 2.5): extracted, and it builds its members.** (a) the four writers and the appender were built together over one `RotatingLedgerAppender` and are only correct together — every ledger line must rotate and be retained like every other (NFR-O2 of `add-serve-sandbox-lifecycle`; NFR-O1, NFR-O3 of `add-base-ref-resolution`); (b) beyond accessors: the constructor builds all four write points from `(appender, slotLedger, instance, clock)`, so no assembly can put one on a second appender, and `newRunSummary()` builds a fresh drain-run writer over the same appender; `ObservabilityWiring` delegates its `newRunSummaryLedgerWriter()` and lifecycle lines to it and drops its own `ledgerAppender` / `instance` fields; (c) "the instance's ledger writers". Pinned by `ObservabilityAssemblySpec` (taskOutcome line on the passed ledger, started line) and `ServeShutdownWiringSpec` (stopped line, run summary). `ObservabilityWiring` ctor 9 → 4                                                                                                                                                                                                       | the parameter type                                                                                                                                                                                                                                                                                                         |
| `TimeSources` (rejected)                                                                                                                                                            | —                                                                                                                                                   | would have been `ManualRunRunner` ctor:152, `SubcommandDispatchFactory.of:30`, `ServeCommand` ctor:94, `ServeRuntimeAssembly.assemble:52`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               | nothing removed. **Verdict 2026-09-26 (task 2.3): rejected — fails (b).** The members do nothing together: `systemClock` and `javaTimeClock` are two port shapes (`domain.engine.port.Clock`, `java.time.Clock`) over the same wall clock, each consumed by different leaves (`TakeCommand` reads only the `java.time` one; the feed automaton, slot ledger and tracker-health decorator only the engine one), and `threadSleeper` already left the runner with the assembly (task 1.1) and reaches only `ManualRunAssembly`. A facade would carry only accessors, and handing it to a leaf would give that leaf a clock it never reads — against `testing.md`'s rule that a component takes exactly the `Sleeper`/`Clock` it drives on virtual time. The sites reach the limit through their other clusters; no spec changed, `checkTestTimeInjection` untouched                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          | —                                                                                                                                                                                                                                                                                                                          |
| `TrackerWiring`                                                                                                                                                                     | `TrackerWiring` (`@Component`; facade over the adapter registry, the credential seam and the definition source) and its narrow face `RefResolution` | `ManualRunRunner` ctor:152, `SubcommandDispatchFactory.of:30`, `TakeCommand` ctor:109, `TakeCommandFactory.of:24` and `:53`, `ServeCommand` ctor:94 (the task's sites), plus — added 2026-09-26 with the user's agreement, so the seam has no holder outside the owner — `TakeDispatcher` (takes `RefResolution` only) and the two read-only commands `BoardCommand` ctor:47, `DashboardCommand` ctor:57                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                | the three hand-listed parameters at every site; the static helpers `TrackerResolution` and `TakeRefResolution`, whose bodies became the facade's methods (`resolveFactory`, `resolveTracker`, `resolveReadOnly`, `resolveRef`, `refuseForeignRef`, `supportedTypes`); the direct `TrustedTierStartup.bind` calls in `take` and `serve` (now `bindStartupLaw`). **Verdict 2026-09-26 (task 2.4): extracted.** (a) the three are used together in one sequence — bind or load the definition → look up the adapter for its type → build the tracker — which `take` and `serve` each spelled by hand; (b) beyond accessors: that sequence, as the methods above, pinned by `TrackerWiringSpec`; the one accessor, `pipelineSource()`, exists for `RunAssembly.withPipelineSource` and carries no credential; (c) "tracker wiring" names what every command means by "reach the tracker". **Shape (research 2026-09-26, Seemann / Fowler role interface / object-capability attenuation):** the consumer that needs only the ref checks, `TakeDispatcher`, takes `RefResolution`, so its signature names what it uses and it holds nothing through which the seam could be reached. **NFR-S1:** `SecretsProvider` gained no consumer and lost eight — after this change it is declared in the composition modules only by `TrackerWiring`, `ManualRunAssembly` and `ProviderDispatchingExternalCheckClient` (the check-provider side) and the `ManualRunConfiguration` bean; the facade has no accessor for it, so a holder of the wiring can use the secrets but not obtain them. Runner 21 → 19, `SubcommandDispatchFactory.of` 14 → 12, `TakeCommand` 16 → 14, `TakeCommandFactory.of` 11/12 → 9/10, `ServeCommand` 14 → 12 | the parameter type at every consumer; `TrackerWiringOwnerBoundarySpec` in `:bootstrap`: the allowlist of files that may declare a `SecretsProvider` field or parameter in `:application` / `:bootstrap`, and `new EpochRecordingTracker(` / `TrustedTierStartup.bind(` in `TrackerWiring` only, each checked for staleness |

| `BoundTracker` | `BoundTracker` (record: `definition`, `trustedBase`, `trackerConfig`, `factory`, `tracker`, `instanceId`; one derived method `credentialEnvVars()`, one wither `withTracker(Tracker)`) | builders: `TakeCommand.run`, `ServeCommand.run`; the serve root's derivation: `ServeRuntimeAssembly.assemble:51` (`bound.withTracker(trackerHealth)`, D8 amendment b); relays, which take it whole: `TakeRefDispatch.run:25`, `TakeBatch.dispatch:117`, `ServeRuntimeAssembly.assemble`, `SlotWiringFactory.slotWiring`; leaves: `TakeDispatcher.runOneRef:55` / `runBare` / `runExplicit` / `runBatch`, which use every member and take it whole, and `ServeAssembly.feedAutomaton:60` (`trackerConfig`, `tracker`, `instanceId`) and `slotRunner` (`definition`, `tracker`, `instanceId`), which use three and take exactly those (corrected 2026-09-27: they were listed as relays) | the hand-listed five or six at every site; `factory.credentialEnvVars(trackerConfig)` spelled at `TakeCommand.run` and `ServeRuntimeAssembly.assemble`, now `bound.credentialEnvVars()`; the raw tracker reaching anything below `assemble` in serve. **Verdict 2026-09-26 (D8): parameter object, not a facade** — the code below the command uses it; one derived method, one wither (a copy, not a construction), membership closed by the javadoc rule. Sweeps: `grep -rn "credentialEnvVars(trackerConfig)" application/src/main bootstrap/src/main` returns only the record's own method; `grep -n "bound.tracker()\|BoundTracker " application/src/main/java/com/github/oinsio/gnomish/app/ServeCommand.java` returns only the one construction and the one `assemble` hand-off — below the root exactly one bound tracker exists, derived over the health-wrapped tracker | the parameter type at every relay and at the leaves; the two sweeps |
| `SlotWiringFactory` | `SlotWiring` (built) | `TakeCommand.run` and `ServeRuntimeAssembly.assemble`, the two places `new SlotWiring(` is spelled (the "Where a SlotWiring is built" section of `introduce-slot-wiring`'s design) | the two inline constructions, and the shared equipment hand-listed in `TakeCommand` ctor, `ServeCommand` ctor, `TakeCommandFactory.of` ×2 and `SubcommandDispatchFactory.of`. **Verdict 2026-09-26 (D9): extracted, as an abstract factory.** (a) the members are used together in one construction; (b) `slotWiring(BoundTracker, TaskGit, TakeHeartbeat)` (the separate `Tracker` of the draft dropped 2026-09-27 — D9 amendment: the abort handler's tracker is `bound.tracker()` by construction), the construction itself, with the Metz test in its javadoc; (c) "the slot wiring" is a glossary term, and the factory of it is named as a factory. No accessor. Sweep: `grep -rn "new SlotWiring(" application/src/main bootstrap/src/main` returns only the factory's method | the parameter type at both commands; the sweep |
| tracker-bind sequence (rejected) | — | would have been `TakeCommand.run` and `ServeCommand.run` | nothing removed. **Verdict 2026-09-26 (D9): rejected — Metz.** The steps are the same, the failure handling around each step is per mode (`serve`'s two `catch` blocks with their own sentences and exit code); a shared method takes a flag or re-maps failures to sentences (NFR-O1). Each command keeps its five lines | — |
| `ContainerSupports` | `ContainerSupports` (`@Component`; facade over the check-client registry, factory/sandbox/binding properties, the binding registry, the Docker probe and `TaskGit.epochs()`) | `ManualRunConfiguration`'s `ManualRunDrive` and `SubcommandDispatch` beans (D6), which take the `manual` support factory and the `ContainerTakeSupport` from it | the two `containerSupportFactory(...)` calls and the inline `new ContainerTakeSupport(...)` in `ManualRunRunner`'s constructor, and the `dockerProbe` field on the runner. **Verdict 2026-09-26 (D10): extracted.** (a) used together in one construction, twice, differing only in the ownership label; (b) `manualSupport()` / `takeSupport()`, the two constructions; (c) "container support" is the code's own name. Test seam: a named test constructor taking the probe, replacing the `.@dockerProbe` write. Sweep: `grep -rn "containerSupportFactory(\|new ContainerTakeSupport(" bootstrap/src/main` returns only the facade | the parameter type; `ManualRunRunnerContainerOwnershipSpec` still asserts both labels off the built pair |
| `ContainerRunSupportFactory` (instance) | `ContainerRunSupport` (built) | `ContainerSupports` builds one per ownership mode | the nine-parameter static `create` at `ContainerRunSupport.create:130` and `ContainerRunSupportFactory.create:61`, and their adjacent `List<String>` pair. **Verdict (D7): instance over the fixed part, no new type**; `create(cloneDir, taskId, segments, definition, credentialEnvVarsToScrub)` | the parameter count; `ContainerRunSupportSpec` unchanged |
| `CheckEquipment` | `CheckEquipment` (facade over the two built-in check runners, the check-client registry and the credential seam) | the `manualRunAssembly` bean (D6), which takes it in place of the four; `RunAssembler` and the folded `CheckProviderWiring`, which ask it for a run's check ports | the four hand-listed parameters of `ManualRunAssembly`'s public constructor and of the bean; the static `CheckProviderWiring`, whose three methods (`credentialNames`, `externalCheckClient`, the subsection resolution) become the facade's. **Verdict 2026-09-27 (task 3.5): extracted.** (a) the four are used together — every method of `CheckProviderWiring` reads two or more of them; (b) `credentialNames(definition, trackerCredentials)`, `externalCheckClient(console, runLaw, runContext)`, `builtinRunner(sandbox)`, `commandRunner(childEnv, sandbox)` — the check-port construction `RunAssembler` spelled over the four; (c) "the check equipment" is what `RunAssembler` calls it. NFR-S1: the seam is a member with no accessor; `TrackerWiringOwnerBoundarySpec`'s allowlist replaces `ManualRunAssembly` with `CheckEquipment` and `ProviderDispatchingExternalCheckClient` stays. `ManualRunAssembly`'s 14-parameter canonical constructor is replaced by a copy constructor over the plain one plus the four wither members, so clusters (ii) and (iii) of the task are moot — rows not added. `manualRunAssembly` bean 10 → 7, constructor 10 → 7 | the parameter type; the boundary spec |
| `ManualRunners` | `ManualRunners` (facade over `GitModeRunner`, `GitResumeRunner`, `ContainerGitModeRunner`, `ContainerResumeRunner`, and `ContainerSupports` for the plan) | the `manualRunDrive` bean (D6); `ManualRunDrive.driveGit` / `driveResume` | the four runner fields of `ManualRunRunner` and of the drive, and the duplicated `switch (plan.mode())` in `driveResume` and `driveGit`. **Verdict 2026-09-27 (D11): extracted.** (a) the four are used together — one plan decides which runs, and the pair per mode differs only by resume-vs-fresh; (b) `run(order, context, initialState)` and `resume(order, resume)`, each taking the plan from `ContainerSupports.plan(order.definition())` and dispatching by mode (signatures corrected 2026-09-27, task 3.6) — the routing that lived twice in the drive; (c) "the manual runners" is the class javadoc's own phrase. `manualRunDrive` bean 11 → 7 | the parameter type; `grep -n "plan.mode()" bootstrap/src/main` returns only the facade |
| `TakeCommand`, `ServeCommand`, `ServeRuntimeAssembly`, `SlotWiringFactory` as beans | the two tracker-driven commands and their fixed equipment | the `subcommandDispatch` bean (D6), which takes the two built commands with `ReportCommands` | the thirteen-parameter `SubcommandDispatchFactory.of`, folded into four bean methods of seven or fewer (`slotWiringFactory` 5, `serveAssembly` 3, `sandboxLifecyclePass` 3, `serveRuntimeAssembly` 7, `takeCommand` 7, `serveCommand` 6, `subcommandDispatch` 3 — the two three-input beans added 2026-09-27, D11 correction; all in `TrackerCommandConfiguration`). **Verdict 2026-09-27 (D11): beans, no new type** — the classes exist; only their construction moves to the root | `FactoryApplicationSpec` unedited (NFR-R2); `SubcommandDispatchSpec` builds through the same bean methods' bodies |
| `BeatTiming` | `BeatTiming` (record: `interval`, `lostDetection`; compact constructor asserting `lostDetection ≥ interval`; `rollUp()`) | `InstanceHeartbeat` ctor:118 — takes it in place of the two adjacent `Duration`s; built only by `LeaseThresholds.beatTiming(config)` | the two `Duration` parameters and `InstanceHeartbeat.rollUpFor` (its `@DoNotMutate` trace moves with it; `HeartbeatRollUpPeriodSpec` retargets). **Verdict 2026-09-27 (D12): extracted** — deletion test passed, invariant owned by the type, behavior `rollUp()`. `InstanceHeartbeat` 8 → 7 | the parameter type; `grep -rn "new BeatTiming(" application/src/main` returns only `LeaseThresholds` |
| `TakeOutcomeDispatch` (instance) | — | `TakeEngineExecution`, `TakeContainerEngineExecution` | the eight-parameter static `dispatch`; see "Applied, section 3". 8 → 4 | the parameter count |
| `ManualRunDrive` and `SubcommandDispatch` as beans | the two subsystems' assembled entry points | `ManualRunRunner` ctor, now four parameters | the fourteen `runner.*` field reads in `ManualRunDrive`, the inline construction of the four manual runners and of the dispatch in the runner's constructor, and `SubcommandDispatchFactory` as a static recipe. **Verdict 2026-09-26 (D6): the assembly moves to `ManualRunConfiguration`**; the runner composes nothing (FR7). Sweep: `grep -n "runner\." bootstrap/src/main/java/com/github/oinsio/gnomish/app/ManualRunDrive.java` returns nothing; `grep -rn "new GitModeRunner(\|new GitResumeRunner(\|new ContainerGitModeRunner(\|new ContainerResumeRunner(" bootstrap/src/main` returns only the bean method or the drive's own constructor | `FactoryApplicationSpec` unedited (NFR-R2); the parameter count |
| assembly instances (D7, no new type) | — | `ServeRuntimeAssembly` (held by `ServeCommand`), `ServeAssembly` (held by `ServeRuntimeAssembly`, over `factoryProperties` / `serveProperties` / `feedClock` — added 2026-09-27), `TakeCommandFactory` (held by the dispatch bean or folded into it), `SubcommandDispatchFactory` (folded into the bean method) | the static signatures carrying the origin's fields; `ServeAssembly.feedAutomaton` 10 → 7 with exact bound-tracker members. Each is listed in its module's `pitest { excludedClasses }` with the covering suite named (`ServeRuntimeWiringSpec`, `ServeShutdownWiringSpec`, `FactoryApplicationSpec`); no spec of its own — except `ServeAssembly`, which keeps `ServeAssemblySpec` / `ServeAssemblyBuildersSpec` (effect-asserting, pre-existing) and stays in the mutation scope | the parameter count; the exclusion rationale names the suite |
| `FeedAutomaton` | — | `ServeAssembly.feedAutomaton:60` | nothing beyond the two defaulting constructors, moved to a test fixture. **Verdict 2026-09-26 (D5, refuted count): SRP finding, follow-up `split-feed-automaton-composition`**; one constructor at ten, named for `add-parameter-count-gate`'s input list (M1, M4). *Applied 2026-09-27 (task 4.2):* the constructor takes `IdleTiming` (now public, with `selection(wipLimit)` building `FeedSelection` over the same bounds and random); the reason it stays at ten is that it is a composition root inside the class — it builds `FeedTracker`, `FeedOutageRetry`, `FeedResilience`, `FeedCycle` and `FeedViewTracker`, and the follow-up hands it the cycle and view tracker built. The defaulting constructors became `FeedAutomatonFixture` in `:test-fixtures` (`:bootstrap` specs use them too), its default gate on virtual time | the parameter count; `checkTestTimeInjection` over `:test-fixtures` |

**Applied, section 3 (2026-09-27) — where the code corrected a row.**

- `BoundTracker` (task 3.1): as the row. `TakeDispatcher.runOneRef` 8 → 4, `runExplicit` 7 → 3,
  `runBare` 5 → 2, `runBatch` 7 → 3, `TakeBatch.dispatch` 8 → 4, `TakeRefDispatch.run` 9 → 5;
  all stay static. The record is package-private, pinned by `BoundTrackerSpec`.
- `SlotWiringFactory` (task 3.2): **six fields, not seven — `git` is not one.** The method takes
  the `TaskGit` (serve substitutes the outage-decorated one), so a field would be a second source
  of the same value, the connascence the D9 amendment removed for the tracker. `TakeCommand`
  keeps its own `git` (it binds the startup law and resolves the tracker through it) and drops
  `taskIdMdcKey`: the wiring is built before the reaper starts, and the `finally` that clears the
  key reads `wiring.taskIdMdcKey()` — the clearing order (reaper stop, key, attempt scope) is
  unchanged. `TakeCommand` ctor 14 → 7, taking `TakeCommandSeams` whole; `TakeCommandFactory` is
  deleted with both overloads (production used neither default), and the specs build through the
  test-side `TakeCommands`, which constructs a `SlotWiringFactory` exactly as the relay does.
  Enforcement upgraded from the grep to `SlotWiringOwnerBoundarySpec`, whose `SlotWiring` owner is
  now `SlotWiringFactory.java` alone. Pinned by `SlotWiringFactorySpec`.
- Assembly instances (task 3.3): **`ServeCommand` takes the `ServeRuntimeAssembly`**, built by its
  relay (`SubcommandDispatchFactory.of`) — constructing it inside the command would have kept every
  ingredient in the command's constructor. `ServeCommand` 13 → 7. `ServeRuntimeAssembly` holds
  seven (`SlotWiringFactory`, `ServeAssembly`, git, `FactoryPaths`, clock, sandbox pass, container
  support). To stay at seven it reads no property and no engine clock itself: the four
  constructions that did — the tracker-health decorator, the slot ledger, the remote-outage gate,
  the observability wiring — move into `ServeAssembly` with the rest (*Combine Functions into
  Class*: every function over the three fixed members), pinned by the new
  `ServeAssemblyEquipmentSpec`. `slotRunner` stays static: it reads none of the three.
  `ServeAssembly` is at the 200-line cap. The serve specs build through the test-side
  `ServeCommands`, over the same three production classes.
- `ContainerRunSupportFactory` (task 3.4): **it implements `ContainerSupportFactory` itself**, with
  four fields (the operator's check credentials, the check-client registry, the ownership mode,
  `epochs`) and the seam's own seven-parameter `create`. The sandbox and factory properties stay
  per-call, because the seam already hands them in on every call; holding them as fields too would
  be a second source. The lambda in `ManualRunRunner.containerSupportFactory` and the static
  `ContainerRunSupport.create` are gone, and with them the adjacent `List<String>` pair: the three
  credential sources are merged inside `create` (tracker, operator checks, pipeline checks — the
  order they had). The E2E specs that built a bundle directly go through
  `ContainerSupportFixture.direct`.
- `ContainerSupports` (task 3.4): as the row, plus **`plan(PipelineDefinition)`** — a manual run's
  mode plan over the facade's own settings and probe — so the probe is read inside the facade only
  and `ManualRunDrive` stops reading four runner fields for it. The test constructor is the
  package-private seven-parameter one (`AppAssemblyFixture.newManualRunRunnerProbing`), replacing
  `ManualRunContainerDispatchSpec`'s `runner.@dockerProbe` write. `ManualRunRunnerContainerOwnershipSpec`
  now asserts both labels off `manualSupport()` / `takeSupport()` directly.
- `CheckEquipment` (task 3.5): **five fields, not four — the operator's `FactoryProperties` is
  one.** The subsection resolution reads `factory.check` and `factory.connections`, so the
  equipment cannot resolve without them; the assembly keeps its own reference for the executor
  and judge settings, and no setting is read through both. The methods are the row's four, with
  `externalCheckClient` keeping a registry-taking overload as the package-private seam
  `ManualRunAssembly.externalCheckClient` hands its specs. A `checkEquipment` bean (5) joins the
  context, as D11 lists.
- The manual-run root (task 3.6): **`ManualRunners.run(order, context, initialState)` and
  `resume(order, resume)`** — the plan is read from `order.definition()` inside the facade, which
  holds `ContainerSupports` as its fifth field, so neither the drive nor the signature carries the
  plan or the definition. The summary-carrying copy of the assembly is derived by one named method,
  `ManualRunAssembly.withRunSummary()`, which both `manualRunners` and `manualRunDrive` call; each
  gets its own `SummaryAccumulatorListener`, and one invocation runs one path. The take/serve beans
  (`sandboxLifecyclePass`, `slotWiringFactory`, `serveAssembly`, `serveRuntimeAssembly`,
  `takeCommand`, `serveCommand`, `subcommandDispatch`) live in a new `TrackerCommandConfiguration`:
  `ManualRunConfiguration` was past the 200-line cap before this task. `slotWiringFactory` and
  `serveRuntimeAssembly` each ask `ContainerSupports.takeSupport()` for their own
  `ContainerTakeSupport` (the recipe shared one); the bundle holds no state, so two instances
  behave as one. `AppAssemblyFixture` builds the runner through the two configurations' bean
  methods, so the fixture's graph is the context's by construction.
- `TakeOutcomeDispatch` (task 3.9): **extracted as an instance, no new type** — D7's shape. The four
  terminal transitions (`retry`, `park`, `abortFuse`, `finish`) are its fields and
  `dispatch(outcome, context, branchName, order)` the per-run job; both engine executions build one
  per run. (a) the four are used together: they are the run's terminal equipment, and the dispatch
  hands each outcome the ones it needs; (b) the exhaustive outcome switch; (c) the class's existing
  name. 8 → 4. No spec constructs it; the engine-execution specs cover every arm.

`TimeSources` (`systemClock`, `javaTimeClock`, `threadSleeper`; task 2.3, decided above) and `TrackerWiring`
(`trackerAdapterRegistry`, `secretsProvider`, `pipelineSource`; task 2.4, decided above — the
claim-epoch book is not a member, it travels inside `TaskGit` and is read as `git.epochs()`)
were candidate clusters named in the proposal and not committed up front: each was decided
against D2 during the change and entered this table with its verdict; `LedgerWriters` above is the same kind of candidate, entered with its row because
the site it serves had no other, and `FeedAutomaton` (D5, task 4.2) enters the same way,
whichever branch is taken. This table is the one record of every D2 verdict (FR4, M2); task
reports cite its rows. No row claims two values are one by
construction, so no identity spec is required; the `workClaimed` row is behavior-preserving
and is pinned by the existing `BareTakeClaimWalk` and `TakeSlotRunner` specs passing with no
expectation edited (their fixtures may need the new entry point; their assertions may not).

## Risks / Trade-offs

- **A facade invented to hit a number is worse than the long list.** → D2's three-part
  criterion, applied and recorded per cluster; M2 makes "facades with only accessors" a
  measured outcome, not a hope.
- **Changing Spring wiring can break context assembly silently.** → NFR-R2: the
  context-start spec must pass unedited, and the change adds no new resolution-by-name.
- **`FeedAutomaton` may turn out to need splitting, not grouping.** → D5 names that outcome
  as acceptable and routes it to a follow-up rather than forcing it into this change.
- **`SecretsProvider` could gain reach through a facade.** → NFR-S1: any facade carrying it
  is checked for consumers gained, and the finding recorded in that facade's row of the
  single-owner table (the `TrackerWiring` candidate, task 2.4).

## Migration Plan

Source-only; no durable state or wire format. The one non-plain-Java step is the
`FactoryPaths` bean (D4), which replaces two `Path` beans in the same commit — there is no
window in which one exists without the other. Rollback is a revert.
