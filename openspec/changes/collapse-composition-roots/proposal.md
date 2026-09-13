# Proposal: collapse-composition-roots

## Why

The two preceding changes name the things a take slot carries: the order (one invocation's
job) and the wiring (one slot's equipment). Twenty signatures remain that carry neither —
they are the code that **builds** collaborators rather than the code that uses them:
`ManualRunRunner` with 27 injected dependencies, `SubcommandDispatchFactory.of` with 19,
`ObservabilityAssembly.assemble` with 16, `ServeRuntimeAssembly.assemble`, the three
`FeedAutomaton` constructors, the `*Command` constructors and the `*Assembly` classes.

For these, a parameter object is the wrong medicine, and the literature is unusually direct
about it. Mark Seemann's remedy for constructor over-injection is a **Facade Service** —
extract an abstraction that hides the *aggregate behavior* of a cluster of collaborators —
and he names the parameter-object alternative a deodorant: it makes the smell go away
without removing what caused it. Spring's own reference documentation says the same in one
sentence: "a large number of constructor arguments is a bad code smell, implying that the
class likely has too many responsibilities".

`ManualRunRunner` is the clearest instance. Nine of its 27 arguments are exactly the nine
`ManualRunAssembly` already takes — the runner re-lists the assembly's ingredients instead
of taking the assembly. Four more are the pre-built report commands. Two are `Path
worktreesRoot` and `Path homeDir`, adjacent parameters of the same type, which
`process-invariants.md` already calls a transposition hazard at any count and which Spring
today tells apart only by parameter name.

## What Changes

- **MODIFIED**: each composition root and assembly listed in the design's cluster table
  takes facade values instead of a flat argument list — clusters extracted by what they
  collectively *do*, not by what groups tidily.
- **ADDED**: the facade types those clusters become. The design names the first set
  (`FactoryPaths`, `ReportCommands`, `TimeSources`, `TrackerWiring`, `VitalSources`); the
  rest are discovered per class against the stated criterion, not invented up front.
- **MODIFIED**: Spring wiring where a facade replaces two beans that were told apart by
  parameter name (`worktreesRoot` / `homeDir`), which is the one place this refactor touches
  context assembly rather than plain Java.
- **MODIFIED**: `ManualRunRunner` takes the `ManualRunAssembly` it currently rebuilds from
  ingredients.
- No behavior change, no spec requirement changed, no new module edge.

## Goals

- **G1** — Every composition root and assembly in `:application` and `:bootstrap` comes
  under the seven-parameter limit: 20 sites before, 0 after.
- **G2** — Each extracted facade is justified by aggregate behavior, not by grouping
  convenience: every facade the change adds has at least one method beyond its accessors,
  or is rejected and its cluster left flat with the reason recorded.
- **G3** — Remove the `Path` / `Path` transposition hazard in the manual-run wiring by
  giving the two roots distinct identity.
- **G4** — Leave behavior bit-identical, including Spring context assembly: the same beans
  reach the same places.

## Non-Goals

- **NG1** — The ten remaining sites outside `:application`/`:bootstrap` (the container
  environment builders in `sandbox/docker`, the agent round executions, the GitHub marker
  and the pipeline model builder). They are unrelated to composition and land with
  `add-parameter-count-gate`, which is where the residue must reach zero.
- **NG2** — Changing which collaborators exist, or what any of them does. Extracting a
  facade must not merge two responsibilities into one class to make a number smaller.
- **NG3** — Introducing a dependency-injection framework feature the project does not use
  today (component scanning, qualifiers beyond what exists). ADR 0001 keeps Spring minimal.
- **NG4** — Any behavior or spec change.

## Users & Scenarios

- **U1** — A developer adding a subcommand today edits `ManualRunRunner`'s 27-argument
  constructor, `SubcommandDispatchFactory.of`'s 19 and the Spring wiring; afterwards the
  command joins `ReportCommands` and the two roots are untouched.
- **U2** — A reviewer asking "what does `ManualRunRunner` actually do?" today reads 27
  arguments and cannot tell responsibilities apart; afterwards the constructor names the
  five or six things it composes.
- **U3** — An operator is unaffected, and must be: the same beans reach the same places
  (G4).

## Requirements

### Functional

- **FR1** — Every site in the design's cluster table takes seven parameters or fewer.
- **FR2** — `ManualRunRunner` takes a `ManualRunAssembly`, not the nine ingredients that
  assembly is built from.
- **FR3** — `worktreesRoot` and `homeDir` reach their consumers through one value with
  distinct accessors, so no call site can transpose them.
- **FR4** — Every facade type added by this change carries at least one method beyond its
  accessors (G2), or the cluster is left flat and the reason recorded in the design.
- **FR5** — Where a facade replaces beans Spring resolved by parameter name, a bean
  producing the facade exists and the context starts with the same graph.

### Non-Functional — Reliability

- **NFR-R1** — Behavior-preserving: the existing suite passes with no spec expectation
  edited. A red spec stops the task.
- **NFR-R2** — The Spring context assembles identically: the context-start spec passes
  unedited, and no bean gains, loses or changes identity.

### Non-Functional — Observability

- **NFR-O1** — Log lines, MDC keys and operator event codes unchanged; the
  log-expectation gate passes with no expectation file edited.

### Non-Functional — Security

- **NFR-S1** — `SecretsProvider` and the credential-name lists keep their current reach: a
  facade must not widen who can see them. Any facade carrying either is checked for
  consumers gained, and the finding recorded.

## Operator Experience Criteria

- **UX1** — No operator-visible change; `gnomish run`, `take`, `serve`, `status`, `board`
  and `dashboard` behave identically.

## Success Metrics

- **M1** — Composition-root and assembly sites over the limit: 20 before, 0 after.
- **M2** — Facades added that carry only accessors: 0 (FR4).
- **M3** — Spring context spec and the full suite pass with zero expectation edits.
- **M4** — Parameter-limit violations remaining in `src/main` after this change: 10, all of
  them the NG1 set, named by file and line for `add-parameter-count-gate`.

## Open Questions

- **Q1** — Do the three `FeedAutomaton` constructors (11, 12 and 13 parameters) reduce to
  one constructor plus a test seam, or does the class need splitting by responsibility
  first? Proposed answer: inspect during the change; if no behavioral cluster exists, this
  is an SRP problem and the honest outcome is a recorded finding plus a follow-up change,
  not a facade invented to hit a number.

## Impact

- **Baseline freshness**: every count in this proposal comes from the 2026-09-12
  scan, taken before `fix-claim-epoch-fence` landed. The preceding change re-takes
  it (`introduce-take-order` task 0.2); correct these figures through
  `/opsx:update` if the fresh count differs, rather than reporting against a stale
  number.
- **Modules**: `:application` (`app`, `app.serve`), `:bootstrap` (the composition root and
  Spring configuration). No new module edge, no new dependency.
- **Declared sync pairs touched**: none expected — the composition roots are single
  implementations. Confirmed by the design's Sync surfaces decision.
- **Spring**: one wiring point changes shape (FR3, FR5).
- **Glossary**: any facade that names a new domain concept gets an entry in the same
  change, per `process-invariants.md`.
- **Depends on**: `introduce-slot-wiring` — several of these sites shrink there first, and
  their residual shape is this change's starting point.
