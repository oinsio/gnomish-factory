# Change: add-sandbox-hardening

Coordination: two active changes land first and their merged text is the
rebase base for this change's deltas — `fix-denial-attribution-durability`
(same `sandbox-egress` requirement "Denials are captured as structured
findings") and `add-serve-sandbox-lifecycle` (same `execution-environment`
"Container adapter" requirement; it replaces startup orphan cleanup with
the `sandbox-lifecycle` ownership scheme). This change *creates* the
`sandbox-provisioning` capability that the depot and executor changes
(C/D/E, `add-artifact-depot`) extend — they depend on it landing first,
not merely "compose" with it. The depot change extends the self-check via
a separate requirement; align the requirement home when the two meet.

## Why

Change A (`add-sandbox-core`) built the sandbox core and left four gaps
(its NG1/NG2/NG6): an allowed host is still an open door — data can leave
through the AI provider on a *foreign* account (documented Anthropic
incident, threat #16 of `docs/sandbox-threat-registry.md`) or via domain
fronting (#17); the real AI key still enters the box; the provider's
server-side web tools fetch arbitrary URLs with the provider's
infrastructure, bypassing the guard (#45); and every project sandbox needs
a hand-built operator image because there is no `.gnomish/` setup surface.
This change is ladder step B: it hardens the guard (TLS interception,
credential injection, L7 rules), puts an AI gateway with per-segment
virtual keys between the gnome and providers, and adds the setup-script +
snapshot provisioning surface. Threat-registry items closed: #11, #15
(L7), #16, #17, #31, #45.

## What Changes

- **ADDED**: AI gateway (mature local OSS gateway, LiteLLM-class) between
  box and providers: per-segment virtual keys with budget, expiry, and
  model restriction; revocation; request rate limit; per-task cost
  accounting as an independent source of truth; server-side tool policy
  (provider web tools stripped unless the stage explicitly allows them);
  protocol translation enabling a different provider/model per stage.
- **ADDED**: sandbox provisioning surface: `.gnomish/setup.sh` from the
  target repo executed only inside a provisioning container; post-setup
  snapshot cache (`docker commit` exists only in the provisioning flow;
  fingerprint-named images, TTL, manual rebuild, label-based cleanup).
- **MODIFIED**: egress guard gains TLS interception mode on the CA import
  seam baked by change A: foreign auth headers stripped and factory
  credentials injected outside the box (account pinning; optionally no key
  inside the box at all), per-host L7 path/method rules, passthrough
  exceptions for pinned hosts. Self-check extended to prove interception
  and header-stripping actually work.
- **MODIFIED**: stage `Mechanism` declares the model/provider for the
  stage and whether server-side provider tools are allowed; the factory
  issues a virtual key restricted accordingly. The pipeline-config
  "tighten-only" rule gains an explicit carve-out for the tool allowance
  (repo-side widening of the model's tool surface only, effective only
  under the operator-enabled tool-policy layer).
- **MODIFIED**: task environment image resolution: operator-fixed image is
  joined by project snapshots produced by provisioning; setup-phase
  secrets never survive into the gnome phase.
- **REMOVED**: nothing; every mechanism here is an operator-enabled layer
  on top of change A defaults.

## Capabilities

### New Capabilities

- `ai-gateway`: virtual key issuance/revocation per segment, budgets and
  expiry, model restriction, rate limits, cost accounting, server-side
  tool policy, multi-provider translation, fail-closed behavior, control
  plane unreachable from the box.
- `sandbox-provisioning`: the `.gnomish/setup.sh` surface, the
  provisioning container flow, snapshot naming/TTL/rebuild/cleanup, the
  no-snapshot-of-gnome-touched-boxes invariant, setup-secret hygiene.

### Modified Capabilities

- `sandbox-egress`: TLS interception mode, credential injection and
  foreign-auth stripping, L7 rules, passthrough exceptions, extended
  startup self-check.
- `pipeline-config`: stage `Mechanism` gains model/provider declaration
  and a server-tools allowance flag; `.gnomish/setup.sh` becomes a
  recognized repo surface; the tighten-only rule gains the tool-allowance
  carve-out.
- `execution-environment`: image resolution consults the project snapshot
  produced by provisioning; the environment receives a virtual key (or a
  sentinel when header injection is active) instead of a real provider
  key.

## Goals

- G1: no real AI-provider credential exists inside the box; blast radius
  of any in-box leak is one revocable per-segment key with a budget
  ceiling — or zero keys when header injection is enabled.
- G2: allowed egress destinations are no longer open doors: requests to
  the AI provider carry only factory-owned credentials, and L7 rules
  narrow what an allowed host permits.
- G3: every model request path is policy-checked, including provider
  server-side tools that would otherwise bypass the guard.
- G4: a target repo declares its toolchain in `.gnomish/setup.sh` and gets
  a cached, seconds-fast sandbox start without operator image work.
- G5: all hardening is fail-closed and layered on change A without port or
  contract changes (gateway plugs into the existing base-url/auth-token
  seam; interception is a guard mode switch, not a tool swap).

## Non-Goals

- NG1: artifact depot (Nexus-class registry proxy, version cooldown,
  quarantine) — the `add-artifact-depot` change, which depends on this one.
- NG2: Colima VM, cloud/k8s, and GHA executors — changes C/D/E.
- NG3: Vault-class SecretsProvider adapter and OIDC bootstrap — arrives
  with changes D/E; the gateway master key uses the existing env adapter.
- NG4: judge-quality evaluation of cross-provider translation — a spike
  during implementation decides whether non-Anthropic judge stages are
  recommended, but no quality guarantee is in scope.
- NG5: prompt/response content filtering or PII guardrails on the gateway
  — only credential, tool, and budget policy.

## Users & Scenarios

- U1: operator enables the gateway; every task runs on budget-capped
  virtual keys, and the task report shows exact spend from the ledger.
- U2: operator serving untrusted task sources enables TLS interception: an
  injected prompt exfiltrating via a foreign provider account produces a
  stripped header and a findings entry, not a leak.
- U3: repo maintainer adds `.gnomish/setup.sh` (pinned versions); the
  first task provisions and snapshots, subsequent tasks start in seconds.
- U4: a stage declares a review model of another provider; the factory
  issues a key restricted to it and the gateway translates the protocol —
  the gnome CLI is unchanged.
- U5: reviewer sees per-task spend anomalies (e.g., 10× stage-typical) and
  stripped-tool/stripped-header events as findings in the task report.

## Requirements

### Functional

- FR1: the factory SHALL integrate an AI gateway such that the box reaches
  providers only through it; the real provider key SHALL live only in
  gateway config (via the SecretsProvider port), never in the box.
- FR2: before each environment segment the factory SHALL issue a virtual
  key with a budget ceiling, an expiry, and a restriction to the
  stage-declared model(s); the key SHALL be revoked at segment end, task
  completion, and escalation.
- FR3: budget exhaustion or key expiry SHALL surface as a distinct,
  operator-visible budget failure, not a silent provider error; it SHALL
  NOT burn a stage attempt as a quality failure.
- FR4: the gateway SHALL enforce a request rate limit per key; the budget
  ceiling doubles as the volume cap (tokens = bytes).
- FR5: the gateway SHALL remove provider server-side tools (web search,
  URL fetch) from requests unless the stage `Mechanism` explicitly allows
  them; each removal SHALL be recorded.
- FR6: per-task spend SHALL be read from the gateway ledger and attached
  to the task report as the authoritative cost record.
- FR7: a stage `Mechanism` SHALL be able to declare the model/provider for
  its executor; the factory SHALL issue the virtual key restricted to that
  declaration and the gateway SHALL translate the wire protocol when the
  provider differs from the agent CLI's native one.
- FR8: the guard SHALL support a TLS interception mode using the CA import
  seam baked into images by change A, with per-host passthrough exceptions
  for certificate-pinned tools; interception SHALL NOT buffer streaming
  (SSE) responses.
- FR9: in interception mode the guard SHALL strip any auth credential not
  issued by the factory from requests to AI-provider hosts and SHALL
  inject the factory-owned credential outside the box; with injection
  active the box MAY hold only a sentinel value.
- FR10: the guard SHALL support per-host L7 rules (path prefixes, methods)
  for allowlisted hosts; rules are operator-owned config.
- FR11: the environment startup self-check SHALL additionally prove, when
  the respective mode is enabled, that interception is active, that a
  foreign auth header does not survive to the upstream, and that a
  disallowed server-side tool is stripped; any probe failure SHALL be an
  infrastructure failure preventing task start.
- FR12: `.gnomish/setup.sh` from the target repo SHALL execute only inside
  a provisioning container created from the base image — never on the host
  and never in a box a gnome has touched.
- FR13: provisioning SHALL snapshot the post-setup state as an image named
  by project plus a fingerprint of setup.sh content and base-image digest;
  materialization SHALL reuse a matching snapshot, rebuild on fingerprint
  mismatch, TTL expiry (`factory.sandbox.snapshot-max-age`, default 7d),
  or explicit operator rebuild (`gnomish env rebuild` / `--rebuild-env`).
- FR14: the snapshot operation SHALL exist only in the provisioning flow;
  the task-environment port SHALL remain snapshot-free (invariant from
  change A), so a gnome-touched box can never be persisted.
- FR15: provisioning SHALL label its snapshots and containers with
  provisioning-scoped factory labels (snapshot images are project-scoped
  and outside the task-keyed `sandbox-lifecycle` ownership scheme, per
  that change's non-goal) and SHALL clean up superseded and orphaned
  provisioning objects by label — this change owns that cleanup.
- FR16: any secret available during provisioning SHALL be absent from the
  gnome phase: setup-phase env and credential material SHALL NOT be
  present in task-environment exec, and the snapshot SHALL NOT contain
  secret values.

### Non-Functional

- NFR-S1: gateway and guard SHALL be local, factory-owned services;
  gateway/guard config, master keys, and TLS private keys SHALL be
  unreachable from inside the box.
- NFR-S2: intercepted traffic SHALL be logged as metadata only — never
  request/response bodies, never credentials (the guard injects them and
  MUST NOT log them).
- NFR-S3: gateway selection SHALL apply the maturity criteria from the
  explore notes (fail-closed on error, no default admin credentials,
  active maintenance) — threat #31.
- NFR-R1: gateway or interception failures SHALL be infrastructure
  failures (Resilience4j retries, no stage attempt burned), consistent
  with change A failure classes; key issuance failure prevents task start
  fail-closed.
- NFR-R2: provisioning SHALL be idempotent and crash-safe: an interrupted
  provisioning run leaves only labeled garbage that cleanup reclaims;
  concurrent tasks of one project SHALL NOT corrupt the snapshot cache.
- NFR-O1: the new denial sources this change adds — stripped headers,
  stripped tools, L7 denials, and budget events — SHALL be logged and
  attached to the task report through the existing verdict-independent
  findings slot delivered by `fix-denial-report-attachment` (a denial is
  visible without flipping the round verdict); spend anomalies relative
  to stage-typical volume SHALL be flagged.
- NFR-P1: interception SHALL NOT add perceptible latency to streamed model
  responses; snapshot reuse SHALL keep environment startup in seconds
  (provisioning cost paid once per fingerprint).
- NFR-C1: the virtual-key budget SHALL bind to the factory's per-task
  budget config, giving one enforcement point for task cost; gateway
  ledger totals reconcile with tracker-reported cost.

## Operator Experience Criteria

- UX1: enabling the gateway, interception, L7 rules, and tool policy are
  each independent factory-config switches with safe defaults (off =
  change A behavior); no target-repo change is required to enable any.
- UX2: a budget-exhausted task reads as "budget exceeded: spent X of Y" in
  the tracker escalation, not as a cryptic provider error.
- UX3: docs state plainly which tools break under interception
  (certificate pinning) and how to add a passthrough exception; images
  built with the factory CA in `ca/` need no rebuild to enable
  interception, while images built with the (previously valid) empty
  `ca/` need one rebuild with the CA present — docs cover both paths.
- UX4: docs instruct repo maintainers to pin versions in setup.sh
  (lockfile discipline), making the snapshot TTL a safety net rather than
  the working mechanism.

## Success Metrics

- M1: E2E: a request from the box carrying a foreign provider key reaches
  the upstream mock with the factory credential only; the stripped header
  appears as a finding.
- M2: E2E: a task exceeding its virtual-key budget stops with the budget
  failure surfaced in the tracker; gateway ledger spend matches the report.
- M3: E2E: second task of a project starts from the snapshot without
  re-running setup.sh; changing setup.sh content triggers exactly one
  rebuild.
- M4: contract test: a request declaring a server-side web tool on a stage
  without the allowance reaches the provider mock with the tool absent.
- M5: self-check E2E: with interception enabled, all extended probes pass
  in container mode; disabling the guard's stripping makes the self-check
  fail the task start.
- M6: E2E assertion: no secret value present during provisioning is
  observable in the gnome phase (env, filesystem, or snapshot layers).

## Open Questions

- Q1: gateway product choice (LiteLLM default vs Bifrost vs
  header-injection-only) — verify current state at implementation start.
- Q2: Matchlock spike (ready-made microVM sandbox with built-in MITM
  injection) as an alternative to self-built interception — evaluate
  before building the interception layer.
- Q3: cross-provider judge quality through protocol translation — spike
  (NG4); transport is expected to work, grading quality is unknown.
- Q4: L7 rule expression format — resolved in design (D4: guard-native
  config, no DSL).
- Q5: virtual key per task vs per environment segment — resolved in
  design (D3: per segment).

## Impact

- New always-on local service (the gateway) joins the operator's stack;
  guard (mitmproxy) gains an interception mode — no new proxy tool.
- `adapters/` gains gateway client and provisioning components; the
  environment port and its adapters are unchanged (G5).
- Stage-manifest schema and pipeline-config validation extend for model
  declaration and tool allowance (three active changes extend `Mechanism`
  — schema merge point with `add-sandbox-cloud-executor`).
- New factory-config surface: gateway settings, interception toggle, L7
  rules, snapshot TTL.
- Depends on change A (CA import seam in the reference image, mitmdump
  guard, env-allowlist seam, SecretsProvider port), on
  `fix-denial-attribution-durability` and `add-serve-sandbox-lifecycle`
  landing first; `docs/glossary.md` gains entries for the new terms.
