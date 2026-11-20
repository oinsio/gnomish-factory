# Tasks: migrate-verify-to-executor-contract

## 1. Manifest and model

- [x] 1.1 Collapse `VerifyCheck.Builtin/Command/External` into one `VerifyCheck.Executor`
  record (executorName, checkId, settings, interval, timeout, timeoutClass, pinPaths,
  verifyIn, threshold, expected) beside `Judge`; map the three tokens to built-in names in
  `VerifyCheckMapper`; specs: legacy manifest loads unchanged, explicit `executor:` form,
  default `external` → `github-actions` (FR1, UX2)
- [x] 1.2 Loader rules: executor must declare role `verdict`; `scm-status` requires non-empty
  `expected`; `threshold` enum with round-trip spec; credentialed executor + `same-box` is a
  located error and `fresh-box` is the default for it (FR1, FR4, NFR-S1, NFR-R3)
- [x] 1.3 Glossary: entries for verdict executor, pending key, expected contexts, SCM-status
  reader (replace the forward reference); update "Verify check" and "Executor" entries;
  threat registry note that external verifiers are executors (process rule)

## 2. Single owner: verdict derivation

- [x] 2.1 Implement `VerdictDerivation(answer, threshold)` in `domain/engine` with the
  data-driven level × threshold spec; `CannotVerify` on a malformed answer (FR7, D5)
- [x] 2.2 Route `VerifyOrchestrator` through it; delete verdict construction in
  `ShellCommandCheckRunner`, `FilesExistCheckRunner`, `ExternalPolling`,
  `VerifyOrchestrator:153`, `HttpExternalCheckClient`, `GithubCheckExternalClient`
- [x] 2.2a A pass keeps its below-threshold findings (FR7, D5 as amended): `Verdict.Pass(runUrl,
  findings)` with the list defensively copied; `VerdictDerivation` fills it with every result
  below the threshold, in the order written; `StateJsonMapper` writes and reads the existing
  `findings` field for `"pass"` (round-trip spec over a pass with warnings; `StateCheckDto` and
  `CheckDto` javadoc updated); `AttemptMapper` and `UsageReportJsonMapper` carry the list;
  `CheckFindings.noteIgnoredOnPass` deleted. Spec: a pass with findings feeds no retry (`RoundExecution`
  filters by `Fail`, FR4 of `add-stage-engine`). Scenarios "Warnings pass under the default
  threshold" and "A pass keeps its findings across the state file". The exit-0 drop is released
  by the `cli/manual-run` delta (REMOVED "Findings-JSON wire format", D5 second amendment): the
  feature under the marker `cli/manual-run / A findings file on a green check is ignored` is
  rewritten to `verification/verdict-executors / A findings file on a green command is read`
- [x] 2.2b The same for the judge (second instance of the class, D5 as amended): `JudgeVerdictExtractor`
  returns `Pass` with the parsed `findings` of a `"passed": true` verdict under the same strict
  schema as a failed one (a malformed array stays `CannotVerify`); scenario "A judge pass keeps
  its findings". The judge classes stay exempt from the derivation gate (D9, NG1)
- [x] 2.3 Old-way sweep: `grep -rn "new Verdict\." --include=*.java */src/main adapters/*/src/main`
  → hits only in `VerdictDerivation`, `StateJsonMapper` and the judge classes named in D9;
  record the list in the task report (M2)
- [x] 2.4 Outcome-classifier gate (carried from D10 of `define-executor-contract`): that change's
  `OutcomeClassifierOwnerSpec` (`:bootstrap`) allowlists every `new Verdict.CannotVerify(` site
  under the pickup `migrate-verify-to-executor-contract: check-side verdict construction`. Delete
  the rows of the classes 2.2 routes (`ShellCommandCheckRunner`, `FilesExistCheckRunner`,
  `ExternalPolling`, `VerifyOrchestrator`); the judge rows (`JudgeVerdictExtractor`,
  `JudgeCriteriaPreflight`, `JudgeCannotVerify`) stay exempt until `extract-agent-round-runner`
  (D9, NG1), so retag them with that pickup and add it to the spec's `PICKUPS`; D9's exempt
  list already names `JudgeCannotVerify` beside the other judge classes (folded 2026-11-08); no
  row keeps this change's name (M2)

## 3. Built-in verdict executors

- [x] 3.0 Leaf `:httpegress` (FR15, D16): move `EgressAllowlist`, `GuardedHttpCheckExchange`,
  `JdkHttpCheckExchange`, `EgressRefusedException` out of `:adapters` (`adapter.check.http`) into
  the new JDK-only leaf, re-point `HttpExternalCheckClient`; architecture spec in `:bootstrap`
  asserting `HttpClient.newBuilder(` / `newHttpClient(` appear in production code only inside the
  leaf (allowlist by file, asserted reached); existing egress specs re-pointed
- [x] 3.0a Library `:githubapi` (FR14, D15; narrowed 2026-11-08 by the decision on this task's
  escalation, D16 as amended): move the bundle's `adapter.github` package (`GithubHttpClient`,
  `GithubCredential`, `GithubConditionalRequestCache`, `GithubRateLimit`, `GithubRetryConfig`,
  exceptions) into the new module depending on `:domain`, `:httpegress`, resilience4j, slf4j —
  never on `:executor-contract`, `:application` or `:adapters` — with **unchanged behavior**:
  the client keeps its own `HttpClient` for this task, and the `PENDING` row of
  `HttpClientConstructionBoundarySpec` is retargeted to the class's new path with 3.0c named as
  the task that deletes it; add `:githubapi` to the bundle's `layering.allowedProjects` with the
  reason; re-point the tracker's imports; `GithubAdapterBoundarySpec` and
  `GithubPluginPackagingSpec` green; grep `class GithubHttpClient` → one hit (M6). The package
  moved in round implement#0 (`4ced515a`, now `com.github.oinsio.gnomish.githubapi`); what remains
  is the main-spec side: rewrite the `ModuleTreeSpec` feature "the github bundle is one module
  whose HTTP core no other production module can reach" against the MODIFIED
  `build-system/module-layering` scenario "The github vendor bundle is one module" — the core's
  classes under `githubapi/src/main/` only, and `project(':githubapi')` declared on a production
  classpath by `:adapters:github` and `:adapters:scm` only (`:adapters:scm` once 3.3 creates it;
  until then the bundle alone) — marker kept
- [x] 3.0b One egress grammar and one operator egress block (FR17; D16 as amended 2026-11-09;
  the operator-configuration, sandbox-egress and vendor-connection-profile deltas). Precondition:
  `/review-artifacts migrate-verify-to-executor-contract D16` run and its findings resolved
  (`design-decisions.md`, "A decision added during implementation is reviewed before its task
  runs"). Move `EgressEntries` from `sandbox/core` into `:httpegress` (`:sandbox:core` takes the
  leaf as `api`; the MODIFIED module-layering scenario "The sandbox core depends only on domain and
  two leafs", marker re-checked), extend the grammar — CIDR, the class words `loopback` and `private`, the apex
  rule of `*.suffix` stated in its javadoc — and make `EgressAllowlist` parse through it (its own
  wildcard match deleted; `EgressGrammarOwnerSpec` in `:bootstrap`: `*.`-prefix matching and the
  class words spelled in `EgressEntries` only). Define `factory.egress.sandbox` (sandbox-boundary)
  and `factory.egress.in-process` (project) in `FactoryProperties`, route the box guard and
  `ExecutorEgress`'s companion reads to the first and the http check's allowlist to the second;
  `factory.sandbox.egress-allowlist` and `factory.check.http.allowlist` become removed keys in a
  new removed-key table in `ConfigViolations`, keyed by the old key, whose violation names the new
  scope (the existing removed key `factory.agent-cli-env-passthrough` keeps its unknown-key
  message); `HttpCheckSubsectionValidator` grades the subsection as
  empty; `gnomish project show` prints the block. Consumers of the grammar: the box guard
  (`EgressGuardConfig.java:142-146`, today over `SandboxProperties.egressAllowlist`,
  `SandboxProperties.java:82`, replaced by `factory.egress.sandbox`; `sandbox` subset),
  `ExecutorDeclarations` (`ExecutorDeclarations.java:142`, the law's `executors.<name>.egress`;
  `sandbox` subset enforced there with its located error), `ProgramDescribe`
  (`ProgramDescribe.java:133`, a describe's `egress`; full grammar), `ExecutorEgress` and the
  describe preflight (`GrantAgreement`), `EgressAllowlist`, the connection `egress` of 3.0c. Old-way sweep: `EgressEntries\|egress-allowlist\|check.http.allowlist\|startsWith(WILDCARD`
  in production code → the leaf's grammar, the two new keys and the removed-key table only. The
  `sandbox` scope and the law's `executors.<name>.egress` accept the name-only subset (exact host,
  IP literal, `*.suffix`) and refuse a CIDR or class word at load (D16, item 10 of the second
  review); the box guard's addon matcher in `EgressGuardConfig.java:60-66` becomes a declared pair
  with `EgressEntries` — `Kept in sync with` at both ends, a row in `manual-sync-pairs.md`'s
  no-shared-classpath table, an identity spec driving both matchers over one table of names, and
  the one exemption of `EgressGrammarOwnerSpec` with that reason. Specs: one feature per scenario
  of "One grammar for every egress entry" (but "A class word waives only its own admitted set",
  which 3.5 takes), the sandbox-egress scenarios, "The operator egress block
  replaces the two allowlist keys", the MODIFIED "Every key declares where it may be set" and
  "Default-deny allowlist owned by the operator", markers included; every fixture that wrote
  `factory.sandbox.egress-allowlist` or `factory.check.http.allowlist` re-pointed; the operator
  file of the WireMock and Gitea fixtures grants `loopback` under `factory.egress.in-process`.
  Docs: `operator-guide.md` and `operator-guide-sandbox.md` (the block, the two removed keys and
  the two-line migration), ADR 0011's key table; glossary entries **operator medium**, **law
  medium**, **egress entry** (the grammar) — the first amendment's **manifest target**, **operator
  connection** and **declared redirect hosts** are not added
- [x] 3.0c Egress admission by medium, refusal classification and the GitHub core over the guarded
  exchange (FR15, FR17, FR18, NFR-R5, UX4; D16 as amended 2026-11-09 after the second and third
  reviews). Precondition as 3.0b. The leaf mechanics are specified on a stand-in consumer key (a
  test connection); the scenarios that need `factory.check.github-actions` or
  `factory.check.scm-status` are 3.3's and 3.5's, the templated manifest host 3.4's.
  In `:domain` (`domain.egress`): the records `EgressMedium`, `ConnectionEndpoint(URI,
  EgressMedium, List<String> egress)`, `AdmittedEndpoint`, and `EgressRefusedException` moved here as a
  `RuntimeException` (no longer an `IOException`). `:httpegress` takes `:domain` as its one
  internal edge (layering block and the MODIFIED "Enforced acyclic dependency direction"
  scenario "The egress module depends only on the domain"; `:sandbox:core`'s edge is 3.0b's).
  In `:httpegress`:
  - `EgressAdmission.admit(ConnectionEndpoint, EndpointDeclaration, OperatorEgress)`, the only
    `new AdmittedEndpoint(`; load judges scheme, host, grammar, grants and the **static** class
    (IP literals, `localhost` and `.localhost` names) and resolves no name; a host whose only
    grant is a class word and whose class is not static is admitted conditionally and judged at
    connect; a law-medium connection is `https` unless statically loopback and granted by
    `loopback`; a literal blocked address is a load error unless its class word is in that
    connection's admitted set; a class word waives only the admitted set it is listed in; no
    entry lifts the cloud-metadata block (`allowlistsLiterally` no longer honours it);
  - the outcome classes: `SCHEME`/`ADDRESS_CLASS`/`NOT_ALLOWLISTED` → `EgressRefusedException`
    naming the consumer's key; `UNRESOLVABLE` removed from the reasons, an unknown host thrown as
    `UnknownHostException`; `REDIRECT_LIMIT`/`RESPONSE_SIZE` (loop included) →
    `ExchangeLimitException`; `BlockedAddressClass` takes its labels from `EgressEntries`;
  - the exchange's `Response` carries the headers; it sends with `sendAsync` and a body
    subscriber of its own (bound or tail per consumer), the whole exchange bounded by the
    consumer's timeout from the send; on expiry `cancel(true)` on the `sendAsync` future and the
    subscription cancelled; no thread parked in a read; measure the stalled-body case on macOS
    and on Linux (`scripts/linux-specs.sh`), asserting the server sees the connection closed
    within the timeout plus one bound, and record JDK build and OS in the report;
  - the hop rule of `RedirectFilter.java:89-96`: `301`/`302` turn only `POST` into a bodiless
    `GET` with no `Content-*`, every other method kept; `303` → bodiless `GET`; `307`/`308` keep
    method and body; `https → http` refused; a visited URL refused; the credential deny-set
    dropped across hosts and every other header kept.
  In `gnomish-plugin-api` (the one MINOR bump, 0.14.0 → 0.15.0, migration step 3; no edge to `:httpegress`):
  `TrackerAdapterFactory.endpoint()` returning `Optional<EndpointDeclaration>`
  (`EndpointDeclaration(String key, String vendorDefaultHost)`; GitHub `api-url`/`api.github.com`,
  in-memory empty); `ConnectionProfiles.endpoint(Map<String, Object>, String key, EgressMedium
  inlineMedium)` beside `resolve` (a profile value `OPERATOR`, an inline one the caller's medium),
  the only `new ConnectionEndpoint(`; japicmp baseline updated. In core: `PipelineLoader` →
  `PipelineMapper.map` → `TrackerConfigMapper.map` gain the adapters' `EndpointDeclaration`s
  (keyed by tracker type, as the tracker validators are) and the `OperatorEgress`; the mapper
  admits the tracker endpoint (`LAW` inline) and stores it as the new `TrackerConfig` component
  `Optional<AdmittedEndpoint> endpoint`; a refusal is the located load error.
  Refusal per layer (D16 table, FR18): `DaemonConditionKind.EGRESS` (javadoc count, `ConditionTokens`
  wire token, `DaemonComponent`-free — a condition, not a loop; the round-trip and closed-vocabulary
  specs extended); `OperatorPreconditionRefusals.kindOf` gains `EgressRefusedException` → `EGRESS`
  (`OperatorPreconditionOwnerSpec` extended); `FeedOutageRetry.run` rethrows a typed refusal
  instead of retrying and the feed reports it to `DaemonConditions`; the `EGRESS` probe repeats the
  tracker endpoint's connect-time judgement, sending no request; `take` before its claim ends on the
  same typed result (exit 8); the heartbeat and the reaper unchanged.
  In `:githubapi`: `GithubHttpClient(AdmittedEndpoint, String token)` (and the package-private
  form with `RetryConfig`) over the guarded exchange — its `HttpClient`, `Redirect.NORMAL`,
  virtual-thread executor and `String` URL constructors deleted, the `PENDING` row deleted, 16 MiB
  and 60 s declared; the retry still retries `IOException` only, so a refusal and an exchange
  limit pass through unwrapped and an unknown host stays `GithubTransportException`;
  `GithubTrackerAdapterFactorySupport` builds the client from `TrackerConfig.endpoint()` in
  `create`, `probeRead` and `refuseForeignRef`, no `api-url` string read.
  Consumers (D9 row): `ConnectionProfiles.endpoint`, `TrackerConfigMapper` via
  `PipelineMapper`/`PipelineLoader`, `HttpCheckSubsectionValidator` and the `http` check's
  manifest loader at load; `OperatorPreconditionRefusals`, `FeedOutageRetry`, the `EGRESS` probe;
  `GithubHttpClient` and the `http` built-in's exchange at connect. Old-way sweep:
  `HttpClient.newBuilder\|Redirect.NORMAL\|factory.check.http.allowlist\|redirect-hosts\|new GithubHttpClient(.*String\|requireStringValue(.*"api-url"\|Reason.UNRESOLVABLE\|extends IOException` (the last over `:httpegress` and `domain.egress`)
  in production code → leaf only, none, none, none, none, none, none, none. Specs: one feature per
  scenario of "Egress admission follows the medium of the URL" (but "A plain-http operator
  connection is admitted as declared", 3.5's, and "A templated manifest host is judged at
  connect", 3.4's), "The guarded exchange serves every factory HTTP consumer" (but "A job log is
  read as a bounded tail", 3.3's), "A redirect hop keeps the request's meaning", "An egress refusal
  is a configuration fault" (but "A refusal names the consumer's configuration key", 3.3's, and "A
  literal blocked address is refused at load", 3.5's), "Load classifies a host only statically"
  (but "A literal loopback connection needs its class word", 3.5's), "A cross-host hop carries no
  credential", "A law-medium connection is https unless loopback", the MODIFIED "The http check
  enforces scheme, address-class, and resource limits", "A profile's egress grants a law-medium
  tracker host", the `serve/factory-serve` requirement "A tracker egress refusal is an operator
  precondition of the daemon", the MODIFIED `plugin-api-contract` scenario "The SPI factories bear
  their declared members" and the module-layering scenario "The egress module depends only on the
  domain", markers included; `EgressAdmissionByMediumSpec` (the identity spec of the D9 row, over
  the real exchange and a local server); `EgressMediumMintingSpec`; `GithubRefusalClassificationSpec`.
  The 37 test files that construct `GithubHttpClient` directly take their endpoint from one
  test-fixture helper, `GithubTestEndpoints.loopback(URI)`, which admits through
  `EgressAdmission` with `loopback` granted — no overload, no default; the configuration fixtures
  write an operator `project.yaml` granting `loopback` under `factory.egress.in-process`; all green
  with no test-only switch; `HttpClientConstructionBoundarySpec` with an empty `PENDING`. Docs:
  `operator-guide.md`'s tracker section gains the grant rule for a non-default `api-url`, the scheme
  rule and the `EGRESS` condition; `docs/sandbox-threat-registry.md` (the paragraph at `:146-147`
  restated for admission by medium); glossary entries for **egress medium** if not added by 3.0b
- [x] 3.1 `files-exist`: wrap `FilesExistCheckRunner` as an `Executor` (describe: role
  `verdict`, settings schema for the path list) emitting SARIF; existing specs re-pointed (FR1)
- [x] 3.1a `files-exist` result cap (human decision 2026-11-10): the `files` setting has no
  count limit; when more than `Answer.Verdict.MAX_RESULTS` paths are missing, the answer carries
  the first `MAX_RESULTS - 1` as `error` results and one `error` result naming how many more are
  missing, so the check still fails as a judgement, never `failed/infrastructure`; the schema's
  `maxItems` and the reader's cap from 3.1 removed; the legacy port renders the same; specs for
  999, 1000, 1001 and 5000 missing paths (FR1, D5)
- [x] 3.2 `shell`: build on the shared command-run core from `add-command-executor`; report
  exit as status, read the findings path through `ResultFileReader`, normalize the legacy
  `{"findings":[...]}` to SARIF; the file is read on every exit code but 126/127 (D5 second
  amendment); specs: legacy file normalized, SARIF passthrough, exit code carries no verdict,
  existing bound/kill specs green (FR5). Move the eight markers of the REMOVED `cli/manual-run`
  requirements in `ShellCommandCheckRunnerSpec` and `CheckFindingsSpec` to the new titles under
  `verification/verdict-executors`: "Red check carries feedback" → "A red command without a
  findings file carries its output tail"; "Missing binary is infrastructure" → "A missing binary
  is an infrastructure failure"; "No factory variable leaks into a check" → "No factory variable
  leaks into a shell check"; "A check inherits no factory variable implicitly" → "A shell check
  inherits no factory variable implicitly"; "Structured findings win" → "Structured findings
  replace the synthetic result"; "Broken reporter cannot mask a red check" → "A broken reporter
  cannot mask a red command"; "A malformed findings file is logged" → same title; "A findings
  file on a green check is ignored" → "A findings file on a green command is read" (if 2.2a did
  not already). `grep -rn "Scenario: cli/manual-run / \(Red check\|Missing binary\|Structured
  findings\|Broken reporter\|A malformed findings\|A findings file on a green\|No factory
  variable\|A check inherits\)"` → zero hits; `checkSpecContract --changes` green
- [x] 3.3 `github-actions`: move `GithubCheckExternalClient` + fetchers out of the bundle into the
  new core-side module `adapters/scm` (depends on `:githubapi`, `:executor-contract`, `:domain`)
  as an `Executor` with `poll`; describe carries the token name, settings schema (former
  subsection) and `checkIdIsPinnedPath = true`; its connection (base URL, token name) is read
  from the operator's `factory.check.github-actions` subsection, inline or by `connection:`
  profile (NFR-S2); its describe `egress` declares `api.github.com`,
  `results-receiver.actions.githubusercontent.com` and `*.blob.core.windows.net`, and the
  connection's optional `egress` list names an enterprise store (FR17, D16 as amended 2026-11-09;
  this task owns the two scenarios of "A redirect target is an egress target", "A job log is read
  as a bounded tail", "A refusal names the consumer's configuration key", "A profile grants the
  enterprise log store" and "A built-in's HTTP call outside its declared hosts is refused" (now
  under "A built-in verdict executor's HTTP passes the egress guard"), markers included; the
  connection is read by `ScmConnectionReader` in `adapters/scm` — the one reader both built-ins
  use: it resolves `factory.check.<name>` through `ConnectionProfiles.endpoint(…, OPERATOR)` and
  admits it through `EgressAdmission` at run assembly, after the describe preflight (in
  `take`/`serve` after the claim), only for a built-in the law's checks name (task 3.6, D18 as
  amended), a refusal being a located `ConfigError` on the describe refusal's path (the task parked,
  or `run`'s located error exit; D18, "Where a refusal at run assembly goes");
  `EgressMediumMintingSpec`'s allowlist gains it;
  docs:
  `docs/guides/operator-guide-github-actions-check.md` gains the connection `egress` list and the
  GHES store example);
  WireMock specs re-pointed unchanged; the bundle's `adapter.check.github`
  package and its `META-INF/services` check entry deleted (FR6, FR13, D4, D17)
