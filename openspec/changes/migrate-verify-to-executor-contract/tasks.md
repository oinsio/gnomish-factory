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
- [ ] 3.0a Library `:githubapi` (FR14, D15; narrowed 2026-11-08 by the decision on this task's
  escalation, D16 as amended): move the bundle's `adapter.github` package (`GithubHttpClient`,
  `GithubCredential`, `GithubConditionalRequestCache`, `GithubRateLimit`, `GithubRetryConfig`,
  exceptions) into the new module depending on `:domain`, `:httpegress`, resilience4j, slf4j —
  never on `:executor-contract`, `:application` or `:adapters` — with **unchanged behavior**:
  the client keeps its own `HttpClient` for this task, and the `PENDING` row of
  `HttpClientConstructionBoundarySpec` is retargeted to the class's new path with 3.0b named as
  the task that deletes it; add `:githubapi` to the bundle's `layering.allowedProjects` with the
  reason; re-point the tracker's imports; `GithubAdapterBoundarySpec` and
  `GithubPluginPackagingSpec` green; grep `class GithubHttpClient` → one hit (M6)
- [ ] 3.0b Egress admission by the author of the URL (FR15, FR17, NFR-R5, UX4; D16 as amended
  2026-11-08). Precondition: `/review-artifacts migrate-verify-to-executor-contract D16` run and
  its findings resolved (`design-decisions.md`, "A decision added during implementation is
  reviewed before its task runs"). In `:httpegress`: the exchange's `Response` carries the
  headers; a second admission source beside the manifest-target allowlist — an operator
  connection admitted by its configured URL's scheme and host plus declared redirect hosts,
  judged at load and refusing as a located error — with the address-class judgement, the hop
  bound and the cross-host header strip shared by both; the hop rule by status (`301`/`302`/`303`
  → `GET` without body, `307`/`308` keep method and body); body bound, request timeout and the
  refusal's configuration key as parameters of the consumer's exchange, with a bounded-tail read.
  In `:githubapi`: `GithubHttpClient` built over the guarded exchange (its `HttpClient`,
  `Redirect.NORMAL` and virtual-thread executor deleted; the `PENDING` row deleted; every request
  carries the core's timeout), a refusal classified apart from transport (no retry, no outage
  signal, the error names the key), the job log read as a bounded tail, the core's own response
  bound. Consumers: `http` built-in (manifest target; findings still render status and body
  only), the bundle's tracker and `GithubCheckClientFactory` (operator connection from `api-url`).
  Old-way sweep: `HttpClient.newBuilder\|Redirect.NORMAL\|factory.check.http.allowlist` in
  production code → leaf only, the key only where the http check builds its allowlist. Specs:
  one feature per scenario of the five ADDED requirements of
  `verification/factory-egress-allowlist`, markers included; `EgressAdmissionByAuthorSpec` (the
  identity spec of the D9 row, over the real exchange and a local server);
  `GithubRefusalClassificationSpec`; the ~75 WireMock and Gitea specs of the GitHub client green
  with no test-only switch; `HttpClientConstructionBoundarySpec` with an empty `PENDING`.
  Docs: glossary entries **manifest target**, **operator connection**, **declared redirect
  hosts** (and the verdict-executor entry's "in-process egress allowlist of its declared hosts"
  restated); `docs/guides/operator-guide-github-actions-check.md` gains `redirect-hosts`
- [ ] 3.1 `files-exist`: wrap `FilesExistCheckRunner` as an `Executor` (describe: role
  `verdict`, settings schema for the path list) emitting SARIF; existing specs re-pointed (FR1)
- [ ] 3.2 `shell`: build on the shared command-run core from `add-command-executor`; report
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
- [ ] 3.3 `github-actions`: move `GithubCheckExternalClient` + fetchers out of the bundle into the
  new core-side module `adapters/scm` (depends on `:githubapi`, `:executor-contract`, `:domain`)
  as an `Executor` with `poll`; describe carries the token name, settings schema (former
  subsection) and `checkIdIsPinnedPath = true`; its connection (base URL, token name) is read
  from the operator's `factory.check.github-actions` subsection, inline or by `connection:`
  profile (NFR-S2); its describe declares the default redirect hosts
  `results-receiver.actions.githubusercontent.com` and `*.blob.core.windows.net`, and the
  connection's optional `redirect-hosts` list names an enterprise store (FR17, D16 as amended;
  the two scenarios of "A built-in declares the redirect hosts its platform answers with" and
  the restated "A built-in's HTTP call outside its declared hosts is refused", markers included);
  WireMock specs re-pointed unchanged; the bundle's `adapter.check.github`
  package and its `META-INF/services` check entry deleted (FR6, FR13, D4, D17)
