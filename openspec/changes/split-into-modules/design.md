# Design: split-into-modules

## Context

Driven by G1–G4 and FR1–FR11 of `split-into-modules`. Today the project is one
Gradle module: ~770 production Java classes, ~580 Spock specs, an 800-line
`build.gradle`, PIT wired into `check` and mutating the whole tree locally.
`domain` is already clean (its only `app` / `adapter` references are javadoc
`{@link}`s). `app` does double duty — use cases plus composition root — and more
than a quarter of its files import adapters. The tracker port is already
almost-SPI (interface + SPI factory + discriminator + registry); the sandbox
port carries a capability-passport negotiation with fail-closed reconciliation.
This change is purely structural: no port contract or runtime behavior changes
(FR9). It is the prerequisite for change B (plugin discovery, needs
`gnomish-plugin-api`) and change C (open adapter-binding registry, needs the
module tree).

## Goals / Non-Goals

**Goals:**
- A layered Gradle module tree with an enforced acyclic dependency direction.
- Per-module `check` / PIT scoping so a single-module change stops mutating the
  whole tree, with the quality-gates contract re-expressed per module (FR11).
- A thin, versioned `gnomish-plugin-api` contract artifact.
- Sandbox backend-specific code moved out of the port core, contract unchanged.

**Non-Goals:**
- Plugin discovery / `ServiceLoader` runtime (change B), check-port provider
  pattern and the `CheckClientFactory` SPI (change B), opening `AdapterBinding`
  (change C), resuming paused sandbox backends (NG5), classloader isolation
  (NG7). This design deliberately keeps the closed `AdapterBinding` enum and the
  flat classpath.

## Decisions

### D1 — Module tree (FR1, FR2)
Two-level Gradle tree:
```
:domain
:gitobjects        (internal shared: git-object utilities + DoNotMutate marker)
:gnomish-plugin-api
:application
:adapters:github   (vendor bundle: shared http core + tracker/github + check/github)
:adapters:git      (git-subprocess adapter)
:adapters:agent    (agent-CLI executor adapter)
:adapters:*        (the remaining small adapters kept coarse — see below)
:sandbox:core
:sandbox:docker
:bootstrap
:test-fixtures
build-logic/       (included build — convention plugins)
```
Dependency rule: `domain` and `:gitobjects` → nothing internal;
`gnomish-plugin-api` → `domain` only (exposed as an `api` dependency, D4);
`:sandbox:core` → `domain` / `:gitobjects`; `application` → `domain`,
`:gitobjects`, `gnomish-plugin-api`, `:sandbox:core`; each `:adapters:*` →
`gnomish-plugin-api` + `application` (+ `:sandbox:core` for execution-environment
bridges such as the shared check runners), never a sibling adapter's internals;
`:sandbox:docker` → `:sandbox:core`; `:bootstrap` → everything (the only
composition root); `:test-fixtures` → the production modules it builds fixtures
for, consumed only via `testImplementation`. **Alternative — flat `:adapters`
single module:** rejected; it keeps the per-module PIT-scoping win (G1) and the
sibling-isolation guarantee (M4) unreachable.

**Pass-2 granularity (resolves Q1).** A module is warranted only when it buys
(a) a PIT-scoping win for a large/churny package, or (b) a vendor/plugin
boundary. Concrete cuts: `:adapters:github` bundles its three packages —
`adapter/github` (shared http), `adapter/tracker/github`, `adapter/check/github`
— into one vendor module (the DEC-9/DEC-10 plugin exemplar; shared http stays an
internal package, not a separate Gradle module); `:adapters:git` (~90 files) and
`:adapters:agent` (~40) each get their own module. `tracker/inmemory` stays
in-tree as the reference/test-double (DEC-10). The small packages (`console`,
`pipeline`, `law`, `engine`, `workspace`, `findings`, `secrets`, `briefing`, and
the shared `adapter/check` runners) are **not** split per-module — the module
overhead exceeds the benefit; they stay coarse until a concrete reason appears.
**Alternative — one module per adapter package:** rejected; tiny modules pay
Gradle overhead for no scoping or boundary gain.

