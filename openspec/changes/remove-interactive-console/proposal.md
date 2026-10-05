# Proposal: remove-interactive-console

## Why

`gnomish run --interactive` lets a human stand in for the gnome, for the judge, and — with no
`factory.check.<provider>` section configured — for the CI that serves an `external` check.
The three console adapters (`InteractiveStageExecutor`, `InteractiveJudgeVoter`,
`InteractiveExternalCheckClient`) were the first-iteration scaffolding of `add-manual-run`
(July 2026): they let the engine be exercised end to end before any real agent adapter
existed. Today every real path — `take`, `serve`, and the manifest-driven `run` — binds the
CLI adapters, and the real user of `run` is an AI agent debugging a pipeline stage as a
subprocess, which cannot answer a console prompt at all.

The mode is unused: in 19 sessions of real pipeline debugging on a downstream project
(29–30 September 2026) `run --interactive` was never started once — the work went through
`status`, a manifest-driven `run`, and `serve` with the dashboard. Yet it is not free. `RunArguments.InteractiveMode` travels through both
argument parsers, `RunOrder`, `TakeArguments`, `ServeAssembly`, `TakeSlotRunner`,
`RunAssembler` and `ExecutorAdapterSelector`, where `take`'s batch form and `serve` only ever
reject or hard-code `NONE`. Twenty-eight specs — the git-mode and take runners, both resume
spec bases, the Gitea E2E suites, and the whole exit-code matrix on the packaged jar — script
the human gnome through stdin instead of the `fake-agent` fixture, so every change to the
run chain has to keep two scripted gnomes in step. The console CI role is a silent fallback:
a pipeline declaring an `external` check on an unconfigured provider does not fail, it
prompts. And exit code 4 exists solely for stdin ending inside an interactive adapter.

## What Changes

- **REMOVED**: the `--interactive[=executor|judge]` flag on `run` and `take`, the
  `RunArguments.InteractiveMode` enum, `InteractiveModeParser`, and the `interactiveMode`
  field of `RunArguments`, `TakeArguments` and `RunOrder`.
- **REMOVED**: the `adapter.console` package — the three interactive adapters,
  `StageBriefing`, `FindingsDialog` — with their unit and contract specs.
- **REMOVED**: the console fallback for external checks. **BREAKING**: a pipeline that declares
  an `external` check whose provider has no `factory.check.<provider>` section now fails at
  startup with the pipeline-load exit code, naming the check, exactly as `api` stages do today.
- **REMOVED**: exit code 4 (`InputExhaustedException`) and the `inputExhausted` latch on
  `DialogConsole`, both unreachable once no engine port reads the console. The number stays a
  documented gap in the exit-code table.
- **MODIFIED**: `ExecutorAdapterSelector` loses its role switches and binds the CLI adapters
  unconditionally; the manifest-driven binding becomes the only binding.
- **MODIFIED**: every spec that scripts the human gnome through `InteractiveMode.ALL` or
  `--interactive` on the packaged jar drives the `fake-agent` fixture instead; the committed
  `status-report-v1.reference.json` is regenerated from that session.
- **MODIFIED**: operator documentation (`README.md`, `operator-guide-run.md`,
  `operator-guide.md`, `operator-guide-serve.md`, `docs/glossary.md`) drops the mode; the
  run guide's exit-code table gains the missing row for code 7 and the gap note for code 4.

Not in this change: the operator dialogs of `run` (escalation decision, checkpoint
confirmation) and the takeover confirmation of `take`. They stand in for the tracker, not for
the gnome; `make-run-headless` (sequenced after this change) removes the first two.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `manual-run`: "Manifest-driven mechanism with interactive override" loses the override;
  the three "Interactive …" requirements are removed; "Exit codes by outcome family" retires
  code 4 and records 5, 6, 7 that the spec table never listed; "EOF semantics without a TTY"
  loses the mid-stage case; "Port-contract compliance of new adapters" is restated for the
  CLI adapters and the fake agent alone.
- `tracker-take`: "take subcommand surface" drops `--interactive` from the flag list and the
  batch-rejection scenario.
- `agent-executor`: "Shared briefing renderer" is restated without the interactive executor
  as its second consumer.
- `verification/verification-hardening`: "Pin-check guards external checks" loses the
  interactive-client scenario; the vacuous pass stays for a declaration naming no pin paths.

## Goals

- G1: no production code path reads the console on behalf of a gnome, a judge, or a CI.
- G2: one scripted gnome in the test tree — the `fake-agent` fixture — for every run-chain
  spec, in every module.
- G3: an `external` check with no configured provider is a startup refusal, never a prompt.
- G4: the flag surface, the exit-code table and the guides describe exactly what the binary
  does.

## Non-Goals

- NG1: changing how `run` handles escalation and checkpoints (`make-run-headless`).
- NG2: removing `gnomish run`, `--mode=in-place`, or the claimless `--resume` path.
- NG3: renumbering exit codes; 4 remains a gap.
- NG4: touching `ConsoleTakeoverConfirmation` or the `status` / `usage` / `board` /
  `dashboard` console commands.
- NG5: any change to the fake-agent binary's contract beyond adding scenarios the migrated
  specs need.

## Users & Scenarios

- U1: **An AI agent debugging a stage** runs `gnomish run` as a subprocess; it needs one
  binding — the manifest's — and a startup refusal when the pipeline asks for a CI nobody
  configured, instead of a prompt it cannot answer.
