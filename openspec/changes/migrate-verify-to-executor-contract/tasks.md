# Tasks: migrate-verify-to-executor-contract

## 1. Manifest and model

- [ ] 1.1 Collapse `VerifyCheck.Builtin/Command/External` into one `VerifyCheck.Executor`
  record (executorName, checkId, settings, interval, timeout, timeoutClass, pinPaths,
  verifyIn, threshold, expected) beside `Judge`; map the three tokens to built-in names in
  `VerifyCheckMapper`; specs: legacy manifest loads unchanged, explicit `executor:` form,
  default `external` → `github-actions` (FR1, UX2)
- [ ] 1.2 Loader rules: executor must declare role `verdict`; `scm-status` requires non-empty
  `expected`; `threshold` enum with round-trip spec; credentialed executor + `same-box` is a
  located error and `fresh-box` is the default for it (FR1, FR4, NFR-S1, NFR-R3)
- [ ] 1.3 Glossary: entries for verdict executor, pending key, expected contexts, SCM-status
  reader (replace the forward reference); update "Verify check" and "Executor" entries;
  threat registry note that external verifiers are executors (process rule)

## 2. Single owner: verdict derivation

- [ ] 2.1 Implement `VerdictDerivation(answer, threshold)` in `domain/engine` with the
  data-driven level × threshold spec; `CannotVerify` on a malformed answer (FR7, D5)
- [ ] 2.2 Route `VerifyOrchestrator` through it; delete verdict construction in
  `ShellCommandCheckRunner`, `FilesExistCheckRunner`, `ExternalPolling`,
  `VerifyOrchestrator:149`, `HttpExternalCheckClient`, `GithubCheckExternalClient`
- [ ] 2.3 Old-way sweep: `grep -rn "new Verdict\." --include=*.java */src/main adapters/*/src/main`
  → hits only in `VerdictDerivation`, `StateJsonMapper` and the judge classes named in D9;
  record the list in the task report (M2)
- [ ] 2.4 Outcome-classifier gate (carried from D10 of `define-executor-contract`): that change's
  `OutcomeClassifierOwnerSpec` (`:bootstrap`) allowlists every `new Verdict.CannotVerify(` site
  under the pickup `migrate-verify-to-executor-contract: check-side verdict construction`. Delete
  the rows of the classes 2.2 routes (`ShellCommandCheckRunner`, `FilesExistCheckRunner`,
  `ExternalPolling`, `VerifyOrchestrator`); the judge rows (`JudgeVerdictExtractor`,
  `JudgeCriteriaPreflight`, `JudgeCannotVerify`) stay exempt until `extract-agent-round-runner`
  (D9, NG1), so retag them with that pickup and add it to the spec's `PICKUPS`; add
  `JudgeCannotVerify` to D9's exempt list beside the other judge classes; no row keeps this
  change's name (M2)

## 3. Built-in verdict executors

- [ ] 3.1 `files-exist`: wrap `FilesExistCheckRunner` as an `Executor` (describe: role
  `verdict`, settings schema for the path list) emitting SARIF; existing specs re-pointed (FR1)
- [ ] 3.2 `shell`: build on the shared command-run core from `add-command-executor`; report
  exit as status, read the findings path through `ResultFileReader`, normalize the legacy
  `{"findings":[...]}` to SARIF; specs: legacy file normalized, SARIF passthrough, exit code
  carries no verdict, existing bound/kill specs green (FR5)
- [ ] 3.3 `github-actions`: move `GithubCheckExternalClient` + fetchers into an `Executor` with
  `poll`; describe carries token name (git-remote connection), settings schema (former
  subsection), pin contribution; WireMock specs re-pointed unchanged (FR6, D4)
- [ ] 3.4 `http`: same for `HttpExternalCheckClient` (conditions, run-context interpolation,
  egress refusal); specs re-pointed (FR6)
