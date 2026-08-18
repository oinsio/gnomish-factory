## Why

After `split-into-modules` (change A) gives us a thin, versioned
`gnomish-plugin-api`, the factory still *discovers* adapters through hardwired
`Map.of("github", …, "inmemory", …)` registries: adding or swapping a vendor
means editing core code. The tracker port is almost-SPI (interface + SPI factory
+ registry keyed by the config's `type` — the factory itself exposes no `type()`
yet); the check port lags behind (no SPI factory interface at all — only the
concrete `GithubCheckClientFactory` — no `provider` field, no registry, GitHub
wired in directly). This change turns the
almost-SPI into a real plugin architecture: `ServiceLoader` discovery, one
mechanism for built-in and third-party providers, the check port brought up to
the tracker pattern, a generic `http` check as an escape-hatch, and — as the
acceptance test — GitHub extracted into a discovered plugin that loads exactly
like a third party's would. Depends on A (needs `gnomish-plugin-api`).

## What Changes

**ADDED**
- `ServiceLoader`-based discovery replacing the hardwired `Map.of(...)`
  registries; one mechanism for built-in and external providers, differing only
  in packaging (DEC-8, DEC-11, DEC-12, DEC-13).
- Check port raised to the tracker pattern: a new `CheckClientFactory` SPI
  interface in `gnomish-plugin-api` (with `provider()`, a generic `create`, a
  per-provider `CheckParamsValidator`, an operator-subsection validator, a
  connection-aware credential-name declaration replacing core's hardwired
  vendor constants, and the pin-contribution hook), plus a
  `Map<provider, CheckClientFactory>` registry via `ServiceLoader`
  (DEC-14–DEC-18).
- Named per-vendor connection profiles in operator config, so one vendor serving
  two ports shares a single connection/credentials definition (DEC-13, Q6).
- Per-check provider selection in the stage manifest — one stage may hold
  several `external` checks from different vendors, each resolved independently
  (DEC-15).
- A built-in `provider: http` generic external check shipped in core, with a
  declarative `pass_when` / `pending_when` verdict contract reusing the
  `External` poll loop (DEC-20, DEC-21).
- A factory-side egress allowlist governing the `http` check (SSRF / exfil
  guard, since `EgressGuard` only watches the agent sandbox) (DEC-23).

**MODIFIED**
- The `External` verify check gains `provider` + opaque `params` (flat JDK
  types, Jackson-free, like `Builtin.params`); `interval` / `timeout` /
  `timeoutClass` / `pinPaths` stay engine-common; no new sealed variant (DEC-16).
- Tracker discovery moves from the hardwired registry to `ServiceLoader` (DEC-8).
- **BREAKING (packaging)** — GitHub (tracker + checks + shared http) leaves core
  into a discovered plugin jar; `inmemory` stays in core as the reference /
  test-double; the default distribution is core + a bundled github jar so the
  built-in path is byte-for-byte the third-party path (DEC-9, DEC-10).
- The `gnomish-plugin-api` japicmp check flips from report-only to a failing gate
  (change A / D10 residual — B is the first external consumer).

## Capabilities

### New Capabilities
- `plugin-discovery`: `ServiceLoader` discovery, per-port registries, the SPI
  no-arg-construction + dependencies-as-method-args contract, and the four-point
  "port is plugin-ready" criteria.
- `check-provider-model`: the check port's `provider` field, registry,
  per-provider validator, per-check selection, and the findings-correlation
  identity + default-provider migration.
- `http-check-provider`: the built-in `http` external check and its
  `pass_when` / `pending_when` verdict contract.
- `factory-egress-allowlist`: the factory-side egress control for the `http`
  check (https-only, blocklists, interpolation whitelist, operator-owned).
- `github-plugin`: GitHub as a discovered plugin bundle and the trust posture
  for loading provider jars in the privileged factory process.
- `vendor-connection-profile`: named per-vendor connection profiles in operator
  config, shared across the ports one vendor serves.

### Modified Capabilities
- `pipeline-config`: the `External` check schema gains `provider` + `params`,
  with the missing-provider → github default and a located error for a provider
  absent from the discovered registry. (Tracker/check *discovery* moving
  to `ServiceLoader` is wiring, captured by the new `plugin-discovery`
  capability — no existing port-contract requirement changes.)
- `github-tracker` / `github-external-check`: their connection config MAY
  reference a named vendor connection profile instead of duplicating endpoint +
  credential name per port.
- `plugin-api-contract`: the api surface gains the check SPI
  (`CheckClientFactory` + `CheckParamsValidator` + `ExternalCheckPinContributor`)
  and the tracker SPI additions (`type()`, `subsectionValidator()`, the
  connection-aware credential declaration), and the japicmp check flips from
  report-only to a failing gate.

## Goals

- **G1** — Discover every provider (tracker + check) through `ServiceLoader`, so
  adding or swapping a vendor never edits a core registry.
- **G2** — Bring the check port to full parity with the tracker port
  (`provider`, registry, validator, discriminator).
- **G3** — Ship a generic `http` external check as the escape-hatch for
  third-party CI/quality services, symmetric to `command` for local checks.