- U2: **A maintainer changing the run chain** edits one scripted gnome (a fake-agent
  scenario) and one argument surface, not an interactive twin of each.
- U3: **A pipeline author** reads the run guide and finds no mode the binary no longer has.

## Requirements

### Functional

- FR1: `run` and `take` SHALL reject `--interactive` in every form as a usage error, the same
  way an unknown flag is rejected today; the enum, the parser and the carried field are
  deleted, not deprecated.
- FR2: the stage executor and the judge voter SHALL be bound from the manifest alone: the CLI
  stage executor (host or sandboxed, as the sandbox binding decides) and the CLI judge voter,
  with no role switch in `ExecutorAdapterSelector`.
- FR3: a pipeline declaring an `external` check whose provider has no `factory.check.<provider>`
  section SHALL be refused in the startup validation chain — pipeline-load exit code, before
  any dialog, naming the stage, the check and the provider — alongside the `api` rejection.
- FR4: the `adapter.console` package SHALL be deleted; no production class SHALL implement a
  `StageExecutor`, `JudgeVoter` or `ExternalCheckClient` over a `DialogConsole`.
- FR5: exit code 4 SHALL be removed from `RunExitCodeMapper` together with
  `InputExhaustedException` and `DialogConsole#inputExhausted`; the escalation resume dialog
  SHALL no longer consult a latch before prompting.
- FR6: every spec that today builds a `RunOrder` with a non-`NONE` mode, or passes
  `--interactive` to the packaged jar, SHALL drive the `fake-agent` fixture; the migration
  SHALL land, green, before the interactive code is deleted.
- FR7: `status-report-v1.reference.json` SHALL be regenerated from the fake-agent session and
  the paid-smoke reference dump SHALL still converge with it.
- FR8: the exit-code table in `operator-guide-run.md` and in the `manual-run` spec SHALL list
  every code the mapper returns (0–3, 5–7, 10–12) and mark 4 as retired: used in the MVP,
  removed as unneeded, skipped so other codes do not shift, may be filled by a future tool
  failure.

### Non-Functional Reliability

- NFR-R1: no migrated spec SHALL become slower than its interactive predecessor by more than
  the fake agent's own process spawn; no spec SHALL depend on real stdin.
- NFR-R2: the build SHALL stay at the 100% mutation gate throughout: the deletion leaves no
  unkillable mutant behind (the `inputExhausted()` guard is the known candidate).

### Non-Functional Observability

- NFR-O1: the FR3 refusal SHALL print the located `ConfigError` on stderr in the same shape as
  the `api` rejection, so an operator reads which provider section to write.

### Non-Functional Security

- NFR-S1: removing the console client SHALL not widen the external-check pin guard: a
  provider-served check still contributes its pin paths and a declaration naming none still
  passes vacuously.

### Non-Functional Cost

- NFR-C1: migrated specs SHALL spend no tokens: the fake agent is the only gnome they run.

## Operator Experience Criteria

- UX1: `gnomish run --help`-level documentation, the README and the guides mention no human
  role; the only way to observe a pipeline without paying is the fake agent in the test tree,
  and the guides say so where `--interactive` used to be recommended.
- UX2: the exit-code table reads as one contiguous contract with one explicit gap.

## Success Metrics

- M1: `grep -rn "InteractiveMode\|--interactive\|adapter.console" */src */*/src docs README.md`
  returns zero hits after the change.
- M2: `grep -rln "Kept in sync with\|fake-agent\|FakeAgent" */src/test` shows every former
  interactive-driven spec on the fake agent; `grep -rn "ScriptedConsoleIO" */src/test` is
  limited to specs of the operator dialogs, `status`/`usage`/`board` and the console itself.
- M3: root `check` green, PIT at 100% in every touched module.

## Impact

- Modules: `:application` (arguments, `RunOrder`, `DialogConsole`, exit-code mapper),
  `:bootstrap` (`ExecutorAdapterSelector`, `RunAssembler`, `CheckProviderWiring`,
  `ManualRunAssembly`, every affected spec), `:adapters` (`adapter.console` package deleted,
  `PipelineLaw` / `BriefingSections` javadoc, startup rule for FR3), `:domain` (a new pure
  validation rule beside `ApiExecutorRule` or its adapter-side twin — decided in design),
  `:test-fixtures` (fake-agent scenarios the migration needs).
- Active changes whose artifacts mention the mode and must be brought in step through
  `/opsx:update` once this change is applied: `add-command-executor` (design D6 wraps
  "interactive substitution" around the agent branch; a risk row), `add-decision-arbiter`
  (design D2 plans an interactive console arbiter adapter).
- Operators' own wrapper scripts and notes may carry an `--interactive` example (a downstream
  project's wrapper header does); after this change such an example is a usage error (exit 2),
  and the guides say so.
- Sequenced before `make-run-headless`, which removes the remaining console prompts.

## Open Questions

- Q1: Does the FR3 rule live in `:domain` beside `ApiExecutorRule` (pure, but it needs the
  configured provider set as input) or in the adapter's `ExternalCheckSeamValidator`, which
  already sees the discovered providers? Resolved in design D3.
- Q2: Which fake-agent scenarios are missing for the migrated E2E journeys (retry, escalate,
  pause, complete in one session)? The migration task enumerates them per spec.
