# Tasks

Group 1 (§1–§2) migrates the test tree onto the fake agent while the interactive code still
compiles (design D7). Group 2 (§3–§5) deletes. Root `check` is green at the end of each group.

## 1. Fake-agent role selection and fixtures

- [ ] 1.1 Extend `fake-agent.sh` with `GNOMISH_FAKE_JUDGE_SCENARIO` + `GNOMISH_FAKE_JUDGE_MODEL`
      (design D4): when both are set and argv carries `--model <GNOMISH_FAKE_JUDGE_MODEL>`, the
      judge scenario plays, else `GNOMISH_FAKE_SCENARIO`; document both in the fake-agent
      README's environment table. Verify with a `FakeAgentBinarySpec` feature that invokes the
      script twice, once per model, and reads the two different `stdout.jsonl` back.
- [ ] 1.2 Add `FakeAgentSupport.propertiesFor(String scenario, String judgeModel, String
      judgeScenario)` writing the three variables into the per-scenario wrapper (one wrapper per
      distinct triple per JVM, same cache), plus a `FakeAgentBinary.commandPrefixFor(...)`-style
      helper the packaged-jar harness can call to obtain a wrapper path. Verify a
      `CliJudgeVoter` spec in `:adapters:agent` drives a `judge-verdict-pass` vote and a
      `plain-round` executor round through one `FactoryProperties` (FR6, NFR-C1).
- [ ] 1.3 Remove the `external` check from `.gnomish-fixtures/e2e/.gnomish/stages/work/stage.yaml`
      (design D5) and rewrite `E2eFixture`'s javadoc to "three check kinds". Add a new fixture
      `.gnomish-fixtures/e2e-unconfigured-provider` declaring one `external` check on provider
      `github` with no `factory.check.github` in the run. Verify both fixtures load (the second
      one must load structurally today and refuse only after task 3.1).
- [ ] 1.4 Make `E2eProcessHarness.run` accept the agent binary: pass
      `--factory.agent-cli-binary=<wrapper>` as a process argument and assert in
      `E2eProcessHarnessSmokeSpec` that `RunArgumentsParser` does not reject it (FR6). Verify
      the smoke spec runs a fake-agent `plain-round` through the jar with no `--interactive`.

## 2. Migrate every interactive-driven spec

- [ ] 2.1 Before editing, produce the migration table in this task's report: for each file
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
- [ ] 2.2 Migrate the `:bootstrap` runner and resume specs (`GitModeRunnerSpec`,
      `GitResumeOutcomeSpec`, `GitResumeContinuationEdgeCasesSpec`, `GitModeRunCloneUntouchedSpec`,
      `GitKillResumeSalvageCompletionSpec`, `RunnerStartHardeningSpec`, `ManualRunRunnerSpec`,
      `ContainerGitModeRunnerSpec`, `ContainerTerminalDriveSpec`, `ContainerResumeSpecBase`,
      `TakeResumeSpecBase`) to `RunOrder(..., InteractiveMode.NONE, ...)` over
      `FakeAgentSupport.propertiesFor(...)`, keeping their stdin scripts only for escalation
      decisions and checkpoint confirmations. Verify each spec passes and its feature names
      still describe the same behavior (NFR-R1: no spec grows a real-stdin dependency).
- [ ] 2.3 Migrate the take specs (`TakeBareAutoSpec`, `TakeCommandSpec`, `TakeCommandMdcSpec`,
      `TakeDispositionSpec`, `TakeEngineExecutionEscalationSpec`, `GiteaBestEffortPushE2ESpec`,
      `GiteaCrossInstanceResumeE2ESpec`) the same way. Verify `:bootstrap:test` green.
- [ ] 2.4 Migrate the packaged-jar journeys: `ReferenceE2ESessionSpec` (decision → quality
      retry → pause → complete: `decision-then-plain` executor, `judge-verdict-pass` judge, stdin
      carries only the decision answer and the checkpoint Enter), `E2eProcessHarnessSmokeSpec`,
      and every `ExitCodeMatrixSpec` row that passed `--interactive` (the "truncated script exits
      4" row is deleted here — see 3.4; "Ctrl-D at the escalation resume prompt exits 10" uses
      `decision-needed`; "Ctrl-D at the manual checkpoint prompt exits 11" uses `plain-round`).
      Verify `e2eTest` green with no `--interactive` argument anywhere under `e2e/`.