- **G4** — Prove the architecture by extracting GitHub into a plugin that loads
  exactly as a third party's would (built-in path ≡ external path).
- **G5** — Close the api compatibility loop: flip japicmp to a failing gate.

## Non-Goals

- **NG1** — ai-provider pluginization (`judge` → voter is the same pattern,
  later).
- **NG2** — Pluginizing the sandbox / `TaskExecutionEnvironment` port: a
  self-declared `CapabilityPassport` from an untrusted jar is a trust hole; the
  sandbox stays first-party (DEC-25). Opening `AdapterBinding` is change C.
- **NG3** — Classloader isolation or a `plugins/` folder; the flat classpath
  from A stays (explore Q3 / A-NG7).
- **NG4** — secrets / observability / workspace as external plugins — they stay
  module boundaries only (DEC-28).
- **NG5** — A managed marketplace, signing infrastructure, or remote plugin
  fetch; trust is decided as a posture (Q4), not built as a system.

## Users & Scenarios

- **U1** — An integrator drops a vendor jar on the classpath; its tracker and
  check providers appear with no core edit.
- **U2** — A stage-manifest author selects a check `provider` per-check and runs,
  in one verify chain, a local `command`, a SonarQube quality gate via `http`,
  and a GitHub Actions build — three providers (DEC-19).
- **U3** — An operator points a stage at a third-party CI's REST API through the
  built-in `http` check without writing any adapter.
- **U4** — An operator restricts where the `http` check may connect via an
  allowlist in operator config; a manifest cannot reach an internal address.
- **U5** — A maintainer removes the github jar from the distribution; github
  providers disappear cleanly and the rest of the factory still runs.

## Requirements

### Functional

- **FR1** — Provider discovery SHALL use Java `ServiceLoader`; the hardwired
  `Map.of(...)` provider registries SHALL be removed. The mechanism SHALL be
  identical for built-in and external providers, differing only in packaging.
- **FR2** — SPI factories SHALL be instantiated by `ServiceLoader` via a no-arg
  constructor; runtime dependencies (`SecretsProvider`, resolved config) SHALL be
  passed as method arguments, never injected into the constructor.
- **FR3** — Registries SHALL be per-port and selection SHALL be by the port's
  discriminator, resolved independently per port; mixing vendors across ports
  (e.g. github tracker + gitlab checks) SHALL be a supported case, and unused
  discovered providers SHALL stay dormant.
- **FR4** — The change SHALL define and apply a four-point "port is plugin-ready"
  criterion — (a) an SPI factory exposing `type()` / `provider()`, (b) a
  `ServiceLoader` registry, (c) a config subsection + an SPI validator, (d)
  discriminator-based selection — assessed independently per port.
- **FR5** — The check port SHALL gain a `provider` discriminator, a
  `Map<provider, CheckClientFactory>` registry discovered via `ServiceLoader`,
  a per-provider `CheckParamsValidator` SPI, and a validator for its
  `factory.check.<provider>` operator subsection — matching the tracker port.
- **FR6** — Check provider selection SHALL be per-check in the stage manifest
  (not per-project); a single stage MAY hold multiple `external` checks with
  different providers, each resolved independently.
- **FR7** — The `External` verify check SHALL carry `provider` + an opaque
  `params` map of flat JDK types (Jackson-free, like `Builtin.params`);
  `interval` / `timeout` / `timeoutClass` / `pinPaths` SHALL remain
  engine-common; no new `VerifyCheck` sealed variant SHALL be added.
- **FR8** — A check's identity for findings correlation SHALL be
  `provider` + `checkId`.
- **FR9** — A built-in `provider: http` external check SHALL ship in core and be
  discovered through the same registry as any other provider.
- **FR10** — The `http` check verdict SHALL be declarative: a `pass_when` (HTTP
  2xx by default, optional jsonPath / regex with `equals`) and an optional
  `pending_when` that polls until a terminal result, reusing the `External` poll
  loop; a one-shot probe is a degenerate poll.
- **FR11** — `http` check authorization SHALL resolve credentials by name through
  `SecretsProvider` and apply them as a request header at runtime.
- **FR12** — GitHub (tracker + checks + shared http core) SHALL be extracted into
  a discovered plugin jar built over a private core that is NOT part of
  `gnomish-plugin-api`; `inmemory` SHALL remain in core as the reference /
  test-double; the default distribution SHALL be core + a bundled github jar such
  that the built-in load path is identical to a third-party one.
- **FR13** — Migration: an `external:` manifest entry without an explicit
  `provider` SHALL default to `provider: github`, so pre-existing manifests keep
  working unchanged.
- **FR14** — The `gnomish-plugin-api` japicmp check SHALL run as a failing gate
  (flipped from report-only), breaking the build on an incompatible api change.
- **FR15** — The check SPI SHALL expose the adapter pin-path contribution hook
  (`ExternalCheckPinContributor`) consumed by the pin-check guard; the `http`
  provider SHALL contribute no pin paths, so only law-declared `pinPaths` pin
  it (vacuously passing when none are declared).
