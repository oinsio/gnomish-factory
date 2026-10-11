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
  round-trip spec, size cap on the key) (FR2, D7)
- [ ] 4.2 Implement `PendingPoll` in `domain/engine` replacing `ExternalPolling`: delivery
  precondition, `start`, persist-before-poll, `poll(key)` at interval with the wait bound,
  timeout-class classification, absent-expected-context → infrastructure; virtual-time specs
  for every scenario of the `pending-poll` spec (FR2, FR3, D2)
- [ ] 4.3 Resume path: `StageAttemptLoop` reads the record and continues polling without
  `start`; `poll` unsupported → infrastructure; kill-point specs on a real bare origin for
  windows W1, W2 and after the terminal verdict, each resumed by a second instance, each with a
  no-op second recovery (D7, NFR-R1, M3)
- [ ] 4.4 Old-way sweep: `grep -rn "sleeper.sleep\|Thread.sleep" adapters/src/main/java/.../check
  adapters/github/src/main` → zero hits; `grep -rn "ExternalPolling"` → zero hits (D9)
- [ ] 4.5 Ledger and MDC: `executor`, `status`, `key` prefix, elapsed/remaining per poll; the
  `resumed key=<prefix>` ledger token; registry pair `LedgerJsonMapper` ↔ `LedgerAggregator`
  updated together (NFR-O1, UX3)

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

## 6. Plugin API

- [ ] 6.1 Remove `CheckClientFactory`, `CheckParamsValidator`, `CheckSubsectionValidator`,
  `ExternalCheckPinContributor`, `AttemptCommitWorkspace`, `CheckRunContext` from
  `gnomish-plugin-api`; bump its major version and re-baseline the surface per its spec;
  grep for the removed names → zero (FR8, M4)
- [ ] 6.2 Rewrite the sample module: a sample verdict program (script, `pending` then `poll`)
  passing the conformance kit; update `docs/guides` adapter author guide and
  `docs/community-plugins.md` wording (FR8)

## 7. Integration

- [ ] 7.1 End-to-end: the SonarQube-shaped reference verdict program against a fake server,
  container mode, kill between `start` and the key commit, resumed by a second packaged jar
  instance (M3)
- [ ] 7.2 `./gradlew check` with PIT; sweep report for D9 rows; recommend a commit message
