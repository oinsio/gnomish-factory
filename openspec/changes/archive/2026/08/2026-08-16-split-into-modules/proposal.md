## Why

The codebase has grown to roughly 770 production Java classes and 580 Spock
specs in a **single** Gradle module with an 800-line `build.gradle`. PIT is
wired into `check` and mutates the whole tree on every local run, so the build —
and especially mutation testing — is slow. `app` mixes use-case logic with the
composition root (more than a quarter of `app` files import adapters), so there
is no enforced boundary to scope work against. We treat the root cause
structurally: split the monolith into layered Gradle modules (explore decision
DEC-1). This is the enabling change for plugin discovery (change B) and an open
adapter-binding registry (change C), both of which need a stable module surface
first.

## What Changes

**ADDED**
- Layered Gradle modules by hexagonal layer: `domain`, `application`,
  `adapters:*`, `bootstrap` (DEC-2, DEC-3).
- `build-logic` convention plugins; each module's build file becomes thin,
  replacing the monolithic `build.gradle` (DEC-4).
- A thin, versioned `gnomish-plugin-api` module — the third-party contract
  surface only: port interfaces, the tracker SPI factory, `SecretsProvider`,
  SPI validators (DEC-6). The check SPI factory is introduced by change B.
- A shared test-fixtures module for common Spock fixtures (DEC-7).
- Sandbox module split: `:sandbox:core` (a first-party port-layer module) +
  per-backend adapter modules; backend-specific dependencies stay out of the
  core; the port contract is unchanged (DEC-24).
- Per-module `check`/PIT scoping so a change touching one module mutates only
  that module's classes.

**MODIFIED**
- `app` is split into `application` (use cases + ports) and `bootstrap`
  (composition root, Spring wiring, `main()`).
- Use cases that reach concrete adapters today are inverted onto ports so they
  can stay in `application`: the adapter types they consume are either relocated
  (ports and value types misfiled under `adapter.*`) or hidden behind a new port
  interface that `bootstrap` binds to the concrete adapter (DEC-2, D12).
- The `quality-gates` contract is re-expressed over the module tree: root
  `check` aggregates every module's `check` (each running that module's PIT);
  the scoped-target property and CI merge-base scoping operate per module.

**REMOVED**
- The single-module layout and monolithic `build.gradle`.

No port contract or runtime *behavior* changes — every existing capability spec
holds unchanged. Internal collaborator types do change where a use case is
inverted onto a port (FR12), so the Spock specs that construct those use cases
are edited at their construction sites only (FR9, M5).

## Capabilities

### New Capabilities
- `module-layering`: layered Gradle modules and the enforced acyclic dependency
  direction between hexagonal layers.
- `plugin-api-contract`: the thin, versioned `gnomish-plugin-api` artifact — its
  contract surface and semver boundary.
- `build-conventions`: `build-logic` convention plugins and per-module
  `check`/PIT scoping (the build-speed win).
- `sandbox-module-split`: `:sandbox:core` plus per-backend adapter modules, port
  contract unchanged.
- `test-fixtures-module`: the shared test-fixtures module.

### Modified Capabilities
- `quality-gates`: the single `check` command, the scoped mutation target, and
  the CI mutation scoping are re-expressed per module (FR11); thresholds and
  whole-tree coverage guarantees are unchanged. (All other behavior is
  preserved — enforced by FR9 / M5.)

## Goals

- **G1** — Scope `check`/PIT per module so mutation runs no longer mutate the
  whole production tree on every change.
- **G2** — Establish enforced layered module boundaries so `app` no longer mixes
  use-case logic with the composition root.
- **G3** — Produce a thin, versioned `gnomish-plugin-api` capturing the
  third-party contract surface, with no discovery or plugin runtime (that is
  change B).
- **G4** — Split sandbox adapters so backend-specific dependencies stay out of
  the core, with an unchanged port contract.
- **G5** — Preserve behavior: every existing capability spec holds, and every
  Spock spec still passes — edited only where an inverted use case's constructor
  arguments changed, never in its `given/when/then` assertions.
- **G6** — Leave `application` genuinely adapter-free, so `bootstrap` holds only
  composition (`main()`, `@Configuration`, assemblies) and not use-case logic.

## Non-Goals

- **NG1** — `ServiceLoader` discovery / plugin runtime (change B).
- **NG2** — Check-port provider pattern, the `CheckClientFactory` SPI,
  per-check providers, generic http-check (change B).
- **NG3** — Extracting GitHub into an external plugin (change B).
- **NG4** — Opening the `AdapterBinding` registry, sealed-enum → discovered
  (change C).
- **NG5** — Resuming the paused sandbox backends (colima / gha / cloud /
  hardening); their module directories may be scaffolded but the backends stay
  paused.
- **NG6** — ai-provider pluginization; secrets / observability as external
  plugins (later; only a module boundary now).
