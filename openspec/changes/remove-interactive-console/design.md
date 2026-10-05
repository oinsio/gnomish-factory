# Design: remove-interactive-console

## Context

See `proposal.md` — Why. The facts that shape the approach:

- `ExecutorAdapterSelector` (`:bootstrap`) holds the only two role switches; both branch on
  `RunOrder.interactiveMode()`, which `RunAssembler.assemble` reads once. `take`'s batch form
  and `serve` already pass `NONE` unconditionally (`TakeArgumentsParser.rejectBatchOnlyFlags`,
  `ServeAssembly`, `TakeSlotRunner` javadoc).
- `CheckEquipment.externalCheckClient` falls back to `InteractiveExternalCheckClient`
  when `factory.check` has no subsection. Pipeline loading already validates every
  `external` check's provider against the **discovered** providers
  (`ExternalCheckSeamValidator`, `PipelineModelBuilder`), so an `external` check on a
  discovered-but-unconfigured provider passes validation and reaches the console fallback.
- Exit code 4 is Case 1 of `add-manual-run` D2: EOF inside an interactive adapter, latched by
  `DialogConsole.inputExhausted`, read by `EscalationResumeDialog.handle`. The four remaining
  prompts (`EscalationResumeDialog`, `RunnerOutcomeLoop.handlePaused`,
  `GitResumeContinuation`, `ContainerResumeOutcomes`) all catch EOF and throw their own
  exit-10/11 exception, so once no port prompts, the latch is never observed `true`.
- The `fake-agent` fixture (`test-fixtures/src/main/resources/fake-agent`) selects one
  scenario per binary through `GNOMISH_FAKE_SCENARIO`, baked into a wrapper by
  `FakeAgentSupport.propertiesFor`. Every fake-agent-driven spec today declares a stage with
  no `judge` check, because the judge would run the same binary and play the same scenario.
  The interactive-driven E2E journeys (`ReferenceE2ESessionSpec`, `ExitCodeMatrixSpec`,
  `E2eProcessHarnessSmokeSpec`) exercise all four check kinds, including a 1-vote judge and
  a provider-less `external` check (`.gnomish-fixtures/e2e`).
- The packaged-jar harness (`E2eProcessHarness`) configures the spawned process through
  environment variables and Spring `--key=value` arguments; `RunArgumentsParser` reads only
  its own option names, so a `--factory.agent-cli-binary=<wrapper>` argument reaches
  `FactoryProperties` (prefix `factory`) untouched.

## Goals / Non-Goals

**Goals:**
- The migration lands green before any deletion, so each deleted class is dead at the moment
  it is deleted (FR6).
- The deletion is purely subtractive in production code except for two additions: the FR3
  startup rule and the fake-agent role selection the migration needs.

**Non-Goals:**
- Reworking the operator prompts (`make-run-headless`).
- A judge fake other than the existing binary.

## Decisions

**D1 — Delete the type, not the branch.** `RunArguments.InteractiveMode` is removed
together with the field in `RunArguments`, `TakeArguments` and `RunOrder`, and the two
`switch`es in `ExecutorAdapterSelector` collapse into unconditional CLI construction. The
`--interactive` token then fails the way any unknown flag fails today. *Rationale:*
`implementation.md` item 3 — a field that only ever holds `NONE` is the escape hatch that
lets a future adapter re-grow the switch; the compiler is the enforcement. *Alternative
rejected:* keep the enum with `NONE` as its single constant "for extension" — it keeps every
constructor site and every spec fixture carrying a value nobody reads.

