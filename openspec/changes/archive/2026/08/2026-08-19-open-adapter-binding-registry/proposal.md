## Why

Today `AdapterBinding` is a sealed enum whose constants (`HOST`, `CONTAINER`)
and their capability passports are edited in `:sandbox:core`: every new sandbox
backend — Colima-VM, GitHub Actions, cloud, hardened container — would have to
reach back into the core enum to add a constant. `split-into-modules` (change A)
already carves the sandbox into `:sandbox:core` + per-backend adapter modules and
deliberately keeps `AdapterBinding` sealed (out of A's scope). This change opens
it: turn the sealed enum into a *discovered* registry so each backend module
contributes its own binding + passport without touching core, unblocking the
paused sandbox backend changes to land as the first discovered backends
(DEC-26). Discovery is **first-party only** — a self-declared passport from an
untrusted jar is a trust hole, and the sandbox is a trust boundary (DEC-25), so
this is a different mechanism from the third-party plugin model of
`add-plugin-architecture` (change B). Depends on A (needs the `:sandbox:core` /
`:sandbox:docker` split).

## What Changes

**MODIFIED**
- `AdapterBinding` changes from a sealed enum with core-edited constants to a
  **discovered registry** of bindings; a binding is contributed by its own
  sandbox module through an SPI, not by editing a core enum (DEC-26). This
  reverses `split-into-modules`' "AdapterBinding stays sealed" requirement, which
  scoped the opening to this change.
- `HOST` migrates to a binding contributed from `:sandbox:core`; `CONTAINER`
  migrates to one contributed from `:sandbox:docker` — both keep their config
  names (`host`, `container`) and passports byte-for-byte.
- `BindingResolver`, `Segment`, `SegmentPlanner`, and `SandboxModeSelector`
  move from enum identity (`== AdapterBinding.CONTAINER`, the planner's
  reference `!=` at the segment boundary) to registry-based lookup and
  config-name identity; observable behavior is unchanged. `SandboxReconciler`
  is verify-only: it already reads only the passport and does not change.

**ADDED**
- A first-party binding-contribution SPI in `:sandbox:core`, exposing a binding's
  config name and fixed `CapabilityPassport` **without instantiating a live
  backend adapter**, so planning-time reconciliation and segment planning stay
  adapter-instance-free (the property `AdapterBinding` guarantees today).
- Fail-fast registry resolution: an unknown configured binding name, or two
  providers claiming one config name, fails at startup with the valid discovered
  options and the way to fix the configuration named — never a silent fallback.
- Passport ratification: `:sandbox:core` holds a trust table of expected
  passports per first-party binding id; a discovered provider whose id is not in
  the table, or whose self-declared passport differs from the expected one, is
  rejected fail-fast at startup — the passport becomes a cross-checked
  declaration, never a trusted input.

**PRESERVED (explicit non-change)**
- The passport-reconciliation contract — operator binds, repo only tightens,
  fail-closed on any unmet need (FR14 of add-sandbox-core) — is unchanged
  (DEC-27); the container-by-default rule (D13) is preserved.

## Capabilities

### New Capabilities
- `adapter-binding-registry`: the discovered binding registry — the first-party
  contribution SPI, config-name + passport exposure without a live adapter,
  passport ratification against a core-owned trust table, fail-fast
  duplicate/unknown resolution, container-by-default preserved, and the
  first-party-only trust boundary that separates it from change B's third-party
  discovery.

### Modified Capabilities
- `sandbox-module-split`: change A's requirement that "`AdapterBinding` stays
  sealed" is reversed — it becomes a discovered registry. (Source spec lives in
  `split-into-modules`; this change depends on it.) The reconciliation semantics
  of `execution-environment` are explicitly *preserved*, not modified — asserted
  by a scenario in the new capability.

## Goals

- **G1** — Replace the sealed `AdapterBinding` enum with a discovered registry so
  a new sandbox backend contributes its binding + passport from its own module
  with no logic edits to `:sandbox:core` — only a one-line, reviewed
  trust-ratification entry (id → expected passport) in the core trust table.
- **G2** — Preserve the passport-reconciliation contract exactly (operator binds,
  repo only tightens, fail-closed on mismatch) — a behavior-preserving refactor
  (DEC-27).
- **G3** — Migrate `HOST` (into `:sandbox:core`) and `CONTAINER` (into
  `:sandbox:docker`) to the registry with no operator-visible config change.
