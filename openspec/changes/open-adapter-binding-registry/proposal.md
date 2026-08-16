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
- `BindingResolver`, `Segment`, `SandboxReconciler`, and `SandboxModeSelector`
  move from enum identity (`== AdapterBinding.CONTAINER`) to registry-based
  lookup and identity; observable behavior is unchanged.

**ADDED**
- A first-party binding-contribution SPI in `:sandbox:core`, exposing a binding's
  config name and fixed `CapabilityPassport` **without instantiating a live
  backend adapter**, so planning-time reconciliation and segment planning stay
  adapter-instance-free (the property `AdapterBinding` guarantees today).
- Fail-fast registry resolution: an unknown configured binding name, or two
  providers claiming one config name, fails at startup with the valid discovered
  options named — never a silent fallback.

**PRESERVED (explicit non-change)**
- The passport-reconciliation contract — operator binds, repo only tightens,
  fail-closed on any unmet need (FR14 of add-sandbox-core) — is unchanged
  (DEC-27); the container-by-default rule (D13) is preserved.

## Capabilities

### New Capabilities
- `adapter-binding-registry`: the discovered binding registry — the first-party
  contribution SPI, config-name + passport exposure without a live adapter,
  fail-fast duplicate/unknown resolution, container-by-default preserved, and the
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
  with zero edits to `:sandbox:core`.
- **G2** — Preserve the passport-reconciliation contract exactly (operator binds,
  repo only tightens, fail-closed on mismatch) — a behavior-preserving refactor
  (DEC-27).
- **G3** — Migrate `HOST` (into `:sandbox:core`) and `CONTAINER` (into
  `:sandbox:docker`) to the registry with no operator-visible config change.
- **G4** — Unblock the paused sandbox backend changes (hardening, colima-vm,
  gha-executor, cloud-executor) to land as the first discovered backends without
  further core edits (DEC-26).

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
  + passport + environment factory) from their own module;
  `factory.bindings.default=<name>` resolves it with no core edit.
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
  name SHALL fail at startup with the valid discovered options named (UX2 of
  add-sandbox-core preserved).
- **FR6** — The passport-reconciliation contract SHALL be unchanged: operator
  binds, repo only tightens, fail-closed on any unmet need (FR14 of
  add-sandbox-core) (DEC-27).
- **FR7** — Binding discovery SHALL be first-party only: the registry SHALL NOT
  load sandbox bindings through the third-party plugin trust model of
  `add-plugin-architecture`; a binding's passport is trusted because its provider
  is first-party (DEC-25).
- **FR8** — Two providers claiming the same config name SHALL fail fast at startup
  with a named error identifying the conflict — never a silent pick.
- **FR9** — The existing execution-environment specs SHALL pass unchanged; the
  registry migration (including the replacement of enum `==` identity in segment
  planning) SHALL be behavior-preserving.

### Non-Functional — Reliability

- **NFR-R1** — Discovery SHALL be deterministic and fail-fast: an absent requested
  binding and a duplicate config name both SHALL fail with a clear named error at
  startup, never a silent fallback or a mid-task failure.

### Non-Functional — Security

- **NFR-S1** — The sandbox is a trust boundary: only first-party modules SHALL
  contribute bindings and passports, and an untrusted jar SHALL NOT be able to
  introduce a lying passport through this registry (the mechanism is not wired to
  third-party discovery). The residual risk on the flat classpath (A / NG7) SHALL
  be stated with the chosen enforcement posture (Q-TRUST).

### Non-Functional — Observability

- **NFR-O1** — At startup the factory SHALL log the discovered bindings (config
  name → isolation summary) so an operator can confirm which backend modules are
  present.

## Operator Experience Criteria

- **UX1** — Selecting an unknown binding name fails at startup with a named,
  actionable error listing the discovered options (behavior preserved, options
  now dynamic).
- **UX2** — An existing `host` / `container` (or unset) config runs with no change
  — zero migration burden for operators.
- **UX3** — Discovered bindings are observable (logged / listable) so an operator
  knows a backend module is active before running a stage.

## Success Metrics

- **M1** — Adding a new binding requires zero edits to `:sandbox:core` binding
  source; no enum constant list of bindings remains (grep + test).
- **M2** — All existing execution-environment and binding specs pass unchanged.
- **M3** — Removing `:sandbox:docker` from the classpath removes the `container`
  binding cleanly: the container default then fails fast with a named error naming
  the discovered options, with no dangling reference (NFR-R1).
- **M4** — A stub first-party backend binding contributed from a new module is
  selected end-to-end (bind → reconcile → plan) without touching core — the
  extension-point acceptance test.

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
  registry-backed type; `BindingResolver`, `Segment`, `SandboxReconciler`, and
  `SandboxModeSelector` migrate to registry lookup + registry-based identity;
  `CapabilityPassport` and `IsolationLevel` are unchanged.
- **Modules** — a `HOST` binding provider in `:sandbox:core`, a `CONTAINER`
  binding provider in `:sandbox:docker`, and the new binding-contribution SPI in
  `:sandbox:core`.
- **Config** — `factory.bindings.*` grammar is unchanged; the set of valid binding
  names becomes dynamic (discovered).
- **Downstream** — unblocks `add-sandbox-hardening` / `-colima-vm` /
  `-gha-executor` / `-cloud-executor` to resume as discovered backends (DEC-26);
  establishes the first-party discovery pattern, distinct from B's third-party
  model (DEC-25).
- **Depends on** — `split-into-modules` (change A): the `:sandbox:core` /
  `:sandbox:docker` split and `gnomish-plugin-api`. Sources the
  `execution-environment` capability from `add-sandbox-core`.
