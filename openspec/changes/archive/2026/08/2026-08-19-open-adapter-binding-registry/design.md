# Design: open-adapter-binding-registry

## Context

Driven by G1–G4 and FR1–FR10 of `open-adapter-binding-registry`, on top of the
`:sandbox:core` / `:sandbox:docker` split from `split-into-modules` (change A).
Today `AdapterBinding` is a sealed enum in `:sandbox:core`: each constant
(`HOST`, `CONTAINER`) carries its `configName()` and a fixed `CapabilityPassport`,
and `BindingResolver` / `SandboxModeSelector` branch on enum identity
(`== AdapterBinding.CONTAINER`). A key property holds today and must survive: the
passport is available *without* a live adapter instance, so planning-time
reconciliation and segment planning never touch a docker daemon (the
execution-environment specs run daemon-free). This change opens the enum into a
registry that backend modules contribute to — but the sandbox is a **trust
boundary**: a self-declared passport from an untrusted jar is a hole (DEC-25), so
discovery here is first-party only and deliberately *not* the third-party plugin
model of `add-plugin-architecture` (change B). Resolves Q-MECH, Q-TRUST,
Q-IDENTITY, Q-DEFAULT-ABSENT.

## Goals / Non-Goals

**Goals:**
- Backend modules contribute a binding (config name + passport) with no
  `:sandbox:core` enum edit (G1, FR1); the environment factory is deferred to
  the first non-docker backend (`add-sandbox-colima-vm`).
- Preserve reconciliation and the container-by-default rule exactly (G2, FR4, FR6).
- Keep planning/reconciliation adapter-instance-free (FR2).

**Non-Goals:**
- Third-party / untrusted sandbox providers, signed-jar verification (NG1, NG2).
- New isolation levels or backends (NG3, NG5) — host + container only.
- Changing the `TaskExecutionEnvironment` port or passport shape (NG4).

## Decisions

**D1 — Q-MECH resolved: JDK `ServiceLoader` as enumerator only, first-party service type (FR1, FR2).**
`bootstrap` builds an `AdapterBindingRegistry` once by draining
`ServiceLoader.load(SandboxBindingProvider.class)` and keying on `configName()` —
the same JVM mechanism as change B/D1, so a backend module contributes purely by
shipping a `META-INF/services` entry. ServiceLoader is the *enumerator only*;
every decision (duplicates, ratification, default) is the registry's own code.
The discovery pass mirrors `TrackerAdapterDiscovery`'s shape — `discover()` /
`discover(ClassLoader)` / a pure `index(...)` — and lives in `:bootstrap`
(building a registry is composition, per module-layering); the SPI type, the
registry type, and the pure index/ratification logic live in `:sandbox:core`.
The service type is public (bootstrap must load it) but **not** part of
`gnomish-plugin-api`: the sandbox port is first-party, stays out of the
third-party plugin surface (DEC-25), and thus causes zero japicmp baseline
churn. The sandbox port deliberately does *not* meet B's four-point
plugin-ready criterion — no config subsection/validator, no third-party trust
model. *Rationale:*
dependency inversion without core referencing backend classes (preserves DEC-24 —
core must not depend on a backend). *Alternative rejected:* a core-owned explicit
list of provider classes — forces core to import each backend, violating DEC-24.
*Alternative rejected:* Spring `@Component` scan — couples discovery to the DI
container and to a Spring dependency in every backend.