- **G4** — Unblock the paused sandbox backend changes (hardening, colima-vm,
  gha-executor, cloud-executor) to land as the first discovered backends with no
  core edits beyond their trust-ratification entries (DEC-26). This change opens
  binding + passport contribution only; generalizing the execution path (mode
  dispatch, environment factory) is deferred to the first non-docker backend
  change (`add-sandbox-colima-vm`).

## Non-Goals

- **NG1** — Third-party / untrusted sandbox plugins: discovery is first-party
  only; a self-declared passport from an untrusted jar is a trust hole and the
  sandbox is a trust boundary (DEC-25). No third-party sandbox jar loading path.
- **NG2** — Reusing change B's third-party `ServiceLoader` plugin trust model for
  the sandbox port; introducing the sandbox port into the plugin model is
  deferred until after it stabilizes (DEC-25).
- **NG3** — Implementing the colima-vm / gha / cloud / hardening backends
  themselves — they resume as separate changes after C (DEC-26).
- **NG4** — Changing the `TaskExecutionEnvironment` port contract or the
  `CapabilityPassport` shape / four dimensions (DEC-24: contract unchanged).
- **NG5** — Adding new isolation levels or passport dimensions beyond
  `NONE` / `CONTAINER`; each new backend adds what it needs in its own change.

## Users & Scenarios

- **U1** — A sandbox-backend module author contributes a new binding (config name
  + passport) from their own module and registers its expected passport in the
  core trust table; `factory.bindings.default=<name>` then resolves it with no
  other core edit.
- **U2** — An operator with an existing `host` / `container` config, or none at
  all, runs unchanged: the container default and both passports behave exactly as
  before.
- **U3** — An operator names an unknown binding; startup fails fast with the valid
  *discovered* options listed (UX preserved, now dynamic).
- **U4** — A stage declares a need the bound backend's passport does not satisfy;
  reconciliation refuses fail-closed with the unmet need named, exactly as today.

## Requirements

### Functional

- **FR1** — `AdapterBinding` SHALL change from a sealed enum with core-edited
  constants to a discovered registry; a binding SHALL be contributed by its own
  sandbox module through a first-party SPI, never by editing a core enum.
- **FR2** — Each contributed binding SHALL expose its config name and its fixed
  `CapabilityPassport` **without instantiating a live backend adapter**, so
  planning-time reconciliation and segment planning stay adapter-instance-free.
- **FR3** — `HOST` SHALL be contributed from `:sandbox:core` and `CONTAINER` from
  `:sandbox:docker`; both SHALL keep their current config names (`host`,
  `container`) and passports.
- **FR4** — The container-by-default rule SHALL be preserved: an unset
  `factory.bindings.default` SHALL resolve to the `container` binding through the
  registry, never a silent host fallback (D13 of add-sandbox-core).
- **FR5** — Binding resolution SHALL be fail-fast: an unknown configured binding
  name SHALL fail at startup with the valid discovered options and the way to
  fix the configuration named (UX2 of add-sandbox-core preserved).
- **FR6** — The passport-reconciliation contract SHALL be unchanged: operator
  binds, repo only tightens, fail-closed on any unmet need (FR14 of
  add-sandbox-core) (DEC-27).
- **FR7** — Binding discovery SHALL be first-party only: the registry SHALL NOT
  load sandbox bindings through the third-party plugin trust model of
  `add-plugin-architecture`; a binding's passport is trusted because its provider
  is first-party (DEC-25).
- **FR8** — Two providers claiming the same config name SHALL fail fast at startup
  with a named error identifying the conflict and how to resolve it — never a
  silent pick.
- **FR9** — The registry migration (including the replacement of enum `==`
  identity in segment planning) SHALL be behavior-preserving: every behavioral
  assertion of the existing execution-environment specs SHALL pass unchanged;
  only construction sites naming the removed enum constants may be edited,
  mechanically, with no assertion touched.
- **FR10** — Passport ratification: `:sandbox:core` SHALL hold the expected
  `CapabilityPassport` per trusted binding id; a discovered provider whose
  declared passport differs from the expected one SHALL be rejected fail-fast at
  startup with the mismatch and the fix named — the provider's declaration is a
  cross-check, never the authority.

### Non-Functional — Reliability

- **NFR-R1** — Discovery SHALL be deterministic and fail-fast: an absent requested
  binding and a duplicate config name both SHALL fail with a clear named error at
  startup, never a silent fallback or a mid-task failure.

