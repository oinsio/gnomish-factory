# Tasks: add-plugin-architecture

Order follows the design decisions. TDD throughout (`.claude/rules/testing.md`):
a failing Spock spec first, then the implementation. Depends on
`split-into-modules` being applied (needs `gnomish-plugin-api`). Keep the suite
green at each group boundary.

## 1. ServiceLoader discovery for the tracker port

- [ ] 1.1 Spec: a `ServiceLoader`-built tracker registry keyed by `type()`
  discovers a factory shipped via `META-INF/services` with no core edit (FR1, D1)
- [ ] 1.2 Add `type()` and `subsectionValidator()` (default: none) to
  `TrackerAdapterFactory` in `gnomish-plugin-api`, and reshape
  `credentialEnvVars()` to its connection-aware `credentialEnvVars(config)`
  form (D11); implement in github (`"github"` + its subsection validator) and
  inmemory, and update the `gnomish-plugin-api:sample` stand-in
  (`SampleTrackerAdapter`) to the widened interface (FR1, FR4, FR17, D1, D3,
  D11)
- [ ] 1.3 Refactor `GithubTrackerAdapterFactory` / `InMemoryTrackerAdapterFactory`
  to a public no-arg constructor; move `SecretsProvider` + config to method args
  (FR2, D2)
- [ ] 1.4 Replace both hardwired `Map.of(...)` registries in
  `TrackerAdapterConfiguration` — factories and subsection validators — with
  registries built from one `ServiceLoader.load(TrackerAdapterFactory.class)`
  pass, validators obtained from each factory (FR1, D1, M1)
- [ ] 1.5 Spec: registry build fails fast with a named error on a missing
  discriminator and on a duplicate discriminator (NFR-R1, D1)
- [ ] 1.6 Add `META-INF/services` entries for github and inmemory tracker
  factories (FR1, D1)

## 2. Check port to tracker parity

- [ ] 2.1 Spec: `CheckClientFactory.provider()` resolves an `ExternalCheckClient`
  from a `ServiceLoader` registry; github is one provider, not special (FR5, D3)
- [ ] 2.2 Define the `CheckClientFactory` SPI in `gnomish-plugin-api` —
  `provider()`, no-arg construction, generic `create(SecretsProvider,
  subsection)`, `paramsValidator()`, `subsectionValidator()` for the
  `factory.check.<provider>` operator subsection, the connection-aware
  credential declaration, `pinContributor()` (move
  `ExternalCheckPinContributor` into the api) — and adapt
  `GithubCheckClientFactory` to implement it (FR5, FR2, FR15, FR17, D2, D3,
  D11, D12)
- [ ] 2.3 Add the `CheckParamsValidator` SPI mirroring
  `TrackerSubsectionValidator`; ship the github validator exposed through its
  factory; derive the validator registry from the discovered factory registry
  (FR5, D1, D3)
- [ ] 2.4 Build the check-client registry via `ServiceLoader`, replacing the
  direct github wiring in `RunAssembler` with a provider-dispatching
  `ExternalCheckClient` composite behind the unchanged engine port — pin guard
  still wraps the seam, the interactive client still replaces it wholesale;
  fail-fast on missing/duplicate provider (FR5, FR6, NFR-R1, D1, D10)
- [ ] 2.5 Contract spec: a second (test-only) check provider passes the same
  port-level suite as github, proving no github special-casing (FR3, FR5)
- [ ] 2.6 Replace the hardwired `GithubCheckClientFactory.TOKEN_ENV_VAR` in
  `RunAssembler`'s credential-name wiring with the union of SPI-declared
  credential names from the selected providers; spec: a plugin's credential is
  scrubbed and cannot be allowlisted with no core constant naming it (FR17,
  D11, check-provider-model delta)
- [ ] 2.7 Spec: a malformed `factory.check.<provider>` operator subsection —
  both or neither connection forms, or a missing provider key — is a located
  `ConfigError` from the provider's `subsectionValidator()`, aggregated with
  other load errors (FR4, FR5, D12)

## 3. External check model: provider + params

- [ ] 3.1 Spec: `VerifyCheck.External` carries `provider` + opaque flat-typed
  `params`; `interval`/`timeout`/`timeoutClass`/`pinPaths` unchanged; no new
  sealed variant (FR7, D3, pipeline-config delta)
- [ ] 3.2 Add `provider` + `params` fields to `External`; update the exhaustive
  `switch` sites to read them without a new variant (FR7, D3)
- [ ] 3.3 Loader: parse `provider` (default `github` when absent) + `params`;
  invoke the provider's `CheckParamsValidator` at the seam, aggregating located
  `ConfigError`s (FR6, FR13, pipeline-config delta)
- [ ] 3.4 Spec: findings correlate on `provider`+`checkId`; same `checkId` under
  two providers stays distinct (FR8, check-provider-model)
- [ ] 3.5 Spec: a pre-existing `external:` manifest without `provider` still
  resolves to github unchanged (FR13, M4)
- [ ] 3.6 Spec: an `external` check naming a provider absent from the discovered
  registry is a located load error naming the provider and the discovered set —
  including the defaulted `github` when the github jar is absent, and uniformly
  across run modes (manual run included, D10) (UX1, FR13, M2, pipeline-config
  delta)

