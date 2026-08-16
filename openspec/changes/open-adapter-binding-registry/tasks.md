## 1. Binding-contribution SPI (`:sandbox:core`)

- [ ] 1.1 Write a failing spec for `SandboxBindingProvider`: a no-arg-constructed
  provider exposes `configName()` and `passport()` without instantiating a live
  environment (FR2, D5).
- [ ] 1.2 Add the `SandboxBindingProvider` SPI in `:sandbox:core` —
  `configName()`, `passport()`, and a lazy `create(...)` for the live
  `TaskExecutionEnvironment` (FR2, D5). Keep it `:sandbox:core`-internal, NOT in
  `gnomish-plugin-api` (DEC-25, NG1).
- [ ] 1.3 Green: implement to pass 1.1; assert `passport()`/`configName()` touch no
  docker SDK.

## 2. Discovery registry + trust allowlist (`:sandbox:core`)

- [ ] 2.1 Write failing specs for `AdapterBindingRegistry`: builds from
  `ServiceLoader.load(SandboxBindingProvider.class)` keyed on `configName()` (FR1,
  D1); duplicate config name fails fast naming the conflict (FR8, NFR-R1); unknown
  requested name fails fast listing discovered options (FR5).
- [ ] 2.2 Write failing specs for the trusted-id allowlist: an allowlisted provider
  is registered; a non-allowlisted one is rejected fail-fast with a named error
  (FR7, NFR-S1, D2).
- [ ] 2.3 Implement `AdapterBindingRegistry` + the core-owned trusted-id allowlist
  (`host`, `container`) to pass 2.1–2.2; build once, no per-invocation reload (D6).

## 3. Migrate HOST and CONTAINER to providers

- [ ] 3.1 Write a failing spec: with only `:sandbox:core` present, the `host`
  binding is registered with the no-isolation passport (FR3).
- [ ] 3.2 Add the `host` `SandboxBindingProvider` in `:sandbox:core` (passport =
  `CapabilityPassport.hostNoIsolation()`) + its `META-INF/services` entry.
- [ ] 3.3 Write a failing spec: with `:sandbox:docker` present, the `container`
  binding is registered with the container passport (FR3).
- [ ] 3.4 Add the `container` `SandboxBindingProvider` in `:sandbox:docker`
  (passport = `CapabilityPassport.container()`) + its `META-INF/services` entry.

## 4. Replace the enum and migrate callers (behavior-preserving)

- [ ] 4.1 Replace the `AdapterBinding` sealed enum with the registry-backed
  interface/record (`configName()` + `passport()`); remove the enum constants and
  `parse(...)` (FR1, D3).
- [ ] 4.2 Migrate `BindingResolver` to resolve the default and per-stage bindings
  from the registry; the unset default resolves the `container` binding, and an
  absent container binding fails fast naming the discovered options — never a
  silent host fallback (FR4, D4).
- [ ] 4.3 Migrate `SandboxModeSelector` host-vs-container branching to resolve the
  `host` / `container` bindings from the registry by name instead of enum `==`
  identity (FR9, D3); keep the docker-prerequisite gate keyed to the container
  binding.
- [ ] 4.4 Migrate `Segment` / `SandboxReconciler` to carry and read the
  registry-backed binding; confirm the reconciler still reads only
  `binding.passport()` (FR6, D6).

## 5. Bootstrap wiring + observability

- [ ] 5.1 Build the `AdapterBindingRegistry` once in `bootstrap` and inject it into
  `BindingResolver` / `SandboxModeSelector` (D6).
- [ ] 5.2 Write a failing spec then implement: at startup the factory logs the
  discovered bindings as config name → isolation summary (NFR-O1, UX3).

## 6. Behavior-preservation gate + registry specs

- [ ] 6.1 Run the existing execution-environment specs unchanged against the
  registry-backed bindings; they SHALL pass with no spec-file edits (FR9, M2).
- [ ] 6.2 Add a spec: an unmet stage need is refused fail-closed against the
  registry-resolved passport, exactly as before (FR6).
- [ ] 6.3 Add the extension-point acceptance spec (M4): a stub first-party binding
  contributed from a test module is discovered, allowlisted, and selected
  end-to-end (bind → reconcile → plan) with no core edit.
- [ ] 6.4 Add a spec: removing `:sandbox:docker` drops the `container` binding and
  the container default then fails fast naming the discovered options (M3).

## 7. Traceability, quality gates, docs

- [ ] 7.1 Verify every FR/NFR/UX of `open-adapter-binding-registry` has an
  implementing entity in code or tests (grep per `traceability.md`).
- [ ] 7.2 Run the full gate: Spotless, Error Prone + NullAway, dependency-analysis,
  JaCoCo + PIT on the touched Java (mutation target 100%, justify any exception).
- [ ] 7.3 Update sandbox docs / examples to note bindings are discovered and the
  trusted-id allowlist governs which first-party bindings load (D2, NFR-S1).
- [ ] 7.4 Recommend a Conventional Commits message referencing this change and the
  FR ids (the agent never commits).
