# Tasks: add-plugin-architecture

Order follows the design decisions. TDD throughout (`.claude/rules/testing.md`):
a failing Spock spec first, then the implementation. Depends on
`split-into-modules` being applied (needs `gnomish-plugin-api`). Keep the suite
green at each group boundary.

## 1. ServiceLoader discovery for the tracker port

- [x] 1.1 Spec: a `ServiceLoader`-built tracker registry keyed by `type()`
  discovers a factory shipped via `META-INF/services` with no core edit (FR1, D1)
- [x] 1.2 Add `type()` and `subsectionValidator()` (default: none) to
  `TrackerAdapterFactory` in `gnomish-plugin-api`, and reshape
  `credentialEnvVars()` to its connection-aware `credentialEnvVars(config)`
  form (D11); implement in github (`"github"` + its subsection validator) and
  inmemory, and update the `gnomish-plugin-api:sample` stand-in
  (`SampleTrackerAdapter`) to the widened interface (FR1, FR4, FR17, D1, D3,
  D11)
- [x] 1.3 Refactor `GithubTrackerAdapterFactory` / `InMemoryTrackerAdapterFactory`
  to a public no-arg constructor; move `SecretsProvider` + config to method args
  (FR2, D2)
- [x] 1.4 Replace both hardwired `Map.of(...)` registries in
  `TrackerAdapterConfiguration` — factories and subsection validators — with
  registries built from one `ServiceLoader.load(TrackerAdapterFactory.class)`
  pass, validators obtained from each factory (FR1, D1, M1)
- [x] 1.5 Spec: registry build fails fast with a named error on a missing
  discriminator and on a duplicate discriminator (NFR-R1, D1)
- [x] 1.6 Add `META-INF/services` entries for github and inmemory tracker
  factories (FR1, D1)

## 2. Check port to tracker parity

- [x] 2.1 Spec: `CheckClientFactory.provider()` resolves an `ExternalCheckClient`
  from a `ServiceLoader` registry; github is one provider, not special (FR5, D3)
- [x] 2.2 Define the `CheckClientFactory` SPI in `gnomish-plugin-api` —
  `provider()`, no-arg construction, generic `create(SecretsProvider,
  subsection)`, `paramsValidator()`, `subsectionValidator()` for the
  `factory.check.<provider>` operator subsection, the connection-aware
  credential declaration, `pinContributor()` (move
  `ExternalCheckPinContributor` into the api) — and adapt
  `GithubCheckClientFactory` to implement it (FR5, FR2, FR15, FR17, D2, D3,
  D11, D12)
- [x] 2.3 Add the `CheckParamsValidator` SPI mirroring
  `TrackerSubsectionValidator`; ship the github validator exposed through its
  factory; derive the validator registry from the discovered factory registry
  (FR5, D1, D3)
- [x] 2.4 Build the check-client registry via `ServiceLoader`, replacing the
  direct github wiring in `RunAssembler` with a provider-dispatching
  `ExternalCheckClient` composite behind the unchanged engine port — pin guard
  still wraps the seam, the interactive client still replaces it wholesale;
  fail-fast on missing/duplicate provider (FR5, FR6, NFR-R1, D1, D10)
- [x] 2.5 Contract spec: a second (test-only) check provider passes the same
  port-level suite as github, proving no github special-casing (FR3, FR5)
- [x] 2.6 Replace the hardwired `GithubCheckClientFactory.TOKEN_ENV_VAR` in
  `RunAssembler`'s credential-name wiring with the union of SPI-declared
  credential names from the selected providers; spec: a plugin's credential is
  scrubbed and cannot be allowlisted with no core constant naming it (FR17,
  D11, check-provider-model delta)
