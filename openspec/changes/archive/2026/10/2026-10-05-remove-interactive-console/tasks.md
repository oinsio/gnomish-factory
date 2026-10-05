# Tasks

Group 1 (§1–§2) migrates the test tree onto the fake agent while the interactive code still
compiles (design D7). Group 2 (§3–§5) deletes. Root `check` is green at the end of each group.

## 1. Fake-agent role selection and fixtures

- [x] 1.1 Extend `fake-agent.sh` with `GNOMISH_FAKE_JUDGE_SCENARIO` + `GNOMISH_FAKE_JUDGE_MODEL`
      (design D4): when both are set and argv carries `--model <GNOMISH_FAKE_JUDGE_MODEL>`, the
      judge scenario plays, else `GNOMISH_FAKE_SCENARIO`; document both in the fake-agent
      README's environment table. Verify with a `FakeAgentBinarySpec` feature that invokes the
      script twice, once per model, and reads the two different `stdout.jsonl` back.
- [x] 1.2 Add `FakeAgentSupport.propertiesFor(String scenario, String judgeModel, String
      judgeScenario)` writing the three variables into the per-scenario wrapper (one wrapper per
      distinct triple per JVM, same cache), plus a `FakeAgentBinary.commandPrefixFor(...)`-style
      helper the packaged-jar harness can call to obtain a wrapper path. Verify a
      `CliJudgeVoter` spec in `:adapters:agent` drives a `judge-verdict-pass` vote and a
      `plain-round` executor round through one `FactoryProperties` (FR6, NFR-C1).
- [x] 1.3 Remove the `external` check from `.gnomish-fixtures/e2e/.gnomish/stages/work/stage.yaml`
      (design D5) and rewrite `E2eFixture`'s javadoc to "three check kinds". Add a new fixture
      `.gnomish-fixtures/e2e-unconfigured-provider` declaring one `external` check on provider
      `github` with no `factory.check.github` in the run. Verify both fixtures load (the second
      one must load structurally today and refuse only after task 3.1).
- [x] 1.4 Make `E2eProcessHarness.run` accept the agent binary: pass
      `--factory.agent-cli-binary=<wrapper>` as a process argument and assert in
      `E2eProcessHarnessSmokeSpec` that `RunArgumentsParser` does not reject it (FR6). Verify
      the smoke spec runs a fake-agent `plain-round` through the jar with no `--interactive`.

## 2. Migrate every interactive-driven spec

- [x] 2.1 Before editing, produce the migration table in this task's report: for each file
      below, the stdin script it feeds today, the fake-agent scenario sequence that replaces it
      (executor scenario, judge scenario, `next-scenario` chain), and the operator-dialog lines
      that stay on stdin. Files (from `grep -rlE 'InteractiveMode\.(ALL|EXECUTOR_ONLY|JUDGE_ONLY)|--interactive' --include='*.groovy' .`):
      `:application` — `RunArgumentsParserSpec`, `ServeArgumentsParserSpec`,
      `TakeArgumentsParserSpec`, `TakeOrderSpec`; `:bootstrap` — `ContainerGitModeRunnerSpec`,
      `ContainerResumeSpecBase`, `ContainerTerminalDriveSpec`, `ExecutorAdapterSelectorSpec`,
      `GitKillResumeSalvageCompletionSpec`, `GitModeRunCloneUntouchedSpec`, `GitModeRunnerSpec`,
      `GitResumeContinuationEdgeCasesSpec`, `GitResumeOutcomeSpec`, `GiteaBestEffortPushE2ESpec`,
      `GiteaCrossInstanceResumeE2ESpec`, `ManualRunAssemblySpec`, `ManualRunRunnerSpec`,
      `RunnerStartHardeningSpec`, `TakeBareAutoSpec`, `TakeCommandMdcSpec`, `TakeCommandSpec`,
      `TakeDispositionSpec`, `TakeEngineExecutionEscalationSpec`, `TakeResumeSpecBase`,
      `e2e/E2eProcessHarnessSmokeSpec`, `e2e/ExitCodeMatrixSpec`, `e2e/ReferenceE2ESessionSpec`,
      `e2e/ollama/OllamaWriteFileScenarioSpec` (javadoc only). Verify the table names every
      file and the grep returns nothing outside it.