- [ ] 2.5 Migrate the argument-surface specs (`RunArgumentsParserSpec`, `TakeArgumentsParserSpec`,
      `ServeArgumentsParserSpec`, `TakeOrderSpec`, `ExecutorAdapterSelectorSpec`,
      `ManualRunAssemblySpec`) so that every feature asserting an interactive selection is
      replaced by one asserting the flag is rejected as unknown (FR1) or the CLI adapter is
      bound (FR2). Keep the features green against the current code (rejection features will
      go red only after 4.1 and are marked `@PendingFeature` until then). Verify
      `:application:test` and `:bootstrap:test` green.
- [ ] 2.6 Decide FR7: `StatusReportReferenceFixture` is hand-built (fixed instants), not
      session-produced — record in this task's report that no regeneration is needed, or
      regenerate if the check shows otherwise. Verify `StatusReportEquivalenceContractSpec` and
      `StatusReportJsonMapperSpec` green.
- [ ] 2.7 Group-1 gate: root `./gradlew check` green with the interactive code still present;
      report the run in this task.

## 3. Startup refusal for unconfigured check providers (FR3, design D3)

- [ ] 3.1 Add `UnconfiguredCheckProviderRule` to `:domain` `pipeline` beside `ApiExecutorRule`:
      `validate(List<StageDefinition>, Set<String> configuredProviders)` → located
      `ConfigError`s in pipeline order, message naming the provider and the configured set
      (NFR-O1). Spock spec with a data table: configured / unconfigured / no external checks /
      two stages ordering. Verify PIT 100% on the class.
- [ ] 3.2 Thread the configured set into `PipelineLoader.loadConfiguration` and
      `PipelineValidator.validate` from the composition root's `FactoryProperties.check()` keys
      resolved through `CheckProviderSeam` — consumers: `PipelineStartup` (`run`),
      `TrustedTierStartup` and `ResumeLawBinding` (`take`, `serve`). First establish, by reading `DashboardCommand` / `TrackerWiring.resolveReadOnly`, whether `dashboard` (and so `gnomish-up`) loads `.gnomish/` through `PipelineLoader`; record the answer in the task report and add a spec for it: a read-only command SHALL NOT refuse because a check provider is unconfigured. Verify
      `ExitCodeMatrixSpec` gains "unconfigured check provider exits 3 before any dialog" over
      the `e2e-unconfigured-provider` fixture, and a `:bootstrap` spec shows `take` refusing the
      same pipeline before any tracker write.
- [ ] 3.3 Simplify `CheckProviderWiring.externalCheckClient`: drop the `DialogConsole` parameter
      and the `configured.isEmpty()` branch; the dispatching composite is built unconditionally
      (the rule in 3.2 guarantees a provider for every declared check). Update
      `ManualRunAssemblyCheckClientWiringSpec`: the "default external-check client" feature
      becomes "a pipeline with no external checks builds the dispatching client over an empty
      configuration and never resolves a credential". Verify spec green.
- [ ] 3.4 Old-way sweep for the second D9 row: `grep -rn "InteractiveExternalCheckClient\|configured.isEmpty()" */src/main` returns nothing;
      add `ExternalCheckWiringBoundarySpec` in `:bootstrap` scanning `*/src/main` for every
      `new .*ExternalCheckClient(` construction and asserting each is
      `ProviderDispatchingExternalCheckClient` or the pin guard (allowlist by file, scan asserts
      it reached each). Verify the spec is red when a console-backed construction is planted in
      a scratch copy and green on the tree.

## 4. Remove the mode

