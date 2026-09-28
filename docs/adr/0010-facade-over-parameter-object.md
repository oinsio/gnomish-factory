# ADR 0010: Facades for Composition Sites

Status: accepted (2026-09-26, introduced by `collapse-composition-roots`; the
criterion is that change's design D2, and the verdicts below are its
single-owner table, recorded here because a change's `design.md` archives
with the change and governs nothing afterwards); amended 2026-09-27 by the
same change — `@Bean` methods and the split of a large root (its design D11),
the deletion test in criterion (a) (its design D12), the context object's one
wither (its design D8, amendment b), and the verdicts taken that day

## Context

`process-invariants.md` caps a signature at seven parameters and names the
remedy for a recurring group: a parameter object (`RunOrder`, `SlotWiring`).
That remedy fits code that *uses* collaborators. It does not fit the code that
*builds* them — composition roots, assemblies and command constructors —
where the argument list recurs nowhere, so a parameter object over it would
satisfy the number while leaving every responsibility where it was. Mark
Seemann names that outcome exactly: a parameter object "only applies
deodorant to the smell", while the real remedy for constructor over-injection
is a Facade Service that "hides the aggregate behavior behind a new
abstraction". Spring's reference documentation says the same from the other
side: many constructor arguments imply that "the class likely has too many
responsibilities".

Two things made this decision worth recording beyond the change that took it.
First, no build gate can tell a facade from an argument bag — the difference
is whether the members are *used together*, which is invisible to a scanner
— so the criterion has to be written and applied in review. Second, one of the
clusters carries the credential seam (`SecretsProvider`), and how a facade
handles a credential-bearing member is a security decision, not a style one.

## Decision

### The criterion: extract only what behaves together

A cluster of collaborators becomes a facade only if all three hold:

- **(a) Used together, or meaningless apart.** The root uses the members
  *together*, in one sequence or one decision, rather than merely receiving
  them together — **or** the members fail Fowler's deletion test: "if you
  deleted one value, would the others make sense?" (a lost-detection
  threshold that is a multiple of a beat interval means nothing without the
  interval). Arriving in the same constructor is not a cluster.
- **(b) Behavior beyond accessors.** The facade carries at least one method
  that is not a getter — the sequence the root used to spell by hand, a
  construction the members are only correct as a group for, a routing, or
  the invariant that makes the members one thing (a compact constructor
  that refuses an inconsistent pair, and the derivation that reads it). A
  type whose every method is an accessor is a parameter object wearing a
  facade's name and is rejected, whatever the parameter count says.