- [x] 2.2 Migrate the `:bootstrap` runner and resume specs (`GitModeRunnerSpec`,
      `GitResumeOutcomeSpec`, `GitResumeContinuationEdgeCasesSpec`, `GitModeRunCloneUntouchedSpec`,
      `GitKillResumeSalvageCompletionSpec`, `RunnerStartHardeningSpec`, `ManualRunRunnerSpec`,
      `ContainerGitModeRunnerSpec`, `ContainerTerminalDriveSpec`, `ContainerResumeSpecBase`,
      `TakeResumeSpecBase`) to `RunOrder(..., InteractiveMode.NONE, ...)` over
      `FakeAgentSupport.propertiesFor(...)`, keeping their stdin scripts only for escalation
      decisions and checkpoint confirmations. Verify each spec passes and its feature names
      still describe the same behavior (NFR-R1: no spec grows a real-stdin dependency).
- [x] 2.3 Migrate the take specs (`TakeBareAutoSpec`, `TakeCommandSpec`, `TakeCommandMdcSpec`,
      `TakeDispositionSpec`, `TakeEngineExecutionEscalationSpec`, `GiteaBestEffortPushE2ESpec`,
      `GiteaCrossInstanceResumeE2ESpec`) the same way. Verify `:bootstrap:test` green.
- [x] 2.4 Migrate the packaged-jar journeys: `ReferenceE2ESessionSpec` (decision → quality
      retry → pause → complete: `decision-then-plain` executor, `judge-verdict-pass` judge, stdin
      carries only the decision answer and the checkpoint Enter), `E2eProcessHarnessSmokeSpec`,
      and every `ExitCodeMatrixSpec` row that passed `--interactive` (the "truncated script exits
      4" row is deleted here — see 3.4; "Ctrl-D at the escalation resume prompt exits 10" uses
      `decision-needed`; "Ctrl-D at the manual checkpoint prompt exits 11" uses `plain-round`).
      Verify `e2eTest` green with no `--interactive` argument anywhere under `e2e/`.
- [x] 2.5 Migrate the argument-surface specs (`RunArgumentsParserSpec`, `TakeArgumentsParserSpec`,
      `ServeArgumentsParserSpec`, `TakeOrderSpec`, `ExecutorAdapterSelectorSpec`,
      `ManualRunAssemblySpec`) so that every feature asserting an interactive selection is
      replaced by one asserting the flag is rejected as unknown (FR1) or the CLI adapter is
      bound (FR2). Keep the features green against the current code (rejection features will
      go red only after 4.1 and are marked `@PendingFeature` until then). Verify
      `:application:test` and `:bootstrap:test` green.
- [x] 2.6 Decide FR7: `StatusReportReferenceFixture` is hand-built (fixed instants), not
      session-produced — record in this task's report that no regeneration is needed, or
      regenerate if the check shows otherwise. Verify `StatusReportEquivalenceContractSpec` and
      `StatusReportJsonMapperSpec` green.
- [x] 2.7 Group-1 gate: root `./gradlew check` green with the interactive code still present;
      report the run in this task.

## 3. Startup refusal for unconfigured check providers (FR3, design D3)

- [x] 3.1 Add `UnconfiguredCheckProviderRule` to `:domain` `pipeline` beside `ApiExecutorRule`:
      `validate(List<StageDefinition>, Set<String> configuredProviders)` → located
      `ConfigError`s in pipeline order, message naming the provider and the configured set
      (NFR-O1). Spock spec with a data table: configured / unconfigured / no external checks /
      two stages ordering. Verify PIT 100% on the class.
- [x] 3.2 Thread the configured set into `PipelineLoader.loadConfiguration` and
      `PipelineValidator.validate` from the composition root's `FactoryProperties.check()` keys
      resolved through `CheckProviderSeam` — consumers: `PipelineStartup` (`run`),
      `TrustedTierStartup` and `ResumeLawBinding` (`take`, `serve`). First establish, by reading `DashboardCommand` / `TrackerWiring.resolveReadOnly`, whether `dashboard` (and so `gnomish-up`) loads `.gnomish/` through `PipelineLoader`; record the answer in the task report and add a spec for it: a read-only command SHALL NOT refuse because a check provider is unconfigured. Verify
      `ExitCodeMatrixSpec` gains "unconfigured check provider exits 3 before any dialog" over
      the `e2e-unconfigured-provider` fixture, and a `:bootstrap` spec shows `take` refusing the
      same pipeline before any tracker write.
      *Report:* `board` and `dashboard` do load through `PipelineLoader`
      (`TrackerWiring.resolveReadOnly` → `TakeCommandSupport.loadPipeline` → `PipelineSource`).
      They now call a new port method, `PipelineSource.loadReadOnly`, which grades the
      configured set as "every discovered provider", so only the rule is skipped and the
      undiscovered-provider check still applies (`GnomishDirPipelineSourceSpec`; `ReportCommandsSpec`
      stubs only `loadReadOnly`, so both views are shown to take it). The message reads
      "check provider 'X' has no factory.check.X section in this factory's configuration;
      configured providers: [...]" rather than D3's "is discovered but …": the rule cannot see
      the discovered set, and an undiscovered provider trips it too (beside the seam's
      "unknown check provider" error). `take`: `TakeCommandSpec` "an external check on an
      unconfigured provider refuses take at load, before any tracker call".
      `PipelineValidator.validate` is re-exposed through `:gnomish-plugin-api`, so the new
      signature broke `japicmpApiGate`: the api went 0.8.0 → 0.9.0 with `compat-baseline/`
      regenerated (decision: the human, over keeping a rule-skipping overload).