- **FR16** — Operator config SHALL support named per-vendor connection profiles
  (endpoint + credential name); a port subsection referencing a profile by name
  SHALL resolve its connection and credential from it, while provider selection
  stays independent per port.
- **FR17** — Every provider SPI (tracker and check) SHALL declare its credential
  environment-variable names, resolved from its configured connection — inline
  subsection or named profile — and the factory SHALL derive the
  child-environment scrub and never-allowlist set from these declarations alone;
  core SHALL NOT name any vendor credential constant. The `http` provider's
  credentials are named per-check in the manifest (FR11); each resolved check's
  credential name SHALL join the same declared set.

### Non-Functional — Observability

- **NFR-O1** — At startup the factory SHALL report the discovered provider set
  per port, including which jar contributed each provider, so a surprise
  provider is visible to the operator.

### Non-Functional — Reliability

- **NFR-R1** — Discovery SHALL be deterministic and fail-fast: a requested
  provider that is absent, or a discriminator that maps to two providers, SHALL
  fail with a clear named error, never a silent fallback.

### Non-Functional — Security

- **NFR-S1** — A committed stage manifest SHALL contain only `provider` and
  non-secret selectors; credentials SHALL be referenced by name and resolved
  through `SecretsProvider` at the factory level, never stored in the manifest.
- **NFR-S2** — The `http` check SHALL be governed by a factory-side egress
  allowlist owned by operator config (never the repo manifest): https-only;
  link-local, cloud-metadata, and RFC1918 targets blocked unless explicitly
  allowed; bounded redirects, response size, and time; and `${...}`
  interpolation permitted only from a whitelist.
- **NFR-S3** — The change SHALL state an explicit trust posture for loading
  provider jars, which execute in the privileged factory process with access to
  credentials (Q4); the sandbox port stays first-party and is NOT pluginized
  (DEC-25).

## Operator Experience Criteria

- **UX1** — Selecting an unknown or missing check `provider` in a manifest fails
  validation with a named, actionable error before the stage runs.
- **UX2** — An `http` target blocked by the egress allowlist is reported with the
  reason (scheme, address class, or missing allowlist entry).
- **UX3** — When one vendor serves two ports (github tracker + github checks),
  the config is ergonomic — a decision on shared connection/creds vs per-port
  duplication is made and documented (Q6).

## Success Metrics

- **M1** — Zero hardwired `Map.of(...)` provider registries remain; all provider
  resolution goes through `ServiceLoader` (grep + test).
- **M2** — Removing the github jar from the classpath disables all github
  providers with no core source change and the factory still starts.
- **M3** — One stage runs `command` + SonarQube-via-`http` + GitHub Actions —
  three providers — in a single verify chain (DEC-19 acceptance test).
- **M4** — Every pre-existing `external:` manifest without a `provider` still
  resolves (default github); no manifest edits required.
- **M5** — The japicmp failing gate is active and breaks the build on a
  deliberately incompatible api change.

## Open Questions

- **Q2** — github plugin: keep it as a module in this repo (we lead the vendor)
  or a separate repository? (explore Q2) → resolved in `design.md` (D7).
- **Q4** — Trust / safety of loading third-party jars that run in the privileged
  factory process with credentials — first-party-only, documented risk, or a
  signing/verification step? (explore Q4) → resolved in `design.md` (D6, NFR-S3).
- **Q6** — Config ergonomics when one vendor serves two ports: a shared
  connection/creds block vs per-port duplication? (explore Q6) → resolved in
  `design.md` (D8, UX3, FR16).
- **Q8** — The final `pass_when` expression language for the `http` check —
  jsonPath, regex, or both? (explore Q8) → resolved in `design.md` (D4, FR10).

## Impact

- **Ports** — the check port gains a new `CheckClientFactory` SPI (`provider()`,
  generic `create`, params + subsection validators, credential declaration +
  pin-contribution hooks) and a `ServiceLoader` registry; `TrackerAdapterFactory`
  gains `type()`, exposes its subsection validator, and its credential
  declaration becomes connection-aware, so tracker discovery moves to
  `ServiceLoader`; the `VerifyCheck.External` record gains `provider` + `params`;
  the engine keeps its single `ExternalCheckClient` port behind a
  provider-dispatching composite.
- **Modules** — `:adapters:github` becomes a discovered plugin bundle over a
  private core; `gnomish-plugin-api` gains the `CheckClientFactory` +
  `CheckParamsValidator` + `ExternalCheckPinContributor` SPI surface and
  `TrackerAdapterFactory.type()`/`subsectionValidator()`/the connection-aware
  credential declaration, and turns on the japicmp gate.
- **New code** — a core `http` check provider, the factory-side egress allowlist,
  per-port `ServiceLoader` registries, and per-provider validators.
- **Manifests** — `external:` entries gain an optional `provider`; absence
  defaults to github (FR13), so existing manifests are unaffected.
- **Downstream** — establishes the discovery pattern that ai-provider and the
  first-party sandbox path (change C) will later reuse (NG1, DEC-28).
