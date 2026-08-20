## 1. Binding-contribution SPI (`:sandbox:core`)

- [x] 1.1 Write a failing spec for `SandboxBindingProvider`: a no-arg-constructed
  provider exposes `configName()` and `passport()` without instantiating a live
  environment (FR2, D5).
- [x] 1.2 Add the `SandboxBindingProvider` SPI in `:sandbox:core` — `configName()`
  and `passport()` only; no environment factory (deferred to
  `add-sandbox-colima-vm`, D5). Public type, but NOT in `gnomish-plugin-api`
  (DEC-25, NG1).
- [x] 1.3 Green: implement to pass 1.1; assert `passport()`/`configName()` touch no
  docker SDK.

## 2. Registry + trust-table ratification (`:sandbox:core`)

- [x] 2.1 Write failing specs for the registry's pure index logic, driven by an
  injected provider list (no jars, no ServiceLoader): duplicate config name
  fails fast naming both providers and the fix (FR8, NFR-R1); an unknown
  requested name fails fast listing the discovered options and the fix (FR5).
- [x] 2.2 Write failing specs for trust-table ratification, driven by an injected
  table: a provider with a table entry and a matching passport is registered; an
  id absent from the table is rejected fail-fast; a declared passport differing
  from the expected one is rejected fail-fast — each error naming the fix (FR7,
  FR10, NFR-S1, D2).
- [x] 2.3 Implement `AdapterBindingRegistry` (pure index + ratification; provider
  list and trust table injectable) and the core-owned production trust table
  (`host`, `container` → their expected passports) to pass 2.1–2.2 (D2, D6).

## 3. Migrate HOST and CONTAINER to providers

- [x] 3.1 Write a failing spec: with only `:sandbox:core` present, the `host`
  binding is registered with the no-isolation passport (FR3).
- [x] 3.2 Add the `host` `SandboxBindingProvider` in `:sandbox:core` (passport =
  `CapabilityPassport.hostNoIsolation()`) + its `META-INF/services` entry.
- [x] 3.3 Write a failing spec: with `:sandbox:docker` present, the `container`
  binding is registered with the container passport (FR3).
- [x] 3.4 Add the `container` `SandboxBindingProvider` in `:sandbox:docker`
  (passport = `CapabilityPassport.container()`) + its `META-INF/services` entry.

## 4. Replace the enum and migrate callers (behavior-preserving)

- [x] 4.1 Replace the `AdapterBinding` sealed enum with the registry-backed
  interface/record (`configName()` + `passport()`); remove the enum constants and
  `parse(...)` (FR1, D3).
- [x] 4.2 Migrate `BindingResolver` to resolve the default and per-stage bindings
  from the registry; the unset default resolves the `container` binding eagerly,
  and an absent container binding fails fast naming the discovered options and
  the ways out (restore `:sandbox:docker`, or explicitly bind
  `factory.bindings.default=host`) — never a silent host fallback (FR4, D4).
  Spec the eagerness: the stripped build fails even when every stage explicitly
  binds `host`.
- [x] 4.3 Migrate `SandboxModeSelector` host-vs-container branching to config-name
  identity, with the registry passed into `plan(...)` (FR9, D3, D6); keep the
  docker-prerequisite gate keyed to the `container` binding.
- [x] 4.4 Migrate `Segment` and `SegmentPlanner` to the registry-backed binding
  with `configName()` identity — the planner's segment-boundary reference `!=`
  becomes a config-name comparison (FR9, D3); verify `SandboxReconciler` needs
  no change (FR6, D6).

## 5. Bootstrap discovery + observability

- [x] 5.1 Add the discovery pass in `:bootstrap` mirroring
  `TrackerAdapterDiscovery` (`discover()` / `discover(ClassLoader)` / pure
  `index(...)`), build the registry once, and thread it into `BindingResolver` /
  `SandboxModeSelector.plan(...)` (D1, D6).
- [x] 5.2 Write a failing spec then implement: the discovered bindings are
  reported through `ProviderDiscoveryReport` — config name, provider class,
  originating jar, passport summary — before any stage runs (NFR-O1, UX3).
- [x] 5.3 Add one contract spec on the real classpath: the production
  `META-INF/services` entries for `host` and `container` are discovered by a
  plain `ServiceLoader` pass (guards the entry files themselves).

## 6. Behavior-preservation gate + acceptance

- [x] 6.1 Run the existing execution-environment specs against the registry-backed
  bindings: behavioral assertions pass unchanged, edits confined to construction
  sites naming the removed enum constants; `AdapterBindingSpec` is superseded by
  the registry specs (FR9, M2).
- [x] 6.2 Add a spec: an unmet stage need is refused fail-closed against the
  registry-resolved passport, exactly as before (FR6).
- [x] 6.3 Add the extension-point acceptance spec (M4): a stub first-party binding
  staged via `discover(loader)` over staged `META-INF/services` entries, with
  its entry in an injected trust table, is selected end-to-end (bind → reconcile
  → plan) with no edit to the discovery or registry mechanism.
- [x] 6.4 Add a spec (M3): a class-loader staging without `:sandbox:docker` drops
  the `container` binding, and the container default then fails fast naming the
  discovered options and the fix.

## 7. Traceability, quality gates, docs

- [x] 7.1 Verify every FR/NFR/UX of `open-adapter-binding-registry` has an
  implementing entity in code or tests (grep per `traceability.md`), including
  the M1 grep: no enum constant carrying a binding's passport remains in core.
- [x] 7.2 Run the full gate: Spotless, Error Prone + NullAway, dependency-analysis,
  JaCoCo + PIT on the touched Java (mutation target 100%, justify any exception).
- [x] 7.3 Update sandbox docs / examples: bindings are discovered; the core trust
  table (id → expected passport) governs which first-party bindings load and
  ratifies their passports (D2, NFR-S1); note the build-time classpath-pinning
  companion change.
- [x] 7.4 Recommend a Conventional Commits message referencing this change and the
  FR ids (the agent never commits).
