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

| Site | Params now → after slot wiring |
|------|--------------------------------|
| `ManualRunRunner` ctor (`:bootstrap`) | 28 → 28 |
| `ObservabilityAssembly.assemble` | 16 → 16 |
| `SubcommandDispatchFactory.of` | 19 → 19 |
| `ObservabilityAssembly.assembleSnapshot` | 14 → 14 |
| `ServeRuntimeAssembly.assemble` | 19 → 19 |
| `ManualRunAssembly` ctors (two, `:bootstrap`) | 14, 10 → 14, 10 |
| `TakeCommand` ctor | 16 → 16 |
| `FeedAutomaton` ctors (three) | 13, 12, 11 → 13, 12, 11 |
| `ServeCommand` ctor | 16 → 16 |
| `ObservabilityWiring` ctor | 9 → 9 |
| `ServeAssembly.feedAutomaton` | 10 → 10 |
| `TakeRefDispatch.run` | 12 → 9 |
| `TakeCommandFactory.of` (two, :24 and :53) | 11, 12 → 11, 12 |
| `TakeOutcomeDispatch.dispatch` | 9 → 8 (`introduce-slot-wiring` task 5.1: takes `AbortFuse` in place of the adjacent handler/threshold pair) |
| `TakeBatch.dispatch` | 11 → 8 |
| `InstanceHeartbeat` ctor | 8 → 8 |
| `ContainerRunSupport.create`, `ContainerRunSupportFactory.create` (`:bootstrap`) | 9 → 9 each |

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

**D2 — The extraction criterion, applied per cluster and recorded per cluster.** A cluster
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

**Sync surfaces: none — this change adds no parallel implementation and touches no declared
pair.** Verified by `grep -rn "Kept in sync with" */src/main` against the twenty-two sites: no
composition root or assembly in the list carries a marker or is named by one. If the
`FeedAutomaton` inspection (D5) produces a second implementation of anything, that outcome
is out of this change's scope by NG2 and moves to the follow-up it recommends.

**Single-owner mechanisms.**