**D2 — Q-TRUST resolved: core-owned trust table ratifies each passport (FR7, FR10, NFR-S1).**
On A's flat classpath (A/NG7) `ServiceLoader` alone would load *any* jar's
provider, letting an untrusted jar assert a lying passport — the exact hole
DEC-25 forbids, and the passport is precisely what reconciliation trusts. So
`:sandbox:core` owns a **trust table: binding id → expected
`CapabilityPassport`** (a binding's id *is* its `configName()` — one identifier,
no separate id space) (`host`, `container`, and each future first-party id
added by that backend's own change). Discovery ratifies every provider against
it, fail-fast with the fix named: an id absent from the table is rejected, and
a declared passport differing from the expected one is rejected. The provider's
self-declaration is thereby a cross-checked proposal, never the authority — the
Kubernetes RuntimeClass model, where the party accountable for isolation owns
the mapping; the passport turns from trusted input into a tripwire. The table
is injectable (production wiring passes the core-owned table; specs pass their
own), which is what makes the M4 acceptance test honest. *Reconciling with
G1/M1:* the entry is a one-line, reviewed *trust registration* — data, not
behavior; the binding definition still lives in the backend module, so M1 (as
reworded) holds. *Rationale:* passport authority must be gated at the trust
boundary by the party that owns the risk; the operator should not have to vouch
for `host`/`container`. *Alternative rejected:* id-only allowlist — leaves the
passport a trusted input; surveyed ecosystems let self-declared capability
metadata cross a trust boundary only as negotiation, never as the boundary
itself. *Alternative rejected:* posture-only (trust the classpath, observe at
startup) as in B/D6 — acceptable for tracker/check providers but under-delivers
DEC-25 for the sandbox, whose passport *is* the security boundary. *Alternative
rejected:* load-time jar signing — dead in practice (OpenJDK never verified JCE
provider signatures; the SecurityManager is gone, JEP 486); post-SecurityManager
JDK doctrine is that the build-assembled classpath IS the trust domain, so the
id-spoofing residual is closed at build time by classpath pinning (the
dependency-verification change), with JPMS qualified `provides` as optional
future hardening. *Alternative rejected:* an operator-config allowlist —
genuine third-party sandbox is explicitly NG2; making operators vouch for
first-party bindings is friction for no gain.

**D3 — Q-IDENTITY resolved: bindings value-identified by `configName()`; `host`/`container` stay privileged names (FR9).**
`AdapterBinding` becomes an interface (or record) exposing `configName()` +
`passport()`; enum identity is gone. The registry guarantees one instance per
config name (built once, frozen), yet callers compare by `configName()`, not
reference — including `SegmentPlanner`'s segment-boundary check, today a
reference `!=`, which migrates with the rest. `SandboxModeSelector`'s host-vs-container
branching (mixed-mode refusal, docker-prerequisite gate) resolves the `container`
and `host` bindings *from the registry by name* rather than comparing enum
constants — the two names are already privileged in core (`container` is the D13
default; `host` is the explicit unsandboxed opt-in). *Rationale:* behavior-
preserving, and it keeps the docker-prerequisite gate keyed to the *actual*
container binding rather than to "any isolated binding." *Alternative rejected:*
branch on `passport().isolation() != NONE` — would misapply the docker
prerequisite to a future VM backend (colima, isolation ≠ NONE, not docker),
planting a latent bug; the general "run mode per backend" abstraction is deferred
to the backend changes that introduce non-host/container bindings (NG3).
*Alternative rejected:* a registry-issued token/enum — reintroduces the closed set
this change removes.

**D4 — Q-DEFAULT-ABSENT resolved: absent container module ⇒ fail fast (FR4, M3, NFR-R1).**
With `factory.bindings.default` unset, `BindingResolver` resolves the `container`
binding from the registry, eagerly; if `:sandbox:docker` is absent, `container`
is not registered and startup fails fast, naming the discovered options and the
ways out (restore `:sandbox:docker`, or explicitly bind
`factory.bindings.default=host` for a trusted environment) — never a silent host
fallback. Eager resolution means a stripped build fails even when every stage
explicitly binds `host` — deliberate: "this distribution's declared default is
unsatisfiable" surfaces immediately, and a spec pins the eagerness. *Rationale:* D13 forbids silently weakening isolation; the default
distribution bundles docker, so this only bites a deliberately stripped build, and
failing loud with the available bindings is the honest outcome. *Alternative
rejected:* fall back to host — reintroduces exactly the silent weakening D13
prohibits. *Alternative rejected:* keep a hardcoded container passport in core so
the default always resolves — a stage would then reconcile green against a
container passport with no backend to run it, failing later and more confusingly.

**D5 — SPI shape: descriptor only — `configName()` + `passport()` (FR2).**
`SandboxBindingProvider` (no-arg constructor, mirroring B/D2) exposes
`String configName()` and `CapabilityPassport passport()` — both cheap and
SDK-free; they must not touch a docker client, so enumerating bindings (and the
reconciliation specs) stays daemon-free. **No environment factory in this
change**: the live execution path is not a single `create(...)` call today —
mode dispatch selects whole runner families assembled in `bootstrap` — so a
factory method would be declared and wired to nothing. The target shape remains
the JDK's descriptor-plus-lazy-factory guideline; the factory lands with its
first consumer, the run-path generalization in `add-sandbox-colima-vm`. Growing
the SPI later is free: it is first-party and outside the japicmp'd plugin
surface. *Rationale:* preserves the current
`AdapterBinding.passport()`-needs-no-instance property (FR2) and keeps this
change's promise measurable — M4 ends at plan, deliberately. *Alternative
rejected:* one method returning a constructed environment — forces an SDK/daemon
touch at discovery time, breaking daemon-free planning and the reconciliation
tests. *Alternative rejected:* shipping an unwired `create(...)` now — a dead
method on a security-sensitive SPI is an unverified promise.

**D6 — Registry built once at bootstrap; reported like the other ports; reconciler untouched (G2, FR6, NFR-O1).**
`bootstrap` builds the registry (D1 ∩ D2) and reports it through the existing
port-agnostic `ProviderDiscoveryReport` — config name, provider class,
originating jar (`CodeSource`), passport summary — before any stage runs
(NFR-O1): observability is the compensating control for the absent runtime
boundary. The registry is then threaded into `BindingResolver` (constructor)
and `SandboxModeSelector` (a parameter of its static `plan(...)` — the selector
is a static utility, so "inject" concretely means a signature change).
"Startup" throughout this change means *before any stage runs*: trust-table and
duplicate failures surface at registry build, unknown-configured-name failures
at binding resolution — both before a stage. `SandboxReconciler` is unchanged —
it already reads only the passport, so the operator-binds / repo-tightens /
fail-closed contract (FR14 of add-sandbox-core) is preserved by construction.
*Rationale:* the smallest seam that opens discovery without disturbing
reconciliation. *Alternative rejected:* re-resolve the registry per invocation
like the tracker factory — the binding set is process-static (classpath-fixed),
so once-at-bootstrap is correct and cheaper. *Alternative rejected:*
warn-and-pick on ambiguity — the documented SLF4J anti-pattern; for a sandbox a
wrong pick means wrong isolation, so every ambiguity is a refusal naming the
fix.

## Risks / Trade-offs

- **Trust-table entry per first-party backend tensions with "zero core edits" (G1)**
  → framed as a minimal, reviewed trust registration (D2) — data, not behavior;
  the binding definition still lives wholly in the backend module, so M1 (as
  reworded) holds.
- **Flat-classpath id spoofing: a malicious jar ships a provider under a trusted
  id with the expected passport** → no runtime defense exists post-SecurityManager
  and load-time signing is dead in practice; closed at build time by classpath
  pinning (the dependency-verification change), with JPMS qualified `provides`
  as optional future hardening. Stated honestly, not assumed away.
- **Default-absent fail-fast surprises a stripped build** → documented; the default
  distribution bundles `:sandbox:docker`, and the error names the available
  bindings (D4, M3).
- **D4's "explicitly bind `host`" way out may be unactionable in a stripped
  build** — `HostTaskExecutionEnvironment` currently ships in `:sandbox:docker`
  (an A-era placement), so a distribution stripped of that module lacks the host
  *environment* too, not just the `container` binding. Binding resolution and
  the run path are deliberately decoupled in this change (M3/6.4 stage the
  absence via a class loader), but the error-message hint should be revisited —
  or the host environment relocated — when the run path generalizes
  (`add-sandbox-colima-vm`).
- **`host`/`container` hardcoded as privileged names in the selector (D3)** → they
  are already privileged by D13 (container default, host opt-in); the coupling is
  pre-existing, not new.

## Migration Plan

1. Add the `SandboxBindingProvider` SPI, the `AdapterBindingRegistry` type with
   its pure index/ratification logic, and the trust table (id → expected
   passport) in `:sandbox:core`, with the `host` provider.
2. Move the container passport binding into a `container` provider in
   `:sandbox:docker` (`META-INF/services`).
3. Replace the `AdapterBinding` enum with the registry-backed interface/record;
   migrate `BindingResolver`, `Segment`, `SegmentPlanner`, and
   `SandboxModeSelector` to registry lookup + `configName()` identity (D3);
   verify `SandboxReconciler` needs no change.
4. Add the discovery pass in `bootstrap` (mirroring `TrackerAdapterDiscovery`),
   report through `ProviderDiscoveryReport` (NFR-O1), and thread the registry
   into `BindingResolver` / `SandboxModeSelector.plan(...)`.
5. Run the existing execution-environment specs as the behavior gate (M2:
   assertions unchanged, construction sites mechanically migrated); add registry
   specs for discovery, ratification rejection (unknown id, passport mismatch),
   duplicate config name, and default-absent fail-fast — every refusal naming
   the fix.

Rollback is self-contained: revert to the sealed enum in a single module — no
persisted state or config-grammar change to undo (`factory.bindings.*` is
unchanged).

## Open Questions

All four carried questions are resolved:
- **Q-MECH** — D1: JDK `ServiceLoader`; the service type is public (bootstrap
  loads it) but outside `gnomish-plugin-api`.
- **Q-TRUST** — D2: core-owned trust table (id → expected passport) ratifies
  discovery.
- **Q-IDENTITY** — D3: value-identity by `configName()`, `host`/`container`
  privileged.
- **Q-DEFAULT-ABSENT** — D4: fail fast, never a silent host fallback.

Residual (not blocking): id-spoofing on the flat classpath — closed at build
time by the dependency-verification change (classpath pinning); JPMS
module-path enforcement remains optional future hardening (NG2).