- [x] 3.3 Simplify `CheckEquipment.externalCheckClient`: drop the `DialogConsole` parameter
      and the `configured.isEmpty()` branch; the dispatching composite is built unconditionally
      (the rule in 3.2 guarantees a provider for every declared check). Update
      `ManualRunAssemblyCheckClientWiringSpec`: the "default external-check client" feature
      becomes "a pipeline with no external checks builds the dispatching client over an empty
      configuration and never resolves a credential". Verify spec green.
- [x] 3.4 Old-way sweep for the second D9 row: `grep -rn "InteractiveExternalCheckClient\|configured.isEmpty()" */src/main` returns nothing;
      add `ExternalCheckWiringBoundarySpec` in `:bootstrap` scanning `*/src/main` for every
      `new .*ExternalCheckClient(` construction and asserting each is
      `ProviderDispatchingExternalCheckClient` or the pin guard (allowlist by file, scan asserts
      it reached each). Verify the spec is red when a console-backed construction is planted in
      a scratch copy and green on the tree.
      *Report:* the grep's only hits are `InteractiveExternalCheckClient`'s own declaration and
      constructors in `adapter.console` — no use survives in `src/main`; the class goes in 4.3.
      The spec derives the implementor names from the tree (the github client is
      `GithubCheckExternalClient`, which `new .*ExternalCheckClient(` misses), allowlists five
      files (the owner, the pin guard, and the http, github and sample provider factories
      building their own clients) and excludes `test-fixtures/` (test support). Planting a
      construction in `CheckEquipment` first showed the spec green — a fully qualified
      `new com.….InteractiveExternalCheckClient(` dodged the pattern; the pattern now accepts a
      qualifier, a seeded row pins it, and the planted copy went red naming `CheckEquipment`.

## 4. Remove the mode