**D2 — Fold `ExecutorAdapterSelector` back into `RunAssembler` only if it fits the file-size
rule.** With the switches gone the class is two constructors plus the sandbox branch. If
`RunAssembler` stays under the 200-line cap with them inlined, inline; otherwise keep the
class and update its javadoc (it says today it was extracted "purely to keep both files
within the project's file-size guidance"). *Rationale:* `process-invariants.md` — a split
must split a responsibility; this one never did. *Alternative rejected:* a decision now,
before the line count is known.
*Resolved at apply (task 4.2): the class stays.* The line count was not the deciding fact.
With the role switches gone, the class still makes four decisions about the execution medium
(host vs sandbox rounds, the host-git decoration on the host branch only, the resume
re-verification wrapper in container mode, the judge's sandbox environments and narrower
progress listener), so the split does split a responsibility — binding the adapters to the
medium — and `RunAssembler` stays an assembler that asks rather than decides. Three active
changes also name the class as the home of the `ExecutorRegistry` dispatch
(`define-executor-contract` D8, `add-command-executor`, `extract-agent-round-runner`);
inlining it here would be undone by the first of them.

**D3 — The FR3 rule is a pure domain rule beside `ApiExecutorRule`, fed the configured
provider set.** `PipelineValidator` gains `UnconfiguredCheckProviderRule.validate(stages,
configuredProviders)` producing a located `ConfigError` (`stages/<name>/stage.yaml`,
`verify[i].provider`, "check provider 'X' is discovered but has no factory.check.X section;
configured providers: [...]") in pipeline order; `PipelineLoader` receives the configured
set the same way it receives the discovered validators, from the composition root's
`FactoryProperties.check()` keys (through the `CheckProviderSeam` resolution that
`CheckEquipment.checkSubsections` already performs). `run`, `take` and `serve` all load
through `PipelineLoader`, so one rule covers three entry points. `dashboard` is a read-only
command whose javadoc declares `PipelineLoadFailedException`; task 3.2 establishes whether it
loads through `PipelineLoader`, and if it does, it is given the loader without the rule (a
read-only view is not refused for an unconfigured check provider). *Rationale:* the `api`
rejection set the precedent — a pipeline that can never run its stage is a load failure,
exit 3, before any dialog (UX2 of `add-agent-executor`); the seam validator is the wrong
home because it grades the manifest against what is *installed*, and "configured" is a
factory-instance fact. *Alternative rejected:* refusing at `CheckEquipment` assembly
time — that runs after the branch and worktree exist, and its exception would map to exit 1,
not the pipeline-load code.
*Resolved at apply (task 3.2): the message drops "is discovered but".* The code emits "check
provider 'X' has no factory.check.X section in this factory's configuration; configured
providers: [...]" (`UnconfiguredCheckProviderRule`). The rule is fed only the configured set,
not the discovered one, so it cannot tell a discovered provider from an unknown one — and an
undiscovered provider trips it too, beside `ExternalCheckSeamValidator`'s "unknown check
provider" error in the same load pass; "is discovered" would claim a fact the rule never checked.

**D4 — Fake-agent role selection by the `--model` token.** The fake gains one optional
variable, `GNOMISH_FAKE_JUDGE_SCENARIO`: when set and the invocation's argv carries
`--model <m>` where `<m>` equals `GNOMISH_FAKE_JUDGE_MODEL`, the judge scenario plays;
otherwise `GNOMISH_FAKE_SCENARIO` plays as today. `FakeAgentSupport.propertiesFor` gains an
overload taking the judge pair, and the E2E fixture's `stage.yaml` already names distinct
models (`work-model`, `judge-model`). *Rationale:* the real adapter puts `--model` first in
every invocation (`AgentInvocationOptions`), for executor and judge alike, and the manifest
already tells the two apart by model; no new harness channel and no stdin sniffing.
*Alternative rejected:* a second fake binary for the judge — `FactoryProperties` carries one
`agentCliBinary`, and adding a judge-binary property to production for a test need is the
wrong direction. *Alternative rejected:* selecting on the prompt text read from stdin —
couples the fake to the briefing renderer's wording.

**D5 — The E2E fixture drops its `external` check; provider dispatch keeps its own specs.**
`.gnomish-fixtures/e2e` declares `ci/build` with no provider, which resolved to the
console. No provider can be configured in a packaged-jar run without a live GitHub or a
WireMock the jar cannot see, so the check leaves the fixture, and the journeys exercise
three check kinds. The pin-guard and the dispatching client stay covered by
`ManualRunAssemblyCheckClientWiringSpec` and the `:adapters:github` contract suite; the FR3
refusal gets its own `ExitCodeMatrixSpec` row over a new `e2e-unconfigured-provider`
fixture. *Rationale:* the E2E layer proves the jar drives the engine; the external-check
port has its own contract layer. *Alternative rejected:* a scripted external-check client
selected by a test-only property — a second console in disguise.

**D6 — Exit code 4 is retired, not reassigned.** `InputExhaustedException`,
`DialogConsole.inputExhausted` and the guard in `EscalationResumeDialog.handle` are
deleted; `RunExitCodeMapper` keeps no arm for 4; the spec table and the guide table carry
the row `4 | retired | used in the MVP for stdin ending inside an interactive adapter;
removed as unneeded; kept as a gap so other codes do not shift; may be filled by a future
tool failure`. *Rationale:* the guard becomes unreachable and would be an unkillable mutant
under the 100% gate (`testing.md`); nobody operates the factory yet, so the number is free
but shifting 5–7 and 10–12 buys nothing. *Alternative rejected:* rewording 4 as "EOF at an
operator prompt" — that case already has 10 and 11.

**D7 — Migration before deletion, in two task groups.** Group 1 rewrites every spec listed
in `tasks.md` §2 onto the fake agent while the interactive code still compiles, and runs
green. Group 2 deletes. *Rationale:* a spec that is rewritten and deleted-against in one
step cannot show which of the two broke it; with the mode still present, a migrated spec
that goes red is a migration bug by construction. *Alternative rejected:* delete first and
fix what breaks — 28 files red at once, no bisection.

**D8 — Sync surfaces.** The change touches no declared pair: the registry rows for
`GitAttemptPersistence`/`EnvironmentAttemptPersistence`, `DecisionFileTransport`/
`BranchDecisionFile`, `RoundTimeout`/`AgentSettingsValidator`, the ledger and snapshot
mappers and the two `FeedState`/`HeartbeatState` decouplings are all outside the run-chain
argument surface, and the `grep -rn "Kept in sync with" */src/main` hits are in the
resume/take/git chains this change does not edit. It adds no parallel implementation: the
CLI adapters were already the second implementation of each port and the interactive ones
are the ones leaving. The three "Press Enter to continue" copies are an undeclared triplet
that `make-run-headless` dissolves by deleting all three; this change leaves them untouched
and names them there.
`Sync surfaces: none — this change adds no parallel implementation and touches no declared pair.`

**D9 — Single-owner mechanisms.** Two mechanisms become single-owner by this change.

| Owner                                                                                                                                                 | Value (type)                                                           | Consumers                                                                                                                                                                                                                                             | Old way removed                                                                                                                                                                   | Enforced by                                                                                                                                                                                                                                      |
|-------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ExecutorAdapterSelector` (kept, D2) — the manifest-driven binding of `StageExecutor` and `JudgeVoter`                                                | `StageExecutor`, `JudgeVoter` placed into `EnginePorts`                | `RunAssembler.assemble` (`:bootstrap`) — the single `EnginePorts` construction site for `run`, `take` (`TakeEngineExecution`, `TakeContainerEngineExecution` via `ManualRunDrive`/`TakeClaimAndWork`) and `serve` (`TakeSlotRunner` → same run chain) | `InteractiveStageExecutor`, `InteractiveJudgeVoter` and the `InteractiveMode` switch — deleted, no exemption                                                                      | the parameter type: no `InteractiveMode` exists to branch on; `adapter.console` package absent from the build, so no console-backed port can be constructed                                                                                      |
| `UnconfiguredCheckProviderRule` in `PipelineValidator` — the only place an `external` check's provider is graded against the factory's configured set | `ConfigError` list, surfaced as `PipelineLoadFailedException` (exit 3) | `PipelineLoader.loadConfiguration` (`:adapters`), reached by `PipelineStartup` (`run`), `TrustedTierStartup` / `ResumeLawBinding` (`take`, `serve`); `dashboard` is not a consumer (task 3.2 confirms or exempts it)                                  | `CheckEquipment.externalCheckClient`'s `configured.isEmpty()` branch and `InteractiveExternalCheckClient` — deleted; the wiring constructs the dispatching client unconditionally | `CheckEquipment` no longer takes a `DialogConsole`; an architecture spec in `:bootstrap` (`ExternalCheckWiringBoundarySpec`) asserts every `ExternalCheckClient` construction site in `src/main` is the dispatching composite or a guard over it |

The fake-agent fixture becomes the only scripted gnome in the test tree; that is a test
convention, so its enforcement is M2's grep in the task's report, not a build gate.

## Risks / Trade-offs

- [A migrated E2E journey needs a scenario sequence the fake cannot express — e.g. "attempt
  1 asks a decision, attempt 2 fails a command check, attempt 3 passes"] → the fixture's
  stateful command check already produces the fail-then-pass sequence with a plain round on
  every attempt; `decision-then-plain` covers the leading decision. Task 2.1 lists the
  sequence per spec before touching it.
- [Executing the fake through the jar on macOS pays Gatekeeper's first-exec scan per wrapper
  file] → one wrapper per scenario per JVM (`FakeAgentSupport` already does this); the
  harness writes its wrappers once per suite.
- [Deleting `DialogConsole.inputExhausted` leaves `DialogConsoleSpec` features that only
  tested the latch] → they are deleted with it; the remaining prompt/ask/meta-command
  features stay until `make-run-headless`.
- [Active changes `add-command-executor` D6 and `add-decision-arbiter` D2 plan on the
  interactive selection path] → listed in the proposal's Impact; `/opsx:update` on each after
  this change is applied, before either is applied.
- [`StatusReportReferenceFixture` may be hand-built rather than session-produced, so FR7's
  "regenerate" may be a no-op] → task 2.6 checks the producer first and records the answer.

## Migration Plan

1. Group 1 (tasks §1–§2): fake-agent role selection, fixture edits, spec migration; root
   `check` green with the interactive code still present.
2. Group 2 (tasks §3–§5): FR3 rule, deletions, docs; root `check` green; M1/M2 greps in the
   task report.
3. No deployment step: the mode was never used outside the test tree.
