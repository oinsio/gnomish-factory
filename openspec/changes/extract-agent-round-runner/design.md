# Design: extract-agent-round-runner

## Context

See proposal.md — Why. Constraints:

- `adapters/agent` (44 files, 4,330 lines): Claude dialect (`AgentCommandLine`,
  `AgentInvocationOptions`, `AgentAiSeam`, `StreamJson*`, `AgentEvent`, `ContentBlock`,
  `TokenUsageMapper`, `ToolTraceBuilder`, `AgentRoundResultExtractor`, `JudgeVerdictExtractor`,
  `ModelIdSyntax`) ~1,900 lines; generic mechanics (`ExecutorRoundExecution`,
  `JudgeRoundExecution`, `StreamDrain`, `RoundTimeout`, environment sources,
  `FreshJudgeEnvironments`, decision transport, denial read, prompt builders) ~1,300 lines.
- Declared pairs touching this module: `ExecutorRoundExecution` ↔ `JudgeRoundExecution`;
  `HostRoundEnvironmentSource` ↔ `SandboxRoundEnvironmentSource` (registry, no classpath);
  `DecisionFileTransport` ↔ `BranchDecisionFile` (registry); `RoundTimeout` ↔
  `AgentSettingsValidator` (registry).
- Leaks outside the module: `AgentSettingsValidator` (adapters/pipeline) validates Claude keys
  for every stage; `AgentProgressEvent` (application) carries `subtype` and `sessionId`;
  `ModelIdSyntax` admits Claude brackets; `JUDGE_READ_ONLY_TOOLS` is the policy language.
- Exit code and stderr of the CLI are never read; `StreamJsonParser` has no cap.
- Available from earlier changes: `Executor`, `ExecutorResult`, `ResultFileReader`,
  `ExecutorRegistry`, describe-validated settings, abstract `Policy`, `VerdictDerivation`,
  `ExecutorOutcomeClassifier`, three bounds, continuation field on `start`.
- `remove-interactive-console` (sequenced before) deletes the interactive stage executor and
  judge voter; `add-decision-arbiter` rebases onto role `decision`.

## Goals / Non-Goals

**Goals:** one runner, one dialect seam (FR1, FR2); Claude as the first dialect with no behavior
loss (FR3); judge on the contract (FR4); every pair in the module dissolved (G3).

**Non-Goals:** out-of-process Claude (NG1), ACP (NG2), repo-policy isolation enforcement
(NG4), long-round resume token (NG5).

## Decisions

**D1 — Runner + dialect, in process, as the first step.** (FR1, FR2, G1.) `AgentRoundRunner`
implements `Executor` for a given `AgentDialect`; the Claude dialect is a package in
`adapters/agent/claude`. *Rationale:* the research-confirmed lesson that dialects are mostly
data; keeping the step in process proves the seam with the existing 51 specs before anything
crosses a process boundary (`externalize-claude-executor` does that next). *Alternative
rejected:* externalize at once — two risks in one change and no reference for the kit yet.

**D2 — Judge is a `verdict` request; votes are N starts.** (FR4.) `JudgeVoting` keeps the
majority reducer and odd-votes rule; each vote is `executor.start(verdictRequest)` in the
fresh environment the verification-hardening spec already requires; verdicts derive through
`VerdictDerivation`; `JudgeVoter` leaves `EnginePorts`. *Alternative rejected:* a built-in
`llm-api` judge now — a second LLM mechanism before the dialect seam is proven (proposal Q3 of
the contract change stays open for `externalize-claude-executor`).

**D3 — Answer file replaces the decision file; path owned by `EnvelopePaths`.** (FR9.) The
decision request is a `decision-request` answer written to `GNOMISH_ANSWER_FILE` under
`.gnomish-task/`; `BranchDecisionFile` reads the same path from the tip through
`ResultFileReader`. The `writeExactly(path)` policy carries that path to the dialect.
*Rationale:* one channel for every structured answer (verdict, select, decision request), one
reader, and the transport pair dissolves. *Alternative rejected:* keep the decision file as a
second channel — the third copy of a path constant the envelope change already removed once.

**D4 — Session id is the continuation key.** (FR9, NFR-R2.) The Claude `init` session id travels
in the result as the continuation key; on a `start` with continuation the dialect renders
`--resume <id>`; a refused resume falls back to a fresh start with the answer in feedback (WARN).
*Alternative rejected:* always fresh start — loses the gnome's context at the exact moment it
asked a question.

**D5 — Policy compiled by the dialect, verified by the runner.** (FR5, NFR-S2.) `readOnly` →
`dontAsk` + read-only tool set (settings may narrow), `workspaceWrite` → `acceptEdits`,
`writeExactly` → the answer path allowance; the runner runs the working-copy-unchanged check
after `verdict`/`decision`. `AgentRole` and `JUDGE_READ_ONLY_TOOLS` are deleted. *Alternative
rejected:* keep vendor tool names in the request — the leak this change exists to close.