- [x] 3.3a Narrow `GithubPluginAbsenceSpec` (M5): the source gate's regex covers references to
  the bundle's remaining package (`adapter.tracker.github`) outside `adapters/github/`, not
  `\bGithub[A-Z]` names; the registry feature asserts the tracker registry loses `github` while
  `ExecutorRegistry` still resolves `github-actions` and `scm-status` with the bundle hidden;
  `bootstrap/build.gradle`'s `runtimeOnly` comment restated for the tracker
- [x] 3.4 `http`: same for `HttpExternalCheckClient` (conditions, run-context interpolation,
  egress refusal); specs re-pointed (FR6); a manifest target whose authority carries a `${…}`
  reference is not judged at load and its substituted host is judged at connect against the
  `in-process` scope (D16); the scenario "A templated manifest host is judged at connect",
  marker included; an unknown host is now `failed/infrastructure` as transport, not the egress
  refusal (D16, the outcome classes); the load-time judgement of a
  literal manifest target (D16 law-medium bullet: a requested host nobody grants is a located load
  error at the line of the request, naming the two ways to grant it) through `EgressAdmission`
  against `factory.egress.in-process`, moved here from 3.0c because the `CheckParamsValidator`
  SPI that 3.0c would have had to widen is removed by 5.2; a spec for it
- [x] 3.5 `scm-status`: new built-in in `adapters/scm` over `:githubapi`, reading GitHub Checks +
  Statuses and Gitea statuses on the attempt SHA; latest-per-context, expected list, SHA verification, rate-limit back-off hint;
  WireMock specs for each scenario and `ScmStatusShaPinningSpec`; its connection from
  `factory.check.scm-status`, admitted as an operator connection with an optional `egress`
  list and a describe `egress` of its endpoint only (FR17), read through the same
  `ScmConnectionReader` as 3.3; the scenarios "A plain-http operator
  connection is admitted as declared", "A class word waives only its own admitted set", "A
  literal blocked address is refused at load" and "A literal loopback connection needs its class
  word", markers included; one feature per scenario of "A credentialed built-in verdict
  executor is connected by operator configuration", markers included (FR4, FR9, NFR-R2, NFR-S2,
  D3, D9)
  (Human decision 2026-11-10: the scenario "Rate limit backs off" needs a typed wait hint the
  contract lacks; its marker moves to 4.0a, and from there to 4.2 by the D19 review of
  2026-11-12, since its THEN needs `PendingPoll`. "Unconnected credentialed built-in is a located
  error" moved to 5.2.)
- [x] 3.6 Register the five built-ins in `ExecutorRegistry` per D18 as amended (2026-11-10): the
  registry's value becomes an entry per name — the name, its `describe` and its long-lived
  collaborators, built at **run assembly** (once per run, after the describe preflight; in
  `take`/`serve` after the claim) — with the executor bound at resolution:
  `executor(TaskExecutionEnvironment)` for a stage round (the existing `RoundExecutorFactory`,
  unchanged) and `executor(VerifyCheck.Executor, Workspace)` for a check round, the check
  dispatch building the D14 wrapper over the bound executor as the `StageExecutor` the engine
  sees (its file named in this task's report and added to the D9 row); nothing bound is cached
  (the registry stays immutable); today's signature `static ExecutorRegistry of(DescribedLaw,
  PipelineDefinition, PipelineLaw, RoundEnvironmentSource, ProgramRoundEquipment,
  CommandExecutorFactory)` (`ExecutorRegistry.java:65-71`) loses its `command` parameter and the
  field `Map<ExecutorName, StageExecutor>` becomes the entry map. The entry of a built-in the law's
  checks never name is not built, so a law without an SCM check runs without
  `factory.check.github-actions` / `factory.check.scm-status`.
  **Refusal outcome** (D18, "Where a refusal at run assembly goes"; human decision 2026-11-10):
  a connection the law's check needs and the operator did not supply, or the guard refused, is a
  located `ConfigError` naming the check, the executor and the key, read before the describe
  branch decides and carried on `LawDescription.Refused`, so in `take`/`serve` the task is parked
  by `TaskTierLaw.parkUndescribed` (host and container paths) and in `run` it is
  `ManualRunDescribe`'s located error exit; no `EgressRefusedException` or
  `IllegalArgumentException` leaves `assemble` (else `ClaimedRunFailureDispatch.java:89` would open
  `EGRESS`), and no `DaemonCondition` is added. Specs: a law that names no SCM built-in runs with
  neither subsection; on the `take` path, a law naming `scm-status` with no
  `factory.check.scm-status` parks the task with a report naming the check and `scm-status`, the
  claim's tracker shows `park(INFRA)` and no condition opens; on `run`, the same law exits with
  that located error. This is the one owner of the case the scenario "Unconnected credentialed
  built-in is a located error" promises; its marker is placed by 5.2 on the same path once
  `factory.check.github-actions` exists (no second validator owns the case). Fix the javadoc of
  `ScmConnectionReader` (`ScmConnectionReader.java:25-26`, "a refusal is a startup failure").
  **Built-in names** (D18, "The six built-in names have one owner"): one set of the six names
  (`files-exist`, `shell`, `github-actions`, `scm-status`, `http`, `command`) in `:domain`
  (`domain.pipeline`), the only spelling of the set (`VerifyCheck.Executor`'s constants,
  `HttpExecutor.NAME` and `CommandExecutorFactory.NAME` keep their single names and the set refers
  to them or they to it); the law's load validation (`ExecutorDeclarations`, with the check mapping for the unknown-executor case) refuses a law declaration
  under any of the six as a located error and reports a check naming an executor outside the six ∪
  the law's declarations as a located error listing that union; the registry's refusal reads the
  same set and stays as a safety net. Move the marker of "Defaulted github provider absent from the
  registry is a located error" from `ExternalCheckSeamValidatorSpec:60` (a test of the check-SPI
  key, `ExternalCheckSeamValidator.java:44-46`, which 5.2/6.1 remove) to a loader feature over a
  law shadowing `github-actions`, and add a loader feature for "Unknown provider is a located load
  error" listing the six ∪ the law's names (its markers at `ExternalCheckSeamValidatorSpec:42` and
  `PipelineLoaderCheckProviderSpec:43` are re-pointed or kept only where the test still exercises
  that scenario). The built-ins are handed in by `ExecutorAdapterSelector` as typed parameters of
  `:adapters:scm`, `:adapters` (package `adapter.check`) and `:adapters:agent`;
  `ExecutorRegistryOwnerSpec` extended per the D9 row (the five `new <Built-in>Executor(` forms
  each in its entry's binding only, `ScmConnectionReader` `.read(` only in the registry assembly
  and the transitional provider, the refusal over the six names).
  **Conformance kit** (D18, "The conformance kit drives a built-in through its entry"): the kit run
  against each built-in as a spec drives it through its registry entry — `BuiltInCandidate`
  takes the entry's describe and a per-case binding (a function from the case's working copy to
  the bound executor; for the SCM built-ins an attempt-commit workspace whose commit the spec
  chooses per case), never one shared instance, and its javadoc (`BuiltInCandidate.java:24-25`)
  is rewritten; an in-process candidate carries the settings of its ordinary cases (`KitSubject`
  gains them, the settings-schema case judges those same settings, a program candidate keeps `{}`
  and the CLI is unchanged); re-point `CommandBuiltInConformanceSpec`, `ConformanceKitSpec`
  (the in-process feature, `:117`) and the `BuiltInCandidate` specs; one feature per scenario of
  the ADDED `executor/conformance-kit` requirement "An in-process candidate carries the settings
  of its ordinary cases", markers included (FR1, FR8, FR13).
  **Usage:** each built-in's describe declares `reportsUsage = true` and its result
  carries usage `REPORTED` with an empty per-model map; a `UsageFold` spec: a task whose checks
  are the five built-ins stays `complete`, one feature per scenario of "Built-in verdict
  executors report a known zero usage", markers included (NFR-C1, D13)

## 4. Single owner: pending/poll with a durable key

- [x] 4.0a Typed pending facts and details on the wire (design D19; human decisions 2026-11-10,
  option A and the four follow-up choices). Contract: `ExecutorResult(Status, @Nullable
  FailureClass, @Nullable PendingKey, @Nullable UntrustedText reason, @Nullable JsonNode answer,
  Usage, List<Denial>)` (`ExecutorResult.java:35-42`) gains `@Nullable Long pollAfterMillis`
  (0–86 400 000), `@Nullable PendingState pendingState` (new `WireToken` enum `running`,
  `unreported`, `unobserved`, `fromWire`; unknown token read as absent) and `@Nullable
  UntrustedText details` (allowed on any status, like `reason`; read only for `failed` and
  `pending`, D19); the constructor refuses either pending field off `pending` and a
  `pollAfterMillis` outside 0–86 400 000 (an in-process built-in's result is never serialized,
  so the constructor is where the range binds it), and `ResultReading` (`:47-53`, the
  `pendingKey` arm's shape) reads a misplaced or out-of-range field as `failed/infrastructure`
  naming it; `SarifResult` (`SarifResult.java:13-18`) gains `@Nullable
  UntrustedText details`, read by `SarifReader` from `properties.details` when a string and
  ignored otherwise; `executor-result.schema.json` gains the three properties and an `allOf` arm
  refusing the two pending fields off `pending`, `executor-answer.schema.json` the
  `properties.details` of a SARIF result (no type constraint there; the reader judges it), the
  pending example carries both fields. Round-trip spec over every `PendingState` constant
  (`values()`), schema specs for each field. GitHub core: `GithubRateLimit` becomes the one owner
  of the rate-limit judgement and its pause — `static boolean isRateLimited(HttpResponse<?>)`
  (`GithubRateLimit.java:22`) is replaced by `static Optional<RateLimit> of(HttpResponse<?>)`,
  `RateLimit(Duration pause)`: `retry-after` (seconds or HTTP-date against `Date`), else
  `x-ratelimit-reset − Date`, else 60 s, for a `403` with the signal and a `429` alike, never
  negative (a reset at or before `Date` is a zero pause);
  `GithubConditionalRequestCache.Fresh(int, String, @Nullable String eTag, boolean rateLimited)`
  (`:125-126`) carries `@Nullable RateLimit rateLimit` instead of the boolean; `GithubRetryConfig`
  (`:55-60`) retries a rate-limited response only when its pause is at most `MAX_INTERVAL` (8 s),
  no sooner than the pause, and returns a longer one at once, judging it through
  `GithubRateLimit.of` (its own `RATE_LIMIT_STATUS = 429`, `:38`, used at `:58`, is deleted); `GithubFreshBody` keeps its
  classification (FR6, NG11) through the owner. `scm-status`: `ScmStatusRead.RateLimited(int
  statusCode)` (`ScmStatusRead.java:22`) gains `Duration pause` (its `RATE_LIMIT = 429`,
  `ScmStatusReader.java:34`, used at `:107`, is deleted); `ScmStatusResults.rateLimited`
  (`:64`) sets `unobserved` and `pollAfterMillis` clamped to at most 86 400 000, `ScmStatusResults.of` (`:41`) sets
  `unreported` while an expected context is absent and `running` otherwise, its reason unchanged.
  Details: `ShellResult.asSarif`, `GithubActionsResults.error`, `HttpResults` and
  `ShellExecutor.java:113-116` (the timeout's `failed/quality`, its output tail) and `:119` (the
  unanswerable `failed/infrastructure`) write `details` (SARIF `properties.details`, or the
  result's `details`) instead of folding; the timeout's tail reaches its finding through 4.0b's
  mapping (`Finding(reason, null, details)`). Old-way sweep, in the report with its hits:
  `grep -rn 'folded(' adapters/src/main adapters/scm/src/main` and a details concatenation into a
  message → zero hits. Rate-limit owner gate (D9 row): `GithubRateLimitOwnerSpec` in
  `:bootstrap`, on the model of `BaseHeadDefaultBoundarySpec`, scans `githubapi/src/main` and
  `adapters/scm/src/main` with comments stripped and fails on the literal `429` or the header
  names `retry-after`, `x-ratelimit-reset`, `x-ratelimit-remaining` outside
  `GithubRateLimit.java`, asserting the scan reached that file (javadoc mentioning `429` in
  `GithubRetryConfig`, `ScmStatusRead`, `GithubFreshBody`, `GithubWorkflowRunPoll`,
  `GithubWorkflowRunQuery`, `GithubWorkflowRunInfrastructureException` stays). Conformance: no
  kit case added. A `program` candidate's result is judged against the schema
  (`WireObservation.java:104-116`); an in-process built-in's result is not
  (`BuiltInCandidate.java:127-130`), so the constructor's refusal is its check. The misbehaving
  fake gains the committed preset section `[pending-bad-hint]` in
  `test-fixtures/src/main/resources/stand-in/tables/executor.params` — the reference executor
  whose `start` answers the new data section `executor-result#pending-bad-hint` (`pending` with
  a key and `pollAfterMillis: -1`) — not a `break-<case>` link: `steps/executor-run.sh:45-53`
  exits 97 on a name that is no kit case, and the case names do not change (the pair `KitCase` ↔
  `executor-run.sh` is untouched, D8). A spec runs the kit's `pending-poll` case against it and
  asserts the case fails naming `pollAfterMillis`; `StandInRowReachSpec` and
  `StandInLibrarySpec` cover the section. Glossary (`docs/glossary.md`, executor-contract
  context): add "wait hint" and "pending state" in this task. Specs, one feature per scenario, markers included:
  `executor/wire-contract` "Misplaced or out-of-range pending fields fail closed" and "A
  third-party details property of another type is ignored" (the wait and timeout scenarios of the
  same delta are 4.2's, "SARIF details reach the finding's details" is 4.2f's); `plugin/github-plugin` "A long rate-limit pause is returned, not
  retried" and "A short rate-limit pause is retried after it" (the retry driven through the
  client's injected `RetryConfig`, no real-time wait); `verification/verdict-executors` "Absent
  context is reported as unreported" and "Running context is reported as running". Specs
  without a marker, the executor's half of scenarios whose engine half lands later (the marker
  goes with the task that completes the scenario): `scm-status` on a rate-limited WireMock
  answer returns `pending` with `unobserved` and `pollAfterMillis` from `retry-after`, else the
  reset (marker "Rate limit backs off" in 4.2); `ScmStatusResults.rateLimited` and
  `GithubRateLimit.of` for a reset more than 24 h away (the hint is 86 400 000) and a reset
  before `Date` (zero pause); `SarifReader` reads a string `properties.details` into
  `SarifResult.details` (marker "SARIF details reach the finding's details" in 4.2f); the
  feature of "A red command without a findings file carries its output tail" asserts the tail
  as the result's `properties.details` on the contract path (FR2, FR4, FR5, FR6, FR9, NFR-R2,
  UX1, D19)