- [ ] 3.5 `scm-status`: new built-in reading GitHub Checks + Statuses and Gitea statuses on the
  attempt SHA; latest-per-context, expected list, SHA verification, rate-limit back-off hint;
  WireMock specs for each scenario and `ScmStatusShaPinningSpec` (FR4, FR9, NFR-R2, D3, D9)
- [ ] 3.6 Register the five built-ins in `ExecutorRegistry`; conformance kit run against each
  as a spec (FR1, FR8)

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
  refusal for a `program` verdict check unless this change gives the poll its run from the record;
  design D7 states how (what the record carries in place of `PendingRuns`, and where the
  before-view comes from on resume), and the second-instance kill-point spec of 4.3 includes a
  `program` verdict executor declaring `poll`. The record-driven poll must also reach the program
  for a superseded key (W3) and for an ended run's key (W4), both of which `PendingRuns` refuses
  today (FR11, D11); before this task is applied, D7 states the replacement and this change's
  `executor/wire-contract` delta MODIFIES "Poll of a program is refused without its
  preconditions" with its scenario titles kept (proposal Q3) (FR3, FR11, NFR-R1)
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
  cases; one feature per scenario of the `executor/wire-contract` and `executor/conformance-kit`
  deltas, markers included; amend ADR 0020 decision 2 (the 2026-11-01 sentence "pending keys are
  not persisted") with the persisted key, the pollable-until-end rule and the idempotent re-poll
  (FR11, FR12)

## 5. Engine ports and registry dispatch

- [ ] 5.1 `VerifyOrchestrator` resolves each check's executor through `ExecutorRegistry` and
  runs it through `PendingPoll`; `PinCheckedExternalCheckClient` becomes a pin decorator over
  `Executor` for checks with pin paths; ordered fail-fast specs green (FR1, FR2, D8)
- [ ] 5.2 Remove `BuiltinCheckRunner`, `CommandCheckRunner`, `ExternalCheckClient` from
  `EnginePorts` and the domain ports package; delete `CheckClientDiscovery`,
  `CheckProviderSeam`, `ProviderDispatchingExternalCheckClient`, `CheckClientConfiguration`
  and the `CheckEquipment` registry fields; `ServiceLoader` grep → tracker and sandbox-binding
  discovery only (G1, M1, D9)
- [ ] 5.3 Fresh-box credential delivery: `SandboxCheckEnvironmentSource.freshBox` takes the
  executor's declared hosts as the egress set and its credential names as the only
  credential layer; extend `ProgramCredentialDeliverySpec` with a verdict check (NFR-S1, D6)
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
  grep for the removed names → zero (FR8, M4)
- [ ] 6.2 Rewrite the sample module: a sample verdict program (script, `pending` then `poll`)
  passing the conformance kit; update `docs/guides` adapter author guide and
  `docs/community-plugins.md` wording (FR8)
- Spec contract (`tighten-spec-contract`): this change REMOVES the `stage-engine` requirement
  "External check poll loop", two `plugin/plugin-api-contract` requirements and seven
  `verification/check-provider-model` requirements; their 18 scenarios carry 30
  `// Scenario: <spec-id> / <title>` markers today (17 of the 18 scenarios) and one line in
  `config/spec-contract-exemptions/plugin-plugin-api-contract.txt` ("Sample check factory
  compiles against the api alone"). In this same change, every marker of a removed scenario goes
  with the test it sits on (or is retargeted to a scenario this change adds), and each exemption
  line naming a removed scenario is deleted, so `checkSpecContract` reports no marker or
  exemption naming a scenario that no longer exists.

## 7. Integration

- [ ] 7.1 End-to-end: the SonarQube-shaped reference verdict program against a fake server,
  container mode, kill between `start` and the key commit, resumed by a second packaged jar
  instance (M3)
- [ ] 7.2 `./gradlew check` with PIT; sweep report for D9 rows; recommend a commit message
