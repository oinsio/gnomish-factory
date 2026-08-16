# Design: add-plugin-architecture

## Context

Driven by G1–G5 and FR1–FR17 of `add-plugin-architecture`, on top of the module
tree from `split-into-modules` (change A). Today the tracker port is almost-SPI —
a `TrackerAdapterFactory` interface resolved from a registry keyed by the
config's `type`, though the factory itself exposes no `type()` yet — and that
registry is a hardwired `Map.of("github", …, "inmemory", …)` in
`TrackerAdapterConfiguration`, whose same file hardwires a `Map.of("github", …)`
validator registry. The check port lags further behind: there is no SPI factory
interface at all — `GithubCheckClientFactory` is a concrete class with a
vendor-specific `create(apiUrl, repo)`, no `provider` field, no registry, wired
in directly (`RunAssembler`). This change turns
almost-SPI into real discovery, brings the check port to tracker parity, adds a
generic `http` check with a factory-side egress guard, and extracts GitHub into a
discovered plugin as the acceptance test. It resolves the four open questions Q2,
Q4, Q6, Q8 carried from the explore notes. Depends on A for `gnomish-plugin-api`.

## Decisions

**D1 — `ServiceLoader` per port, registry built once at bootstrap (FR1, FR3, NFR-R1).**
`bootstrap` builds one `Map<discriminator, Factory>` per port by iterating
`ServiceLoader.load(TrackerAdapterFactory.class)` / `.load(CheckClientFactory.class)`
and keying on `type()` / `provider()` — which requires adding the missing
`type()` to `TrackerAdapterFactory` (an api addition, D9). A discriminator
served by zero factories or by two factories fails fast with a named error at
registry-build time, not at first use. Validator registries are not discovered
separately: each port's validators are obtained from its discovered factories
(D3), so both hardwired maps in `TrackerAdapterConfiguration` — factories and
subsection validators — are replaced by the one discovery pass and stay keyed
identically by construction. *Rationale:* one discovery mechanism for built-in
and third-party providers (DEC-8); collisions and gaps surface at startup, not
mid-task; validators cannot drift from factories.
*Alternative rejected:* Spring `@Component` scanning of factories — it couples
discovery to the DI container and can't load a jar a third party ships without a
Spring dependency.