- **NG7** — Classloader isolation or a `plugins/` folder; the flat classpath
  stays (explore Q3).

## Users & Scenarios

- **U1** — A developer changes one adapter and runs mutation tests; only that
  module's production classes are mutated, not the whole tree.
- **U2** — A third-party integrator compiles against `gnomish-plugin-api` (its
  single declared dependency; the `domain` types the ports reference come with
  it transitively) and sees ports, SPI factories, and config types — never
  `application` or `bootstrap` internals.
- **U3** — A maintainer adds a new adapter; the build rejects any import of a
  sibling adapter's internals.
- **U4** — Any maintainer runs the full suite after the split and every existing
  spec passes with no source changes to the specs.

## Requirements

### Functional

- **FR1** — The build SHALL be split into layered Gradle modules: `domain`,
  `application`, `adapters:*`, `bootstrap`, plus the `gnomish-plugin-api`,
  `gitobjects`, `sandbox` and `test-fixtures` modules.
- **FR2** — The dependency direction SHALL be enforced acyclic: `domain` and
  `gitobjects` depend on nothing internal; `gnomish-plugin-api` depends only on
  `domain`; `:sandbox:core` depends only on `domain` / `gitobjects`;
  `application` depends only on `domain`, `gitobjects`, `gnomish-plugin-api`,
  and `:sandbox:core`; adapter modules depend on `gnomish-plugin-api` and
  `application` (plus `:sandbox:core` where they bridge to the execution
  environment) but never on a sibling adapter's internals; sandbox backend
  modules depend on `:sandbox:core`; no production module depends on
  `test-fixtures`; `bootstrap` is the only module that wires adapters together.
- **FR3** — `app` SHALL be split into `application` (use cases + ports) and
  `bootstrap` (composition root, Spring configuration, `main()`). The split
  SHALL be by *role*, not by the incidental import set: composition — `main()`,
  `@Configuration`, assemblies and factories whose job is to instantiate and
  connect adapters — goes to `bootstrap`; use-case logic stays in
  `application`, its adapter references inverted per FR12.
- **FR4** — A `gnomish-plugin-api` module SHALL contain exactly the third-party
  contract surface — port interfaces, the existing tracker SPI factory,
  `SecretsProvider`, SPI validators — and nothing from `application` /
  `bootstrap` internals. Domain value and config types referenced by these
  ports stay in `domain` and are exposed transitively (single declared
  dependency for a third party).
- **FR5** — `gnomish-plugin-api` SHALL be independently versioned by semver;
  the semver surface is the api module plus the `domain` types it exposes
  transitively; `application` internals and unexposed `domain` types may change
  without an api version bump.
- **FR6** — Common build configuration SHALL move to `build-logic` convention
  plugins; each module build file SHALL stay within the project file-size cap.
- **FR7** — Shared Spock test fixtures SHALL move to a dedicated test-fixtures
  module (or Gradle `java-test-fixtures`).
- **FR8** — The sandbox port SHALL split into `:sandbox:core` (port + shared
  capability-passport negotiation and reconciliation) and per-backend adapter
  modules; `:sandbox:core` SHALL carry no backend-specific dependencies (the
  docker backend is subprocess-CLI-based today; future backends' SDKs land in
  their own modules); the `TaskExecutionEnvironment` port contract is unchanged.
- **FR9** — The split SHALL be behavior-preserving: every existing capability
  spec holds unchanged and every Spock spec passes. Spec edits SHALL be confined
  to collaborator-construction sites forced by FR12 (constructor arguments, test
  doubles standing in for a newly introduced port); no spec's scenario names,
  `given/when/then` structure, or assertions SHALL change.
- **FR10** — Adapters SHALL be split vertically per technology into per-adapter
  modules (second pass); the shared `github` HTTP core stays a package internal
  to its own vendor module (tracker + check).
- **FR11** — The `quality-gates` contract SHALL be re-expressed over the module
  tree: root `./gradlew check` aggregates every module's `check` (each running
  that module's PIT); the scoped-target property narrows within a module; CI
  mutation scoping maps changed classes to their owning modules; the union of
  module scopes preserves whole-tree coverage.
- **FR12** — `application` SHALL contain no import of an adapter
  implementation. Where a use case reaches one today, the dependency SHALL be
  inverted by one of two means: (a) **relocation**, when the referenced type is
  a port interface or a pure value/utility type merely misfiled under
  `adapter.*` — it moves to its correct layer with no signature change; or
  (b) **a port interface** owned by `application` (or `domain` where the engine
  already consumes it), with the concrete adapter bound in `bootstrap`. The
  inverted seams SHALL be the smallest capability the use case actually needs,
  not a mirror of the adapter's full class surface.

### Non-Functional — Performance