**D6 — Settings schema from describe; bounds replace `roundTimeout`'s special status.** (FR7.)
The Claude dialect's schema accepts the four legacy keys plus the three bounds;
`AgentSettingsValidator`'s Claude list and `RoundTimeout`'s shape parsing move into the schema;
the `RoundTimeout` ↔ `AgentSettingsValidator` pair dissolves (shared abstraction: the schema).

**D7 — Sync surfaces.** Scout: `grep -rn "Kept in sync with"` and the registry.
- `ExecutorRoundExecution` ↔ `JudgeRoundExecution`: **dissolved** into `AgentRoundRunner`
  (shared abstraction; role is a request field).
- `HostRoundEnvironmentSource` ↔ `SandboxRoundEnvironmentSource`: **dissolved** — the runner
  takes a `RoundEnvironment` from the one environment acquisition the verify path already
  uses per `verifyIn`; registry row removed.
- `DecisionFileTransport` ↔ `BranchDecisionFile`: **dissolved** (D3): one path owner, one
  reader; registry row removed.
- `RoundTimeout` ↔ `AgentSettingsValidator`: **dissolved** (D6); registry row removed.
- `GitAttemptPersistence` ↔ `EnvironmentAttemptPersistence`: untouched (round close is
  unchanged); `MidRoundHarvestListener` ↔ `MidRoundPushListener`: untouched, they subscribe to
  the vendor-neutral progress events (FR6) — one mirrored edit each to drop the session id,
  listed as a task.
- *No new parallel implementation:* the fake dialect in `test-fixtures` is a test double, not a
  second production dialect.

**D8 — Single-owner mechanisms.**

| Owner | Value (type) | Consumers | Old way removed | Enforced by |
|---|---|---|---|---|
| `AgentRoundRunner` (adapters/agent/runner) | `ExecutorResult` | `ExecutorRegistry` entry `claude` (stage dispatch), `JudgeVoting` via registry, arbiter via registry | `CliStageExecutor`, `CliJudgeVoter`, `ExecutorRoundExecution`, `JudgeRoundExecution`, `ResumeVerificationStageExecutor` (its check moves to the engine's resume path) deleted | `JudgeVoter` type deleted (compile); `EnginePorts` holds one executor port (spec) |
| `AgentDialect` (interface) | `RenderedArgv`, `ParsedEvents`, `Answer`, `ContinuationKey` | `AgentRoundRunner` only | `AgentCommandLine`/`AgentInvocationOptions` called from round classes; `AgentAiSeam` static names | `ArchUnit`: no class outside `adapters/agent/claude` imports a Claude type; grep `stream-json\|ANTHROPIC_\|acceptEdits` → dialect package only (M2) |
| `EnvelopePaths.answerFile` (domain) | `EnvelopePath` | `AgentRoundRunner` (hands the path), `BranchDecisionFile` (reads it), salvage (`FactoryOwnedPaths`) | `DecisionFileTransport` deleted; `GNOMISH_DECISION_FILE` literal removed | existing envelope-paths build gate extended with the new constant |
| Policy compilation (`ClaudePolicyCompiler`) | argv fragment from `Policy` | `ClaudeDialect` | `AgentRole`, `JUDGE_READ_ONLY_TOOLS`, per-role branches in `AgentInvocationOptions` | `Policy` sealed type in the request; spec: no argv carries `bypassPermissions` |
| Credential names (`ClaudeDialect.describe`) | `ExecutorCredentialNames` | `ChildEnvAllowlist` layer via the contract's delivery | `AgentAiSeam` deleted; `FactoryProperties.agentCliEnvPassthrough` dead knob deleted | identity spec `ProgramCredentialDeliverySpec` extended for `claude` |

**D9 — Crash consistency.** No new durable step: the answer file rides the snapshot/salvage
commit exactly as the decision file did; the continuation key is a field of the recorded
`decision-request` in the attempt record (one commit). Kill-point specs of the decision path
are re-pointed at the answer file.

**D10 — Lock scope.** No lock added; `FreshJudgeEnvironments` keeps its resource-serializing
lock (the exception, documented) and becomes the `verifyIn: fresh-box` acquisition for votes.

## Risks / Trade-offs

- [Behavior drift while splitting 44 files] → the 51 existing specs and the reference stream-json
  fixtures are kept green through the refactor; the fake dialect proves the seam (M4).
- [`--json-schema` output differs from today's parsed final text] → the Claude dialect falls
  back to final-text extraction when the CLI version lacks the flag, logged at WARN.
- [Dropping `sessionId` from progress breaks dashboards] → only `MidRound*Listener` and
  `AgentActivityEnricher` consume it; each is adapted in its task.
- [`add-decision-arbiter` written against `DecisionNeeded`] → its rebase is a named task in that
  change; this design fixes the shape it targets (role `decision`, answer `select`).

## Migration Plan

1. After `migrate-verify-to-executor-contract` and `remove-interactive-console`.
2. Introduce runner + Claude dialect behind the existing registry entry; switch judge; delete
   the old classes; remove four registry rows.
3. Follow-ups: `externalize-claude-executor` (reference external executor, Codex dialect),
   `add-acp-executor`, `harden-verification-integrity` (uses the describe fields added here).