**D2 — No-arg SPI constructors; dependencies as method args (FR2).**
`ServiceLoader` requires a public no-arg constructor, so factories take
`SecretsProvider` and resolved config as arguments to `create(...)` /
`validate(...)`, never in the constructor. The existing
`GithubTrackerAdapterFactory(SecretsProvider)` and
`GithubCheckClientFactory(SecretsProvider)` constructors are refactored to no-arg
+ method injection (this is the DEC-11 leak-fix from A's table applied at runtime).
*Alternative rejected:* `ServiceLoader.Provider` + a custom provider-lookup that
injects constructors — more machinery than passing two arguments.

**D3 — The check SPI is introduced, mirroring the tracker port; no new sealed variant (FR5, FR7, FR8, FR15).**
No check SPI factory exists yet, so this change *introduces* `CheckClientFactory`
in `gnomish-plugin-api`: `provider()`, a no-arg constructor (D2), a generic
`create(SecretsProvider, subsection)` returning the port's `ExternalCheckClient`,
a `paramsValidator()` (`CheckParamsValidator`, mirroring
`TrackerSubsectionValidator`), a `subsectionValidator()` for the operator-side
`factory.check.<provider>` subsection (D12), a connection-aware credential
declaration (D11), and the existing pin-contribution hook
`pinContributor()` — `ExternalCheckPinContributor` moves into the api, since the
verification-hardening pin guard unions its contribution with law-declared
`pinPaths`. The config split: connection config (endpoint, repo, credential
name) comes from the operator-config `factory.check.<provider>` subsection or a
named connection profile (D8); per-check selectors travel as the manifest's
`params` on `VerifyCheck.External` into `poll(...)`. `GithubCheckClientFactory`
is adapted to implement the SPI (its vendor-specific `create(apiUrl, repo)`
becomes the github implementation detail); `TrackerAdapterFactory` symmetrically
gains `type()` and `subsectionValidator()` (default: none — inmemory).
`VerifyCheck.External` gains two fields — `provider` (String) and `params`
(`Map<String,Object>` of flat JDK types, Jackson-free like `Builtin.params`) —
keeping `checkId`, `interval`, `timeout`, `timeoutClass`, `pinPaths`
engine-common. Findings correlation keys on `provider`+`checkId`.
*Rationale:* the engine already loops a heterogeneous `List<VerifyCheck>` with an
exhaustive switch; a fifth variant would force every switch to change for no gain
(DEC-16). *Alternative rejected:* a new `ProviderExternal` sealed variant —
breaks every exhaustive switch and duplicates the poll machinery. *Alternative
rejected:* a standalone `ServiceLoader` SPI per validator — a second registry per
port that can drift from the factory registry, and it would force a
discriminator onto the plain functional `TrackerSubsectionValidator`.

**D4 — Q8 resolved: `pass_when` supports both jsonPath and regex (FR10).**
The http verdict language accepts a `pass_when` with an optional `jsonPath`
selector, an optional `regex` extraction, and an `equals` comparison; default is
HTTP 2xx. Both extractors are offered because CI/quality REST APIs split between
JSON status bodies (jsonPath) and plain-text/heterogeneous bodies (regex);
supporting only one would force an adapter for the other. `pending_when` uses the
same extractor grammar to detect a non-terminal state and drives the reused
`External` poll loop; absent `pending_when` = a one-shot probe. *Alternative
rejected:* jsonPath-only — excludes non-JSON endpoints; regex-only — brittle on
structured JSON. *Alternative rejected:* a full expression DSL — over-built for a
pass/pending predicate.

**D5 — Factory-side egress allowlist for the http check (NFR-S2).**
A new `EgressAllowlist`, owned by operator config (never the repo manifest),
guards the http check: https-only; deny link-local / cloud-metadata / RFC1918
unless explicitly allowlisted; bounded redirects, response size, and total time;
every redirect hop re-checked against the same rules; `${...}` interpolation
drawn only from a fixed, engine-defined whitelist — `${task.id}`,
`${task.branch}`, `${attempt.commit}`, `${stage.name}` — enough to address a
run/branch-scoped CI result (M3) while neither the manifest nor operator config
can widen it. This is distinct from `EgressGuard`, which
watches the agent sandbox (mitmdump) and not the factory's own outbound calls
(DEC-23). *Rationale:* the http check is a factory-process outbound call with
secrets attached — an unguarded SSRF/exfil vector. *Alternative rejected:*
reuse `EgressGuard` — wrong layer; it sits in the sandbox, not the factory
process.

**D6 — Q4 resolved: first-party trust posture, no isolation, observability instead (NFR-S3).**
A discovered jar runs in the privileged factory process with credential access,
so a hostile jar is game-over regardless of guards short of classloader/OS
isolation — which A's NG7 and this change's NG3 keep out of scope. The posture is
therefore: **only trusted (first-party or operator-vetted) jars go on the
classpath**, documented as an operator responsibility, and the factory makes the
loaded provider set observable at startup (which jars contributed which
providers) so a surprise provider is visible. Signing/verification and a managed
marketplace are explicit non-goals (NG5). The sandbox port stays first-party and
un-pluginized (DEC-25) — a self-declared `CapabilityPassport` from an untrusted
jar would be a trust hole. *Alternative rejected:* classloader-isolated plugins
now — large machinery for a threat the trusted-classpath posture already bounds;
deferred with A-NG7.

**D7 — Q2 resolved: github plugin stays a module in this repo (FR12).**
`:adapters:github` remains a module in this monorepo, built as the plugin jar over
its private HTTP core, rather than a separate repository. *Rationale:* we lead the
github integration; a monorepo module keeps it refactorable in lockstep with the
api and gives the plugin path a real in-repo exemplar without cross-repo release
friction. The separate-repo option stays open for genuine third parties — the
`ServiceLoader` contract is identical either way. *Alternative rejected:* split to
its own repo now — premature; adds release coordination for a vendor we own, with
no isolation benefit under D6's trusted-classpath posture.

**D8 — Q6 resolved: shared vendor connection block, per-port selection (UX3, FR16).**
When one vendor serves two ports (github tracker + github checks), config uses a
**shared per-vendor connection/credentials block** referenced by name from each
port's subsection, rather than duplicating connection + creds per port. The
profile lives in operator config (`factory.connections.<name>`: endpoint,
credential name — the operator owns credentials); a port subsection — the
repo-side `tracker.github` or the factory-side `factory.check.github` —
references it as `connection: <name>` instead of inlining those keys, declaring
exactly one of the two forms. Each port still selects its provider independently
(D1); they simply share a named connection profile. A profile carries the
credential *name* only, never a value; how a profile-resolved name reaches the
child-environment scrub set is D11, and how a `connection:` reference is
validated at load is D12. *Rationale:* avoids
credential duplication and drift while keeping port selection independent
(DEC-13). *Alternative rejected:* full per-port duplication — two copies of the
same PAT/endpoint to keep in sync; *Alternative rejected:* implicit "same vendor
⇒ shared everything" — hides the coupling and breaks the mixed-vendor case.

**D9 — japicmp flips to a failing gate at the api version B consumes (FR14).**
B is the first external consumer of `gnomish-plugin-api`, so `published-api-
conventions` (from A/D10) flips japicmp from report-only to a failing gate. The
gate turns on against the api version this change ships (resolving A's residual
"which version"): the surface additions here — the `CheckClientFactory` SPI
(with `CheckParamsValidator`, `ExternalCheckPinContributor`, the subsection
validator and the connection-aware credential declaration of D11/D12),
`TrackerAdapterFactory.type()` + `subsectionValidator()` + the reshaped
`credentialEnvVars(config)`, the `External`
provider/params fields — land first, then the gate is armed against that
baseline. The stable `plugin-api-contract` spec's report-only wording is
superseded by this change's delta. *Alternative rejected:* keep report-only through B — leaves the
compatibility promise unenforced exactly when a real consumer appears.

**D10 — Engine port unchanged: a provider-dispatching composite (FR3, FR6).**
The engine keeps its single `ExternalCheckClient` in `EnginePorts`; the
composition root injects a dispatching composite that routes each
`VerifyCheck.External` to the client resolved from the registry by
`check.provider`, constructing clients lazily so dormant providers stay
unexercised (FR3). The pin-check guard keeps wrapping the external seam
(verification-hardening), and manual-run's `InteractiveExternalCheckClient`
keeps replacing that whole seam regardless of provider — both unchanged.
Provider-existence validation stays uniform across run modes: manual run's
interactive client replaces the seam wholesale, but the manifest must still
name only discovered providers — a mode-dependent validation would hide a
broken manifest until a real run.
*Rationale:* per-check provider selection is wiring, not engine semantics; no
engine spec changes. *Alternative rejected:* widening the engine port to a
registry/resolver — leaks discovery into the domain and touches every engine
spec for a composition concern. *Alternative rejected:* skipping
provider-existence validation in manual run — the same manifest would load in
one mode and fail in another, breaking load-time determinism (NFR-R1).

**D11 — Credential declaration through the SPI, connection-aware (FR17, NFR-S1).**
Core currently names `GithubCheckClientFactory.TOKEN_ENV_VAR` directly when
building the child-environment credential list (`RunAssembler`), and
`TrackerAdapterFactory.credentialEnvVars()` is a no-arg method returning fixed
constants. Both break under this change: an extracted github plugin (FR12)
means core can no longer name a vendor constant, and a connection profile (D8)
can supply the credential name as config data a no-arg method cannot see. So
the credential declaration becomes a member of both SPI factories, taking the
resolved connection config (inline subsection or named profile) —
`credentialEnvVars(config)` — and the composition root unions the declarations
of the active tracker factory and every check provider the manifest selects,
deriving the scrub + never-allowlist set from that union alone. The built-in
`http` provider is the one case whose credential names arrive per-check in
manifest `params` (FR11) rather than from a connection subsection; the loader
adds each resolved http check's named credential to the same declared set, so
a manifest-named credential is equally barred from the passthrough allowlist
(worst case, a manifest naming an operator passthrough variable as its
credential fails startup visibly — fail-closed, never a leak). No vendor
credential constant remains in core. *Rationale:* the scrub set must follow
whatever names the configuration actually resolves, and only the provider
knows them. *Alternative rejected:* keeping constants and forbidding profiles
from renaming credentials — reintroduces the per-vendor coupling profiles
exist to remove, and leaves core naming a plugin's constant. *Alternative
rejected:* scrubbing every profile credential name globally regardless of
provider selection — over-scrubs and hides a mis-wired selection instead of
failing on it.

**D12 — Operator-subsection validation and the profile-reference seam (FR4, FR5, FR16).**
Criterion (c) of FR4 requires a config subsection plus an SPI validator per
port. For the check port the manifest-side `params` are covered by
`CheckParamsValidator` (D3); the operator-side `factory.check.<provider>`
subsection gets its own validator exposed by the same factory —
`subsectionValidator()`, symmetric to
`TrackerAdapterFactory.subsectionValidator()` — validating the connection form
(exactly one of inline keys or `connection: <name>`, D8) and the provider's
own keys. Because a `connection:` reference can appear in the repo-side
`tracker.github` subsection while profiles live in operator config, subsection
validators receive the set of defined profile names alongside the subsection
content; the composition root hands that set to the loader, so an undefined
reference is a located load error aggregated with the rest
(vendor-connection-profile capability), never a first-use failure.
*Rationale:* validation stays at the seam that already aggregates located
`ConfigError`s, and the cross-source knowledge — which profiles exist —
travels as plain data. *Alternative rejected:* validating profile references
only at adapter construction — turns a config typo into a mid-take failure
instead of a load error. *Alternative rejected:* a separate discovered
validator SPI — D3's objection: a second registry per port that can drift from
the factory registry.

## Risks / Trade-offs

- **`ServiceLoader` ordering / duplicate discriminators across jars** → D1 builds
  the registry eagerly and fails fast on collisions with a named error; startup,
  not mid-task, surfaces the problem.
- **http check as an SSRF/exfil hole** → D5's operator-owned allowlist with
  https-only, address-class blocks, redirect re-checks, and a whitelisted
  interpolation set; the manifest can never widen it.
- **Trusted-classpath posture depends on operator discipline** → D6 documents the
  responsibility and makes the loaded provider set observable at startup; deeper
  isolation is deferred (NG3, NG5), not silently assumed.
- **Default-to-github migration hides an intended non-github check** → the loader
  records the defaulted `provider: github` explicitly (FR13) so it is visible in
  the typed model and reports, not a silent guess.
- **api surface still shifting when the gate arms** → D9 lands the additions
  first, then arms japicmp against that exact baseline, so the flip is a one-line
  change against a settled surface.

## Open Questions

All four questions carried into this change are resolved:
- **Q2** — Resolved by D7: github plugin stays a module in this repo.
- **Q4** — Resolved by D6: trusted-classpath posture + startup observability; no
  isolation now (NG3, NG5).
- **Q6** — Resolved by D8: shared per-vendor connection block, per-port selection.
- **Q8** — Resolved by D4: `pass_when` supports both jsonPath and regex, with
  `pending_when` reusing the same grammar.

Residual (not blocking): whether a later change adds signed-jar verification once
genuinely third-party (non-first-party) providers appear — tracked with ai-provider
pluginization (proposal NG1).