## 4. Built-in http check provider

- [ ] 4.1 Spec: `provider: http` resolves from the same registry as any provider
  (FR9, D4, http-check-provider)
- [ ] 4.2 Implement the core http `CheckClientFactory` + client; a one-shot 2xx
  probe passes without polling (FR9, FR10, D4)
- [ ] 4.3 Implement the `pass_when` grammar: default 2xx, optional jsonPath and/or
  regex extraction compared with `equals` (FR10, D4, Q8)
- [ ] 4.4 Implement `pending_when` reusing the `External` poll loop; timeout
  classifies via `timeoutClass`; absent `pending_when` = degenerate poll (FR10, D4)
- [ ] 4.5 Implement http auth: resolve credential by name via `SecretsProvider`,
  set the header at runtime; manifest holds only the name, and the resolved
  name joins the run's scrub / never-allowlist set (FR11, FR17, NFR-S1, D11)
- [ ] 4.6 Ship the github `CheckParamsValidator`'s http counterpart validating the
  http `params` shape at the seam (FR6, http-check-provider)
- [ ] 4.7 Spec: the http provider contributes no pin paths — only law-declared
  `pinPaths` pin it, vacuously passing when none are declared (FR15,
  http-check-provider)

## 5. Factory-side egress allowlist for http

- [ ] 5.1 Spec: an operator-config `EgressAllowlist` refuses a non-permitted host
  before any socket opens; a manifest cannot widen it (NFR-S2, D5)
- [ ] 5.2 Implement https-only + address-class blocks (link-local, cloud-metadata,
  RFC1918) unless explicitly allowlisted (NFR-S2, D5)
- [ ] 5.3 Enforce bounded redirects/size/time; re-check every redirect hop against
  the same rules (NFR-S2, D5)
- [ ] 5.4 Restrict `${...}` interpolation to the fixed engine-defined whitelist
  (`task.id`, `task.branch`, `attempt.commit`, `stage.name`); a non-whitelisted
  variable is a located validation error (NFR-S2, D5)
- [ ] 5.5 Spec: blocked targets report the reason (scheme / address class /
  missing allowlist entry) (UX2, factory-egress-allowlist)

## 6. Extract GitHub into a discovered plugin

- [ ] 6.1 Package `:adapters:github` (tracker + checks + shared http) as the
  plugin jar over its private HTTP core; expose only SPI factories/validators via
  `META-INF/services` (FR12, D7, github-plugin)
- [ ] 6.2 Spec: the api surface holds no github http-client/rate-limit/cache/retry
  type (FR12, github-plugin)
- [ ] 6.3 Spec: the bundled github jar loads through the identical discovery path
  a third-party jar uses — no built-in shortcut (FR12, D7)
- [ ] 6.4 Spec: removing the github jar disables all github providers with no core
  source change and the factory still starts; inmemory remains (FR12, M2)
- [ ] 6.5 Startup observability: report the discovered provider set per port,
  with the contributing jar, so loaded jars are visible (NFR-O1, NFR-S3, D6,
  plugin-discovery)

## 7. Config ergonomics + api gate

- [ ] 7.1 Implement named per-vendor connection profiles in operator config
  (`factory.connections.<name>`), referenced as `connection: <name>` from each
  port subsection; ports still select independently; hand the set of defined
  profile names to the subsection validators at the load seam (FR16, UX3, D8,
  D12, vendor-connection-profile delta)
- [ ] 7.2 Spec: a github tracker + github checks share one named connection
  profile without duplicating creds; a mixed-vendor config still works; an
  undefined profile reference is a located config error; a profile-resolved
  credential name is scrubbed and cannot be allowlisted like a declared
  constant (FR16, FR17, UX3, D8, D11)
- [ ] 7.3 Land the `gnomish-plugin-api` additions (the `CheckClientFactory` SPI
  with `CheckParamsValidator` + `ExternalCheckPinContributor` + its subsection
  validator and credential declaration,
  `TrackerAdapterFactory.type()`/`subsectionValidator()`/`credentialEnvVars(config)`,
  `External` provider/params), then flip japicmp from report-only to a failing
  gate against that baseline (FR14, D9, M5, plugin-api-contract delta)

## 8. Acceptance + traceability

- [ ] 8.1 Acceptance spec: one stage runs `command` + SonarQube-via-`http` +
  GitHub Actions — three providers — in a single verify chain (M3, FR6, DEC-19)
- [ ] 8.2 Grep gate: no `Map.of(...)` provider registry remains anywhere (M1)
- [ ] 8.3 Spec: assess the tracker and check ports against the four-point
  plugin-ready criterion — discriminator SPI, discovered registry, config
  subsection + validator, discriminator selection (FR4, plugin-discovery)
- [ ] 8.4 Verify every FR/NFR/UX of this change has an implementing spec or test
  (`.claude/rules/traceability.md`); run the full suite green
- [ ] 8.5 Recommend a Conventional Commits message for the change (agent never
  commits — `.claude/rules/process-invariants.md`)