- [ ] 4.1 Delete `RunArguments.InteractiveMode`, `InteractiveModeParser`, the `interactiveMode`
      field and constructor parameter of `RunArguments`, `TakeArguments`, `RunOrder`, the
      `rejectBatchOnlyFlags` interactive branch, and the `NONE` literals in `ServeAssembly`,
      `TakeDispatcher`, `TakeSlotRunner` javadoc and every spec fixture (design D1). Un-pend the
      2.5 rejection features. Verify `grep -rn "InteractiveMode\|interactiveMode\|--interactive" */src */*/src`
      returns nothing and the build compiles, and a `RunArgumentsParserSpec` feature shows the
      usage message of `run` lists exactly `dir, task, task-file, task-id, from-stage, mode,
      base, resume, discard-work` (the usage error is the operators' only `--help`).
- [ ] 4.2 Collapse `ExecutorAdapterSelector.stageExecutor` / `judgeVoter` to unconditional CLI
      construction; apply design D2 (inline into `RunAssembler` if it stays under 200 lines,
      else keep the class with a corrected javadoc). Delete the interactive features of
      `ExecutorAdapterSelectorSpec` / `ManualRunAssemblySpec` left from 2.5. Verify first D9 row:
      `grep -rn "new EnginePorts(" */src/main` returns only `RunAssembler`.
- [ ] 4.3 Delete the `adapter.console` package (`InteractiveStageExecutor`, `InteractiveJudgeVoter`,
      `InteractiveExternalCheckClient`, `StageBriefing`, `FindingsDialog`, `package-info`) and
      its nine specs plus `ExternalCheckDialogFixture`; fix the `PipelineLaw` and
      `BriefingSections` javadocs that name `StageBriefing`. Verify `:adapters` compiles, the
      dependency-analysis plugin reports no now-unused dependency, and `grep -rn "adapter.console" */src` is empty.
- [ ] 4.4 Retire exit code 4 (design D6): delete `InputExhaustedException`, the
      `case InputExhaustedException` arm in `RunExitCodeMapper`, `DialogConsole.inputExhausted`
      and its latch in `readLine`, the guard in `EscalationResumeDialog.handle`, and the
      `RunExceptionReporting` catch of the type; delete the latch features of `DialogConsoleSpec`
      and the exit-4 features of `RunExitCodeMapperSpec` / `ExitCodeMatrixSpec`. Verify PIT 100%
      on `DialogConsole`, `EscalationResumeDialog`, `RunExitCodeMapper` (NFR-R2).
- [ ] 4.5 Remove `ScriptedConsoleIO` usages that only fed interactive adapters; verify
      `grep -rln "ScriptedConsoleIO" */src/test` lists only dialog, report-command and console
      specs (M2) and record the list in this task.

## 5. Documentation and gates

- [ ] 5.1 `docs/guides/operator-guide-run.md`: delete the `--interactive` flag row and the
      "Manifest-driven run and `--interactive` overrides" section (keep the manifest-driven
      paragraph and the `api` sentence, add the unconfigured-provider sentence); rewrite the
      exit-code table per FR8 with rows 5, 6, 7 and the retired-4 note. Verify the table
      matches `RunExitCodeMapper` arm for arm.
- [ ] 5.2 `README.md` (`run` paragraph), `docs/guides/operator-guide.md` (the `--interactive[=executor|judge]` row of the `run`/`take` flag table),
      `docs/guides/operator-guide-serve.md` (lines 40–52), `docs/glossary.md` ("Run order" entry):
      drop the mode; where a guide recommended `--interactive` for a free dry-run, point at the
      fake-agent fixture in the test tree (UX1). Verify `grep -rn "interactive" README.md docs`
      returns only the sandbox-VM "interactive-latency" sentence and the serve guide's
      "non-interactive" statements.
- [ ] 5.3 Update `.claude/rules/testing.md` if it names the interactive adapters (grep), and add
      the `ExternalCheckWiringBoundarySpec` to the enforcement examples in
      `.claude/rules/implementation.md` item 4 only if the precedent list is meant to grow (else
      leave). Verify greps.
- [ ] 5.4 Group-2 gate and metrics: root `./gradlew check` green; record M1 (`grep -rn "InteractiveMode\|--interactive\|adapter.console" */src */*/src docs README.md` empty), M2 and M3
      results in this task; list the two active changes (`add-command-executor`,
      `add-decision-arbiter`) that need `/opsx:update` next.
