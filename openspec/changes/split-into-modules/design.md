# Design: split-into-modules

## Context

Driven by G1–G6 and FR1–FR12 of `split-into-modules`. Today the project is one
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
`serveobservability`, `status` and `usage` → `:application` (their four
adapter-importing files are inverted per D12 rather than sunk into
`:bootstrap`); `gitobjects` + the root `DoNotMutate` marker → `:gitobjects`;
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

**Sequencing correction (task 4.1).** `:bootstrap` is extracted *after* the
adapters move (task 5.1), not before. While the adapters still live in the root
project, a separate `:bootstrap` module would have to depend on `project(':')` —
an inverted edge that task 5.1 would immediately re-point, and that would drag
`bootJar` and the E2E specs across module boundaries twice. The root project is
already the composition root, so the order becomes 4.1–4.6 → 5.1 → 4.7–4.9:
carve `:application`, move the adapters out, and whatever remains in the root is
`:bootstrap` by construction.

### D3 — Bootstrap owns all wiring (FR3, NFR-R1)
`app` splits into `application` (use cases + ports, adapter-free) and `bootstrap`
(`@SpringBootApplication`, `main()`, all `@Configuration`). The split rule is by
**role**: composition — `main()`, `@Configuration`, and the assembly/factory
classes whose job is to instantiate and connect adapters — goes to `bootstrap`;
use-case logic stays in `application`, with its adapter references inverted per
D12. Adapters expose factories / `@Configuration` but perform no cross-module
component scanning; `bootstrap` is the single scan root. This preserves the flat
classpath and centralizes wiring so no new runtime failure mode appears.
**Alternative — leave `app` intact:** rejected; it is the exact node that mixes
wiring with logic (the change's premise).

**Correction (task 4.1, measured against the tree).** D3 originally read the
rule as *by import kind* — "any `adapter.*` import → `bootstrap`" — and asserted
that `RunAssembler`, `ManualRunRunner` and `SandboxModeSelector` consume only the
`:sandbox:core` port and so stay in `application`. Both halves are wrong against
the current code. All three construct concrete adapters
(`adapter.check.*` runners, `adapter.console.DialogConsole`,
`adapter.environment.ContainerEnvironments`, `adapter.law.PipelineLawReader`),
and applying the import-kind rule literally sends 57 files to `bootstrap` — then
11 more transitively, because `application` files import them (`TakeSlotRunner` →
`ManualRunAssembly`, `ServeCommand` → `SlotLedger`, `TakeFinishReport` →
`TerminalWriteRetry`, …), which `application` may not do. The fixpoint is 70
`bootstrap` / 257 `application`, and it drags genuine use cases
(`TakeEngineExecution`, `TakeReconcile`, `GitModeRunner`) into the composition
root — exactly the "`bootstrap` regrows into a second `app`" outcome D11
rejects. The rule is therefore restated as *by role* above, and the import
problem is solved by inverting the dependencies (D12) rather than by relocating
their holders.

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

**Correction (task 2.3, found during extraction).** `ExternalCheckClient` and
`Workspace` cannot move into the api: `:domain`'s own engine consumes both
(`EnginePorts`, `Engine`, `ExternalPolling`, `VerifyOrchestrator`, and four
`domain.engine.port` interfaces type their signatures in `Workspace`), so
relocating them would make `:domain` depend on `gnomish-plugin-api` and close
exactly the cycle FR2 forbids. They stay in `:domain` and reach a third party
through the same transitive `api` edge as the value types — the api module holds
the tracker ports, `SecretsProvider`, `TrackerAdapterFactory` and
`TrackerSubsectionValidator`. UX3's "one declared dependency" is unaffected.

**Correction (task 2.1).** D1's layer-home note sends the root `DoNotMutate`
marker to `:gitobjects`, but four `:domain` types carry it and FR2 forbids
`:domain` depending on anything internal. The marker therefore travels with
`:domain`, which every upper module already depends on; `:gitobjects` keeps the
self-contained copy it already had (D19 of add-sandbox-core). No module gains a
dependency, and the marker is still absent from the api module itself. Because
PIT matches the annotation by *simple* name, both copies keep working.

**Relocation of `TrackerSubsectionValidator` (task 2.3).** It moves package as
well as module — `adapter.pipeline` → `app`, next to the `TrackerAdapterFactory`
SPI. Leaving an `adapter.*` package inside the published contract would misread
to a third party and would trip D5's "`application..` must not depend on
`..adapter..`" rule from `app`'s own command classes, which consume it.

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

**Sequencing correction (task 2.1).** `:test-fixtures` is extracted with
`:domain`, not in its own later pass: the shared engine fakes and the
port-contract suites live in the domain test tree, so carving out `:domain`
strands every other module's specs the moment it happens. Task 7 then only has
the remaining fixtures to sweep.

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

**Package relocation (task 3.1, implied by D5).** The extracted types move out
of `com.github.oinsio.gnomish.adapter.environment` into
`com.github.oinsio.gnomish.sandbox`. A port-layer module `:application` depends
on cannot live in an `adapter.*` package: D5's ArchUnit rule ("`application..`
must not depend on `..adapter..`") would fail task 4.6 on exactly the nine
use-case files D3 keeps in `application`. Same reasoning as D4's
`TrackerSubsectionValidator` relocation; the churn is import lines only. The
backends keep the `adapter.environment` package until task 6.1 carves
`:sandbox:docker`.

### D12 — Invert the use cases' adapter dependencies (FR3, FR12, G6)
D3's correction leaves 70 files bound for `bootstrap`. Rather than accept that,
each adapter reference held by a use case is inverted, in two buckets measured
against the tree:

**(a) Relocation — 29 types, no signature change.** A large share of the
"adapter" references are not adapters at all: port interfaces (`ConsoleIO`,
`ActivityTracker`, `StatusRenderer`, `AgentProgressListener`,
`RoundEnvironmentSource`, `JudgeEnvironmentSource`, `CheckEnvironmentSource`,
`ExternalCheckPinContributor`, `TaskSalvage`, `BranchLocation`,
`BranchStateResult`, `UsageHistoryResult`), value types (`UsageTotals`,
`UsageRow`, `TaskListRow`, `DeliveredBranchState`, `AttemptCommitRef`,
`AgentProgressEvent`, `Segment`), pure utilities (`TaskIdSanitizer`,
`TaskWorktreePath`, `SegmentPlanner`, `ChildEnvAllowlist`) and adapter-layer
exceptions. They are misfiled under `adapter.*`; moving them to `application`
(or `:sandbox:core` for the sandbox pair) is the same treatment D4 gave
`FindingsSanitizer` and `TrackerSubsectionValidator`. Measured effect: 70 → 59.

**(b) Port interfaces — the genuine adapters.** `SystemClock` / `ThreadSleeper`
are already implementations of the `Clock` / `Sleeper` ports that exist in
`:domain`; replacing their direct construction with injection of the port costs
nothing and takes 59 → 46. The residual 46 are held by real collaborators — the
git subprocess surface (`GitProcessRunner`, `GitTaskRepository`,
`GitAttemptPersistence`, the worktree/branch helpers), the console
(`DialogConsole`, `SystemConsoleIO`), pipeline loading (`PipelineLoader`,
`PipelineLawReader`), `DirectoryWorkspace`, the check runners, and
`ContainerEnvironments`. Each gets an `application`-owned port sized to the
capability the use case actually needs, bound in `bootstrap`.

*Rationale:* neither change B nor change C touches this surface — B's NG4 keeps
secrets/observability/workspace as module boundaries only and it reaches just
`ExternalCheckPinContributor`; C migrates `SandboxModeSelector`'s enum identity
but not its `ContainerEnvironments` probe. Left undone, the 46 files stay in the
composition root indefinitely, and FR2's ArchUnit rule would be enforcing a
boundary drawn around the wrong set of files.

*Cost, accepted deliberately:* 151 of 461 spec files reference the bucket-(b)
adapters and are edited at their construction sites. FR9 and M5 were rewritten
to permit exactly that and nothing more — no scenario, `given/when/then` or
assertion changes — so the behavior-preservation guarantee still has teeth.

**Alternative — mechanical rule + closure (70/257), document the deviation:**
rejected; it makes `bootstrap` a second `app` and leaves G2/G6 unmet.
**Alternative — defer bucket (b) to a follow-up change:** rejected; the boundary
rules land in this change, so shipping them around a knowingly wrong file set
would bake the wrong layering into the enforced gate.

### D13 — Closing the coverage gap per-module PIT exposes (FR11, NFR-P1, M5)
D6 makes a property visible that the whole-tree run hid: a class is now gated
only by its own module's specs. Measured at task 8.1, the gap concentrates in
`:application` (and, before its closure there, `:adapters`), because tasks
4.5/5.1 partitioned specs by what *compiles* — "stays with the composition root
if it imports an adapter or reaches a fixture that does" — which answers where
a spec MAY live, not whose coverage it carries. Three distinct causes get three
distinct treatments, decided per class, never as a blanket:

**(a) Spec ownership.** A spec whose subject is a module's own class and which
needs neither the composition root nor a real daemon moves to that module —
including specs that drive a use case through the in-memory reference adapter,
which is a test fake by design (unit specs of the application layer, not
integration specs of `bootstrap`). File moves only; no spec body changes, so
M5 is untouched. A spec that assembles the real run through `ManualRunAssembly`
(which names `adapter.check` types on its fields — composition by D3's by-role
rule) is genuinely a composition-root integration spec and stays.

**(b) Arid wiring.** Factories, assemblies and seams whose uncovered mutations
are all delegation-shaped (a removed void hand-off, a nulled return of a
constructed collaborator). A unit test killing such a mutant asserts "method
calls method" — it duplicates the implementation, resists refactoring, and adds
no confidence. These classes are excluded per class with a written rationale —
the same documented-exception discipline testing.md already applies to `main()`
wiring, and the category Google's mutation infrastructure suppresses wholesale
as "arid nodes".

**(c) Decision-bearing orchestration.** The take/resume/serve chain classes
whose uncovered mutations negate real conditionals. These get port-fake unit
specs in `:application` — new files, outside M5's budget by construction. A
class whose branches turn out to be trivial guards over delegation may instead
carry a per-class integration-covered exemption naming the `:bootstrap` suite
that drives it, per the orchestration-testing distinction (mock-tests of simple
coordinators are change-detectors; complex orchestration deserves unit specs).

*Rationale:* the 100%-with-named-exemptions gate keeps its teeth only while
every exemption is individually reviewable; the closure work is in-change
because the gap is a direct product of this change's own re-scoping, and task
9.1's "full suite green" gate must not carry a knowingly red module.
**Alternative — cross-module mutation (`additionalMutableCodePaths` /
`crossModule`):** rejected; it re-couples the modules PIT-wise, produces
duplicate per-class results, and reintroduces the whole-tree run time D6 exists
to remove. **Alternative — lower `mutationThreshold` for `:application`:**
rejected; a threshold is a blanket, not a reviewable list, and it surrenders
the gate exactly where the most orchestration logic lives. **Alternative —
defer the orchestration specs to a follow-up change:** rejected by decision;
see the in-change rationale above.

## Risks / Trade-offs

- **Large mechanical move breaks imports / conflicts** → two-pass, module-by-
  module, suite green at every checkpoint (D2); revert-branch rollback since the
  change is structural with no data migration.
- **`gnomish-plugin-api` surface wrong (too wide/narrow)** → spike-seeded and
  conservative (D4); widen on demand; japicmp gate deferred to Q2 once the
  surface settles.
- **Per-module PIT misses cross-module behavior** → `targetClasses` is Java
  production per module; integration behavior is covered by `bootstrap` and the
  E2E layer. The loss this predicts materialized at task 8.1 and is closed by
  D13: spec-ownership moves, per-class arid-wiring exemptions, and port-fake
  unit specs for decision-bearing orchestration — never a lowered threshold.
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
4. Invert the use cases' adapter dependencies (D12) and carve `:application`;
   the composition classes stay in the root project, which is the composition
   root today. Suite green.
5. Move adapters into a `:adapters` block; add dependency-analysis + ArchUnit
   boundary rules; resolve the five leaks; suite green. Only then extract
   `:bootstrap` from what remains in the root project.
6. Extract `:sandbox:docker`; extract `:test-fixtures`.
7. Turn on per-module PIT wired into each module's `check`; re-express the
   quality-gates contract (root aggregation, per-module property, CI module
   scoping); drop the whole-tree PIT task. Close the coverage gap the
   re-scoping exposes (D13): spec-ownership moves, arid-wiring exemptions,
   port-fake unit specs for the orchestration chain.
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