- **NFR-P1** — After the split, running `check` / PIT for a change touching a
  single module SHALL mutate only that module's production classes, not the whole
  tree.
- **NFR-P2** — Full clean-build wall-time SHALL NOT regress versus the monolith
  (module-level parallelism offsets the multi-module overhead).

### Non-Functional — Reliability

- **NFR-R1** — The split SHALL introduce no new runtime failure mode: the flat
  classpath is preserved and all wiring is centralized in the single `bootstrap`
  composition root.

### Non-Functional — Security

- **NFR-S1** — Module boundaries SHALL prevent adapters from reaching secrets
  internals except through the `SecretsProvider` port exposed by
  `gnomish-plugin-api`; no credentials appear in any module metadata.

## Operator Experience Criteria

- **UX1** — A developer can run mutation tests for only the module they touched
  via a documented Gradle invocation.
- **UX2** — A module dependency-direction violation fails the build with a clear,
  actionable message (dependency-analysis / ArchUnit).
- **UX3** — The `gnomish-plugin-api` surface is discoverable as a single declared
  dependency a third party compiles against (the artifact plus the `domain`
  types it transitively exposes).

## Success Metrics

- **M1** — PIT scope for a single-adapter change drops from the whole production
  tree to that module's classes only.
- **M2** — The monolithic `build.gradle` is replaced by convention plugins plus
  thin per-module build files, each within the file-size cap.
- **M3** — The `gnomish-plugin-api` artifact has zero imports from `application`
  or `bootstrap` internals (verified by dependency-analysis).
- **M4** — Zero adapter → sibling-adapter-internal imports remain.
- **M5** — All existing specs (575 at the time of writing) pass; the diff over
  `src/test` touches only construction sites (imports, constructor arguments,
  test doubles) — zero changes to scenario names, `given/when/then` blocks, or
  assertions, verifiable by reviewing the spec diff.
- **M6** — Zero `application` → `..adapter..` imports remain, enforced by the
  ArchUnit rule of FR2/UX2 rather than by convention.

## Open Questions

All four are resolved in `design.md` (see the noted decisions):
- **Q1** — Vertical split depth → vendor/technology seams: `:adapters:github`
  bundle, `:adapters:git`, `:adapters:agent`; small adapters stay coarse (D1).
- **Q2** — japicmp gate → publish + report-only now, failing gate in change B
  (D10).
- **Q3** — api surface → spike done: tracker/secrets/check ports + SPI
  validators; the check SPI *factory* is deferred to change B; domain value
  types stay in `:domain` (exposed transitively); five adapter leaks resolved;
  `DoNotMutate` stays internal (D4).
- **Q4** — Paused sandbox backend directories are created by their resumed
  changes, not scaffolded now (D7).

Residual (not blocking): the api version at which change B flips japicmp from
report-only to a failing gate.

## Impact

- **Build** — `settings.gradle` gains the module tree; the monolithic
  `build.gradle` is replaced by `build-logic` convention plugins and thin
  per-module build files; CI mutation scoping is re-pointed at owning modules
  (FR11).
- **Source moves** — `board`, `dashboard`, `serveobservability` and the
  port-only parts of `status` / `usage` land in `:application` (their
  adapter-importing files go to `:bootstrap`); `gitobjects` plus the root
  `DoNotMutate` marker form the internal shared `:gitobjects` module; root
  `@ConfigurationProperties` types follow their consumers (`FactoryProperties`,
  `ServeProperties` → `:application`; `SandboxProperties`, `BindingProperties`,
  `ResourceLimits` → `:sandbox:core`); `FactoryApplication` → `:bootstrap`;
  sandbox packages under `adapter/environment` split into `:sandbox:core` and
  backend modules; javadoc `{@link}` references crossing new module boundaries
  are rewritten as plain text.
- **Port inversion (FR12)** — ports and value types misfiled under `adapter.*`
  (`ConsoleIO`, `ActivityTracker`, `AgentProgressListener`,
  `ExternalCheckPinContributor`, `BranchLocation`, `UsageTotals`,
  `TaskIdSanitizer`, `Segment`/`SegmentPlanner`, the adapter-layer exceptions,
  …) relocate to `application` / `domain`; the git-subprocess, console,
  pipeline-loading, workspace and check-runner collaborators of the remaining
  use cases move behind new `application`-owned ports bound in `bootstrap`.
  Spock specs for those use cases are edited at their construction sites only.
- **New artifacts** — `build-logic`, `gnomish-plugin-api`, `:gitobjects`, a
  test-fixtures module, `:sandbox:core`, and the extracted sandbox backend
  module(s).
- **Dependencies** — no new runtime dependencies; only Gradle convention-plugin
  infrastructure.
- **Downstream** — unblocks change B (needs `gnomish-plugin-api`; introduces the
  check SPI factory there) and change C (needs the module tree).
