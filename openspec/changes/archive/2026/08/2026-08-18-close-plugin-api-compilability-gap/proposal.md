# Proposal: close-plugin-api-compilability-gap

## Why

add-plugin-architecture proved G4 ("built-in load path ≡ third-party path") for
the *loading* mechanism only: `adapters/github` still declares
`implementation project(':application')` for two types a genuine third-party
bundle cannot reach — `AttemptCommitWorkspace` (the check SPI accepts the api's
`Workspace` but the protocol requires a downcast to this hidden type to learn
the attempt-commit sha, so a third-party external check cannot know which commit
it verifies) and `FindingsSanitizer` (the log strip/cap security invariant only
first-party code can apply). Recorded as the G4 residual in
add-plugin-architecture's design.md; this change resolves it before the parent
change archives and the record stops being read.

## What Changes

- Publish a minimal `AttemptCommitWorkspace` **interface** (`attemptCommitSha()`)
  in `gnomish-plugin-api`; the record in `:application` implements it; the
  github check client's downcast retargets to the api type.
- Move `FindingsSanitizer` (dependency-free) into `gnomish-plugin-api` as a
  contract utility every check plugin can (and should) apply before findings
  reach the tracker.
- Drop `implementation project(':application')` from `adapters/github`, prune
  its `layering.allowedProjects` to `:domain` + `:gnomish-plugin-api`, and take
  the `:application` record out of its specs (through the shared
  `AttemptCommitWorkspaces` fixture) — the vendor bundle then compiles against
  the published contract alone on every configuration, enforced by the build.
- Extend `gnomish-plugin-api:sample` with a `CheckClientFactory` implementation
  that reads the sha through the new api type, so "a third party can build an
  external check against the api alone" is a compile-time fact, not prose.
- Rename the `:application` workspace record to
  `RecordedAttemptCommitWorkspace` so the plugin-facing api type claims the
  undecorated name (design D2); the record gains the `implements` clause and is
  not api surface.
- Re-baseline japicmp for the grown api surface via
  `updateApiCompatibilityBaseline` (additive — no **BREAKING** change to
  existing api types; the renamed record is `:application`-internal and outside
  the compared surface).

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `plugin-api-contract`: the contract surface grows by the
  `AttemptCommitWorkspace` interface and the `FindingsSanitizer` utility; the
  "third party compiles against a single declared dependency" requirement is
  strengthened to cover authoring an external check (workspace sha access and
  findings sanitization included), and the first-party github bundle SHALL
  itself satisfy it — no `:application` dependency.

## Impact

- **Modules**: `gnomish-plugin-api` (+2 types, japicmp re-baseline),
  `application` (record renamed and implements the new interface;
  `FindingsSanitizer` leaves), `adapters/github` (`:application` edge removed,
  imports retargeted, `layering.allowedProjects` pruned to `:domain` +
  `:gnomish-plugin-api` — `:gitobjects` and `:sandbox:core` reached it only
  through `:application`), `test-fixtures` (`AttemptCommitWorkspaces` narrowed
  to the api return type so github's specs name no `:application` type),
  `gnomish-plugin-api:sample` (new `CheckClientFactory` + service entry),
  `bootstrap` (only if wiring names the renamed/moved types).
- **Callers of `FindingsSanitizer`** across `application` and adapters keep
  their imports: the move preserves package and FQN (design D3), so only the
  owning module changes.
- **Consumers of the renamed record** across `:adapters`, `:adapters:git`,
  `:adapters:agent`, `:bootstrap` and their tests are a compiler-driven,
  behavior-preserving sweep.
- **Tests**: the two github check specs build their workspace through the
  shared fixture instead of the `:application` record, keeping
  dependency-analysis (global `onAny fail`) from demanding a
  `testImplementation project(':application')`; other existing specs
  move/retarget with their types;
  `GithubPluginPackagingSpec` export list unchanged (SPI factories only); the
  sample module has no test source set by design (compilation IS the
  assertion), so the check path is enforced there by compiling against its one
  declared dependency plus `layering.allowedProjects`; PIT scope follows the
  moved classes.
- **api compatibility**: additions only; `compat-baseline/` jars regenerate as
  the deliberate reviewable act (per add-plugin-architecture task 7.3's
  deviation).

## Traceability

- **G1** — a third-party external-check plugin can be *compiled and functional*
  against `gnomish-plugin-api` alone (closes the G4 residual of
  add-plugin-architecture).
- **FR1** — `gnomish-plugin-api` SHALL expose an `AttemptCommitWorkspace`
  interface with `attemptCommitSha()`; the engine's workspace record SHALL
  implement it; check adapters SHALL downcast to the api type only.
- **FR2** — `FindingsSanitizer` SHALL live in `gnomish-plugin-api`; all callers
  SHALL use it from there.
- **FR3** — `adapters/github` SHALL declare no dependency on `:application` on
  any configuration, production or test; its `layering.allowedProjects` SHALL
  name only `:domain` and `:gnomish-plugin-api`.
- **FR4** — `gnomish-plugin-api:sample` SHALL implement `CheckClientFactory`
  (registered via `META-INF/services`), reading the attempt-commit sha through
  the api interface, with `gnomish-plugin-api` as its only declared dependency;
  the enforcement is the module's compilation plus its `layering` declaration,
  not a spec.
- **FR5** — japicmp SHALL pass against a regenerated baseline; the change SHALL
  be additive for all pre-existing api types.
- **NFR-S1** — the sanitization invariant (strip + cap before tracker
  publication) SHALL remain enforced at the first-party call sites after the
  move.
- **M1** — `./gradlew check` green with the `:application` edge absent from
  `adapters/github`.
- **NG1** — no capability/adapter framework (`getAdapter(Class<T>)`-style) on
  `Workspace`; a plain interface suffices at two consumers.
- **NG2** — no separate `gnomish-plugin-support` utility module for one
  dependency-free class.