- [x] 4.0b The engine port carries a check round (D18, "The verdict answer crosses the engine
  port"; D14, sequencing): runs after 4.0a and before 4.1/4.2. 4.0a owns the pending facts' wire
  form and the contract side; this task owns the domain port only, and carries D19's facts across
  it: `record Pending(String key)` (`ExecutionResult.java:132`) becomes `record Pending(String
  key, @Nullable Duration pollAfter, PendingState state, @Nullable UntrustedText reason,
  @Nullable UntrustedText details)`, `enum PendingState { RUNNING, UNREPORTED, UNOBSERVED }` in
  `domain/engine` (absent wire field → `RUNNING`), filled by `ProgramResultMapping.toEngine`
  (`ProgramResultMapping.java:55`, today keeping the key only, `:66-73`), the one contract →
  domain mapping (D9 row, D8) for a check round and a transform round alike — no second switch
  over `Status`/`PendingState`/`details` is written beside it. Failed details (D18 as amended
  2026-11-12): `record Failed(FailureClass failureClass, UntrustedText reason, List<Finding>
  findings)` (`ExecutionResult.java:107`) gains `@Nullable UntrustedText details`;
  `ProgramResultMapping.failed` (`:106-116`) fills it from the result's `details` and, for
  `failed/quality`, builds its one finding as `new Finding(reason.raw(), null, details)` (today
  `:113-114`, details `null`). Every use of the record changes with it — the deconstruction
  `ExecutorOutcomeClassifier.java:61` and each construction: production
  `AgentRoundFailure.java:70`, `ProgramStageExecutor.java:80`, `ProgramResultMapping.java:99`
  and `:115`, `RoundFailures.java:46` (`domain/engine`); specs `ExecutionResultSpec.groovy:108,
  127`, `ExecutorOutcomeClassifierSpec.groovy:26`, `ExecutorOutcomeTableSpec.groovy:66`,
  `ExecutorReportedFailureSpec.groovy:79,196`, `EnginePreflightRecordedStopSpec.groovy:96`,
  `CannotExecuteDenialsSpec.groovy:85`, `RecordIdentitySpec.groovy:114` (`:domain`),
  `RoundLogLineSpec.groovy:47,90`, `RoundOutcomeWordsSpec.groovy:24,44-46,52-53`,
  `RoundOutcomeLedgerWriterSpec.groovy:141-142` (`:application`),
  `RoundTimeoutDenialReportSpec.groovy:132` (`adapters/git`), and the source fixture string in
  `OutcomeClassifierOwnerSpec.groovy:183` (`:bootstrap`); re-grep `new ExecutionResult.Failed(`
  and `Failed(var` before closing and list the hits in the report. The SARIF half: only the
  reading of one SARIF result is extracted from `ShellResult.asDomain` (`ShellResult.java:99`;
  `SarifResult` → `VerdictAnswer.Result`, `SarifResult.details` → `Finding.details`) into a
  function `ProgramResultMapping`'s verdict branch calls; the declared pair
  `ExecutionResult` ↔ contract `Status`/`FailureClass` gains `PendingState` at both ends —
  exhaustive switch, `ProgramResultMappingSpec` iterates `values()` of both — and this task edits
  that row's invariant in `.claude/rules/manual-sync-pairs.md` ("Declared pairs with no shared
  classpath") to name `PendingState`, with the class javadocs' `Kept in sync with` lines. Today `record Request(TaskContext context,
  StageDefinition stage, Workspace workspace, int attempt, List<CheckResult> feedback)`
  (`StageExecutor.java:62-63`) and `sealed interface Answer permits DecisionRequest`
  (`ExecutionResult.java:145`); the domain's polling port is only `PollStatus
  poll(VerifyCheck.Executor check, Workspace workspace)` (`ExternalCheckClient.java:40`), with no
  `start` and no key. Changes: `StageExecutor.Request` gains the check as its subject in role
  `verdict` beside the stage, and `@Nullable PendingTurn pending` (`record PendingTurn(String key,
  Origin origin)`, `enum Origin { ISSUED, RECORDED }`, `domain/engine/port`; D14);
  `ExecutionResult.Answer` permits `record VerdictReply(VerdictAnswer answer)` beside
  `DecisionRequest`; the check wrapper of 3.6 (`CheckRound`, which already calls
  `ProgramResultMapping.toEngine`, `CheckRound.java:66`) dispatches none → `start`, `ISSUED` →
  `poll(request, key)`, and `ProgramResultMapping` maps a contract verdict answer of a verdict
  round to `VerdictReply` through the extracted SARIF-result reading (its second consumer, rule
  of two); grep before closing: `new ExecutionResult.Pending(` and `new VerdictReply(` in
  production only in `ProgramResultMapping`; a
  stage round never takes a `VerdictReply` (`ProgramResultMapping.java:89-98` keeps refusing it for
  a transform round, the stage engine reads only `DecisionRequest`). Specs: `ExecutionResultSpec`
  over the new variant and `Failed.details`, the wrapper's two arms, a stage round refusing a
  verdict answer; `ProgramResultMappingSpec` over a failed result's `details` for each class (the
  `failed/quality` finding carries it) and a pending result's facts, iterating `values()` of
  `PendingState`; a mapping spec without a marker (the scenario's recorded half is 4.2f's
  `ShellDetailsFlowSpec`, which carries the marker of `executor/wire-contract` "SARIF details
  reach the finding's details"): a SARIF `error` result with `properties.details` becomes a
  `Finding` whose details are that text and whose message is unchanged;
  and, with the poll arm in place, the kit run as a spec against `http`, `github-actions` and
  `scm-status` through their entries (3.6 ran it for `command`, `files-exist` and `shell` only:
  their pending case needs the poll arm and a stateful fake server, and the SCM per-case binding
  over an attempt-commit workspace is first exercised there) (FR1, FR2, D14, D18)
- [x] 4.0c The pending key is one JDK-only leaf, `:pendingkey` (design D20; human decision
  2026-11-12, option B of the 4.1 blocker). Today `record PendingKey(@JsonValue String value)`
  with `MAX_LENGTH = 4096` and `static @Nullable String unusable(String)`
  (`executor-contract/.../executorcontract/PendingKey.java:16-41`), `record PendingTurn(String key,
  Origin origin)` (`PendingTurn.java:13`), `ExecutionResult.Pending(String key, …)`
  (`ExecutionResult.java:147-148`), the unwrap in `ProgramResultMapping.java:183-184` and the
  re-wrap `new PendingKey(turn.key())` in `CheckRound.java:80`. Changes:
  (a) new project `pendingkey` (`settings.gradle`, with a comment in the style of the
  `:untrustedtext` entry; `library-conventions` + `layering-conventions`, `allowedProjects = []`,
  no production artifact), package `com.github.oinsio.gnomish.pendingkey`, holding `public record
  PendingKey(String value)` (no Jackson annotation), `MAX_LENGTH = 4096`, the compact constructor's
  refusals and `public static @Nullable String unusable(String value)` moved verbatim in meaning
  (same refusal words); its own spec moved from `ValueTypesSpec` (blank, 4096 accepted, 4097
  refused, each control character refused, `toString` is the value) and the module's PIT scope
  100 %;
  (b) `executorcontract.PendingKey` deleted; `:executor-contract` declares `api
  project(':pendingkey')` and its `allowedProjects` becomes `[':untrustedtext', ':pendingkey']`;
  `ExecutorResult`, `Executor.poll`, `ResultReading` (`:67`, `:80`) name the leaf type;
  `ExecutorContractJson` registers a serializer (the value as a bare JSON string) for it beside the
  `UntrustedText` pair, and no deserializer — a result is read through `ResultReading` from
  `WrittenResult`'s `String` (`ExecutorResult.java:46`, `WrittenResult.java:21`), which no
  contract type bypasses (D20);
  the wire, the result schema and `ResultReading`'s refusal words are unchanged —
  `WireSchemasSpec`, `ExecutorResultReadSpec`, `PendingFactsReadSpec`, `AdditiveEvolutionSpec` pass
  with only their imports changed, plus one round-trip feature through `ExecutorContractJson` (a
  key written is the bare JSON string and reads back equal through `ResultReading`). The javadoc
  `{@link PendingKey}` of `Status.java:23` (same package today, no import) and every other
  same-package use (`ExecutorResult`, `Executor`, `ResultReading`) import the leaf type. Fix the stale sentence of the build
  file's header comment ("the engine in `:domain` … all reach this module": `:domain` does not; it
  reaches the leaf);
  (c) `:domain` declares `api project(':pendingkey')` with a comment in the style of its
  `:untrustedtext` entry, and lists it in its `allowedProjects` (`DomainLeafPuritySpec` admits it);
  `PendingTurn(PendingKey key, Origin origin)` and `ExecutionResult.Pending(PendingKey key, …)`;
  `ProgramResultMapping` passes `result.pendingKey()` through; `CheckRound`'s arms hand
  `turn.key()` to `poll` with no re-wrap;
  (d) every module whose production classpath now reaches the leaf without declaring it lists it
  in its layering allowlist with a comment naming the edge it arrives through (`:domain` or
  `:executor-contract`): `:application` (until 4.3a declares it for `PendingAdoption`), `:sandbox:core`, `:sandbox:docker`, `:httpegress`,
  `:githubapi`, `:gnomish-plugin-api`, `gnomish-plugin-api:sample`, `:adapters:github`,
  `:adapters:git` (until 4.1 declares it), `:bootstrap`; modules whose sources name the type declare
  it directly (`:adapters` — `HttpResults`, `HttpExecutor`, `ShellExecutor`, `FilesExistExecutor`; `:adapters:scm` —
  `GithubActionsResults`, `ScmStatusResults` and their executors; `:adapters:agent` —
  `ProgramExecutor`, `PendingRuns`, `ProgramSpawn`, `CommandStageExecutor`, `ProgramResultMapping`;
  `:bootstrap` — `CheckRound`, `ConformanceKit`; `:test-fixtures` — `InProcessExecutorDouble`) —
  the dependency-analysis gate decides which, and its verdict is the answer;
  (e) architecture specs: `:pendingkey` in `ModuleTreeSpec.NAMED_PROJECTS`; `pendingkey: [] as Set`
  in `ModuleBuildFileSpec.LEAF_PRODUCTION_DEPENDENCIES` (the leaf-table feature, already marked
  "The dependency-free leafs depend on nothing internal", then covers the leaf with no
  `LEAF_ALLOWED_PROJECTS` row); `:pendingkey` in `ReachEntrySpec.TRANSITIVE_LEAFS` and
  `:executor-contract` in `ReachEntrySpec.CARRIERS`, its javadoc naming the edge as it names
  `:githubapi`'s (without it a comment naming `:executor-contract` alone, as (d) asks, fails the
  spec); one new `ModuleBuildFileSpec` feature reading `domain/build.gradle` and
  `executor-contract/build.gradle` as data — both declare `api project(':pendingkey')`, and
  `:executor-contract`'s `allowedProjects` is exactly `[':untrustedtext', ':pendingkey']` — carrying
  the markers "The pendingkey leaf stays empty of dependencies" (beside the leaf-table feature's
  own marker for the emptiness half) and "The executor contract depends only on two JDK-only
  leafs"; the domain and reach scenarios that now name the leaf keep their existing markers
  (`DomainLeafPuritySpec`, `ReachEntrySpec`) or exemption lines (`verifyModuleLayering`);
  (f) plugin API: retyping two components of re-exposed `:domain` records is a binary break of the
  domain jar and the leaf jar joins the published graph — absorbed into this change's `0.15.0`
  (unreleased; `main` is `0.14.0`): `./gradlew :gnomish-plugin-api:updateApiCompatibilityBaseline`
  over the five-jar set, the version comment of `gnomish-plugin-api/build.gradle` and the README's
  "Versioning" entry naming D20 beside D16, `japicmpApiGate` green; the exemption line of
  "The baseline covers the published jar set" in
  `config/spec-contract-exemptions/plugin-plugin-api-contract.txt` (line 9) names the five jars
  (`gnomish-plugin-api`, `domain`, `untrustedtext`, `operatorevent`, `pendingkey`), as the
  MODIFIED "The published jar graph is the compatibility baseline" of the `plugin/plugin-api-contract`
  delta does.
  Consumers (D9 row `PendingKey`): `ResultReading`, `ExecutorContractJson`, `ExecutorResult`,
  `Executor`, `Status` (javadoc), `HttpResults`, `HttpExecutor`, `ShellExecutor`, `FilesExistExecutor`, `GithubActionsResults`,
  `GithubActionsExecutor`, `ScmStatusResults`, `ScmStatusExecutor`, `ProgramExecutor`,
  `PendingRuns`, `ProgramSpawn`, `CommandStageExecutor`, `ProgramResultMapping`, `CheckRound`,
  `ConformanceKit`, `InProcessExecutorDouble`, `PendingTurn`, `ExecutionResult.Pending`; and the
  specs that name the type (`grep -rln PendingKey --include='*.groovy'`: 26 files in
  `executor-contract`, `adapters`, `adapters/scm`, `adapters/agent`, `bootstrap`); and the eight
  specs that build a turn or a pending result from a `String` key and so name no `PendingKey` —
  `:domain`: `ExecutionResultSpec` (`:150`, `:160`), `ExecutionResultCheckRoundSpec`,
  `ExecutorOutcomeClassifierSpec`, `ExecutorOutcomeTableSpec`, `ExecutorReportedFailureSpec`,
  `engine/port/StageExecutorRequestSpec` (`:166`); `:application`: `status/RoundOutcomeWordsSpec`
  (`:44`, `:57`); `:bootstrap`: `app/CheckDispatchSpec` (`:119`) — each moved to `new
  PendingKey(...)` and its `.key()` comparisons to the typed value (dynamic Groovy compiles them
  as they are and fails only at run time, so they are run by name with this task). Old-way sweep
  before closing, hits listed in the report: `grep -rnE "PendingTurn\('|ExecutionResult\.Pending\('"
  --include='*.groovy' .` → zero; `grep -rn "PendingKey" executor-contract/src/main` → every file
  hit imports `com.github.oinsio.gnomish.pendingkey.PendingKey`; `grep -rn "executorcontract.PendingKey"` → zero;
  `grep -rn "record PendingKey\|class PendingKey" --include='*.java'` → `pendingkey/src/main` only;
  `grep -rn "new PendingKey(" --include='*.java' */src/main */*/src/main` → no argument read from a
  turn's or a result's `.key()`/`.value()`; `grep -rn "String key" domain/src/main` → no pending
  key. PIT scope: the leaf, `ExecutorContractJson`, `ResultReading`, `ProgramResultMapping`,
  `CheckRound` (FR2, FR3, D20)
- [x] 4.0d The run URL of a pass crosses the contract (design D23; human decision 2026-11-20,
  option A; runs after every ticked task, on the shape of 4.0a's `properties.details`). Contract:
  `SarifRun(UntrustedText tool, List<SarifResult> results)` (`SarifRun.java:12`) gains `@Nullable
  UntrustedText runUrl` and keeps only its canonical three-component constructor — no
  `SarifRun(tool, results)` convenience form, which would be the escape hatch that hid this
  regression (D23, `implementation.md` item 3); its seven production construction sites each pass
  their URL or `null` explicitly: `SarifReader.java:56`, `GithubActionsResults.java:76`,
  `HttpResults.java:90`, `ScmStatusResults.java:74`, `ShellExecutor.java:142`,
  `FilesExistExecutor.java:122`, `PinCheck.java:168`, and the thirteen test constructions
  (`AnswerReadSpec`, `WireSamples`, `AnswerTreeSpec`, `VerdictThresholdSpec`,
  `ResultDetailsReadSpec`, `SarifLogReadSpec`) likewise; `SarifReader.run` (`SarifReader.java:50-57`) reads the run's
  `properties.runUrl` when a non-blank string, minted in the writer's family through
  `WrittenTexts`, and ignores a blank value or another type (never malformed); `AnswerWriter.sarif`
  writes it as the run's `properties.runUrl` when present; `executor-answer.schema.json` gains the
  run's `properties` with a `runUrl` member and no type constraint, its description naming D23; no
  `protocolVersion` change (additive, D23). Built-ins: `GithubActionsResults.of`
  (`GithubActionsResults.java:42`) and `HttpResults.of` (`HttpResults.java:48`) render
  `PollStatus.Pass pass` as a completed answer whose one run carries `pass.runUrl()` (minted
  `tracker` and `manifest` respectively, like each one's findings), a `null` URL writing none; the
  javadoc of both classes and the "bare marker" paragraph of `GithubWorkflowRunPoll`
  (`GithubWorkflowRunPoll.java:38-42`) are rewritten to say the pass carries the run URL onward.
  Engine side: `SarifResultReading` (`app.port.check`, already `@UntrustedExit`) gains the reading
  of the run URL off a verdict answer — the first run, in the order written, carrying one, as a
  `@Nullable String` — and `ProgramCompletedAnswer.toEngine` (`ProgramCompletedAnswer.java:54`)
  builds the canonical `VerdictAnswer.Sarif(List<Result> results, @Nullable String runUrl)` from
  it. `VerdictAnswer.Sarif`'s one-argument constructor (`VerdictAnswer.java:45-47`) is deleted
  (D23): its one production caller, `RoundRecords.java:107` (the empty chain's vacuous pass),
  passes `null` explicitly, and so do its test constructions (about 37, in `:domain`,
  `:application`, `:adapters`, `:adapters:agent`, `:adapters:scm`, `:bootstrap` specs and
  `ScriptedAnswers` in `:test-fixtures`). Plugin API: `VerdictAnswer` is a re-exposed `:domain`
  type and `compat-baseline/` already holds this branch's `0.15.0` domain jar, so the removal is a
  binary break `japicmpApiGate` reports — absorbed into this change's one unreleased `0.15.0` bump
  (D20, as 4.0c (f) and 4.1r were): `./gradlew :gnomish-plugin-api:updateApiCompatibilityBaseline`
  over the five-jar set, the version comment of `gnomish-plugin-api/build.gradle` and the README's
  "Versioning" `0.15.0` row naming D23 (`VerdictAnswer.Sarif` lost its one-argument
  constructor), `japicmpApiGate` green. Gates: `UntrustedTextGateSpec`'s pinned exit set does not
  change; `UntrustedTextSinkGateSpec`'s pinned ambiguous-name list ("FR7: the names the scan must
  stay silent about are exactly these") gains `runUrl`, with a comment in the shape of the
  `resumedKey`/`details` entries: a carrier on `SarifRun`, a plain String on `Verdict.Pass`,
  `VerdictAnswer.Sarif`, `PollStatus.Pass` and the `state.json`/`status --json`/usage DTOs
  (`StateCheckDto`, `status/json/CheckDto`, `usage/json/CheckDto`); it stays decided wherever the
  receiver's type is declared. Exemption (D9 row): `CheckFindings.relayed` (a `shell` check's
  relayed SARIF findings file) reads runs through `SarifReader` and keeps their results only — the
  `shell` built-in's verdict is its own one run, and a relayed log's run URL is not carried (FR6
  covers `github-actions` and `http` only). Conformance: no kit case; the kit
  accepts a run with or without the property and rejects nothing new (D23). Specs, one feature per
  scenario, markers included: `executor/wire-contract` "A completed answer carries its run URL"
  (contract → engine: a SARIF answer with a run URL and no result at the threshold, through
  `ProgramResultMapping` and `VerdictDerivation`, records `Verdict.Pass.runUrl`), "The first run's
  URL is recorded" and "A malformed run URL is ignored" (`SarifReader` over an object, the answer
  still a verdict); `AnswerTreeSpec`'s round trip gains a run carrying a URL; `GithubActionsResults`
  and `HttpResults` specs assert the written `properties.runUrl` for a pass and none for a pass
  without a URL. Flow spec (the D9 row's identity spec): in `ThreeProviderVerifyChainSpec`
  (`:bootstrap`, `ThreeProviderPlatformFixture`, whose `stubRun` (`:127-133`) gains an `html_url`
  member, `…/actions/runs/<runId>`, which the feature asserts against), a feature asserting that the `github-actions` check's `Verdict.Pass.runUrl` equals
  the stubbed run's `html_url` and the `http` check's equals its target URL, through the real
  registry, `CheckRound`, `ProgramResultMapping` and `VerdictDerivation`, carrying `// Scenario:
  verification/github-external-check / A green check is auditable from the tracker` beside the two
  existing half markers (`GithubWorkflowRunVerdictWireMockSpec`, `TakeFinishReportRunUrlSpec`).
  Old-way sweep, in the report with its hits: `grep -rn "case PollStatus.Pass pass -> completed(List.of())"
  adapters` → zero; `grep -rn "new VerdictAnswer.Sarif(" --include='*.java' */src/main
  */*/src/main` → `ProgramCompletedAnswer` and `RoundRecords.java:107` (the empty pass, `null`),
  both with two arguments, only (no one-argument form is left to call, nor a two-argument
  `SarifRun`: the compiler is the gate); `grep -rn "bare marker" adapters/scm/src/main` → zero.
  PIT scope: `SarifRun`, `SarifReader`, `AnswerWriter` (`:executor-contract`),
  `SarifResultReading` (`:application`), `ProgramCompletedAnswer` (`:adapters:agent`),
  `GithubActionsResults` (`:adapters:scm`), `HttpResults` (`:adapters`), `VerdictAnswer`,
  `RoundRecords` (`:domain`); then the final root
  `./gradlew check` of 7.2 runs again (FR6, FR7, D23; NFR-O2 of add-sandbox-core)
- [x] 4.1 Add `PendingPollRecord` to the attempt state (`StateAttemptDto`, `StateJsonMapper`,
  round-trip spec, size cap on the key) (FR2, D7 and D20 as amended 2026-11-12). Carried from Q1
  of `define-executor-contract`, closed by D20: the record is `PendingPollRecord(CheckRef check,
  int attempt, PendingKey key, Instant since)` in `domain/engine`, the key the `:pendingkey` leaf's
  type (4.0c), never a `String`; `:adapters:git` declares the leaf (its mapper names the type) and
  drops it from the reach-only part of its allowlist. Its one state-file mapping is an optional
  `pendingPoll` object of the attempt in `StateAttemptDto`: `{"check": {"index": <zero-based
  position>, "ref": "<the check's label, as StateCheckDto.ref>"}, "key": "<value>", "since":
  "<ISO-8601 instant>"}` — the index carried in full, unlike the checks list's placeholder
  (`StateJsonMapper.java:31-38`), since the resume (4.3) picks the check by it; the attempt is the
  attempt the object sits in, never repeated. Cap: the key is built through the leaf's
  constructor, so the state file's cap **is** `PendingKey.MAX_LENGTH` (4096 UTF-16 characters, no
  ISO control character) with no second constant; a key it refuses, a missing `key`, `check` or
  `since`, or a malformed instant makes the file a `MalformedStateFileException`. An absent
  `pendingPoll` reads as no record, so a state file written before this change reads unchanged.
  The record holds no wait hint and no pending state (D19, D7 as amended): a resume polls at once.
  Specs: a round-trip spec (`toDto` → JSON → `fromDto` equal, a key of exactly 4096 characters
  included), the absent-field and the refused-key features, the JSON shape asserted as text; the
  scenario "Key lands before polling" is 4.2's. Consumers left to later tasks: `PendingPoll`
  writing it before the first poll and on a key change (4.2, D12), `VerifyOrchestrator` (4.2), the
  `StageAttemptLoop` resume path and `PendingAdoption` (4.3, 4.3a), the kill-point specs (D7)
- Order of the remaining tasks of this section (D18 as amended 2026-11-12, human decision on
  blocker B of task 4.2; as amended by the D21/D22 review of 2026-11-12, the host snapshot and the
  run's round cell before the pending commit that stands on them): 4.1r → 4.2 → 4.2c → 4.2a → 4.2b
  → 4.2d → 4.2e (formerly 5.1a) → 4.2f (formerly 5.1) → 4.3 → 4.3a → 4.6 → 4.4; 4.5 and 4.7
  anywhere in that order. 4.2e and 4.2f were moved here from section 5 for that order and
  renumbered into this section; 4.2c stands before 4.2a in this list and in the file.
- [x] 4.1r Rework 4.1: the record leaves the attempt for the open round's list (design D21, D20 as
  amended; human decision 2026-11-12). `PendingPollRecord(CheckRef check, int attempt, PendingKey
  key, Instant since)` (`PendingPollRecord.java:26`) becomes `PendingPollRecord(CheckRef check, int
  attempt, @Nullable RoundToken token, PendingKey key, Instant since)` (the token null only in the
  in-place run). `TaskState` (`public record TaskState(Position position, int attemptsUsed,
  List<AttemptRecord> attempts, UsageSnapshot totals, ExecutorVersions executorVersions)`,
  `TaskState.java:41-46`) gains `List<PendingPollRecord> pendingPolls`, set only through `TaskState
  withPendingPolls(List<PendingPollRecord>)`; every other writer (`atStageStart`,
  `recordUnburnedRound`, `recordQualityFailure`, `advanceTo`, `recordPassAndAdvance`,
  `startOfStage`, and the lifecycle rewrites that build a `TaskState`) yields an empty list.
  Delete `AttemptRecord.pendingPoll` (`AttemptRecord.java:106-117`, the last component) and
  `AttemptRecordChecks.requirePendingPollOfRound` (`AttemptRecordChecks.java:75-81`), and the `null`
  `RecordedRound.record` passes for it (`RecordedRound.java:44-45`). State file: delete
  `StateAttemptDto.pendingPoll` (`StateAttemptDto.java:78`); `StateJsonDto(int version,
  StatePositionDto position, int attemptsUsed, List<StateAttemptDto> attempts, UsageSnapshotDto
  totals, @Nullable EgressCursorDto egressCursor, @Nullable Map<String, String> executorVersions)`
  (`StateJsonDto.java:45-52`) gains `@Nullable List<StatePendingPollDto> pendingPolls` (absent =
  empty, so files written before read unchanged); `StatePendingPollDto` gains `attempt` and `token`;
  `StatePendingPollMapper.toDto(@Nullable PendingPollRecord)` (`:37`) and `fromDto(@Nullable
  StatePendingPollDto dto, int attempt)` (`:55`) become list mappings with the attempt read from the
  element; a missing `attempt`, a malformed `token` (through `RoundToken.of`) or a refused key is a
  `MalformedStateFileException`, as in 4.1. Specs: 4.1's round-trip, absent-field and refused-key
  features rewritten over the root array (a list of two entries included); a data-driven spec over
  every `TaskState` writer asserting the empty list (D9 row "`TaskState.withPendingPolls`"). The
  `[x]` of 4.1 stays: this task records what changes (FR20, D21)
- [x] 4.2 Implement `PendingPoll` in `domain/engine` (design D21; D18 as amended 2026-11-12: on fake
  ports only — no production wiring changes here, `ExternalPolling` stays wired until 4.2f). It adds the
  persistence port's new operation `void persistPending(String taskId, TaskState state)` beside
  `void persist(String taskId, TaskState state, ToolTrace trace)` (`AttemptPersistence.java:39`) —
  its contract: the attempts, position and counter equal the last persisted state's, the list is
  not empty, no trace — and the round's state and token handed in by `RoundExecution`:
  `VerificationResult verify(List<VerifyCheck> checks, TaskContext context, Workspace workspace,
  AttemptKey key)` (`VerifyOrchestrator.java:93`) gains `TaskState state, @Nullable RoundToken
  token`, the token being the one `RoundExecution` takes once before verification
  (`RoundExecution.java:132`, `:145-148`; `RoundIdentity`, `RoundIdentity.java:33`, unchanged).
  `PendingPoll` writes an entry per pending check and keeps ended entries until the round closes;
  it turns an entry into a `RECORDED` turn only when its attempt is the round's and its token equals
  the round's token, live and resumed through one method (D21, items 13 and 14). Persist-before-poll
  is asserted against a fake persistence (the first `poll` follows `persistPending`); a throwing
  `persistPending` (D21, "What a failed `persistPending` comes to") makes no `poll` and the check
  cannot-verify through `VerifyOrchestrator.runCheck`'s catch (`VerifyOrchestrator.java:151-161`),
  a fake-port feature; the markers of "Key lands before polling", "Every pending check of the
  round is continued" and "An entry of another round is not continued" go on the fake-port
  features here, re-asserted on the real medium in 4.3 ("A pending entry names no outcome" is
  4.2b's: its THEN needs a pickup). Otherwise as before, through the
  engine's `StageExecutor` port as 4.0b extended it (a request with the check as subject; `start`
  is a request with no turn, `poll(key)` one with an `ISSUED` turn): delivery
  precondition, `start`, persist-before-poll, `poll(key)` at `max(interval, pollAfter)` (no in-poll wait
  bound, NG13), a hint reaching past the deadline ending the wait with no
  poll, timeout classified first by the last pending answer's `PendingState` (`UNREPORTED`,
  `UNOBSERVED` → cannot-verify whatever the class) and then by the declared class; the timeout
  result's details are the reason of the last pending answer whose state was not `UNOBSERVED`,
  followed by the last answer's reason when that one was `UNOBSERVED` (the last reason alone when
  no answer was other than `UNOBSERVED`), both kept in memory for the wait and not persisted
  (D19 "The texts" as amended 2026-11-12; this replaces the earlier
  "absent-expected-context → infrastructure" item); the timeout classification by
  `PendingState` applies to the check subject (D19 "Subject"); virtual-time specs for every
  scenario of the `pending-poll` spec — "Timeout after a rate-limited read still names the
  missing contexts" included (absent → `unobserved` with a hint past the deadline → the
  cannot-verify names the absent context, then the rate-limit reason) — and of the
  `executor/wire-contract` scenarios "Wait hint delays the next poll", "Wait hint past the
  deadline ends the wait", "Unreported subject at the timeout cannot verify" and "Unknown pending
  state keeps the declared class", markers included; and the feature of
  `verification/verdict-executors` "Rate limit backs off" (marker moved here from 4.0a): `scm-status`
  over a WireMock server answering a rate limit with `retry-after`, driven by `PendingPoll` on
  virtual time, asserting the result's `unobserved` state and hint and that no poll of the key
  is made sooner (FR2, FR3, FR9, UX1, D2, D19)
- [x] 4.2c The host snapshot, the run's round cell and the container's close outcome (D22; human
  decisions 2026-11-12). Runs before 4.2a.
  **The step.** One snapshot step over a medium strategy (D8, the `ResumeMechanics<B>` shape). The
  step owns the subject (`ServiceCommitMessages.snapshot(String stage, int round, RoundToken token)`,
  or the new `ServiceCommitMessages.unverifiedSnapshot` for an `UNVERIFIABLE` close; `AdmissionRule.snapshot`
  takes the subject the step chose), the range judgement (`GnomeRangeInspection`), the empty-round
  commit, the register and `CurrentRound.snapshotted`. Strategies:
  - the container's in-box commit + harvest + admission (today `public
    EnvironmentRoundSnapshot(TaskExecutionEnvironment environment, GitRunner runner, Path cloneDir,
    CurrentRound rounds)`, `public String snapshot(String taskId, String stage, int round)`,
    `EnvironmentRoundSnapshot.java:68-69`, `:89`; trigger at the container round close);
  - the host worktree: `EnvelopeRestore`, `git add -A`, `WorktreeClosingCommit.commit` with a new
    allow-empty form for this caller only (today it refuses an unchanged tree,
    `WorktreeClosingCommit.java:80-82`).
  **Host boundary verdict first (D22, review of 2026-11-12).** The host strategy takes
  `GnomeRangeInspection` from the opened-on tip, then `EnvelopeRestore.uncommittedEdits()`, *before*
  `EnvelopeRestore.restore()` (the order of `GitAttemptPersistence.verifyBoundary`,
  `GitAttemptPersistence.java:158-171`). On `Violated` or `CannotVerify` it restores, commits and
  throws nothing, and the cell stays without `snapshotted`: the round's `persist` judges the same
  tree and ends the task `Aborted`, as today.
  **Host trigger.** A git-mode host stage-chain decorator snapshots after the delegate returns
  `completed`, not after `failed`, not after `decisionNeeded`. It sits inside
  `ResumeVerificationStageExecutor`, so a resumed round is not snapshotted again, and outside
  `ExecutorDispatch`.
  **The run's round cell (D22, "The run's round cell has one owner").** One `CurrentRound` per
  git-mode host run, constructed by the run preparation at `GitModeRunner.java:125`,
  `TakeEngineExecution.java:130` and `GitResumeContinuation.java:150`, travels with the resume's
  `@Nullable PendingVerification` as one per-run value of `app.port.git`. The pending verification
  reaches the preparation as an explicit argument, the shape of the container's
  `TakeContainerResumeRunner.java:93` (`…, finalState, pending)`), `:112` (`null`) and
  `TakeContainerFreshClaim.java:122` (`null`); never by a second read of the register inside the
  preparation (crash-consistency item 14):
  - `TakeResult run(TakeOrder order, ResumeBootstrap bootstrap, TaskContext context, TaskState
    state)` (`TakeEngineExecution.java:122`) gains `@Nullable PendingVerification pending`. Callers:
    `TakeFreshClaim.java:137` passes `null`; `TakeResumeRunner.resumeWithoutDecision`
    (`TakeResumeRunner.java:105-119`, the call at `:119`) passes the no-salvage owner's answer
    (4.2d); `TakeResumeRunner.resumeDecided` (`:146`) passes `null`.
  - `private void runToTerminalBoundary(RunOrder order, TaskContext context, TaskState state)`
    (`GitResumeContinuation.java:147`) gains `@Nullable PendingVerification pending`. Callers:
    `resumeFromRecordedPosition(RunOrder order, TaskState finalState)` (`:75-82`) passes the
    owner's answer (4.2d); `resumeEscalated` (`:112`) and `resumePaused` (`:127`) pass `null`.
  - `GitModeRunner.java:125`, the fresh run, holds `null`.
  Until 4.2d moves the no-salvage rule into its owner, the two resume callers pass `null`.
  The cell is handed to:
  - `AttemptPersistence attemptPersistence(Path worktree, String taskId)` (`TaskStoreGit.java:43`),
    which becomes `attemptPersistence(Path worktree, String taskId, CurrentRound rounds)`, and its
    `GitTaskStore` implementation (`GitTaskStore.java:86-88`) and fakes;
  - `TaskGit`'s `midRoundPush` (`UnaryOperator<RoundEnvironmentSource>`, `TaskGit.java:49`), which
    becomes a role interface of `app.port.git` that decorates a host source over a given cell;
  - `public MidRoundPushRounds(RoundEnvironmentSource host, GitRunner runner, InstantSource clock)`
    (`MidRoundPushRounds.java:77`), which takes the cell instead of `new CurrentRound()` (`:58`);
  - `ManualRunAssembly.withHostGitPush(UnaryOperator<RoundEnvironmentSource> decoration)`
    (`ManualRunAssembly.java:195`), which also takes the per-run value, read by `stageRounds` for
    the snapshot decorator (and by 4.2d for `ResumeVerificationStageExecutor`).
  No setter or attach step: the cell exists before both its readers are constructed.
  **The closed round's registered commit.** `public record ClosedRound(RoundToken token, String
  attemptCommit)` (`ClosedRound.java:17`) becomes `ClosedRound(RoundToken token, String
  attemptCommit, String registered)`. `registered` equals `attemptCommit` after
  `snapshotted(String attemptCommit)` (`CurrentRound.java:60`) and after `restore`; 4.2a adds the
  one writer that moves it (`pendingRegistered`). Every reader of `ClosedRound` keeps reading
  `attemptCommit` for the snapshot and reads `registered` for the parent and the range base.
  **Host persistence.** `public GitAttemptPersistence(GitRunner runner, Path worktreeRoot, String
  taskId, ClaimEpochSource epochs)` (`GitAttemptPersistence.java:99`) gains `CurrentRound rounds`.
  The base of its round boundary, and of `BestEffortPush.pushBestEffort(…, String previousTip)`'s
  ancestry guard (`BestEffortPush.java:93-125`, called at `GitAttemptPersistence.java:150-151`), is
  `ClosedRound.registered` when the round was snapshotted and the tip the round opened on
  otherwise. The field `previousTip` (`:88`, set `:109` and `:143`, read `:130`) is deleted.
  **The container's close outcome (D22, "The container's round close names its outcome"; human
  decision 2026-11-12).** `void closeRound()` (`RoundEnvironmentSource.java:72`) takes a two-valued
  close outcome, `VERIFIABLE` or `UNVERIFIABLE`. Its implementations follow:
  `HostRoundEnvironmentSource` (`:71`, ignores it), `MidRoundPushRounds.PushRound` (`:134`,
  forwards it), `SandboxRoundEnvironmentSource.SandboxRound` (`:179`, hands it to the step), and the
  specs' fakes. Callers:
  - `ExecutorRoundExecution.java:132` passes `VERIFIABLE`.
  - `ProgramStageExecutor.java:66` passes `VERIFIABLE` exactly when `ProgramResultMapping`, the one
    mapping, maps the contract result to a `completed` engine result. That judgement is made before
    the close; the denials read after it still join the mapped result. The task chooses the
    spelling inside `ProgramResultMapping`, never a second judgement beside it.
  Glossary (`docs/glossary.md`): the round-token entry's "rides the snapshot commit's subject in
  container mode" covers both modes, and a new **unverified snapshot** entry is added.
  **Specs.**
  - `HostSnapshotFlowSpec`, the identity spec of the D9 row: a completed host round's checks
    observe exactly the registered snapshot's tree, and its round commit's parent is the snapshot.
    The pending-commit case is 4.2a's.
  - The failed and decision rounds land one commit.
  - A host round with an uncommitted envelope edit, and one with a range violation: no snapshot,
    `Aborted` with the violation. These carry the marker of the stable scenario
    `git/git-task-persistence` "An uncommitted envelope edit does not ride the host round commit"
    and of the new "A boundary violation of a completed host round takes no snapshot".
  - One feature per scenario of the MODIFIED "Round commit of the whole working tree" ("Host round
    atomicity", "A completed host round is verified at its snapshot", the two kept scenarios),
    markers included.
  - A container program round answering `failed`, killed after its close and before its state
    commit (a stand-in program on the container medium), resumes through the stage executor with
    no attempt burned. It carries the marker of `git/git-task-persistence` "A failed program round
    is not resumed as completed".
  - `SnapshotTipCheckSpec` gains a feature for a registered unverified snapshot (empty).
  (FR19, D22)
- [x] 4.2a The pending commit at both persistence ends (D21 as amended by the review of 2026-11-12;
  D8, touched pair `GitAttemptPersistence` ↔ `EnvironmentAttemptPersistence`; after 4.2c, whose
  cell and host snapshot it stands on). `persistPending` writes exactly `state.json` with the
  subject `gnomish: pending <stage>#<round> <token>`. The subject comes from a new
  `ServiceCommitMessages.pending(String stage, PendingPollRecord last)` (beside
  `round(AttemptRecord)`, `ServiceCommitMessages.java:49`): the stage from `state.position()`
  (`Position.AtStage`, `Position.java:29`), round and token from the list's last entry, the same
  source on both ends. The parent is the round's last registered commit, the register moves in one
  ref transaction, then the closing push. A cell with no snapshot refuses before any write.
  `CurrentRound` gains `void pendingRegistered(String commit)` beside `snapshotted(String
  attemptCommit)` (`CurrentRound.java:60`): it moves `ClosedRound.registered` (introduced by 4.2c)
  to the landed pending commit. The host persistence already bases its round boundary and the
  `BestEffortPush` ancestry guard on `ClosedRound.registered` (4.2c), so a round with pending
  commits is judged from the last of them with no further change there.
  **Host.** Built from objects in the host clone's `.git` through `GitObjects`, no worktree and no
  index (the `GitObjectsTerminalCommits.cleanUp` precedent, `GitObjectsTerminalCommits.java:22-28`):
  - `state.json` becomes a blob; the tree is `registered`'s tree with that blob replaced;
    `commit-tree -p <registered>`;
  - `FactoryRegister.close(Path repo, String taskId, String commit, String expectedTip)`
    (`FactoryRegister.java:64`) with `expectedTip` = `registered`.
  The persist's working-tree boundary then recognises the lag (D21): it restores
  `.gnomish-task/state.json` from the register before `uncommittedEdits()` when the index's and
  the working tree's copies both equal the `state.json` of a registered commit of the round (first
  parents from `ClosedRound.registered` to `ClosedRound.attemptCommit`).
  **Container.** The in-box commit is built through a private index (`GIT_INDEX_FILE` read from
  `HEAD`, only `state.json` added), then `commit-tree -p <registered>` and `update-ref HEAD
  <commit> <registered>`. Then the harvest, a new `AdmissionRule.pending` (parent = `registered`,
  exactly `state.json` changed), and the read-back. `HarvestedStateCommitCheck.verify(String taskId,
  String tip, String snapshot, String tracePath, byte[] stateBytes, byte[] traceBytes)`
  (`HarvestedStateCommitCheck.java:67`) takes the expected parent (`registered`) in place of
  `snapshot`, for the pending and the state commit alike (`verifyParent`, `:76-95`).
  **Decorators.** `PushBestEffortAttemptPersistence` (`:41`) forwards the write.
  `RevocationCheckingAttemptPersistence` (`:150`) forwards it too, and once it has recorded a
  revocation (`revocation()`), every later `persist`/`persistPending` through it throws before
  its delegate. `InMemoryAttemptPersistence` (`:30`) keeps the write in memory (in-place run: "An
  in-place run keeps the key in memory").
  **The working-copy check across a pending commit** (`adapters/agent`, `WorkingCopyCheck` and
  `StatusEntries`; D21, "The lag the write leaves behind", as amended by review r2 of 2026-11-12).
  When the view's `branch.oid` moved, the move counts as the factory's only when the `branch.oid`
  after the run equals the round's last registered commit, read from the run's `CurrentRound`
  through a new read-only role interface of `app.port.git` that `CurrentRound` implements (one
  method, the round's last registered commit, empty when the round has none — then any move is
  refused). The role reaches the check through `ProgramRoundEquipment` (built per run in
  `ExecutorAdapterSelector.stageRounds`, `ExecutorAdapterSelector.java:93`, from the host's per-run
  value of 4.2c or `SandboxRunPieces.rounds()`) and `ProgramExecutor` (`new WorkingCopyCheck(...)`,
  `ProgramExecutor.java:136`), so `WorkingCopyCheck(TaskExecutionEnvironment environment, Duration
  bound, InstantSource clock)` (`WorkingCopyCheck.java:76`) gains it; the conformance kit's
  `ProgramCandidate` (`ProgramCandidate.java:116`) and the in-place run pass the role's local
  default, which holds no commit. No `git diff` subprocess is run: the move is judged by identity,
  never by content (the content rule is rejected in D21). A `.gnomish-task/state.json` entry counts
  as lag only when its index object equals `<before>:.gnomish-task/state.json` and its working-tree
  column is unchanged. The path comes from `EnvelopePaths` (`:domain`), not a literal. Every other
  difference still refuses, any commit of the run included.
  **Pair and docs.** Both ends' `Kept in sync with` javadoc gains the pending commit. The registry
  row of `.claude/rules/manual-sync-pairs.md` ("Initial registry", `GitAttemptPersistence`) gains
  "and the pending commit: exactly `state.json`, its tree `registered`'s with that one blob
  replaced and its only parent `registered` (host from objects, container through a private
  index), its register in the same transaction". Glossary (`docs/glossary.md`): a **pending
  commit** entry, and the closing-commit entry lists it beside the round snapshot.
  **Specs.** One feature per scenario of `git/git-task-persistence` "Pending commits of an open
  round" except the two resumed ones ("A pending commit sits on the round's last registered
  commit", "A second pending check stacks on the first pending commit", "A pending commit is built
  from the registered commit alone", "The factory's own pending commit is no envelope edit at the
  round close", "A harvested pending commit that changes another path is refused"). Then one
  feature per scenario of the MODIFIED "Sandboxed round protocol: snapshot and state commits"
  ("Daemon-inserted commit is caught by the parent-check" against `registered`, "The state commit
  sits on the round's last pending commit"), markers included. Also:
  - `HostSnapshotFlowSpec` gains the case "the round commit's parent is the round's last pending
    commit";
  - `HarvestedStateCommitCheckSpec` runs over a pending parent;
  - one feature per scenario of the `executor/wire-contract` ADDED requirement "The factory's own
    pending commit is not a change of the run", markers included (`WorkingCopyCheck` over a real
    git repository; "An empty commit of the run is refused" both with and without a pending commit
    landed during the run).
  "Interrupted verification resumes through a pending commit" is 4.2b's, and "A key rotation
  stacks a pending commit" is 4.6's. (FR2, FR10, FR20, D21)
- [x] 4.2b The recogniser sees through a pending commit (D21). `public SnapshotTipCheck(GitRunner
  runner, Path cloneDir)` (`SnapshotTipCheck.java:74`), `public Optional<PendingVerification>
  inspect(String taskId)` (`:88`): a registered `gnomish: pending` subject is followed by first
  parents over `pending` subjects of the same stage, round and token to the snapshot of that
  identity; anything else is refused as today (`warnMalformed`, empty). `public record
  PendingVerification(String attemptCommit, String stage, int round, RoundToken token,
  Optional<RoundDecisionRequest> request)` (`PendingVerification.java:41-42`) gains `String
  registered`; `CurrentRound.restore(PendingVerification pending)` (`CurrentRound.java:70`) restores
  both commits. `ResumeVerificationStageExecutor` stays the one owner that skips the executor; the
  entries reach `PendingPoll` with the resumed `TaskState` read at the register through
  `StateJsonMapper`. Specs: `SnapshotTipCheckSpec` features for a registered pending commit, a
  stacked one, and a pending commit whose parent is not a snapshot of its identity (refused);
  markers of `git/git-task-persistence` "Interrupted verification resumes through a pending commit",
  `lifecycle/task-branch-contract` "A registered pending commit classifies by its envelope" and
  `verification/pending-poll` "A pending entry names no outcome" (on the container medium, whose
  resume decorator is already wired; 4.3 re-asserts it on both media) (FR3, FR20, D21)
- [x] 4.2d The host resume of an interrupted verification (D22). `static StageRounds
  stageRounds(ManualRunAssembly assembly, ChildEnvAllowlist childEnv, PipelineLaw law, DescribedLaw
  described, PipelineDefinition definition, CheckRunContext runContext)`
  (`ExecutorAdapterSelector.java:70-76`) wraps both media's stage chain in
  `ResumeVerificationStageExecutor` (`public ResumeVerificationStageExecutor(StageExecutor delegate,
  CurrentRound rounds, @Nullable PendingVerification pending)`, `:56`; today sandbox only,
  `:98-100`). The host wrapping uses the per-run value of 4.2c (the cell and the pending
  verification) and a `SnapshotTipCheck` over the host clone. A host snapshot carrying no decision
  request re-verifies; a container snapshot carrying one is re-raised as today
  (`ResumeVerificationStageExecutor.java:81-83`).
  **The no-salvage rule.** One owner of "no salvage over an interrupted verification": the rule
  leaves `static @Nullable PendingVerification prepare(SandboxRunSupport support, boolean
  discardWork, Position position, String taskId)` (`ContainerResumePreparation.java:44-56`). Its
  callers:
  - `ContainerResumePreparation.prepare` itself;
  - `GitResumeContinuation.resumeFromRecordedPosition(RunOrder order, TaskState finalState)`
    (`GitResumeContinuation.java:75-80`);
  - `TakeResumeRunner.resumeWithoutDecision(TakeOrder order, ResumeBootstrap bootstrap, TaskState
    finalState)` (`TakeResumeRunner.java:105-110`);
  - the two revocation stops, `RevocationHandler.handle` (`RevocationHandler.java:75`, wired at
    `TakeEngineExecution.java:153`) and `ContainerRunSupport.revocationSalvageAndPush`
    (`ContainerRunSupport.java:308-311`).
  The two host resume callers hand the owner's answer on as the `pending` argument 4.2c added
  (`TakeEngineExecution.run(…, pending)`, `runToTerminalBoundary(…, pending)`), where they passed
  `null` until this task.
  Over a registered snapshot or pending commit nothing is salvaged. The host discards the residue
  (`WorktreeSalvage.discard`, `WorktreeSalvage.java:105-111`), with or without `--discard-work`.
  The container skips its salvage. Both revocation stops still push, and the stop note names the
  discard.
  **Sweep.** `grep -rn "\.salvage(" application/src/main bootstrap/src/main`: every hit routes
  through the owner or is listed with its reason (a round killed before its snapshot).
  **Specs.**
  - One feature per scenario of `git/git-task-persistence` "No salvage over an interrupted
    verification", "A revocation during a verification lands no salvage commit" included, and of
    `lifecycle/task-branch-contract` "An interrupted verification is resumed by its one owner",
    markers included.
  - A host kill after the snapshot, resumed by a second instance on a real bare origin, runs no
    stage executor and burns no attempt.
  - The same on the `take` path: a host `take` resume over a registered snapshot
    (`TakeResumeRunner.resumeWithoutDecision` → `TakeEngineExecution.run(…, pending)`) runs no
    stage executor, burns no attempt and starts no check whose key the pending commit records; the
    `run` path's feature does not cover it.
  (FR19, D22)
  Note from 4.1r (2026-11-12): `Engine.run` calls `state.startOfStage()` (`Engine.java:58`), whose
  normalising branch (`advanceTo`) empties `pendingPolls`; a pending commit of a stage's first round
  carries the previous stage's PASSED attempt, so a resume that reads the open round's entries from
  the state after `startOfStage` loses them and re-`start`s. Show, with a kill-point feature on a
  second stage, that the resume reads the entries before that normalisation (or from
  `PendingVerification`), never after it.
- [x] 4.2e (formerly 5.1a) Wire contract: add `checkIdIsPinnedPath` (boolean, default false) to the describe
  schema and `Describe`, with its round-trip spec and the conformance kit reading it; one feature
  per scenario of the `executor/wire-contract` ADDED requirement "A describe may pin the check's
  id", markers included (FR16, D17); the `github-actions` describe (`GithubActionsExecutor`,
  3.3) then declares `checkIdIsPinnedPath = true`, which 3.3 could not set before the field existed
- [x] 4.2f (formerly 5.1) `VerifyOrchestrator` hands each check round to the engine's `StageExecutor` port, the
  request naming the check as its subject in role `verdict` (the port as 4.0b extended it;
  `domain` cannot name the registry or the contract), takes the result's
  `ExecutionResult.VerdictReply` into `VerdictDerivation.derive(VerdictAnswer answer, Threshold
  threshold)` (`VerdictDerivation.java:41`), and `PendingPoll` drives the same port; the check dispatch behind the port resolves the registry
  entry and binds it to the check and its working copy (D18 as amended); `PinCheckedExternalCheckClient` becomes a pin decorator over
  `Executor` whose pin set is the law's `pinPaths` ∪ `checkId` when the executor's describe
  declares `checkIdIsPinnedPath` (D17; `GithubCheckPinPaths` deleted; `PinDecoratorSpec`
  data-driven over the flag, one feature per scenario of the MODIFIED pin requirements, markers
  included); ordered fail-fast specs green (FR1, FR2, FR16, D8). Where a contract result's
  `details` travel is settled by D19 (2026-11-10): SARIF `properties.details` and a result-level
  `details`, written by the executors in 4.0a and mapped into `Finding.details` and
  `CannotVerify.details` by the one contract → domain mapping of 4.0b (`ProgramResultMapping`;
  `VerifyOrchestrator` carries a `failed/infrastructure` result's `ExecutionResult.Failed.details`
  into `CannotVerify.details`); this task routes every check through that mapping and keeps green
  on the contract path the scenarios "A red command without a findings file carries its output
  tail" and `verification/check-provider-model` "A hung check fails instead of hanging the run"
  (the timeout's output tail in its finding's details; its marker moves from
  `ShellCommandCheckRunnerTimeoutSpec.groovy:35`, on the old `CommandCheckRunner` port path, to a feature
  on the contract path). Identity spec (D9 row): `ShellDetailsFlowSpec` — a `shell` check through
  the real environment whose command writes no findings file and exits red, its output tail
  written as `properties.details`, read back from the state file as `Finding.details` with the
  message unchanged; it carries the marker of `executor/wire-contract` "SARIF details reach the
  finding's details" (D9, D19).
  [Moved from section 5 as 5.1; D18 as amended 2026-11-12, blocker B of task 4.2.] This task is the
  production cut-over: `VerifyOrchestrator` drives every executor check through `PendingPoll`,
  `ExternalPolling` is deleted, and the run assembly routes a check request
  (`request.check() != null`) to `CheckDispatch.bind(check, workspace).execute(request)` outside
  `ResumeVerificationStageExecutor` and `ExecutorDispatch` (`ExecutorDispatch(ExecutorRegistry
  registry, RoundEnvironmentSource rounds, StageExecutor agent)`, `ExecutorDispatch.java:50`, whose
  `execute` ignores the check, `:57-66`; `record StageRounds(StageExecutor executor, RoundIdentity
  identity, CheckDispatch checks)`, `ExecutorAdapterSelector.java:113`, whose `checks` is read here
  for the first time), with the pin decorator above in the same task, so no tree runs a check
  unpinned or through the stage chain; a spec asserts a check request reaches no stage executor and
  no resume decorator.
  Owner of the `verification/pending-poll` scenario "Oversized key is infrastructure" (noted by 4.2):
  a check round whose result carries a key over `PendingKey.MAX_LENGTH` is refused at the read
  (`ResultReading`) and resolves through the wrapper as cannot-verify naming the cap, with no poll;
  one feature on the switched production path, marker included.
  Note from 4.2e (2026-11-13): the markers of `executor/wire-contract` "Declared flag pins the check
  id" and "Absent flag pins nothing of its own" sit in `DescribePinFlagSpec` on the wire half of
  their THEN only; the pin half (the pin set built from the registry entry's describe flag) is this
  task's pin decorator, so its spec carries both markers again on features that assert the pin set.
- [x] 4.3 Resume path on both media (D21, D22; after 4.2f): the resumed `TaskState` read at the
  registered commit carries the entries, and `PendingPoll` continues each live one without `start`;
  `poll` unsupported → infrastructure; kill-point specs on a real bare origin, container and host
  each, for windows W0 (snapshot registered, no `start`), W1, W1′ (pending commit landed locally,
  push refused), W2, W3 (after a key-rotating poll, before its pending commit), W4 (after a terminal
  poll, before the round's closing commit) and after the closing commit, each with a no-op second
  recovery; W2–W4 assert no `start`. The resuming clone is named per window (D21's table): W0 and
  W1 are resumed both on the clone that wrote the snapshot (re-verify, no stage executor) and by a
  second instance on another clone (origin's register is the previous closing commit: salvage and
  re-run of the stage executor, no attempt burned; on the container, except a snapshot that
  attempt delivery pushed); W1′ on this clone as W2, on another as W0/W1; W2–W4 and after the
  close by a second instance on another clone; markers of the
  `verification/pending-poll` scenarios re-asserted on the real medium and of
  `lifecycle/task-branch-contract` "The round close drops the pending entries" (D7 and D21 as
  amended, NFR-R1, NFR-R4, M3)
  Note from 4.1r (2026-11-12): `Engine.run` calls `state.startOfStage()` (`Engine.java:58`), whose
  normalising branch (`advanceTo`) empties `pendingPolls`; a pending commit of a stage's first round
  carries the previous stage's PASSED attempt, so a resume that reads the open round's entries from
  the state after `startOfStage` loses them and re-`start`s. Show, with a kill-point feature on a
  second stage, that the resume reads the entries before that normalisation (or from
  `PendingVerification`), never after it.
  Note from 4.2d (2026-11-13): 4.2d added `Engine.resumedFrom` (entries carried across
  `startOfStage`, `EngineResumedStateSpec`, second-stage W2 in `HostInterruptedVerificationTakeSpec`).
  Open: the engine hands `persistPending` the state after `advanceTo` (a stage's first round
  records `attempts=[]`), while D21 item 12 and the port's contract say the attempts equal the last
  persisted state's; settle which side is right (`/architect` Mode C if the design moves) and pin it
  here. Also the D9 row "The interrupted-verification resume" lists "pending entries" inside
  `PendingVerification`, whereas 4.2b/4.2d carry them in the `TaskState` read at the register;
  align the row with the code.
  Note from 4.2f (2026-11-13): the re-assertion handed on by 4.2d — a resume over a pending commit
  starts no check whose key the pending commit records, on the real medium for host `run` and
  `take` — needs a pollable check on the real medium (an `http` server or a `program` with
  adoption); the host-take world's only check is `shell`, never pending. It is this task's W2–W4
  "no `start`" features. Also check that `CheckRound` passes `Bounds.none()` (the interval never
  travels as the in-poll wait bound) is what D14 intends.
  Split (2026-11-13): the host half of the matrix is done here (`HostPendingPollKillPointSpec`,
  `HostPendingPollOtherCloneSpec`, `EnginePendingWriteStateSpec`; D21 item 12 met in the engine,
  the D9 resume row aligned); the container half is 4.3b, with no window or assertion dropped.
- [x] 4.3b Resume path on the container medium (split from 4.3 on 2026-11-13; D21, D22): the same
  kill-point matrix as 4.3, on the container medium — W0, W1, W1′, W2, W3, W4 and after the closing
  commit, each with a no-op second recovery, W2–W4 asserting no `start` over a pollable check
  (the `http` built-in on a loopback server, as 4.3's host matrix). Resuming clone per D21's table:
  this clone without a daemon (`LocalBoxEnvironment` through the engine, as `PendingCommitResumeSpec`);
  another clone as a Docker end-to-end spec on the `ContainerParkSpecBase` base (Gitea,
  `FakeAgentSandboxImage`) with kill points after the snapshot, after the pending commit and after
  the terminal poll — W0/W1/W1′ salvage and re-run the stage executor, no attempt burned, except a
  snapshot that attempt delivery pushed. Markers of `verification/pending-poll` and
  `lifecycle/task-branch-contract` "The round close drops the pending entries" re-asserted on this
  medium (D7, D21, NFR-R1, NFR-R4, M3)
- [x] 4.3a Program executors on the resume path (carried from NG7 and design D3 "Poll of a
  program" of `define-executor-contract`): as built there, `ProgramExecutor.poll` refuses, with no
  process spawned, a key absent from its `PendingRuns`, and the working-copy view the `verdict`
  poll is judged against (FR11, NFR-S4 of that change) is held in that same entry. Today the table
  is one executor instance's (`new PendingRuns()`, `ProgramExecutor.java:42`), while a check binds
  a new executor per request (`CheckRoundRoute.java:34-38`), and a persisted key resumed by a
  second instance (FR3) has no entry anywhere. Implement design D14 as amended 2026-11-13 over the
  port of 4.0b (which already carries `@Nullable PendingTurn pending` and the wrapper's none and
  `ISSUED` arms):
  - *Done in this task's first half:* `PendingPoll` sets `RECORDED` on the resume path only
    (`PendingPoll.java:91`); the wrapper's arm `RECORDED` → `PendingAdoption.adopt(request, key)`
    then `poll`, `ISSUED` → `poll` (`CheckRound.java:93-101`); `PendingAdoption` in `app/port/check`
    with `:application` declaring `project(':pendingkey')` (D14 as amended by D20);
    `ProgramExecutor implements PendingAdoption`; `RecordedKeyAdoption`; `PendingAdoptionOwnerSpec`.
  - *The check's subject on the port.* `StageExecutor.Request` (today `record Request(TaskContext
    context, StageDefinition stage, Workspace workspace, int attempt, List<CheckResult> feedback,
    VerifyCheck.@Nullable Executor check, @Nullable PendingTurn pending)`, `StageExecutor.java:76-83`)
    gains `@Nullable CheckRef checkRef` after `check`, set together with it (the compact constructor
    refuses one without the other); the stage-round constructor (`:99-106`) keeps its signature and
    passes null for both. `PendingRound.request(VerifyCheck.Executor check, @Nullable PendingTurn
    turn)` (`PendingRound.java:125-126`) becomes `request(VerifyCheck.Executor check, CheckRef ref,
    @Nullable PendingTurn turn)`, and `PendingPoll.answer` passes the `ref` it already holds
    (`PendingPoll.java:83`, `:93`, `:129`). `CheckRoundRoute.execute` (`CheckRoundRoute.java:34-38`)
    hands `request.checkRef()` to `CheckDispatch.bind(VerifyCheck.Executor check, Workspace
    workspace)` (`CheckDispatch.java:44`), which gains `CheckRef ref` after the check, as do
    `RegistryEntry.executor(VerifyCheck.Executor judged, Workspace workspace)` (`RegistryEntry.java:94`)
    and `CheckBinding.bind(VerifyCheck.Executor check, Workspace workspace)` (`CheckBinding.java:25`);
    the five built-ins' bindings ignore it. A stage round has no subject (D14, "Where the subject
    comes from").
  - *The run's table.* Move `PendingRuns` from the executor instance to the `program` registry
    entry: one table per entry per run, built in `ExecutorRegistry.of` beside
    `new ProgramExecutorFactory(…)` (`ExecutorRegistry.java:84`) and handed to it (today
    `public ProgramExecutorFactory(PipelineLaw law, DescribedProgram program, ProgramRoundEquipment
    equipment)`, `ProgramExecutorFactory.java:29`) and, when 5.3 builds it, to the program check
    binding. `final class PendingRuns` (`PendingRuns.java:32`) and its no-argument constructor become
    `public`; `claim`, `adopted`, `settled` and `Entry` stay package-private. `ProgramExecutor` takes
    a sixth constructor parameter, `PendingScope scope` (today `public ProgramExecutor(ExecutorDeclaration
    declaration, Describe describe, List<String> credentials, TaskExecutionEnvironment environment,
    ProgramRoundEquipment equipment)`, `ProgramExecutor.java:51-56`), and builds no table
    (`new PendingRuns()`, `:42`, deleted). `PendingScope` is a new public record of
    `adapter.agent.program`: `PendingScope(PendingRuns runs, @Nullable CheckRef subject, @Nullable
    String expectedHead)` — `ProgramExecutorFactory` binds every stage executor with no subject and
    no expected `HEAD`; the kit's candidate (`ProgramCandidate.java:112`) builds one table per case
    with no subject; 5.3's fresh-box binding scopes to the request's `CheckRef` and the attempt
    commit it materialised. Key the map by subject and key (today `ConcurrentMap<PendingKey, Entry>`
    with `claim(PendingKey)`, `adopted(PendingKey, Entry)`, `settled(Entry, ExecutorResult)`,
    `PendingRuns.java:57-94`); the claim stays the first phase and removes the entry, and `settled`
    re-enters it only under a `pending` key, so the terminal poll leaves the table clean (corrected by
    4.7 per D14: the poll that ends the run re-enters it under the polled key, for FR12's re-poll).
  - *`ISSUED` adopts nothing.* The live turn polls the key from the run's table; its answer is
    judged against the view its `start` took.
  - *Adoption by identity.* `RecordedKeyAdoption.entry(String executor, PendingKey key,
    WorkingCopyView view)` (`RecordedKeyAdoption.java:31`) stops judging by
    `StatusEntries.changedAgainstHead` (`:38`) and asks a second query of `FactoryMove` beside
    `changed(Set<String>, Set<String>, Optional<String>, Function<String, Optional<String>>)`
    (`FactoryMove.java:49-53`). One rule, two halves (D14): the view's `branch.oid` SHALL equal the
    expected `HEAD` — the scope's `expectedHead` when set (a fresh box's attempt commit,
    `SandboxCheckEnvironmentSource.java:93`), otherwise `LastRegisteredCommit.lastRegistered()` —
    equality, as `FactoryMove.changed` judges a run's move (`FactoryMove.java:56`), never membership
    of the chain; and its only other entry is the `state.json` lag — working tree equal to the
    index, mode included, index object the `state.json` of a commit of the round's registered chain,
    read per listed commit through the `stateBlobAt` that `WorkingCopyCheck` owns
    (`WorkingCopyCheck.java:127`, an object read by commit id). The chain is not walked here: the
    read-only role `LastRegisteredCommit` (today the one method `Optional<String> lastRegistered()`)
    gains `List<String> registeredChain()` (last registered first, down to the snapshot; empty for
    `NONE`), `CurrentRound` implements it from `restore(PendingVerification)` and appends each
    pending commit it registers; `PendingVerification` (today `(String attemptCommit, String stage,
    int round, RoundToken token, Optional<RoundDecisionRequest> request, String registered)`,
    `PendingVerification.java:50-56`) gains the chain, which `PendingChain.snapshotBelow(String
    branch, RegisteredCommit pending, AttemptKey key, RoundToken token)` (`PendingChain.java:58`)
    yields beside the snapshot from the walk `SnapshotTipCheck.inspect` already runs (crash-consistency
    item 14: no second walker). Anything else refuses (`failed/infrastructure` naming the key and
    the paths, or `HEAD`).
  - *Specs.* `ProgramPollSpec` keeps its markers and gains one feature per new or changed scenario
    of the MODIFIED "Poll of a program is refused without its preconditions" (incl. "A key returned
    to an earlier binding is polled" — two bindings of one entry, no adoption — and "A key issued
    for one check is unknown to another", two `CheckRef`s over one table) and "A pending judge is
    judged when it ends", and of the ADDED "A recorded key is adopted only over an unchanged working
    copy": a host worktree with leftovers (refused), "A resume over the pending commit's lag adopts
    the key" (a real repository with a hand-made pending commit above a registered snapshot, index
    and working tree left on the snapshot's `state.json`, then a rotated pending commit above it;
    `HEAD` expected on the last registered commit), "Adoption away from the expected commit is
    refused", and "HEAD on an earlier commit of the round's chain is refused" (`HEAD` on the snapshot
    while the last registered commit is a pending commit, no expected `HEAD` set); a fresh-box view
    (clean, `HEAD` on the attempt commit set as the expected `HEAD`, below a pending commit) adopted;
    a `FactoryMove` spec for the adoption query; one feature in `CheckRoundAdoptionSpec` that an
    `ISSUED` turn calls no `adopt`; a `PendingPoll` feature that every check-round request carries
    the check's `CheckRef` and a `StageExecutor.Request` spec for the together-or-neither rule; a
    `PendingChain`/`SnapshotTipCheck` feature that the chain read equals the commits walked. Markers
    included (FR3, FR11, NFR-R1, D14).
  - *Enforcement (D9 row `PendingRuns`).* Extend `PendingAdoptionOwnerSpec`: `adopt` called only by
    `CheckRound` on its `RECORDED` arm; `new PendingRuns(` only in `ExecutorRegistry` and
    `ProgramCandidate`; targets asserted reached. The table's readers and writers are confined to
    `adapter.agent.program` by visibility, which the compiler enforces, so the spec does not repeat
    them. Rewrite the javadoc of `PendingRuns` ("The runs of one `ProgramExecutor` instance", "Any
    other key, one another instance issued included") and of `PendingAdoptionOwnerSpec` and
    `ProgramExecutor.poll`'s refusal text ("no start of this executor instance returned it") to the
    run's table.
  The adopted key covers the record's first key (W2), a superseded key (W3) and an ended run's key
  (W4) (FR11, D11). The in-poll wait bound is withdrawn (NG13; `CheckRound`'s `Bounds.none()`
  stays). The second-instance kill-point row of a `program` check runs where a `program` check
  binding exists: task 5.3, on the container medium (NG14 for the host and `same-box`). D14 as
  amended 2026-11-12: the record comes from the open round's list, not from the attempt; runs
  after 4.3.
- [x] 4.6 Key rotation (FR10, D12 as amended 2026-11-12; after 4.3a): a `poll` returning `pending`
  with another key persists it as the check's entry in the next pending commit, through
  `persistPending` on the previous pending commit, the other entries carried unchanged, before the
  next poll; virtual-time spec plus one feature per scenario of "A changed pending key is persisted
  before the next poll" and of `git/git-task-persistence` "A key rotation stacks a pending commit",
  markers included; the entry and its write stay subject-neutral so `extract-agent-round-runner`
  adds only the `Round` subject (FR10, NFR-R4)
- [x] 4.4 Old-way sweep (after 4.2f, which deletes `ExternalPolling`): `grep -rn "sleeper.sleep\|Thread.sleep" adapters/src/main/java/.../check
  adapters/github/src/main` → zero hits; `grep -rn "ExternalPolling"` → zero hits (D9)
- [x] 4.5 Ledger and MDC: `executor`, `status`, `key` prefix, elapsed/remaining per poll; the
  `resumed key=<prefix>` ledger token; registry pair `LedgerJsonMapper` ↔ `LedgerAggregator`
  updated together; the timeout's log line names every expected context still absent, one feature
  for the `verification/pending-poll` scenario "Timeout names the missing contexts" (finding and log
  line), marker included (the finding half is pinned by 4.2's `PendingPollTimeoutSpec`) (NFR-O1, UX3)
- [x] 4.7 Wire obligation and re-poll case (FR11, FR12, D11): add `KitCase.PENDING_REPOLL`
  (`pending-repoll`, gated by `poll` and driven by `--pending-settings` like `PENDING_POLL`) to
  the kit (start → pending → poll to a terminal → poll the same key again → same status and
  answer); the stand-in `steps/executor-run.sh` lists the case in its header and `BROKEN`
  switch, and the misbehaving fake's `break-pending-repoll` preset answers the second poll of
  the ended run differently or refuses it (a committed preset, no per-test script, per
  `testing.md` "Stand-ins are prepared"); the reference executor passes unchanged (verify:
  its poll of `reference-deferred` answers the run every time); `KitCaseScriptIdentitySpec`
  green; the registry row `KitCase` ↔ `executor-run.sh` in `manual-sync-pairs.md` says thirteen
  cases, and so do `KitCase`'s class javadoc, the script's header and the glossary's
  conformance-kit entry (`twelve` → `thirteen`); one feature per scenario of the `executor/wire-contract` and `executor/conformance-kit`
  deltas, markers included; amend ADR 0020 decision 2 (the 2026-11-01 sentence "pending keys are
  not persisted") with the persisted key, the pollable-until-end rule and the idempotent re-poll
  (FR11, FR12)

## 5. Engine ports and registry dispatch

- Former 5.1a and 5.1 were moved into section 4 as 4.2e and 4.2f, after 4.2d, by the order
  decision of 2026-11-12 (D18 as amended).
- [x] 5.2 Remove `BuiltinCheckRunner`, `CommandCheckRunner`, `ExternalCheckClient` from
  `EnginePorts` and the domain ports package; delete `CheckClientDiscovery`,
  `CheckProviderSeam`, `ProviderDispatchingExternalCheckClient`, `CheckClientConfiguration`
  and the `CheckEquipment` registry fields; `ServiceLoader` grep → tracker and sandbox-binding
  discovery only (G1, M1, D9)
  Also delete the transitional `github` check provider of 3.3 (`GithubCheckClientFactory` and its
  `META-INF/services` entry in `:adapters:scm`, its `pinContributor()`, the `SCM_DIR` exemption in
  `GithubPluginAbsenceSpec`) and move every fixture and doc from `factory.check.github` to
  `factory.check.github-actions`, read only by `ScmConnectionReader`; the scrub set keeps
  `GNOMISH_GITHUB_ACTIONS_TOKEN` through the executor's describe credential; the credential an `http` check
  names in its own settings (its describe declares none) keeps its place in the scrub and
  never-allowlist sets that `CheckProviderSeam.checkCredentialEnvVars` fed until now — name the
  owner that unions it before deleting the seam; the scenario "Unconnected credentialed built-in is a
  located error" (its WHEN names `factory.check.github-actions`, which exists only after the key
  move above), marker included, moved here from 3.5: its feature drives the one owner 3.6 built —
  the run-assembly refusal on the describe refusal's path, a located `ConfigError` naming the check
  and `github-actions` — with no second load-time validator for the case (D18, "Where a refusal at
  run assembly goes")
- [x] 5.3 Fresh-box credential delivery for `program` verdict executors:
  (Carried from 5.2, 2026-11-14: the credential-names owner of the scrub and never-allowlist sets is
  `BuiltInCheckSubsections` (D9 row); this task adds its gate — `HttpExecutor.credentials(` called in
  production only there, in `ExecutorRegistryOwnerSpec` — and settles the D9 "Credential delivery" row's
  "`CheckEquipment.credentialNames` deleted": the method survives as the union with the tracker's
  names, so either the row is amended or the union moves.)
  (Settled 2026-11-14 by `/architect` Mode C, human decision: a verdict program reads the attempt
  commit as `HEAD` of its fresh box, a built-in from the engine's attempt-commit workspace handle;
  the deltas and `proposal.md` now say so, the wire field is reopened by NG14's pickup and an echo
  of the judged commit is NG15. Identity spec of this task: a fresh-box `program` verdict check
  sees `HEAD` equal to `ClosedRound.attemptCommit` on `start` and on its poll, in the container
  kill-point matrix below.)
  `SandboxCheckEnvironmentSource.freshBox` takes the law's `executors.<name>.egress` grant, which
  the describe preflight bounds (`ExecutorEgress`, `GrantAgreement`), as the egress set beside
  `factory.egress.sandbox`, and its credential names as the only credential layer; a credentialed
  built-in gets no box and no box credential — its token reaches only the in-process client under
  the `:httpegress` admitted set of its connection; extend `ProgramCredentialDeliverySpec` with a program verdict
  check and a built-in verdict check (token absent from every box) (NFR-S1, D6, D16)
  Moved from 4.3a (2026-11-13, corrected by the D14 review the same day): this task builds the one
  `program` check binding of the change, through the fresh box, handed the program entry's run
  table (D14 as amended 2026-11-13) and scoping it per request with `PendingScope(runs,
  request's CheckRef, the attempt commit it materialised the box at)` — the expected `HEAD` of an
  adoption (D14); the binding lives in `adapter.agent.program` or reaches it through a public
  factory there, since `PendingRuns`' members are package-private. Refuse what it cannot bind (D18,
  "A `program` check outside a fresh box"; proposal NG14): a law whose check names a `program`
  executor that would run outside a fresh box (host mode, or the `same-box` placement) is a located
  `ConfigError` naming the check (manifest, `verify[<i>]`) and the executor, on the describe
  refusal's path `LawDescription.Refused` as task 3.6's refusals — parked by
  `TaskTierLaw.parkUndescribed` in `take`/`serve`, the located exit of `ManualRunDescribe.described`
  in `run` — never an `IllegalStateException` from `RegistryEntry.executor`; one feature per path
  for the `verification/verdict-executors` scenario "A program verdict check outside a fresh box is a
  located error", marker included (FR1, FR3); add a `program` verdict executor declaring `poll` to the
  container kill-point matrix of 4.3b (the same-clone spec without a daemon and the other-clone
  Docker end-to-end spec on `ContainerParkSpecBase`): W2 (adopt then poll, no `start`), W3 with a
  rotated key distinguishable from the first, W4 showing `poll`, not `start`; each with a no-op
  second recovery. The host and `same-box` half is deferred (proposal NG14): no task binds a
  `program` check there (`RegistryEntry.java:17-18`).
  Box per request (D11 as amended 2026-11-14, settled by `/architect` Mode C): each `start` and
  each poll of a fresh-box `program` check materializes its own box at the attempt commit and
  disposes it (`SandboxCheckEnvironmentSource.java:93`, `:111-113`); the premises (one attempt
  commit across requests and resumes; the run retained outside the box) are verified in D11 as
  amended. One feature per scenario of the ADDED `verification/verdict-executors` requirement
  "Each request of a fresh-box program check gets its own box at the attempt commit", markers
  included (FR11, FR3). Measure the box's cost per request against the process spawn (materialize
  to dispose, and the spawn, on the container medium) and record both numbers in the task report:
  the measurement is the pickup of proposal NG16 (box reuse).
- [x] 5.3a Conformance kit: the pending-then-poll and re-poll cases poll from a new working copy
  (D11 as amended 2026-11-14; FR12, FR11), in the program and the in-process candidate alike.
  The kit's mechanics, verified by the scoped D11 review of 2026-11-14:
  - each case gets one poll copy, at `<scratch>/poll/<case>`, serving all of its polls, apart from
    `start`'s copy; the path keeps the `/<case>` ending the fake's `BROKEN` switch and
    `ConformanceKitPendingSpec` select by (`executor-run.sh:74-78`, `CaseDirectories.java:30-32`);
  - its content follows the case's role (the first live role, `ConformanceKit.java:42`): for
    `verdict`/`decision` the content before `start`, for `transform` the files of `start`'s copy at
    the poll without its `.git` — so the `break-pending-repoll` marker in `.git`
    (`executor-run.sh:84`) is not carried; that case must still fail as its scenario says, so state
    in the report where the fake now keeps it (outside the copy, as a conforming program would not);
  - the poll executor shares the start executor's `PendingRuns` (today `ProgramCandidate.java:126-127`
    builds one per executor): `KitSubject.CaseRun` (`KitSubject.java:82-88`) gains a poll binding,
    and the case's `violations()` joins both observations; the javadoc of `PendingCases.java:17-18`
    ("polled on the same executor") is rewritten;
  - the misbehaving fake gains a preset that records its pending run in its working copy's `.git`
    at `start` and reads it back at `poll`, as a section of `tables/executor.params` selected by
    name, not through `BROKEN` (the `KitCase` ↔ `executor-run.sh` pair keeps its thirteen names;
    `KitCaseScriptIdentitySpec` and `StandInLibrarySpec` green).
  Known limits, as the requirement's Notes state: a re-poll is not sent from a second new copy; a
  multi-role candidate is run in its first live role. One feature per scenario of the ADDED
  `executor/conformance-kit` requirement "Pending cases poll from a new working copy" and of the
  `executor/wire-contract` scenario "A poll from another working copy reaches the run", markers
  included; `ConformanceKitSpec` and the reference executor stay green.
- [x] 5.4 Describe preflight, check use site (carried from NG1 of `define-executor-contract`):
  add the check use site to `DescribeAgreement` — a check needs role `verdict` — so a check
  whose executor's describe lists only `effect` is refused with a located error naming the
  check, the executor and `effect` as reserved; a feature in `DescribePreflightSpec` carries
  `// Scenario: executor/wire-contract / Effect role is refused by the engine in this version`
  beside the stage feature's marker (FR1)

## 6. Plugin API

- [x] 6.1 Remove `CheckClientFactory`, `CheckParamsValidator`, `CheckSubsectionValidator`,
  `ExternalCheckPinContributor`, `CheckRunContext` from `gnomish-plugin-api`, and **move**
  `AttemptCommitWorkspace` (an interface extending the domain's `Workspace`, `String
  attemptCommitSha()`) out of it into `:domain` (package `domain.engine.port`, beside `Workspace`):
  D18 keeps it as the way the SCM built-ins read the attempt commit (`GithubActionsExecutor.java:104`,
  `ScmStatusExecutor.java:94`) and eleven other production files use it (a `package-info` among them, and
  `FilesExistCheckRunner`, `HttpExternalCheckClient`, `CurrentRound`,
  `RecordedAttemptCommitWorkspace`, `ContainerRunSupport`, `FreshJudgeEnvironments`,
  `RemoteAttemptDelivery`), every one of whose modules — `:adapters:scm` included
  (`adapters/scm/build.gradle:30`) — already reaches `:domain`, so no edge is added; the
  `:adapters:scm` build-file comment naming the api for it is updated; the break is absorbed into
  the api's one MINOR bump of this change (`0.15.0`, D16, D20; no second bump) and re-baseline the surface per its spec (the move is part of the re-baseline);
  grep for the removed names in `gnomish-plugin-api` → zero (the moved type survives in `:domain`
  only); in
  `config/spec-contract-exemptions/plugin-plugin-api-contract.txt` rewrite the lines of
  "api artifact excludes application and bootstrap internals" (the sample compiles against the
  tracker port, the tracker SPI factory, `SecretsProvider`, `TrackerSubsectionValidator` and
  `FindingsSanitizer`; no `AttemptCommitWorkspace`, no "both SPI factories") and "A third party
  compiles against a single declared dependency" (the sample tracker adapter only); the sample
  verdict program's own dependency-freedom is pinned by the kit feature of 6.2 (FR8, M4)
- [x] 6.2 Rewrite the sample module: a sample verdict program (script, `pending` then `poll`)
  passing the conformance kit; update `docs/guides` adapter author guide and
  `docs/community-plugins.md` wording; rewrite `docs/guides/operator-guide-github-actions-check.md`
  for the `github-actions` verdict executor — no `GithubCheckExternalClient` or
  `ExternalCheckClient` wording, and its "Base URL config" section names the
  `factory.check.github-actions` subsection and its `connection:` reference (FR8, NFR-S2)
- Spec contract (`tighten-spec-contract`): this change REMOVES the `stage-engine` requirement
  "External check poll loop", two `plugin/plugin-api-contract` requirements and seven
  `verification/check-provider-model` requirements; their 18 scenarios carry 30
  `// Scenario: <spec-id> / <title>` markers today (17 of the 18 scenarios) and one line in
  `config/spec-contract-exemptions/plugin-plugin-api-contract.txt` ("Sample check factory
  compiles against the api alone"). In this same change, every marker of a removed scenario goes
  with the test it sits on (or is retargeted to a scenario this change adds), and each exemption
  line naming a removed scenario is deleted, so `checkSpecContract` reports no marker or
  exemption naming a scenario that no longer exists. The scenarios of the MODIFIED requirements
  keep their titles: their markers move with their features to the specs that replace the deleted
  ones — `HttpCheckProviderSpec`'s to the registry spec of 3.6 or the re-pointed `http` specs of
  3.4, `VerifyCheckSpec`'s to the mapper and loader specs of 1.1, the stateless-poll markers of
  the GitHub check specs to the re-pointed WireMock specs of 3.3, and
  `PinCheckedExternalCheckClientSpec`'s and `HttpCheckClientFactorySpec`'s to the pin-decorator
  spec of 4.2f, and the four markers of "The SPI factories bear their declared members" in
  `CheckClientFactorySpec` to a feature asserting the tracker factory's members and the absence
  of any check SPI type from the api jar, beside the one `ApiCompatibilityGateSpec:64` keeps over
  the re-baselined `SHIPPED_SPI` of 6.1 — so no kept scenario is left without a marker.

- [x] 6.3 Plugin specs and docs for the tracker-only bundle (FR13, FR14): deltas
  `plugin/github-plugin`, `plugin/plugin-discovery`, `plugin/plugin-api-contract` (vendor bundle
  scenario) as written in `specs/`, one feature per scenario of their ADDED requirements, markers
  included; `PluginReadyPortCriterionSpec` and `DiscoveredRegistryOnlySpec`
  restated for the tracker port; the marker of "Different vendors serve different ports"
  (`VendorConnectionProfileSpec:93`, which constructs `GithubCheckClientFactory`) moves to a
  feature resolving the github tracker from its registry and `scm-status` from the executor
  registry; glossary: "Vendor bundle" (tracker only), "GitHub API library"
  (`:githubapi`), "HTTP egress leaf" (`:httpegress`), "Built-in executor" entry gains "named by
  core; a bundled jar's provider is not a built-in"; `docs/community-plugins.md` and the adapter
  author guide say the tracker is the pluginizable port
- [x] 6.4 Amend ADR 0020 (D4, D16): decision 1 defines a built-in executor as compiled into and
  named by core and states that "distribution is not mechanism" governs tracker plugins only;
  the rejected alternative "everything is a process" loses the words "in-process egress control
  of the HTTP client for the SCM built-ins" in favour of the `:httpegress` allowlist every
  built-in's HTTP passes (the GitHub client had none before this change); decision 2 gains the
  registry principle of D18 as amended: the registry holds entries bound at resolution to the
  round's environment or the check's working copy, never executors built before the round or the
  check exists,
  and the wire carries only what every implementer can honour (the attempt commit is read from
  the working copy, never a request field); the amendment names
  the escalation of task #121 (2026-11-06) and the task 3.6 blocker (2026-11-10) as provenance

## 7. Integration

- [x] 7.1 End-to-end: the SonarQube-shaped reference verdict program against a fake server,
  container mode, kill between `start` and the key commit, resumed by a second packaged jar
  instance (M3)
- [x] 7.2 `./gradlew check` with PIT; sweep report for D9 rows; recommend a commit message
- [x] 7.3 Archive-time step (a delta carries no Purpose): when `/opsx:archive` merges
  `verification/factory-egress-allowlist`, restate its Purpose (main `spec.md:5`, today "outbound
  calls made by the built-in http check", "the sole defence") as the guard of every credentialed
  in-process HTTP call of the factory, admitting by the medium of the URL (D16), with the banned
  synonym "whitelist" dropped from it (NG10 re-defers the heading and two scenario titles); and confirm
  `scripts/check-merged-specs.sh migrate-verify-to-executor-contract` green before the archive
