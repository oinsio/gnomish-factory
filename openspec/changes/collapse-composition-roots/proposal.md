# Proposal: collapse-composition-roots

## Why

The two preceding changes name the things a take slot carries: the order (one invocation's
job) and the wiring (one slot's equipment). Twenty-two signatures remain that carry neither —
they are the code that **builds** collaborators rather than the code that uses them:
`ManualRunRunner` with 28 injected dependencies, `SubcommandDispatchFactory.of` with 19,
`ObservabilityAssembly.assemble` with 16, `ServeRuntimeAssembly.assemble`, the three
`FeedAutomaton` constructors, the `*Command` constructors and the `*Assembly` classes.

For these, a parameter object is the wrong medicine, and the literature is unusually direct
about it. Mark Seemann's remedy for constructor over-injection is a **Facade Service** —
extract an abstraction that hides the *aggregate behavior* of a cluster of collaborators —
and he names the parameter-object alternative a deodorant: it makes the smell go away
without removing what caused it. Spring's own reference documentation says the same in one
sentence: "a large number of constructor arguments is a bad code smell, implying that the
class likely has too many responsibilities".

`ManualRunRunner` is the clearest instance. Ten of its 28 arguments are exactly the ten
`ManualRunAssembly` already takes — the runner re-lists the assembly's ingredients instead
of taking the assembly; three of the ten it holds for no other purpose, the other seven it
also uses itself and sheds only with the cluster each belongs to. Four more are the pre-built
report commands. Two are `Path
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
- **MODIFIED**: the order for an already-claimed task is assembled and dispatched in one
  place, `TakeClaimAndWork.workClaimed`, instead of being spelled identically by the bare
  take walk and the serve slot runner (handed over by `introduce-slot-wiring`).
- No behavior change, no spec requirement changed, no new module edge.

## Goals

- **G1** — Every composition root and assembly in `:application` and `:bootstrap` comes
  under the seven-parameter limit: 22 sites before, 0 after.
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

- **U1** — A developer adding a subcommand today edits `ManualRunRunner`'s 28-argument
  constructor, `SubcommandDispatchFactory.of`'s 19 and the Spring wiring; afterwards the
  command joins `ReportCommands` and the two roots are untouched.
- **U2** — A reviewer asking "what does `ManualRunRunner` actually do?" today reads 28
  arguments and cannot tell responsibilities apart; afterwards the constructor names the
  five or six things it composes.
- **U3** — An operator is unaffected, and must be: the same beans reach the same places
  (G4).

## Requirements

### Functional

- **FR1** — Every site in the design's cluster table takes seven parameters or fewer.
- **FR2** — `ManualRunRunner` takes a `ManualRunAssembly`, not the ten ingredients that
  assembly is built from; the three ingredients it held only for the assembly leave its
  constructor, and it reads none of the other seven back through the assembly.
- **FR3** — `worktreesRoot` and `homeDir` reach their consumers through one value with
  distinct accessors, so no call site can transpose them.
- **FR4** — Every facade type added by this change carries at least one method beyond its
  accessors (G2), or the cluster is left flat and the reason recorded in the design.
- **FR5** — Where a facade replaces beans Spring resolved by parameter name, a bean
  producing the facade exists and the context starts with the same graph — including every
  `@Component` that Spring fed from the replaced beans, not only the composition roots.
- **FR6** — A `TakeOrder` for a task the caller has already claimed is assembled and
  dispatched by one owner, `TakeClaimAndWork.workClaimed(RunOrder, TaskRef)`, used by the
  bare take walk and the serve slot runner; the explicit-ref path, which builds its order
  before claiming, is the named exemption. Behavior-preserving: MDC, anchor log and summary
  stay with the callers.

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

- **M1** — Composition-root and assembly sites over the limit: 22 before, 0 after.
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

- **Baseline freshness**: every count in this proposal was confirmed on 2026-09-26 by
  re-running the scanner of `introduce-take-order` task 0.2 over the working tree after
  both predecessors were archived: 32 signatures over the limit in `src/main`, 22 in
  `:application` / `:bootstrap` (this change) and 10 elsewhere (NG1), at the file:line
  positions recorded in `introduce-slot-wiring`'s residual list (task 6.2). Re-take the
  scan at the start of `/opsx:apply` (task 0.1) and correct the figures through
  `/opsx:update` if it differs.
- **Modules**: `:application` (`app`, `app.serve`), `:bootstrap` (the composition root and
  Spring configuration). No new module edge, no new dependency.
- **Test fixtures**: twenty-five spec and fixture files under `bootstrap/src/test` and
  `application/src/test` construct the listed sites by hand (`new ServeCommand(...)`,
  `TakeCommandFactory.of(...)`, `new StatusCommand(...)`, ...) and therefore change with
  every signature this change touches; task 3.8 names them. Their assertions do not change
  (NFR-R1): "no expectation edited" is the criterion, not "unedited".
- **Declared sync pairs touched**: none expected — the composition roots are single
  implementations. Confirmed by the design's Sync surfaces decision.
- **Spring**: one wiring point changes shape (FR3, FR5).
- **Glossary**: any facade that names a new domain concept gets an entry in the same
  change, per `process-invariants.md`.
- **Depends on**: `introduce-slot-wiring` — several of these sites shrink there first, and
  their residual shape is this change's starting point.
- **Sequenced before**: `add-claim-return` (edits `TakeClaimAndWork`, the owner of FR6's
  `workClaimed`), `add-pipeline-routing` (edits `TakeCommand` / `ServeCommand`, the
  composition roots whose signatures sections 1–3 change) and `add-epic-decomposition` (edits
  `TakeOutcomeDispatch`, in the design's site table). This change lands first, and each of
  the three rebases its task text onto the resulting signatures, as they already did onto
  `introduce-take-order`. `add-parameter-count-gate` states its own dependency on this change.