**Layer homes for the non-adapter packages.** `board`, `dashboard`,
`serveobservability` → `:application`; `status`, `usage` → `:application`
except their adapter-importing files, which follow the D3 rule into
`:bootstrap`; `gitobjects` + the root `DoNotMutate` marker → `:gitobjects`;
root config-properties types follow their consumers (`FactoryProperties`,
`ServeProperties` → `:application`; `SandboxProperties`, `BindingProperties`,
`ResourceLimits` → `:sandbox:core`); `FactoryApplication` → `:bootstrap`.

### D2 — Two passes, not a big bang (FR1, FR10)
Pass 1 (horizontal): carve the layers — `domain`, `:gitobjects`,
`gnomish-plugin-api`, `:sandbox:core`, `application`, `bootstrap`,
`test-fixtures`, `:sandbox:docker`, plus `build-logic`; adapters move as one
`:adapters` block first. Pass 2 (vertical): split `:adapters` per technology
into `:adapters:*`. Each pass keeps the whole suite green before the next
starts. **Alternative — single big-bang move:** rejected; unreviewable diff and
no green checkpoint to bisect against.

### D3 — Bootstrap owns all wiring (FR3, NFR-R1)
`app` splits into `application` (use cases + ports, adapter-free) and `bootstrap`
(`@SpringBootApplication`, `main()`, all `@Configuration`). The split rule is by
import kind, not a fixed file list: `app` files importing adapter
*implementations* move into `bootstrap`; files consuming only ports — including
the execution-environment use-case files (`RunAssembler`, `ManualRunRunner`,
`SandboxModeSelector`, …), which after D11 import the port from `:sandbox:core`
— stay in `application`. Adapters expose factories / `@Configuration` but
perform no cross-module component scanning; `bootstrap` is the single scan root.
This preserves the flat classpath and centralizes wiring so no new runtime
failure mode appears. **Alternative — leave `app` intact:** rejected; it is the
exact node that mixes wiring with logic (the change's premise).

### D4 — `gnomish-plugin-api` surface, spike-derived (FR4, FR5, resolves Q3)
The spike (github adapter imports minus its own packages) gives the transitive
contract closure. The module holds only:
- **Ports** — `port.tracker.*` (`Tracker` + its ~16 DTO/result/exception types),
  `port.secrets.SecretsProvider`, `domain.engine.port.ExternalCheckClient`,
  `domain.engine.port.Workspace`.
- **SPI factory + validator interfaces** — `TrackerAdapterFactory` and the
  relocated `TrackerSubsectionValidator`. A check-side SPI factory
  (`CheckClientFactory`) does **not** exist in the codebase and is deliberately
  not created here: change B introduces it into the api together with its
  `provider()` discriminator; until then `GithubCheckClientFactory` remains a
  concrete record wired by `bootstrap`. Creating it now would be a port-contract
  change this change forbids (FR9, NG2).

Domain **value types** referenced by those ports (`Finding`, `PollStatus`,
`VerifyCheck`, `TrackerConfig`, `ConfigError`) stay in `:domain`; the api
exposes them through an `api` (transitive) dependency on `:domain`, so a third
party compiles against one declared dependency (UX3). The exposed domain types
are thereby part of the semver surface (FR5, D10). The `DoNotMutate` marker
stays in the internal shared `:gitobjects` module, **not** the public api.
Implementations, orchestrators, and any `application` / `bootstrap` internal
stay out (verified by M3).

The spike also found five adapter→sibling-adapter leaks in the github code; each
resolves during the extraction, giving M4:

| Leaked type | Pulled by | Resolution |
|---|---|---|
| `TrackerSubsectionValidator` | `GithubTrackerSubsectionValidator` | it is an SPI validator → move into the api |
| `ExternalCheckPinContributor` | `GithubCheckClientFactory` | first-party check surface → `application` (drawn into the api by change B) |
| `EnvFileSecretsProvider` | github factories | inject the `SecretsProvider` port as a method arg (DEC-11) → leak gone |
| `AttemptCommitWorkspace` | `GithubCheckExternalClient` | depend on the `Workspace` port, not the impl → leak gone |
| `FindingsSanitizer` | `GithubWorkflowJobsFetcher` | plain util → shared util/application, **not** public api |

**Alternative — publish `application` as the api:** rejected; it exposes
internals and defeats FR5's free-to-change guarantee.

### D5 — Boundary enforcement: Gradle deps + dependency-analysis + ArchUnit (FR2, UX2, M3, M4)
Gradle project dependencies already make a sibling adapter's internals
unreachable (they are not on the compile classpath). The dependency-analysis
plugin flags misdeclared / unused edges. ArchUnit — deferred in ADR 0001 to "the
first ports/adapters change", which is this one — adds package-level rules within
a module (e.g. `application..` must not depend on `..adapter..`). A violation
fails `check` with a named rule. **Alternative — convention only:** rejected;
UX2 requires a build failure, not a review comment.

### D6 — PIT scoped per module, quality-gates re-expressed (FR11, NFR-P1, G1, M1)
Each module runs its own `pitest` with `targetClasses` bound to that module's
Java packages (Java production only, per testing.md — never Groovy test
bytecode), wired into that module's `check`. Root `./gradlew check` therefore
aggregates every module's PIT run — the quality-gates "single command" contract
holds — while `:module:check` mutates only that module. The scoped-target
property keeps working, narrowing within a module; when absent, each module
mutates its full module tree, so the union equals the old whole-tree run. CI
maps changed classes to their owning modules and runs only those modules'
mutation gates (FR11). No single whole-tree PIT task remains; an opt-in
aggregate task remains for one-shot full reports. The `quality-gates` capability
is updated by a MODIFIED delta, not silently. **Alternative — keep global PIT
with incremental analysis:** rejected in DEC-1 as a stop-gap; the structural
split is the durable fix.

### D7 — Sandbox split: core vs backends (FR8, NG4, NG5, Q4)
`:sandbox:core` keeps the `TaskExecutionEnvironment` port, `CapabilityPassport`
negotiation, reconciliation, and `IsolationLevel` / `AdapterBinding` (a closed
enum — opening it is change C). `:sandbox:docker` takes the docker backend.
Today that backend shells out to the `docker` CLI (per ADR 0001), so no heavy
production SDK exists to move — the split buys the boundary that keeps future
backend SDKs (colima / gha / cloud) out of the core *by construction*, plus a
PIT scope for the large backend package. Only the backends that have real code
today are extracted; `:sandbox:colima` / `-gha` / `-cloud` directories are
**not** scaffolded now — they are created by their resumed changes as the first
discovered backends (resolves Q4). The port contract is byte-for-byte unchanged.

### D8 — Shared fixtures as a standalone `:test-fixtures` module (FR7)
Common Spock fixtures move to a standalone `:test-fixtures` module consumed via
`testImplementation`, rather than Gradle `java-test-fixtures` (which attaches
fixtures to one producing module). Fixtures are shared across many modules, so a
standalone module fits better. **Alternative — `java-test-fixtures` per module:**
rejected for cross-module sharing; would force fixtures to live under one owner.

### D9 — Convention plugins in `build-logic` (FR6, M2)
`build-logic` is an included build providing: `java-conventions` (toolchain,
Spotless, Error Prone + NullAway, JaCoCo), `test-conventions` (Spock, PIT
per-module), `library-conventions`, and `published-api-conventions` for
`gnomish-plugin-api`. The shared `gradle/libs.versions.toml` version catalog stays
and is used by every module. Each module build file becomes thin (within the
file-size cap).

### D10 — Publish the api now, japicmp report-only now, gate later (FR5, resolves Q2)
`published-api-conventions` applies `maven-publish` and a real semver to
`gnomish-plugin-api` in this change (today it is `0.1.0-SNAPSHOT`; no publishing
or compat tooling exists yet, and ADR 0001 is silent on it). japicmp runs in
**report-only** (non-failing) mode now — over the api artifact *and* the
`:domain` types it exposes transitively, since those are part of the semver
surface (D4) — to capture a baseline while the surface is still moving during
the split; the failing gate is turned on later, in change B, where the api is
first consumed from outside. **Alternative — a hard gate now:** rejected; it
would fight every type relocation while the surface is still settling (D4).
**Alternative — no japicmp until B:** rejected; a report-only baseline is cheap
and makes the eventual gate a one-line flip.

### D11 — `:sandbox:core` is a port-layer module (FR2, FR3, FR8)
The execution-environment port lives under `adapter/environment` today and is
consumed by nine `app` use-case files. Treating those files as
"adapter-importing" would sink use-case logic (`RunAssembler`,
`ManualRunRunner`, …) into `bootstrap`. Instead `:sandbox:core` is placed as a
first-party port-layer module (like `gnomish-plugin-api`, but internal):
`application` depends on it, backends implement it, and it is extracted
**before** the `app` split so the edge exists when `application` is carved.
**Alternative — move the port into `gnomish-plugin-api`:** rejected; it widens
the third-party surface before changes B/C define the discovery and binding
story. **Alternative — move the nine files into `bootstrap`:** rejected; they
are use cases, and `bootstrap` would regrow into a second `app`.

## Risks / Trade-offs

- **Large mechanical move breaks imports / conflicts** → two-pass, module-by-
  module, suite green at every checkpoint (D2); revert-branch rollback since the
  change is structural with no data migration.
- **`gnomish-plugin-api` surface wrong (too wide/narrow)** → spike-seeded and
  conservative (D4); widen on demand; japicmp gate deferred to Q2 once the
  surface settles.
- **Per-module PIT misses cross-module behavior** → `targetClasses` is Java
  production per module; integration behavior is covered by `bootstrap` and the
  E2E layer; justify any <100% module per testing.md.
- **Hidden `app ↔ adapter` cycles surface during the split** → `bootstrap`
  absorbs the composition root and ports invert the direction, breaking cycles
  by construction (D3).
- **Spring component-scan leaking across modules** → `bootstrap` is the only scan
  root; adapters export explicit `@Configuration` / factories (D3).
- **Javadoc `{@link}`s crossing new module boundaries break the javadoc build**
  (e.g. `domain` → `app` / `adapter` links) → rewrite as plain-text references
  during the move; boundary rules govern real imports only.

## Migration Plan

1. Stand up `build-logic` convention plugins; keep the single module compiling
   through them (no move yet).
2. Extract `:domain` (already clean), `:gitobjects`, and `:gnomish-plugin-api`
   (spike surface).
3. Extract `:sandbox:core` (port layer, D11); backend classes stay put for now.
4. Split `app` → `:application` + `:bootstrap` by the D3 import rule; suite
   green.
5. Move adapters into a `:adapters` block; add dependency-analysis + ArchUnit
   boundary rules; resolve the five leaks; suite green.
6. Extract `:sandbox:docker`; extract `:test-fixtures`.
7. Turn on per-module PIT wired into each module's `check`; re-express the
   quality-gates contract (root aggregation, per-module property, CI module
   scoping); drop the whole-tree PIT task.
8. Pass-1 verification: full suite, file-size caps, wall-time baseline.
9. Pass 2: split `:adapters` into `:adapters:github` (vendor bundle),
   `:adapters:git`, and `:adapters:agent`; keep the small adapters coarse (D1).

Rollback: revert the branch — the change is structural, no runtime state or data
is migrated.

## Open Questions

All four questions from the proposal are now resolved:
- **Q1** — Resolved by D1: vendor/technology seams (`:adapters:github` bundle,
  `:adapters:git`, `:adapters:agent`); small adapters stay coarse.
- **Q2** — Resolved by D10: publish + japicmp report-only now, failing gate in
  change B.
- **Q3** — Resolved by D4: concrete api surface + the five-leak resolution table;
  the check SPI factory is deferred to change B; `DoNotMutate` stays internal.
- **Q4** — Resolved by D7: paused sandbox backend directories are created by
  their resumed changes, not scaffolded now.

Residual (not blocking this change): the exact api version at which change B flips
the japicmp gate from report-only to failing.