- **(c) A name the reader already has.** The facade names something the
  domain or the code base already talks about ("the report commands", "the
  instance's ledger writers", "the tracker wiring"). A name invented to
  cover a grouping is the tell that (a) failed.

The deletion-test branch of (a) is the looser of the two, deliberately:
Fowler's *Data Clumps* says outright "don't worry about data clumps that use
only some of the fields of the new object", and Evans and Vernon state the
same test from the value-object side ("taken apart, each attribute fails to
provide a cohesive meaning"). What stays strict is (b): a pair that passes
the deletion test but only holds two accessors is Fowler's "structure without
behavior migration" and is rejected — the invariant must live in the type
(`BeatTiming`, introduced by `collapse-composition-roots`, design D12, is the
precedent).

A cluster failing any one of the three is left flat, and the signature comes
under the limit some other way or not at all — a recorded rejection is a
valid outcome, an invented facade is not. The verdict for every cluster, pass
or fail, is written into the change's single-owner table with the failing
criterion named; a task report cites the row.

### Pass-through members: a role interface, never a read-back

When a downstream component needs only some of a facade's members, the
facade is not unpacked for it. Two shapes are acceptable, and one is not:

- The downstream component takes the **whole facade** and calls its
  behavior — right when it needs most of it.
- The downstream component takes a **role interface** the facade implements
  — a narrow face exposing exactly the methods it calls (Fowler's Role
  Interface; the interface-segregation principle; the "parameter object stops
  at the last relay" clause of `process-invariants.md`). Its constructor then
  names what it uses, and its spec fakes only that.
- **Rejected:** the holder reading members back out through accessors and
  handing them on (`wiring.registry()`, `wiring.secrets()`). That is the
  "digging into collaborators" flaw: the constructor signature lies about the
  dependency, and — see below — for a credential it hands out the whole
  authority.

A plain accessor is allowed for a member that carries no authority and that
another component must be *constructed from* (a source handed to an assembly
builder), and the facade's row records which accessor exists and why.

### Credential-bearing members: grant use, never possession

In the object-capability reading of a dependency graph, authority travels
only by reference: whoever holds a reference to the credential seam holds all
of it and can hand it on. So a facade that carries the seam **exposes no
accessor for it**. It exposes the operations that need the credential
(`resolveTracker`, `refuseForeignRef`), each taking the seam from the
facade's own field — an *attenuated* capability: a holder of the facade can
have work done with the secrets and cannot obtain them. The measure of such
a change is the seam's reach — the set of production classes declaring it as
a field or parameter — which must not grow and is pinned by an architecture
spec that allowlists the declaring files.

### A factory with one return type is a facade shape, and is named as one

An object holding fixed collaborators whose one method builds a single
type — the slot's wiring, from the equipment both tracker-driven commands
share — passes the criterion: the members are used together in one
construction (a), the construction is the behavior (b), and the built type
already has a name (c). Seemann's test tells this apart from a Service
Locator: "an Abstract Factory is a generic type with a non-generic Create
method; a Service Locator is a non-generic type with a generic Create
method" — one return type is a factory, a `create(Class<T>)` is the
anti-pattern. Such a facade is named as what it is (`SlotWiringFactory`),
never by an invented collective noun ("harness", "toolkit", "kit"): the
literature has no such term, and reaching for one is the tell that (c)
failed. Its members are exactly what the build uses; a consumer's extras
stay flat in the consumer.

### A context object carries a membership rule and constructs nothing

Some clusters are not facades at all: values that one invocation binds
once (the pipeline definition, the tracker config, the adapter factory, the
live tracker, the instance id) and that every layer below *uses*. That is
Allan Kelly's Encapsulate Context, and it is a parameter object, not a
facade — the "code that uses" case of `process-invariants.md`. Kelly's own
liabilities are the rules for it:

- **Membership is closed by a sentence in the javadoc** ("only what exists
  once the tracker is provisioned and stays fixed for the invocation"); a
  later member must pass the sentence or start its own type. Without it the
  context grows into the kitchen-sink blob Kelly warns of.
- **It constructs nothing.** A derived read of its own members
  (`credentialEnvVars()`) is a value object's behavior and is fine; so is a
  wither that copies the context with one member substituted
  (`withTracker(decorated)`, the composition root's derivation over a
  decorated collaborator, so nothing below the root receives the raw one) —
  a copy is not a construction. A method that builds a collaborator from a
  member plus an argument (`abortFuse(clock)`) makes the context a locator
  and belongs in the facade that owns the construction.
- **The leaf takes it whole only if it uses every member**; otherwise the
  role-interface rule above applies (Henney's role-specific context).

### `@Bean` methods obey the limit; a large root is split, not exempted

Moving an assembly out of a component into `@Configuration` (Seemann: the
graph is composed only at the entry point) produces `@Bean` methods with the
component's old argument list. They are held to the same seven parameters.
The alternative — exempt `@Bean` methods, on the ground that the composition
root is where "everything is coupled" (Seemann, *Composition Root Reuse*) and
a long list there is the honest inventory of the graph — was weighed and
rejected on two points. The only remedy Seemann gives for a root that grew
large is factories and builders wired at the root, or a convention-based
container — never an exemption. And the exemptions Checkstyle's own
`ParameterNumber` check offers (`ignoreOverriddenMethods`,
`ignoreAnnotatedBy`) exist for signatures the author does *not* control,
which a `@Bean` method is not. No source was found that exempts
dependency-registration code from size heuristics.

So a large root is split into more beans, each at seven or fewer — Facade
Services cascading "closer and closer to the application boundary"
(Seemann), and Spring's own advice to split configuration by concern and
inject by parameter. Borrowed from the rejected option: no bean is added for
a number alone; each new bean is a class that already exists (a command, an
assembly object) or a facade with its own row under the criterion above.
A configuration class past the file-size cap is split by concern the same
way (`TrackerCommandConfiguration` beside `ManualRunConfiguration`,
introduced by `collapse-composition-roots`, design D11).

### Duplicated sequences are consolidated; the exit criterion is Metz's

Two callers spelling the same unconditional sequence over the same inputs
(bind the definition → look up the adapter → build the tracker) are one
facade method, not a declared pair: a sequence with one owner is pinned by
one spec, a hand-synced pair is a divergence waiting to happen. The point at
which this stops being true is Sandi Metz's: if the shared method starts
taking a flag or growing a conditional to serve one caller differently, the
abstraction is wrong and the callers should own their sequences again.
Substituted collaborators are not flags: `serve` handing the shared
`slotWiring(...)` its health-wrapped tracker and outage-decorated git where
`take` hands its plain ones is a parameter, and the method stays. The
same test also says when *not* to consolidate: `take` and `serve` run the
same three tracker-binding steps, but `serve` wraps each step in its own
`catch` with its own console sentence and exit code — one shared method
would need a flag or would re-map failures to sentences, so each command
keeps its lines and the rejection is recorded.

### An assembly object gets no spec of its own

A static helper extracted for file size that takes its origin's fields as
parameters is turned back into an instance holding them (Fowler, *Replace
Function with Command* with *Extract Class*; the fields-into-parameters
clause of `process-invariants.md`). Such an object has no behavior beyond
construction, so a unit spec for it can only assert "method calls method".
It is exercised through the effect on the real flow and listed in its
module's mutation exemptions with the covering suite named — the arid-wiring
category `testing.md` defines. Where a parameter object alone brings a
static site under the limit, the helper stays static: a Command with no state
is added complexity for nothing.

### Enforcement

- The **parameter type** at every consumer: once the facade exists, a
  constructor that still accepts the raw members compiles only if someone
  re-adds them, which review sees.
- An **architecture spec in `:bootstrap`** for every claim the type cannot
  carry — the declaring-file allowlist for a credential seam, the
  single-construction-site check for the value the facade builds
  (`TrackerWiringOwnerBoundarySpec` is the precedent, after
  `SlotWiringOwnerBoundarySpec`).
- **Review**: `/review-artifacts` checks that every cluster in a change's
  table has a verdict with (a)–(c) answered; `/audit-implementation` checks
  the code against every row (`implementation.md`).

## Decisions taken under this criterion (2026-09-26 and 2026-09-27)

| Cluster                                                                                                                                                          | Verdict                                                                                                                                                   | Behavior beyond accessors                                                                                                    | Note                                                                                                                                                                                                                                                                                    |
|------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `FactoryPaths` (worktrees root, home dir)                                                                                                                        | extracted                                                                                                                                                 | `underHome(Path)`; two named accessors replace a `Path, Path` adjacency                                                      | also removes Spring's resolution of two `Path` beans by parameter name                                                                                                                                                                                                                  |
| `ReportCommands` (status, usage, board, dashboard)                                                                                                               | extracted                                                                                                                                                 | `run(Subcommand, args)` — the report routing, moved out of the dispatch record                                               | a Spring component; the four share what they do not do: claim, write, drive                                                                                                                                                                                                             |
| `SnapshotSources` (the ten live feeds of the status snapshot)                                                                                                    | extracted, renamed from the proposed `VitalSources`                                                                                                       | `snapshot(...)` — the whole assembly of the snapshot                                                                         | `vitals` is one of six sections, so the proposed name failed (c)                                                                                                                                                                                                                        |
| `LedgerWriters` (four writers + appender)                                                                                                                        | extracted                                                                                                                                                 | the constructor builds all four over one appender; `newRunSummary()`                                                         | keeps every ledger line on one rotation/retention by construction                                                                                                                                                                                                                       |
| `TrackerWiring` (adapter registry, credential seam, definition source)                                                                                           | extracted; `TakeDispatcher` takes the `RefResolution` face                                                                                                | bind → resolve factory → resolve tracker; ref expansion; foreign-ref refusal                                                 | no accessor for the seam; its reach fell from nine declaring classes to four                                                                                                                                                                                                            |
| `TimeSources` (two clocks, a sleeper)                                                                                                                            | rejected, fails (b)                                                                                                                                       | —                                                                                                                            | two port shapes over one wall clock, consumed by different leaves; a facade would hand leaves a clock they never read                                                                                                                                                                   |
| `BoundTracker` (definition, trusted base, tracker config, adapter factory, live tracker, instance id)                                                            | parameter object, not a facade                                                                                                                            | `credentialEnvVars()`, a derived read; `withTracker(decorated)`, a copy; constructs nothing                                  | Encapsulate Context with a membership sentence; whole at the relays and at the leaf that uses every member, exact members at the two `ServeAssembly` builders that use three                                                                                                            |
| `SlotWiringFactory` (the equipment `take` and `serve` build a slot wiring from)                                                                                  | extracted, as an abstract factory                                                                                                                         | `slotWiring(bound, git, heartbeat)` — the construction, no flag                                                              | named as a factory; the abort handler is built over `bound.tracker()`, so no second tracker argument can disagree with it; `serve`'s extras stay flat in `ServeCommand`                                                                                                                 |
| tracker-bind sequence (`bindStartupLaw → resolveFactory → resolveTracker`)                                                                                       | rejected — Metz                                                                                                                                           | —                                                                                                                            | the steps match, the per-step failure handling does not; a shared method would take a flag                                                                                                                                                                                              |
| `ContainerSupports` (check-client registry, factory/sandbox/binding properties, binding registry, Docker probe, epoch book)                                      | extracted                                                                                                                                                 | `manualSupport()` / `takeSupport()` — the two constructions that differ only in the ownership label                          | "container support" is the code's own name; carries the Docker-probe test seam through a named constructor                                                                                                                                                                              |
| `ManualRunAssembly` (ten ingredients the runner re-listed)                                                                                                       | the runner takes the assembly, not its ingredients                                                                                                        | — (the facade already existed)                                                                                               | the runner reads nothing back through the assembly's fields                                                                                                                                                                                                                             |
| `ManualRunRunner` as composition root (manual-run graph + subcommand dispatch, 20 deps)                                                                          | assembly moved to `@Configuration`; the runner takes four assembled values (`GitVersionCheck`, `SubcommandDispatch`, `ManualRunDrive`, the error console) | —                                                                                                                            | Seemann: application code "is never composed"; a sibling reading the runner's fields was a locator by another route                                                                                                                                                                     |
| `CheckEquipment` (two built-in check runners, check-client registry, credential seam, operator's factory settings)                                               | extracted                                                                                                                                                 | `credentialNames(...)`, `externalCheckClient(...)`, `builtinRunner(...)`, `commandRunner(...)` — the check-port construction | no accessor for the seam; replaces `ManualRunAssembly` in the seam's allowlist                                                                                                                                                                                                          |
| `ManualRunners` (the four manual runners + `ContainerSupports` for the plan)                                                                                     | extracted                                                                                                                                                 | `run(order, context, initialState)` / `resume(order, resume)` — the mode routing that lived twice                            | "the manual runners" is the code's own phrase                                                                                                                                                                                                                                           |
| `TakeCommand`, `ServeCommand`, `ServeRuntimeAssembly`, `SlotWiringFactory`, `ServeAssembly`, `SandboxLifecyclePass` as beans                                     | beans, no new type                                                                                                                                        | —                                                                                                                            | the thirteen-parameter `SubcommandDispatchFactory.of` folded into bean methods of seven or fewer (the `@Bean` section above)                                                                                                                                                            |
| static recipes (`ServeRuntimeAssembly`, `ServeAssembly`, `SubcommandDispatchFactory`, `TakeCommandFactory`, `ContainerRunSupportFactory`, `TakeOutcomeDispatch`) | instances over the origin's fields, no new type; `SubcommandDispatchFactory` and `TakeCommandFactory` folded into beans and deleted                       | —                                                                                                                            | an instance with no decision (`ServeRuntimeAssembly`) gets no spec and is exempted with its covering suite named; one that kept or gained a decision (`ServeAssembly`, `ContainerRunSupportFactory`, `TakeOutcomeDispatch`'s outcome switch) stays in the mutation scope with its specs |
| `TakeRefDispatch`, `TakeBatch`, `TakeDispatcher.runOneRef`                                                                                                       | stay static                                                                                                                                               | —                                                                                                                            | `BoundTracker` alone brings them under the limit; a Command with no state is added complexity                                                                                                                                                                                           |
| `BeatTiming` (beat interval, lost-detection threshold)                                                                                                           | extracted — the deletion-test branch of (a)                                                                                                               | the compact constructor's ordering invariant; `rollUp()`                                                                     | built only by `LeaseThresholds.beatTiming(config)`; removes a `Duration, Duration` adjacency                                                                                                                                                                                            |
| `FeedAutomaton` constructor (13)                                                                                                                                 | split, in `add-parameter-count-gate` (design D7): the building moved out into `FeedAssembly`, an assembly object holding the timing equipment as fields; the automaton's constructor takes its cycle and view tracker built, at seven | `feedAutomaton(tracker, instanceId, ledger, runner, notifier, gate)` — the one construction; the automaton's constructor is package-private, so nothing else can assemble one | takes `IdleTiming`; the constructor was a composition root inside the class — a responsibility problem, not a grouping one — so the fix was a split, not a facade over the ten                                                                                                        |

## Alternatives Considered

**Parameter object over each composition root** (`ManualRunRunnerArgs` and
the like). Satisfies the limit today and leaves twenty-eight responsibilities
in one class; the record exemption of the count scanner would be doing the
work instead of the design. Rejected as the deodorant Seemann describes.

**Facade with accessors, members read back for downstream consumers.** Fewer
edits per change. Rejected: it turns the facade into a holder whose
constructor signature misstates what it needs, and for a credential-bearing
member it widens reach to every future holder of the facade. The role
interface costs one small type and removes both problems.

**Leave the credential seam reach as convention.** Rejected: the 2026-09-26
count found the seam declared in nine production classes of the composition
modules, six of them relays that never used it — reach grows silently unless
a spec counts it.

## Consequences

Positive: every composition site in `:application` / `:bootstrap` is under
the limit by a facade it can justify, or by a recorded rejection; the
credential seam's reach is measured and pinned; the tracker-resolution
sequence has one owner and one spec.

Negative: each facade is one more type to name, document and keep in the
glossary; a role interface is a second type for one consumer. Accepted as the
price of signatures that say what they mean.

## See also

- `.claude/rules/process-invariants.md` — the seven-parameter limit, "the
  parameter object stops at the last relay", the fields-into-parameters
  anti-split.
- `.claude/rules/design-decisions.md` — the single-owner table every
  extraction is recorded in.
- Seemann, *Refactoring to Aggregate Services* (2010), *On Constructor
  Over-injection* (2018), *Composition Root* (2011), *Composition Root
  Reuse*, *Abstract Factory or Service Locator?* (2010) and *Integration
  Testing composed functions* (2015); Fowler, *Role Interface*, *Preserve
  Whole Object*, *Replace Function with Command*, *Combine Functions into
  Class*, *Data Clumps* and *Dependency Composition*; Evans, *Domain-Driven
  Design*, and Vernon, *Implementing Domain-Driven Design* (value objects);
  Checkstyle, `ParameterNumber` check documentation; Kelly, *The
  Encapsulate Context Pattern* (Overload 63); Henney, *Context
  Encapsulation* (EuroPLoP 2005); Hevery, *Guide to Writing Testable Code*
  ("digging into collaborators"); Miller, *Robust Composition*
  (object-capability attenuation); Metz, *The Wrong Abstraction*.
