# Design: collapse-composition-roots

## Context

See `proposal.md` — Why. After `introduce-take-order` and `introduce-slot-wiring`, thirty
signatures in `src/main` remain over the limit; twenty of them are in `:application` and
`:bootstrap` and are composition code. This design covers those twenty. The other ten
(`sandbox/docker` environment builders, agent round executions, `GithubMarkerJson`,
`PipelineModelBuilder.mapAndValidate`) are not composition and are deliberately left to
`add-parameter-count-gate`, which must reach zero.

The measured starting shape, after the two preceding changes:

| Site | Params now → after slot wiring |
|------|--------------------------------|
| `ManualRunRunner` ctor (`:bootstrap`) | 27 → 26 |
| `ObservabilityAssembly.assemble` | 16 → 16 |
| `SubcommandDispatchFactory.of` | 19 → 14 |
| `ObservabilityAssembly.assembleSnapshot` | 14 → 14 |
| `ServeRuntimeAssembly.assemble` | 20 → 13 |
| `ManualRunAssembly` ctors (two) | 13, 9 → 13, 9 |
| `TakeCommand` ctor | 17 → 12 |
| `FeedAutomaton` ctors (three) | 13, 12, 11 → 12, 11, 10 |
| `ServeCommand` ctor | 16 → 11 |
| `ObservabilityWiring` ctor | 9 → 9 |
| `ServeAssembly.feedAutomaton` / `slotRunner` | 10, 16 → 9, 8 |
| `TakeRefDispatch.run` | 12 → 9 |
| `TakeCommandFactory.of` | 12 → 8 |
| `TakeOutcomeDispatch.dispatch`, `TakeBatch.dispatch` | 11 → 8 each |
| `InstanceHeartbeat` ctor | 8 → 8 |

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
`ManualRunRunnerArgs`-style records — they would satisfy the limit today and leave 27
responsibilities in one class, and the record exemption would be doing the work instead of
the design.

**D2 — The extraction criterion, applied per cluster and recorded per cluster.** A cluster
becomes a facade only if (a) its members are used together by the root rather than merely
arriving together, (b) the facade carries at least one method beyond its accessors (FR4),
and (c) the facade names something a reader already recognizes. A cluster failing any of
the three is left flat and the reason is written into the task report. *Rationale:* the
research found no operational test distinguishing a real facade from an argument bag; since
no gate can make that call, the criterion has to be explicit and per-cluster, checked in
review. *Alternative rejected:* "group anything above seven" — that is the failure mode
the whole family of changes exists to avoid.

**D3 — `ManualRunRunner` takes the assembly, not its ingredients (FR2).** Nine of the 27
arguments are exactly `ManualRunAssembly`'s nine constructor parameters; the runner rebuilds
the assembly inline today. It takes the assembly as a bean instead. *Rationale:* the largest
single reduction in the change, and it needs no new type — the facade already exists and is
simply not being used as one. *Alternative rejected:* leaving it, because the runner also
builds a second, listener-carrying copy — that copy is derived from the first by
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

**Sync surfaces: none — this change adds no parallel implementation and touches no declared
pair.** Verified by `grep -rn "Kept in sync with" */src/main` against the twenty sites: no
composition root or assembly in the list carries a marker or is named by one. If the
`FeedAutomaton` inspection (D5) produces a second implementation of anything, that outcome
is out of this change's scope by NG2 and moves to the follow-up it recommends.

**Single-owner mechanisms.**

| Owner | Value (type) | Consumers | Old way removed | Enforced by |
|-------|--------------|-----------|-----------------|-------------|
| `FactoryPaths` | `FactoryPaths` (record, accessors `worktreesRoot()` / `homeDir()`) | `ManualRunRunner` ctor:141, `SubcommandDispatchFactory.of:30`, `ServeRuntimeAssembly.assemble:51`, `ObservabilityAssembly.assemble:103`, `ServeCommand` ctor:93, `TakeCommand` ctor:112, `TakeCommandFactory.of:53`, and the Spring configuration that declares both `Path` beans | the two bare `Path` beans resolved by parameter name, and every `(Path, Path)` adjacency; sweep `grep -rn "Path worktreesRoot\|Path homeDir" application/src/main bootstrap/src/main` must return only `FactoryPaths`' own declaration. Exemption: none | the parameter type — a bare `Path` no longer satisfies these signatures, so a transposed or misnamed injection does not compile |
| `ManualRunAssembly` | `ManualRunAssembly` | `ManualRunRunner` ctor:141 | the nine ingredient parameters and the inline `new ManualRunAssembly(...)` in the runner's constructor body; sweep `grep -rn "new ManualRunAssembly(" bootstrap/src/main` must return only the bean method | the parameter type |
| `ReportCommands` | `ReportCommands` (facade over status / usage / board / dashboard) | `ManualRunRunner` ctor:141, `SubcommandDispatchFactory.of:30` | the four hand-listed command parameters. Conditional: if the cluster fails D2's criterion (no method beyond accessors), it is not extracted and the reason is recorded instead | the parameter type, if extracted |
| `VitalSources` | `VitalSources` (facade over the snapshot's vital feeds) | `ObservabilityAssembly.assemble:103`, `ObservabilityAssembly.assembleSnapshot:175`, `ObservabilityWiring` ctor:47 | the eight hand-listed feed parameters those three share. Conditional on D2 as above | the parameter type, if extracted |

`TimeSources` and `TrackerWiring` are candidate clusters named in the proposal but not
committed here: each is decided against D2 during the change and enters this table with its
verdict, extracted or rejected with a reason. No row claims two values are one by
construction, so no identity spec is required.

## Risks / Trade-offs

- **A facade invented to hit a number is worse than the long list.** → D2's three-part
  criterion, applied and recorded per cluster; M2 makes "facades with only accessors" a
  measured outcome, not a hope.
- **Changing Spring wiring can break context assembly silently.** → NFR-R2: the
  context-start spec must pass unedited, and the change adds no new resolution-by-name.
- **`FeedAutomaton` may turn out to need splitting, not grouping.** → D5 names that outcome
  as acceptable and routes it to a follow-up rather than forcing it into this change.
- **`SecretsProvider` could gain reach through a facade.** → NFR-S1: any facade carrying it
  is checked for consumers gained, and the finding recorded in the task report.

## Migration Plan

Source-only; no durable state or wire format. The one non-plain-Java step is the
`FactoryPaths` bean (D4), which replaces two `Path` beans in the same commit — there is no
window in which one exists without the other. Rollback is a revert.
