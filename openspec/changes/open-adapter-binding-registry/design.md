# Design: open-adapter-binding-registry

## Context

Driven by G1–G4 and FR1–FR9 of `open-adapter-binding-registry`, on top of the
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
- Backend modules contribute a binding (config name + passport + lazy environment
  factory) with no `:sandbox:core` enum edit (G1, FR1).
- Preserve reconciliation and the container-by-default rule exactly (G2, FR4, FR6).
- Keep planning/reconciliation adapter-instance-free (FR2).

**Non-Goals:**
- Third-party / untrusted sandbox providers, signed-jar verification (NG1, NG2).
- New isolation levels or backends (NG3, NG5) — host + container only.
- Changing the `TaskExecutionEnvironment` port or passport shape (NG4).

## Decisions

**D1 — Q-MECH resolved: JDK `ServiceLoader`, first-party service type (FR1, FR2).**
`bootstrap` builds an `AdapterBindingRegistry` once by iterating
`ServiceLoader.load(SandboxBindingProvider.class)` and keying on `configName()` —
the same JVM mechanism as change B/D1, so a backend module contributes purely by
shipping a `META-INF/services` entry, no core edit. But the service *type* is
`:sandbox:core`-internal, **not** part of `gnomish-plugin-api`: the sandbox port is
first-party and stays out of the third-party plugin surface (DEC-25). *Rationale:*
dependency inversion without core referencing backend classes (preserves DEC-24 —
core must not depend on a backend). *Alternative rejected:* a core-owned explicit
list of provider classes — forces core to import each backend, violating DEC-24.
*Alternative rejected:* Spring `@Component` scan — couples discovery to the DI
container and to a Spring dependency in every backend.

**D2 — Q-TRUST resolved: core-owned first-party id allowlist gates discovery (FR7, NFR-S1).**
On A's flat classpath (A/NG7) `ServiceLoader` alone would load *any* jar's
provider, letting an untrusted jar assert a lying passport — the exact hole
DEC-25 forbids. So the registry cross-checks each discovered provider's
`configName()` against a small **allowlist of trusted first-party binding ids held
in `:sandbox:core`** (`host`, `container`, and each future first-party backend id
added by that backend's own change). A discovered provider whose id is not
allowlisted is rejected fail-fast at startup with a named error. *Reconciling with
G1/M1:* the allowlist is a one-line *trust registration*, not a binding
definition — the passport, config, and environment factory still live entirely in
the backend module; what M1 measures (no enum constant carrying a passport in
core) still holds. *Rationale:* passport authority must be gated at the trust
boundary; the operator should not have to vouch for `host`/`container`.
*Alternative rejected:* posture-only (trust the classpath, observe at startup) as
in B/D6 — acceptable for tracker/check providers but under-delivers DEC-25 for the
sandbox, whose passport *is* the security boundary. *Alternative rejected:* an
operator-config allowlist — genuine third-party sandbox is explicitly NG2; making
operators vouch for first-party bindings is friction for no gain.

**D3 — Q-IDENTITY resolved: bindings value-identified by `configName()`; `host`/`container` stay privileged names (FR9).**
`AdapterBinding` becomes an interface (or record) exposing `configName()` +
`passport()`; enum identity is gone. `SandboxModeSelector`'s host-vs-container
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
binding from the registry; if `:sandbox:docker` is absent, `container` is not
registered and startup fails fast, naming the discovered options — never a silent
host fallback. *Rationale:* D13 forbids silently weakening isolation; the default
distribution bundles docker, so this only bites a deliberately stripped build, and
failing loud with the available bindings is the honest outcome. *Alternative
rejected:* fall back to host — reintroduces exactly the silent weakening D13
prohibits. *Alternative rejected:* keep a hardcoded container passport in core so
the default always resolves — a stage would then reconcile green against a
container passport with no backend to run it, failing later and more confusingly.

**D5 — SPI shape: passport cheap, environment lazy (FR2).**
`SandboxBindingProvider` (no-arg constructor, mirroring B/D2) exposes
`String configName()` and `CapabilityPassport passport()` — both cheap and
SDK-free — plus a factory method that constructs the live `TaskExecutionEnvironment`
only when a stage actually runs under the binding. `configName()`/`passport()`
must not touch a docker client, so enumerating bindings (and the reconciliation
specs) stays daemon-free. *Rationale:* preserves the current
`AdapterBinding.passport()`-needs-no-instance property (FR2). *Alternative
rejected:* one method returning a constructed environment — forces an SDK/daemon
touch at discovery time, breaking daemon-free planning and the reconciliation
tests.

**D6 — Registry built once at bootstrap; reconciler untouched (G2, FR6, NFR-O1).**
`bootstrap` builds the registry (D1 ∩ D2), logs the discovered bindings
(`configName` → isolation summary, NFR-O1), and injects it into `BindingResolver`
and `SandboxModeSelector`. `SandboxReconciler` is unchanged — it already reads only
`binding.passport()`, so the operator-binds / repo-tightens / fail-closed contract
(FR14 of add-sandbox-core) is preserved by construction. *Rationale:* the smallest
seam that opens discovery without disturbing reconciliation. *Alternative
rejected:* re-resolve the registry per invocation like the tracker factory — the
binding set is process-static (classpath-fixed), so once-at-bootstrap is correct
and cheaper.

## Risks / Trade-offs

- **Allowlist edit per first-party backend tensions with "zero core edits" (G1)**
  → framed as a minimal, reviewed trust registration (D2); the binding definition
  (passport + factory) still lives wholly in the backend module, so M1 holds.
- **Flat-classpath id spoofing: a malicious jar ships a provider under an
  allowlisted id (`container`)** → the allowlist bounds *which ids* are trusted,
  not *who* ships them; full defense needs signed jars, deferred until third-party
  sandbox is considered (NG2; residual of B/D6). Stated honestly, not assumed away.
- **Default-absent fail-fast surprises a stripped build** → documented; the default
  distribution bundles `:sandbox:docker`, and the error names the available
  bindings (D4, M3).
- **`host`/`container` hardcoded as privileged names in the selector (D3)** → they
  are already privileged by D13 (container default, host opt-in); the coupling is
  pre-existing, not new.

## Migration Plan

1. Add `SandboxBindingProvider` SPI + `AdapterBindingRegistry` + the trusted-id
   allowlist in `:sandbox:core`, with the `host` provider.
2. Move the container passport into a `container` provider in `:sandbox:docker`
   (`META-INF/services`).
3. Replace the `AdapterBinding` enum with the registry-backed interface/record;
   migrate `BindingResolver`, `Segment`, `SandboxReconciler`, `SandboxModeSelector`
   to registry lookup + `configName()` identity (D3).
4. Wire the registry in `bootstrap`; log discovered bindings (NFR-O1).
5. Run the existing execution-environment specs unchanged as the behavior gate
   (M2); add registry-specific specs for discovery, allowlist rejection, duplicate
   config name, and default-absent fail-fast.

Rollback is self-contained: revert to the sealed enum in a single module — no
persisted state or config-grammar change to undo (`factory.bindings.*` is
unchanged).

## Open Questions

All four carried questions are resolved:
- **Q-MECH** — D1: JDK `ServiceLoader`, `:sandbox:core`-internal service type.
- **Q-TRUST** — D2: core-owned first-party id allowlist gates discovery.
- **Q-IDENTITY** — D3: value-identity by `configName()`, `host`/`container`
  privileged.
- **Q-DEFAULT-ABSENT** — D4: fail fast, never a silent host fallback.

Residual (not blocking): signed-jar / id-spoofing defense, deferred until genuine
third-party sandbox providers are considered (NG2).