### Non-Functional — Security

- **NFR-S1** — The sandbox is a trust boundary: only first-party modules SHALL
  contribute bindings and passports, and an untrusted jar SHALL NOT be able to
  introduce a lying passport through this registry — discovery is gated by the
  core trust table (id + expected passport), and the mechanism is not wired to
  third-party discovery. The residual flat-classpath risk (a malicious jar
  shipping a provider under a trusted id with the expected passport) has no
  runtime defense post-SecurityManager and SHALL be closed at build time by
  classpath pinning (the dependency-verification change).

### Non-Functional — Observability

- **NFR-O1** — At startup the factory SHALL report the discovered bindings —
  config name, provider class, originating jar (code source), and passport
  summary — through the same provider-discovery report as the tracker/check
  ports, so an operator can see exactly what the classpath loaded before any
  stage runs.

## Operator Experience Criteria

- **UX1** — Selecting an unknown binding name fails at startup with a named,
  actionable error listing the discovered options (behavior preserved, options
  now dynamic).
- **UX2** — An existing `host` / `container` (or unset) config runs with no change
  — zero migration burden for operators.
- **UX3** — Discovered bindings are observable (logged / listable) so an operator
  knows a backend module is active before running a stage.

## Success Metrics

- **M1** — Adding a new binding requires no `:sandbox:core` logic edits — only
  its one-line trust-table entry; no enum constant carrying a binding's passport
  or behavior remains in core (grep + test).
- **M2** — All existing execution-environment and binding specs pass with no
  behavioral assertion changed; edits are confined to construction sites naming
  the removed enum constants (the enum's own `AdapterBindingSpec` is superseded
  by the registry specs).
- **M3** — Removing `:sandbox:docker` from the classpath removes the `container`
  binding cleanly: the container default then fails fast with a named error naming
  the discovered options, with no dangling reference (NFR-R1).
- **M4** — A stub first-party backend binding, staged through the discovery
  class-loader seam and ratified through an injected trust table, is selected
  end-to-end (bind → reconcile → plan) with no edit to the discovery or registry
  mechanism — the extension-point acceptance test.

## Open Questions

- **Q-MECH** — Discovery mechanism: JDK `ServiceLoader` (same JVM mechanism as B,
  different trust policy) vs a core-owned explicit registry of first-party
  providers? → resolve in `design.md` (FR1).
- **Q-TRUST** — On A's flat classpath, how is "first-party only" enforced vs
  merely documented — an allowlist of trusted binding ids in core, or a
  posture-only statement? → resolve in `design.md` (NFR-S1).
- **Q-IDENTITY** — Replacement for `AdapterBinding` value identity used in
  `Segment` / `SandboxModeSelector` (`== CONTAINER` / `== HOST`): compare by
  config name, by isolation level, or a registry-issued token? → resolve in
  `design.md` (FR9).
- **Q-DEFAULT-ABSENT** — When the `container` default binding's module is absent,
  is fail-fast the correct behavior (chosen) or should the default fall through?
  → resolve in `design.md` (FR4, M3).

## Impact

- **Types** — `AdapterBinding` in `:sandbox:core` changes from enum to a
  registry-backed type; `BindingResolver`, `Segment`, `SegmentPlanner`, and
  `SandboxModeSelector` migrate to registry lookup + config-name identity;
  `SandboxReconciler`, `CapabilityPassport`, and `IsolationLevel` are unchanged.
- **Modules** — a `HOST` binding provider in `:sandbox:core`, a `CONTAINER`
  binding provider in `:sandbox:docker`, the binding-contribution SPI + trust
  table in `:sandbox:core`, and the discovery pass (ServiceLoader + registry
  build) in `:bootstrap`, beside the tracker/check discoveries.
- **Config** — `factory.bindings.*` grammar is unchanged; the set of valid binding
  names becomes dynamic (discovered).
- **Downstream** — unblocks `add-sandbox-hardening` / `-colima-vm` /
  `-gha-executor` / `-cloud-executor` to resume as discovered backends (DEC-26);
  establishes the first-party discovery pattern, distinct from B's third-party
  model (DEC-25). Run-path generalization and the lazy environment factory are
  explicitly deferred to `add-sandbox-colima-vm`.
- **Depends on** — `split-into-modules` (change A): the `:sandbox:core` /
  `:sandbox:docker` split and `gnomish-plugin-api`. Sources the
  `execution-environment` capability from `add-sandbox-core`.