- [ ] 3.3a Narrow `GithubPluginAbsenceSpec` (M5): the source gate's regex covers references to
  the bundle's remaining package (`adapter.tracker.github`) outside `adapters/github/`, not
  `\bGithub[A-Z]` names; the registry feature asserts the tracker registry loses `github` while
  `ExecutorRegistry` still resolves `github-actions` and `scm-status` with the bundle hidden;
  `bootstrap/build.gradle`'s `runtimeOnly` comment restated for the tracker
- [ ] 3.4 `http`: same for `HttpExternalCheckClient` (conditions, run-context interpolation,
  egress refusal); specs re-pointed (FR6)
- [ ] 3.5 `scm-status`: new built-in in `adapters/scm` over `:githubapi`, reading GitHub Checks +
  Statuses and Gitea statuses on the attempt SHA; latest-per-context, expected list, SHA verification, rate-limit back-off hint;
  WireMock specs for each scenario and `ScmStatusShaPinningSpec`; its connection from
  `factory.check.scm-status`, admitted as an operator connection with an optional
  `redirect-hosts` list and no default redirect host (FR17); one feature per scenario of "A credentialed built-in verdict
  executor is connected by operator configuration", markers included (FR4, FR9, NFR-R2, NFR-S2,
  D3, D9)
- [ ] 3.6 Register the five built-ins in `ExecutorRegistry` per D18: the registry's value becomes
  the contract's `Executor`, stage dispatch wraps into `StageExecutor` at resolution, the
  built-ins are handed in by `ExecutorAdapterSelector` as typed parameters of `adapters/scm`,
  `adapters/check` and `adapters/agent`, and the law-declaration refusal covers all six built-in
  names (`ExecutorRegistryOwnerSpec` extended); conformance kit run against each as a spec (FR1,
  FR8, FR13); each built-in's describe declares `reportsUsage = true` and its result
  carries usage `REPORTED` with an empty per-model map; a `UsageFold` spec: a task whose checks
  are the five built-ins stays `complete`, one feature per scenario of "Built-in verdict
  executors report a known zero usage", markers included (NFR-C1, D13)

## 4. Single owner: pending/poll with a durable key