- [x] 4.1 Delete `RunArguments.InteractiveMode`, `InteractiveModeParser`, the `interactiveMode`
      field and constructor parameter of `RunArguments`, `TakeArguments`, `RunOrder`, the
      `rejectBatchOnlyFlags` interactive branch, and the `NONE` literals in `ServeAssembly`,
      `TakeDispatcher`, `TakeSlotRunner` javadoc and every spec fixture (design D1). Un-pend the
      2.5 rejection features. Verify `grep -rn "InteractiveMode\|interactiveMode\|--interactive" */src */*/src`
      returns nothing and the build compiles, and a `RunArgumentsParserSpec` feature shows the
      usage message of `run` lists exactly `dir, task, task-file, task-id, from-stage, mode,
      base, resume, discard-work` (the usage error is the operators' only `--help`).
      *Report:* the grep's only hits are the FR1 rejection features themselves
      (`RunArgumentsParserSpec`, `TakeArgumentsParserSpec`, `ServeArgumentsParserSpec`), which
      must name the token they refuse. `serve` dropped `interactive` from its own rejected list:
      the token now fails as an unknown option like on `run` and `take`, and the serve spec's
      row stays green on the generic message. `ManualRunRunnerSpec` still passed `--interactive`
      in its "input-exhausted message" feature, which 2.x missed; it tested the exit-4 path 4.4
      retires and is deleted here. `RawOptionReadBoundarySpec` lost the `InteractiveModeParser`
      allowlist row. `:application:test` green; `:bootstrap` touched specs green.
- [x] 4.2 Collapse `ExecutorAdapterSelector.stageExecutor` / `judgeVoter` to unconditional CLI
      construction; apply design D2 (inline into `RunAssembler` if it stays under 200 lines,
      else keep the class with a corrected javadoc). Delete the interactive features of
      `ExecutorAdapterSelectorSpec` / `ManualRunAssemblySpec` left from 2.5. Verify first D9 row:
      `grep -rn "new EnginePorts(" */src/main` returns only `RunAssembler`.
      *Report:* D2 resolved to **keep the class** (design D2 amended). With the switches gone it
      still holds four medium decisions — host vs sandbox rounds, the host-git decoration on the
      host branch only, the resume re-verification wrapper in container mode, the judge's
      sandbox environments and its narrower progress listener — pinned by five surviving
      features of `ExecutorAdapterSelectorSpec`; and `define-executor-contract` D8/D10,
      `add-command-executor` and `extract-agent-round-runner` 5.1 name it the owner of the
      `ExecutorRegistry` dispatch. Javadoc rewritten (no "extracted for file size"); the
      `DialogConsole` and mode parameters are gone. Grep: one hit, `RunAssembler.java:102`.
- [x] 4.3 Delete the `adapter.console` package (`InteractiveStageExecutor`, `InteractiveJudgeVoter`,
      `InteractiveExternalCheckClient`, `StageBriefing`, `FindingsDialog`, `package-info`) and
      its nine specs plus `ExternalCheckDialogFixture`; fix the `PipelineLaw` and
      `BriefingSections` javadocs that name `StageBriefing`. Verify `:adapters` compiles, the
      dependency-analysis plugin reports no now-unused dependency, and `grep -rn "adapter.console" */src` is empty.
      *Report:* six production files and nine test files (eight specs plus the fixture) deleted.
      Javadocs fixed beyond the two named: `BriefingSections` (its two consumers are now the
      executor and judge prompt builders — the agent-executor delta), `adapter/briefing` and
      `adapter/engine` `package-info`, `PipelineLaw.Content`, and `CliStageExecutorContractSpec`
      (it compared itself to the deleted interactive contract spec). `:adapters:projectHealth`
      advice empty; the grep is empty; `:adapters:test` and `:adapters:agent:test` green.
      `ExternalCheckWiringBoundarySpec` keeps the name `InteractiveExternalCheckClient` only as a
      seeded source string — the planted console client its seeded feature must still catch.
- [x] 4.4 Retire exit code 4 (design D6): delete `InputExhaustedException`, the
      `case InputExhaustedException` arm in `RunExitCodeMapper`, `DialogConsole.inputExhausted`
      and its latch in `readLine`, the guard in `EscalationResumeDialog.handle`, and the
      `RunExceptionReporting` catch of the type; delete the latch features of `DialogConsoleSpec`
      and the exit-4 features of `RunExitCodeMapperSpec` / `ExitCodeMatrixSpec`. Verify PIT 100%
      on `DialogConsole`, `EscalationResumeDialog`, `RunExitCodeMapper` (NFR-R2).
      *Report:* the latch features were not deleted outright: three `DialogConsoleSpec` features
      kept their EOF-propagation half (renamed, latch assertion dropped), only "is not exhausted
      before any EOF" went. `RunnerOutcomeLoopSpec` lost the Case-1 feature, the "Paused ignores
      the latch" feature and its `ReExhaustibleConsoleIO` helper. `RunExceptionReporting` keeps
      the "Input exhausted — stopping." line for `ConsoleClosedException` (a prompt outside the
      run loop, e.g. the takeover confirmation); its specs and
      `ReportedFailureExceptionReporterSpec` now use that type. `RunExitCodeMapperSpec` gains
      "the retired code 4 is never returned". The `ExitCodeMatrixSpec` exit-4 row was already
      gone in 2.4. `GitModeRunner` (declared pair with `ContainerGitModeRunner`) changed in
      javadoc only — the twin never named the type, so no mirrored change. `:application:pitest`:
      whole module 100%, 0 survived, 0 no-coverage.
- [x] 4.5 Remove `ScriptedConsoleIO` usages that only fed interactive adapters; verify
      `grep -rln "ScriptedConsoleIO" */src/test` lists only dialog, report-command and console
      specs (M2) and record the list in this task.
      *Report:* nothing left to remove — every spec that fed an interactive adapter lost that
      use with the adapters (4.3) or the migration (§2). The list: dialogs and runner prompts
      (`RunnerOutcomeLoopSpec`, `RunChainFakes` and its four runner users
      `GitModeRunnerFreshRunSpec`, `GitResumeRoutingSpec`, `ContainerResumeRoutingSpec`,
      `ContainerTerminalDriveDisposalSpec` — each script is a checkpoint Enter or an escalation
      answer); report commands (`StatusCommandSpec`, `UsageCommandSpec`, `BoardCommandSpec`,
      `ReportCommandsSpec`, `ProjectCommandSpec`); the console itself (`DialogConsoleSpec`,
      `ScriptedConsoleIOSpec`); output capture at the entry point (`EntrypointSpec`,
      `CliEntrypointContractSpec`, `OperatorConfigLoaderHarness`, empty scripts).

## 5. Documentation and gates

- [x] 5.1 `docs/guides/operator-guide-run.md`: delete the `--interactive` flag row and the
      "Manifest-driven run and `--interactive` overrides" section (keep the manifest-driven
      paragraph and the `api` sentence, add the unconfigured-provider sentence); rewrite the
      exit-code table per FR8 with rows 5, 6, 7 and the retired-4 note. Verify the table
      matches `RunExitCodeMapper` arm for arm.
- [x] 5.2 `README.md` (`run` paragraph), `docs/guides/operator-guide.md` (the `--interactive[=executor|judge]` row of the `run`/`take` flag table),
      `docs/guides/operator-guide-serve.md` (lines 40–52), `docs/glossary.md` ("Run order" entry):
      drop the mode; where a guide recommended `--interactive` for a free dry-run, point at the
      fake-agent fixture in the test tree (UX1). Verify `grep -rn "interactive" README.md docs`
      returns only the sandbox-VM "interactive-latency" sentence and the serve guide's
      "non-interactive" statements.
- [x] 5.3 Update `.claude/rules/testing.md` if it names the interactive adapters (grep), and add
      the `ExternalCheckWiringBoundarySpec` to the enforcement examples in
      `.claude/rules/implementation.md` item 4 only if the precedent list is meant to grow (else
      leave). Verify greps.
- [x] 5.4 Group-2 gate and metrics: root `./gradlew check` green; record M1 (`grep -rn "InteractiveMode\|--interactive\|adapter.console" */src */*/src docs README.md` empty), M2 and M3
      results in this task; list the two active changes (`add-command-executor`,
      `add-decision-arbiter`) that need `/opsx:update` next.
      *Result (2026-10-05):*
      - M3: root `./gradlew check --continue` (55 min): every test, PIT and gate task green; the
        only failures were `spotlessJavaCheck` in `:application` and `:bootstrap` — signatures
        shortened by the removed `interactiveMode` parameter (`RunOrder`, `ManualRunDrive`,
        `RunAssembler`, `ServeArgumentsParser`, …). Fixed with `spotlessApply`
        (whitespace only); `./gradlew spotlessCheck` plus compile re-run green.
      - M1: zero hits in `docs`, `README.md` and every `src/main`. The remaining hits are
        `src/test` specs that assert the FR1 rejection (`RunArgumentsParserSpec`,
        `TakeArgumentsParserSpec`, `ServeArgumentsParserSpec`); they are the regression guard
        FR1's scenarios require, so the metric is read as "no production or doc hit".
      - M2: `ScriptedConsoleIO` remains only in operator-dialog specs (`RunnerOutcomeLoopSpec`,
        `RunChainFakes`, `GitModeRunnerFreshRunSpec`, `GitResumeRoutingSpec`,
        `ContainerResumeRoutingSpec`, `ContainerTerminalDriveDisposalSpec`), report commands
        (`StatusCommandSpec`, `UsageCommandSpec`, `BoardCommandSpec`, `ReportCommandsSpec`,
        `ProjectCommandSpec`), the console itself (`DialogConsoleSpec`, `ScriptedConsoleIOSpec`)
        and entry-point output capture (`EntrypointSpec`, `CliEntrypointContractSpec`,
        `OperatorConfigLoaderHarness`) — the task-4.5 list exactly.
      - Docs grep of task 5.2: beyond the serve guide's "non-interactive", three hits stay in
        `operator-guide-run.md`, each deliberate — the FR8-mandated retired-4 row, the git
        credential-prompting note (unrelated), and the note that the former flag is now a
        usage error (Impact). The sandbox-VM "interactive-latency" sentence no longer exists.
      - `/opsx:update` next, before either is applied: `add-command-executor` (design D6),
        `add-decision-arbiter` (design D2, tasks) — and, found by the sweep, also
        `define-executor-contract` (design D8 "interactive substitution wraps the agent arm",
        tasks). `migrate-verify-to-executor-contract` already accounts for this change.