- [x] 2.7 Spec: a malformed `factory.check.<provider>` operator subsection —
  both or neither connection forms, or a missing provider key — is a located
  `ConfigError` from the provider's `subsectionValidator()`, aggregated with
  other load errors (FR4, FR5, D12)

  Deviations recorded while applying group 2:
  - `FactoryProperties.check` became `Map<provider, Map<String,Object>>`; the
    vendor-shaped `Check.Github` record and its throwing both-or-neither
    constructor are gone from core, that rule now living in
    `GithubCheckSubsectionValidator` as located `ConfigError`s (FR4, D12). The
    aggregating gate is `CheckClientConfiguration`, which fails startup listing
    every provider's problems at once
  - Under FR3's lazy client construction a provider's credential now resolves at
    its first selected poll rather than at assembly, so the FR26 fail-closed
    moment moved from wiring to first use. Still fail-closed, still naming the
    secret; covered by `ProviderDispatchingExternalCheckClientSpec`
  - `VerifyCheck.External` carries no `provider` yet (task 3.2), so the composite
    takes its selection as an injected function; the composition root binds
    `defaultProviderOf()` (= `github`, FR13's migration default) and task 3.3
    replaces it with the parsed field
  - `:bootstrap` now depends on `:adapters:github` as `runtimeOnly` — no core
    production source names a github type any more, which the dependency-analysis
    gate enforces from here on (an early down payment on FR12)

## 3. External check model: provider + params

- [x] 3.1 Spec: `VerifyCheck.External` carries `provider` + opaque flat-typed
  `params`; `interval`/`timeout`/`timeoutClass`/`pinPaths` unchanged; no new
  sealed variant (FR7, D3, pipeline-config delta)
- [x] 3.2 Add `provider` + `params` fields to `External`; update the exhaustive
  `switch` sites to read them without a new variant (FR7, D3)
- [x] 3.3 Loader: parse `provider` (default `github` when absent) + `params`;
  invoke the provider's `CheckParamsValidator` at the seam, aggregating located
  `ConfigError`s (FR6, FR13, pipeline-config delta)
- [x] 3.4 Spec: findings correlate on `provider`+`checkId`; same `checkId` under
  two providers stays distinct (FR8, check-provider-model)
- [x] 3.5 Spec: a pre-existing `external:` manifest without `provider` still
  resolves to github unchanged (FR13, M4)
- [x] 3.6 Spec: an `external` check naming a provider absent from the discovered
  registry is a located load error naming the provider and the discovered set —
  including the defaulted `github` when the github jar is absent, and uniformly
  across run modes (manual run included, D10) (UX1, FR13, M2, pipeline-config
  delta)

## 4. Built-in http check provider

- [x] 4.1 Spec: `provider: http` resolves from the same registry as any provider
  (FR9, D4, http-check-provider)
- [x] 4.2 Implement the core http `CheckClientFactory` + client; a one-shot 2xx
  probe passes without polling (FR9, FR10, D4)
- [x] 4.3 Implement the `pass_when` grammar: default 2xx, optional jsonPath and/or
  regex extraction compared with `equals` (FR10, D4, Q8)
- [x] 4.4 Implement `pending_when` reusing the `External` poll loop; timeout
  classifies via `timeoutClass`; absent `pending_when` = degenerate poll (FR10, D4)
- [x] 4.5 Implement http auth: resolve credential by name via `SecretsProvider`,
  set the header at runtime; manifest holds only the name, and the resolved
  name joins the run's scrub / never-allowlist set (FR11, FR17, NFR-S1, D11)
- [x] 4.6 Ship the github `CheckParamsValidator`'s http counterpart validating the
  http `params` shape at the seam (FR6, http-check-provider)
- [x] 4.7 Spec: the http provider contributes no pin paths — only law-declared
  `pinPaths` pin it, vacuously passing when none are declared (FR15,
  http-check-provider)

  Deviations recorded while applying group 4:
  - `jsonPath` is a deliberate subset — an optional `$` root, dot-separated field
    names, `[n]` indexes — implemented over Jackson rather than by adding a
    JSONPath dependency. It addresses a status field in a status document, which
    is what a pass/pending predicate needs (D4 rejects a full expression DSL for
    the same reason); anything beyond it is regex territory (D4 ships both)
  - The manifest keys are `pass-when` / `pending-when` / `json-path`, matching the
    hyphenated style of every other manifest key, not the prose's `pass_when`
  - Task 4.5's "joins the run's scrub / never-allowlist set" needed an SPI
    addition D11 implies but task 7.3 did not list:
    `CheckClientFactory.checkCredentialEnvVars(params)` — the per-check half of
    the credential declaration, defaulting to empty, which only the http provider
    overrides. `CheckProviderSeam.checkCredentialEnvVars(definition, registry)`
    unions it over the loaded pipeline; core still interprets no params. Both
    `ChildEnvAllowlist` construction sites take it, which is why
    `ContainerSupportFactory.create` gained the `PipelineDefinition` argument.
    Task 7.3's api-surface list is extended accordingly
  - `HttpCheckParams` / `HttpCheckCondition` are final classes, not the records
    their shape suggests: every mutation of a record here came back RUN_ERROR
    (PIT's minion crashing on the JVMTI record-redefinition restriction,
    hcoles/pitest#1285), which would have dropped both types out of the mutation
    gate. `.claude/rules/testing.md` allows `@DoNotMutate` for that case; dropping
    the record shape keeps the gate real instead, and `:adapters` stays at 100%
  - The JDK exchange follows no redirects: a redirect is a target the manifest
    did not declare. NFR-S2's bounded, re-checked redirect hops belong to the
    egress allowlist (group 5), which is the component that can re-check them

## 5. Factory-side egress allowlist for http

- [x] 5.1 Spec: an operator-config `EgressAllowlist` refuses a non-permitted host
  before any socket opens; a manifest cannot widen it (NFR-S2, D5)
- [x] 5.2 Implement https-only + address-class blocks (link-local, cloud-metadata,
  RFC1918) unless explicitly allowlisted (NFR-S2, D5)
- [x] 5.3 Enforce bounded redirects/size/time; re-check every redirect hop against
  the same rules (NFR-S2, D5)
- [x] 5.4 Restrict `${...}` interpolation to the fixed engine-defined whitelist
  (`task.id`, `task.branch`, `attempt.commit`, `stage.name`); a non-whitelisted
  variable is a located validation error (NFR-S2, D5)
- [x] 5.5 Spec: blocked targets report the reason (scheme / address class /
  missing allowlist entry) (UX2, factory-egress-allowlist)

  Deviations recorded while applying group 5:
  - The allowlist's home is the http provider's own operator subsection —
    `factory.check.http.allowlist`, a list of bare hosts (`sonar.example.com`,
    `*.ci.example.com`, or a literal address) — graded by
    `HttpCheckSubsectionValidator`, which until now required that subsection to be
    empty. An absent allowlist permits nothing: configuring the provider enables
    it, saying where it may call is a separate act
  - "unless explicitly allowlisted" (5.2) is read as *the operator allowlisted that
    literal address*. A name never waives the address-class block, because a
    public name answering with an internal address is exactly the rebinding case
    the block exists for; the judgement is on the resolved address, through an
    injected `HostResolver` seam so the rules are specifiable without DNS
  - Two refusal reasons beyond UX2's three: `UNRESOLVABLE` (a host whose class
    cannot be judged is refused, not attempted) and `RESPONSE_SIZE`. Both are
    fail-closed cases the three named reasons have no room for
  - Redirects are followed by a new `GuardedHttpCheckExchange` decorator rather
    than by `JdkHttpCheckExchange` — the JDK client would take the hop before any
    guard saw it. `HttpCheckExchange.Response` gained one header, `location`, for
    exactly that; total time is bounded by construction (bounded hops × the
    per-hop timeout) rather than by a clock seam. A cross-host redirect carries no
    headers forward, so a credential cannot follow one
  - Task 5.4 needed a run-scoped value source the check SPI did not have. Added
    `CheckRunContext` (api) — a closed name→value lookup for `task.id`,
    `task.branch`, `stage.name` — plus a defaulted
    `CheckClientFactory.create(secrets, subsection, runContext)`; the fourth
    variable, `attempt.commit`, comes from the `AttemptCommitWorkspace` at poll
    time, since it changes every round. `RunCheckRunContext` binds them in the
    composition root, reading the stage name live from the status holder. Task
    7.3's api-surface list is extended accordingly
  - The url's syntax check now grades the url with its `${...}` erased: braces are
    not legal URL characters, so the shape to validate is the one the request will
    take

## 6. Extract GitHub into a discovered plugin

- [x] 6.1 Package `:adapters:github` (tracker + checks + shared http) as the
  plugin jar over its private HTTP core; expose only SPI factories/validators via
  `META-INF/services` (FR12, D7, github-plugin)
- [x] 6.2 Spec: the api surface holds no github http-client/rate-limit/cache/retry
  type (FR12, github-plugin)
- [x] 6.3 Spec: the bundled github jar loads through the identical discovery path
  a third-party jar uses — no built-in shortcut (FR12, D7)
- [x] 6.4 Spec: removing the github jar disables all github providers with no core
  source change and the factory still starts; inmemory remains (FR12, M2)
- [x] 6.5 Startup observability: report the discovered provider set per port,
  with the contributing jar, so loaded jars are visible (NFR-O1, NFR-S3, D6,
  plugin-discovery)

  Deviations recorded while applying group 6:
  - Groups 1-2 had already done the mechanical half of 6.1 (the module, both
    `META-INF/services` entries, `:bootstrap`'s `runtimeOnly` edge). What 6.1 adds
    is the part that keeps it true: `GithubPluginPackagingSpec` fixes the export
    list to the two SPI factories and asserts the private HTTP core appears on
    neither the service entries nor the exported factories' own signatures
  - The bundle still declares `implementation project(':application')` for
    `FindingsSanitizer` and `AttemptCommitWorkspace` — two types a genuine
    third-party plugin could not reach. FR12's spec asks only that the *api* carry
    no vendor internals (6.2), not that github reach nothing but the api, so the
    edge stays; whether those two belong in `gnomish-plugin-api` is task 7.3's
    surface question, not this group's
  - "Removing the github jar" (6.4) is staged inside one JVM by hiding the
    artifact's service registrations (`GithubArtifact.hiddenFrom`), since discovery
    reads registrations and nothing else. M2's "with no core source change" half is
    a separate source-level gate: no production source outside `adapters/github/`
    names a github type in code (comments excluded — prose is not a dependency)
  - 6.2's ArchUnit rule names the bundle's three packages literally: a `..github..`
    pattern also matches the project's own `com.github.oinsio` root, which made the
    first draft fail over all 143 api classes
  - The startup report reads each provider's contributing artifact from its class's
    own `CodeSource` — a jar file name in a distribution, a module output directory
    in a build. NFR-S3's other half, the trust posture as an operator
    responsibility, is documented in `gnomish-plugin-api/README.md`, whose stale
    "discovery is change B" section this group replaced

## 7. Config ergonomics + api gate

- [x] 7.1 Implement named per-vendor connection profiles in operator config
  (`factory.connections.<name>`), referenced as `connection: <name>` from each
  port subsection; ports still select independently; hand the set of defined
  profile names to the subsection validators at the load seam (FR16, UX3, D8,
  D12, vendor-connection-profile delta)
- [x] 7.2 Spec: a github tracker + github checks share one named connection
  profile without duplicating creds; a mixed-vendor config still works; an
  undefined profile reference is a located config error; a profile-resolved
  credential name is scrubbed and cannot be allowlisted like a declared
  constant (FR16, FR17, UX3, D8, D11)
- [x] 7.3 Land the `gnomish-plugin-api` additions (the `CheckClientFactory` SPI
  with `CheckParamsValidator` + `ExternalCheckPinContributor` + its subsection
  validator and both halves of the credential declaration —
  `credentialEnvVars(subsection)` and the per-check
  `checkCredentialEnvVars(params)` group 4 added,
  `TrackerAdapterFactory.type()`/`subsectionValidator()`/`credentialEnvVars(config)`,
  `External` provider/params, and the `CheckRunContext` SPI with the run-aware
  `create(secrets, subsection, runContext)` group 5 added), then flip japicmp from report-only to a failing
  gate against that baseline (FR14, D9, M5, plugin-api-contract delta)

  Deviations recorded while applying group 7:
  - D12 says subsection validators "receive the set of defined profile names".
    They receive the profiles themselves — a new api type, `ConnectionProfiles`,
    added as a defaulted fourth argument on both `TrackerSubsectionValidator` and
    `CheckSubsectionValidator`. The default resolves the reference and delegates to
    the existing three-argument form, so an adapter states its key rules ONCE and
    grades an inline and a referencing subsection alike; neither github validator
    needed an override. Core owns the reference itself (malformed / undefined /
    declared alongside an inline key the profile carries) at the two seams that
    already aggregate located errors — `TrackerSeamValidator` and
    `CheckProviderSeam`
  - The profile is resolved INTO the model rather than carried alongside it:
    `TrackerConfigMapper` resolves the tracker subsection at load, and
    `CheckProviderSeam.resolve` the check subsections at assembly, so every
    consumer downstream (`create`, `expandRef`, `credentialEnvVars`,
    `refuseForeignRef`) reads one flat inline shape and no adapter learns a
    profile was involved. `ConnectionProfiles.resolve` is deliberately lenient on
    an undefined name — validation has already located it, so resolution has no
    second way to fail
  - FR17's "profile-resolved credential name" needed a key for a provider to be
    renamed BY: `credential`, read by both github providers through the shared
    `GithubCredential` helper, defaulting to each provider's historical constant so
    an inline subsection predating profiles resolves exactly the variable it always
    did. Without it "a profile renames a vendor's credential" had nothing to rename
  - `FactoryProperties` gained `connections` as a sixth component plus a
    five-argument convenience constructor, so the ~30 existing construction sites
    keep compiling; Spring binds a record through its canonical constructor, which
    is unaffected
  - **Task 7.3's baseline is committed, not published.** japicmp cannot be armed
    against an artifact that does not exist, and nothing is released yet, so
    `gnomish-plugin-api/compat-baseline/` carries the two jars of the semver
    surface (this module's and the `:domain` one it re-exposes) and
    `updateApiCompatibilityBaseline` regenerates them as the deliberate,
    reviewable act that accepts a surface change. `-PapiBaselineVersion` survives
    as the override for the day the api is published. Third-party libraries are
    excluded from the comparison: they are not our contract, and their releases
    must not fail our gate — this narrows the previous "whole runtime classpath"
    surface to the project artifacts alone
  - JetBrains' `binary-compatibility-validator` was evaluated as a japicmp
    replacement (committed textual dump, diffable in review) and rejected on
    evidence, not taste: in a Java-only project it registers no tasks at all, and
    with the Kotlin plugin applied its `apiBuild` fails on our bytecode with
    "Unsupported class file major version 69" (Java 25). It is in maintenance mode
    and its successor lives inside the Kotlin Gradle plugin. japicmp reads our
    major-69 artifacts and fails correctly on a deliberate break — verified by
    removing `CheckClientFactory.provider()` (M5)

## 8. Acceptance + traceability

- [x] 8.1 Acceptance spec: one stage runs `command` + SonarQube-via-`http` +
  GitHub Actions — three providers — in a single verify chain (M3, FR6, DEC-19)
- [x] 8.2 Grep gate: no `Map.of(...)` provider registry remains anywhere (M1)
- [x] 8.3 Spec: assess the tracker and check ports against the four-point
  plugin-ready criterion — discriminator SPI, discovered registry, config
  subsection + validator, discriminator selection (FR4, plugin-discovery)
- [x] 8.4 Verify every FR/NFR/UX of this change has an implementing spec or test
  (`.claude/rules/traceability.md`); run the full suite green
- [x] 8.5 Recommend a Conventional Commits message for the change (agent never
  commits — `.claude/rules/process-invariants.md`)

  Deviations recorded while applying group 8:
  - 8.1's http leg needed a real TLS endpoint. The provider's production path is always
    `GuardedHttpCheckExchange(JdkHttpCheckExchange, EgressAllowlist)` — https only, no
    exchange seam on the factory (and none was added: `ServiceLoader` builds it no-arg, so
    a seam could only be a mutable static). So `LoopbackTlsFixture` mints a PKCS12 with
    `SAN=ip:127.0.0.1` through `keytool` and installs a *composite* default `SSLContext`
    for the spec's lifetime — platform authorities first, this certificate only as a
    fallback, restored in `cleanupSpec`. WireMock's bundled keystore cannot serve this: its
    certificate carries no `SubjectAltName` at all, and its on-the-fly generator keys on
    SNI, which a client never sends for an IP literal. What is staged is *which CAs the JVM
    trusts* — the act an operator performs for a private SonarQube; the guard, the
    allowlist and the literal-address waiver are all the production ones, which is what the
    allowlist-refusal feature proves
  - `TempDirCheckEnvironments`: a verify chain has one workspace, and the two default
    consumers want different subtypes of it — `HostCheckEnvironmentSource` a
    `DirectoryWorkspace`, the github client an `AttemptCommitWorkspace`. Production resolves
    that with `SandboxCheckEnvironmentSource` over the round's container lease, so a mixed
    chain really is supported but only over Docker; the fixture stands in for the lease so
    the acceptance chain stays in-process
  - The acceptance spec is two files — `ThreeProviderPlatformFixture` carries the WireMock
    and TLS staging — because one file ran to 266 lines against the 200 cap
  - 8.2 is three features, not the one grep the task names. `Map.of(` occurs ~45 times in
    production source legitimately (empty defaults, environment maps) and once inside a
    javadoc sentence describing the registry that was removed, so the literal grep is the
    weakest of the three ways to say M1. It is joined by a structural ArchUnit rule (no
    production class *constructs* an SPI factory — a hardwired registry cannot exist
    without one, whatever it is named) and a behavioural one (hide every `META-INF/services`
    registration of a port's SPI and its registry comes back empty). All three were proven
    to bite by planting `Map.of("inmemory", new InMemoryTrackerAdapterFactory())` in
    `TrackerAdapterConfiguration`: the structural and literal features failed, then the
    plant was reverted
  - 8.3 lives in `adapter.plugin`, beside the other discovery specs, not in `architecture` —
    that package holds ArchUnit boundary gates, and the four-point criterion is a capability
    assessment
  - PIT needed no `excludedTestClasses` entry for any of the three new specs: they are
    in-process, resolve no build artifact through a system property, and `:bootstrap` stayed
    at 100% (146/146) with them in scope
  - 8.4's audit (ID -> change-name co-occurrence within one sentence, over comment-flattened
    non-openspec sources; second column counts `/src/test/` files only): every FR/NFR/UX/M
    of this change is claimed by at least one spec or test. `M3`, unclaimed before this
    group, is closed by `ThreeProviderVerifyChainSpec`. Thinnest claims, each real and
    single: `FR8` (`CheckRefSpec`), `FR11` (`JdkHttpCheckExchangeSpec`), `FR14`
    (`ApiCompatibilityGateSpec`), `NFR-S1` (`HttpCheckParamsValidatorSpec`), `M3`, `M4`
    (`PipelineLoaderCheckProviderSpec`), `M5`. `NFR-S3`'s other half is the posture text in
    `gnomish-plugin-api/README.md`, documentation by design (group 6)