| Owner | Value (type) | Consumers | Old way removed | Enforced by |
|-------|--------------|-----------|-----------------|-------------|
| `FactoryPaths` | `FactoryPaths` (record, accessors `worktreesRoot()` / `homeDir()`) | Three kinds of consumer, split 2026-09-26 because `process-invariants.md` ("the parameter object stops at the last relay") gives each a different shape. **Relays, which take the value whole** — the four sites that today take both paths adjacently: `ManualRunRunner` ctor:152 (`:169-170`), `SubcommandDispatchFactory.of:30` (`:33-34`), `ServeCommand` ctor:94 (`:97-98`), `ServeRuntimeAssembly.assemble:52` (`:54-55`). **Plain-Java single-path leaves, which take one `Path` read from the value at their relay** — `TakeCommand` ctor:109 (`worktreesRoot` only, `:112`), `TakeCommandFactory.of:24` and `:53` (`worktreesRoot` only, `:27` / `:56`; `:24` was missing from this row), fed `FactoryPaths.worktreesRoot()` by `SubcommandDispatchFactory.of:50`; `ObservabilityAssembly.assemble:103` (`homeDir` only, `:107`), fed `FactoryPaths.homeDir()` by `ServeRuntimeAssembly.assemble:142`. A leaf that needs one path does not receive both. **Spring-fed leaves, which take the value whole and read one accessor** — `StatusCommand` ctor:65 (`Path worktreesRoot`) and `DashboardCommand` ctor:60 (`Path homeDir`), the two `@Component`s Spring feeds from the `Path` beans by parameter name (added 2026-09-26: they were missing from this row and `StatusCommand` was wrongly listed as an exemption, while the Migration Plan deletes the beans they resolve); no relay exists to pre-read for them, so they are the one place a single-path consumer takes the whole value — and the Spring configuration that declares both `Path` beans | the two bare `Path` beans resolved by parameter name, and every `(Path, Path)` adjacency; sweep: `grep -rn "Path worktreesRoot\|Path homeDir"` over the files of the four relays and the two Spring-fed leaves must return nothing; over each plain-Java single-path leaf it must return exactly its one declaration; `grep -rln "@Component" application/src/main bootstrap/src/main \| xargs grep -ln "Path worktreesRoot\|Path homeDir"` must return nothing (no Spring-fed leaf survives on the deleted beans); and across `application/src/main bootstrap/src/main` no signature may take the two adjacently. Exemption: downstream carriers of a single path are fed from `FactoryPaths`, not owners of it — the `SlotWiring` component `worktreesRoot` (introduced by `introduce-slot-wiring`), filled from `FactoryPaths.worktreesRoot()` at its two assembly points, `TakeCommand.run:210` and `ServeRuntimeAssembly.assemble:106`; and the plain-Java leaf components that take one worktrees path (`TaskWorktreeGit`, `WorktreeJanitor`, `ServeAssembly.worktreeJanitor:110`, `HostResumeMechanics`, `GitResumeRunner` and the like), which receive it from those two values and are never Spring-injected | the parameter type at the relays and the Spring-fed leaves — a bare `Path` no longer satisfies those signatures, so a transposed or misnamed injection does not compile; at the plain-Java single-path leaves, the absence of a second `Path` to transpose with, pinned by the sweep |
| `ManualRunAssembly` | `ManualRunAssembly` | `ManualRunRunner` ctor:152 | the three ingredient parameters the runner holds only for the assembly (`filesExistCheckRunner`, `shellCommandCheckRunner`, `threadSleeper`) and the inline `new ManualRunAssembly(...)` in the runner's constructor body (D3; the other seven ingredients stay until their own clusters are decided); sweep `grep -rn "new ManualRunAssembly(" bootstrap/src/main` must return only the bean method | the parameter type |
| `ReportCommands` | `ReportCommands` (facade over status / usage / board / dashboard) | `ManualRunRunner` ctor:152, `SubcommandDispatchFactory.of:30` | the four hand-listed command parameters. Conditional: if the cluster fails D2's criterion (no method beyond accessors), it is not extracted and the reason is recorded instead | the parameter type, if extracted |
| `TakeClaimAndWork.workClaimed(RunOrder, TaskRef)` | the post-claim `TakeOrder` and its dispatch (FR6) | `BareTakeClaimWalk.resolve:74-75`, `TakeSlotRunner.run:142-143` — the two places that today spell `new TakeOrder(run, tracker.fetchTask(ref), tracker, instanceId)` followed by `claimAndWork.dispatchAfterClaim(order)` for a task this caller has already claimed; the MDC key, the anchor log and the summary stay with the callers | the inline fetch-then-dispatch pair at both sites; sweep `grep -rn "new TakeOrder(" application/src/main bootstrap/src/main` must return exactly three hits: `TakeOrder.withDefinition`, `workClaimed`'s own body, and the one exemption. Exemption: `TakeDispatcher.runOneRef:89` builds its order *before* the claim — `TakeDisposition.dispose` claims or takes over afterwards — so it is not an already-claimed order and must not route through `workClaimed`. This supersedes the "exactly three places" statement of `introduce-take-order`'s table: the count is one owner plus one pre-claim site | the sweep in task 3.7, and `dispatchAfterClaim` narrowed so that `workClaimed` is the only public entry that takes a `TaskRef` for a claimed task |
| `VitalSources` | `VitalSources` (facade over the snapshot's vital feeds) | `ObservabilityAssembly.assemble:103`, `ObservabilityAssembly.assembleSnapshot:175` — the two sites that share the feeds; `ObservabilityWiring` ctor:47 was listed here until 2026-09-26 but shares none of them (see the next row) | the ten parameters the two sites share (`automaton`, `slotLedger`, `slotCapacity`, `progress`, `trackerHealth`, `heartbeat`, `standingReaper`, `worktreeJanitor`, `sweepTickLog`, `remoteOutageGate`; corrected 2026-09-26 from "eight"), of which the four `VitalsSnapshotAssembler.assemble` consumes (`heartbeat`, `standingReaper`, `worktreeJanitor`, `sweepTickLog`) are the vital feeds proper — whether the facade covers the four or the ten is D2's call in task 2.2. Conditional on D2 as above | the parameter type, if extracted |
| `LedgerWriters` | `LedgerWriters` (facade over the ledger write points one instance owns) | `ObservabilityWiring` ctor:47 — its nine parameters are `lifecycleTracker`, `snapshotWriter`, the four `*LedgerWriter`s (`lifecycle`, `taskOutcome`, `sweep`, `remoteOutage`), `ledgerAppender`, `instance`, `clock`; none is a snapshot feed, so no `VitalSources` extraction moves it (added 2026-09-26: the constructor had no cluster and no task of its own) | the four `*LedgerWriter` parameters and `ledgerAppender`, which `assemble:103` builds together over one `RotatingLedgerAppender` and which the wiring hands out through `taskOutcomeLedgerWriter()`, `sweepLedgerWriter()`, `remoteOutageLedgerWriter()` and `newRunSummaryLedgerWriter()`. Conditional on D2 in task 2.5: the candidate method beyond accessors is `newRunSummaryLedgerWriter()` (a writer built over the shared appender) — if that is all the cluster does together, the row records the rejection | the parameter type, if extracted |

`TimeSources` (`systemClock`, `javaTimeClock`, `threadSleeper`; task 2.3) and `TrackerWiring`
(`trackerAdapterRegistry`, `secretsProvider`, `pipelineSource`; task 2.4 — the claim-epoch
book is not a member, it travels inside `TaskGit` and is read as `git.epochs()`) are
candidate clusters named in the proposal but not committed here: each is decided against D2
during the change and enters this table with its verdict, extracted or rejected with a
reason; `LedgerWriters` above is the same kind of candidate, entered with its row because
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