- [ ] 4.1 Add `PendingPollRecord` to the attempt state (`StateAttemptDto`, `StateJsonMapper`,
  round-trip spec, size cap on the key) (FR2, D7). Carried from Q1 of
  `define-executor-contract`: the key's shape and cap are this task's; that change already bounds
  the key where a result is read (`PendingKey`: at most 4096 characters, no ISO control
  character, the result schema's `pendingKey` stating the same), so the record stores a
  `PendingKey` and states how its state-file cap relates to that bound
- [ ] 4.2 Implement `PendingPoll` in `domain/engine` replacing `ExternalPolling`: delivery
  precondition, `start`, persist-before-poll, `poll(key)` at interval with the wait bound,
  timeout-class classification, absent-expected-context → infrastructure; virtual-time specs
  for every scenario of the `pending-poll` spec (FR2, FR3, D2)
- [ ] 4.3 Resume path: `StageAttemptLoop` reads the record and continues polling without
  `start`; `poll` unsupported → infrastructure; kill-point specs on a real bare origin for
  windows W1, W2, W3 (after a key-rotating poll, before its commit), W4 (after a terminal poll,
  before the verdict commit) and after the terminal verdict, each resumed by a second instance,
  each with a no-op second recovery; W3 and W4 assert no `start` (D7 as amended, NFR-R1, NFR-R4,
  M3)
- [ ] 4.3a Program executors on the resume path (carried from NG7 and design D3 "Poll of a
  program" of `define-executor-contract`): as built there, `ProgramExecutor.poll` refuses, with no
  process spawned, a key its own instance did not issue (`PendingRuns`, in memory per instance),
  and the working-copy view the `verdict` poll is judged against (FR11, NFR-S4 of that change) is
  held in that same entry. A persisted key resumed by a second instance (FR3) therefore reaches a
  refusal for a `program` verdict check unless this change gives the poll its run from the record.
  Implement design D14: `StageExecutor.Request` gains `@Nullable PendingTurn pending`
  (`PendingTurn(String key, Origin origin)`, `Origin { ISSUED, RECORDED }`, `domain/engine/port`),
  `PendingPoll` sets `RECORDED` on the resume path only; the application-side wrapper of D18
  dispatches none → `start`, `ISSUED` → `poll`, `RECORDED` → `PendingAdoption.adopt(request,
  key)` (new role interface in `app/port/check`) then `poll`; `ProgramExecutor` implements
  `PendingAdoption` — takes the view of its own environment's working copy, refuses with
  `failed/infrastructure` naming the key and the changed paths when the view shows any change
  against `HEAD`, otherwise enters the key into `PendingRuns` with that view as `before`; a key
  enters `PendingRuns` from nowhere else (no wire field), and `Executor` is unchanged. The adopted key covers
  the record's first key (W2), a superseded key (W3) and an ended run's key (W4) (FR11, D11);
  the second-instance kill-point spec of 4.3 includes a `program` verdict executor declaring
  `poll`. Specs: `ProgramPollSpec` keeps its two markers and gains one feature per new scenario
  of the MODIFIED "Poll of a program is refused without its preconditions" and "A pending judge
  is judged when it ends" and of the ADDED "A recorded key is adopted only over an unchanged
  working copy" (a host worktree with leftovers), markers included (FR3, FR11, NFR-R1, D14)
- [ ] 4.4 Old-way sweep: `grep -rn "sleeper.sleep\|Thread.sleep" adapters/src/main/java/.../check
  adapters/github/src/main` → zero hits; `grep -rn "ExternalPolling"` → zero hits (D9)
- [ ] 4.5 Ledger and MDC: `executor`, `status`, `key` prefix, elapsed/remaining per poll; the
  `resumed key=<prefix>` ledger token; registry pair `LedgerJsonMapper` ↔ `LedgerAggregator`
  updated together (NFR-O1, UX3)
- [ ] 4.6 Key rotation (FR10, D12): a `poll` returning `pending` with another key persists it in
  the record, one commit with its register, pushed, before the next poll, through the same write
  as the first key; virtual-time spec plus one feature per scenario of "A changed pending key is
  persisted before the next poll", markers included; the record and its write stay
  subject-neutral so `extract-agent-round-runner` adds only the `Round` subject (FR10, NFR-R4)
- [ ] 4.7 Wire obligation and re-poll case (FR11, FR12, D11): add `KitCase.PENDING_REPOLL`
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

- [ ] 5.1 `VerifyOrchestrator` resolves each check's executor through `ExecutorRegistry` and
  runs it through `PendingPoll`; `PinCheckedExternalCheckClient` becomes a pin decorator over
  `Executor` whose pin set is the law's `pinPaths` ∪ `checkId` when the executor's describe
  declares `checkIdIsPinnedPath` (D17; `GithubCheckPinPaths` deleted; `PinDecoratorSpec`
  data-driven over the flag, one feature per scenario of the MODIFIED pin requirements, markers
  included); ordered fail-fast specs green (FR1, FR2, FR16, D8)
- [ ] 5.1a Wire contract: add `checkIdIsPinnedPath` (boolean, default false) to the describe
  schema and `Describe`, with its round-trip spec and the conformance kit reading it; one feature
  per scenario of the `executor/wire-contract` ADDED requirement "A describe may pin the check's
  id", markers included (FR16, D17)
- [ ] 5.2 Remove `BuiltinCheckRunner`, `CommandCheckRunner`, `ExternalCheckClient` from
  `EnginePorts` and the domain ports package; delete `CheckClientDiscovery`,
  `CheckProviderSeam`, `ProviderDispatchingExternalCheckClient`, `CheckClientConfiguration`
  and the `CheckEquipment` registry fields; `ServiceLoader` grep → tracker and sandbox-binding
  discovery only (G1, M1, D9)
- [ ] 5.3 Fresh-box credential delivery for `program` verdict executors:
  `SandboxCheckEnvironmentSource.freshBox` takes the executor's declared hosts as the egress set
  and its credential names as the only credential layer; a credentialed built-in gets no box and
  no box credential — its token reaches only the in-process client under the `:httpegress`
  allowlist of its declared hosts; extend `ProgramCredentialDeliverySpec` with a program verdict
  check and a built-in verdict check (token absent from every box) (NFR-S1, D6, D16)
- [ ] 5.4 Describe preflight, check use site (carried from NG1 of `define-executor-contract`):
  add the check use site to `DescribeAgreement` — a check needs role `verdict` — so a check
  whose executor's describe lists only `effect` is refused with a located error naming the
  check, the executor and `effect` as reserved; a feature in `DescribePreflightSpec` carries
  `// Scenario: executor/wire-contract / Effect role is refused by the engine in this version`
  beside the stage feature's marker (FR1)

## 6. Plugin API

- [ ] 6.1 Remove `CheckClientFactory`, `CheckParamsValidator`, `CheckSubsectionValidator`,
  `ExternalCheckPinContributor`, `AttemptCommitWorkspace`, `CheckRunContext` from
  `gnomish-plugin-api`; bump its major version and re-baseline the surface per its spec;
  grep for the removed names → zero; in
  `config/spec-contract-exemptions/plugin-plugin-api-contract.txt` rewrite the lines of
  "api artifact excludes application and bootstrap internals" (the sample compiles against the
  tracker port, the tracker SPI factory, `SecretsProvider`, `TrackerSubsectionValidator` and
  `FindingsSanitizer`; no `AttemptCommitWorkspace`, no "both SPI factories") and "A third party
  compiles against a single declared dependency" (the sample tracker adapter only); the sample
  verdict program's own dependency-freedom is pinned by the kit feature of 6.2 (FR8, M4)
- [ ] 6.2 Rewrite the sample module: a sample verdict program (script, `pending` then `poll`)
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
  spec of 5.1, and the four markers of "The SPI factories bear their declared members" in
  `CheckClientFactorySpec` to a feature asserting the tracker factory's members and the absence
  of any check SPI type from the api jar, beside the one `ApiCompatibilityGateSpec:64` keeps over
  the re-baselined `SHIPPED_SPI` of 6.1 — so no kept scenario is left without a marker.

- [ ] 6.3 Plugin specs and docs for the tracker-only bundle (FR13, FR14): deltas
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
- [ ] 6.4 Amend ADR 0020 (D4, D16): decision 1 defines a built-in executor as compiled into and
  named by core and states that "distribution is not mechanism" governs tracker plugins only;
  the rejected alternative "everything is a process" loses the words "in-process egress control
  of the HTTP client for the SCM built-ins" in favour of the `:httpegress` allowlist every
  built-in's HTTP passes (the GitHub client had none before this change); the amendment names
  the escalation of task #121 (2026-11-06) as provenance

## 7. Integration

- [ ] 7.1 End-to-end: the SonarQube-shaped reference verdict program against a fake server,
  container mode, kill between `start` and the key commit, resumed by a second packaged jar
  instance (M3)
- [ ] 7.2 `./gradlew check` with PIT; sweep report for D9 rows; recommend a commit message
