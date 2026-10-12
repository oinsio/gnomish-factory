# Tasks: extract-agent-round-runner

## 1. Dialect seam and runner

- [ ] 1.1 Define `AgentDialect` (describe fields, `render(request) → argv`, `parse(line)`,
  `answer(events, answerFile)`, `status(exit, events)`, `continuation(events)`) and the
  vendor-neutral progress events (model, tool name, summary, usage); adapt
  `MidRoundHarvestListener`, `MidRoundPushListener`, `AgentActivityEnricher` to drop the
  session id; specs green (FR2, FR6, D7)
- [ ] 1.2 Implement `AgentRoundRunner implements Executor`: launch via `RoundEnvironment`,
  concurrent capped drains (byte + line caps on stdout), three bounds on virtual time,
  `ResultFileReader` for result and answer, denials, post-hoc working-copy check for
  `verdict`/`decision`; specs with the fake dialect for all three roles, exit-without-result,
  turn-limit → `failed/limit`, oversized stdout, silence bound (FR1, FR8, NFR-R1, NFR-S2)
- [ ] 1.3 Add the fake dialect to `test-fixtures` and run the conformance kit against the runner
  with it (M4)

## 2. Claude dialect

- [ ] 2.1 Move `AgentCommandLine`, `AgentInvocationOptions`, `StreamJson*`, `AgentEvent`,
  `ContentBlock`, `TokenUsageMapper`, `ToolTraceBuilder`, `AgentRoundResultExtractor`,
  `ModelIdSyntax` into `adapters/agent/claude` behind `ClaudeDialect`; reference stream-json
  fixtures replay identically (FR3)
- [ ] 2.2 `ClaudePolicyCompiler`: `Policy` → permission mode, tool sets, `--strict-mcp-config`,
  answer-path allowance; delete `AgentRole`, `JUDGE_READ_ONLY_TOOLS`; specs: no argv carries
  `bypassPermissions`, settings cannot widen read-only (FR5, D5)
- [ ] 2.3 Describe: roles, capabilities, `TOOL_GATE`, credential names, egress host from base
  URL, `CLAUDE_CONFIG_DIR`, repo-policy-disabling flags, settings schema (four legacy keys +
  three bounds); delete `AgentAiSeam`, the Claude list in `AgentSettingsValidator`,
  `RoundTimeout` shape parsing, `FactoryProperties.agentCliEnvPassthrough`; located-error spec
  for a settings typo (FR7, NFR-S1, D6)
- F107 (carried from `tighten-spec-contract`): since that change, the agent CLI's credentials and
  every child-environment value reach a round only through `SecretsProvider`: the run's
  `ChildEnvAllowlist` resolves them through the `ChildEnvValues` role interface the root binds at
  `CheckEquipment` (`childEnv` for the run path, `installationChildEnv` for the container path),
  `AgentAiSeam.variables(childEnv)` reads the five seam names through `ChildEnvAllowlist.resolve`,
  and `EnvFileSecretsProvider` is the only production reader of the process environment
  (`SecretsPortBoundarySpec`). Deleting `AgentAiSeam` in 2.3 keeps that route: the names
  `ClaudeDialect.describe` declares are resolved through the run's allowlist, never through
  `System.getenv` and never through a new `ChildEnvAllowlist.of` overload without a value source;
  `AgentCredentialSeamBoundarySpec` moves its one allowed file from `AgentAiSeam` to the dialect,
  and `AgentSecretsFolderRoundSpec` (a host-folder token reaching every round and vote) stays
  green. Note that the allowlist's declared credential names are a withheld set — `compose` and
  `resolve` strip them — so the dialect's credential names must not be added through `declaring`.
- [ ] 2.4 `--json-schema` for `verdict` and `decision` answers with final-text fallback at WARN;
  `JudgeVerdictExtractor` reduced to SARIF conversion; malformed → malformed answer (FR3, FR4)
- [ ] 2.5 Continuation: session id as key in the result; `--resume` on `start` with continuation;
  refused resume → fresh start with WARN; ledger token `continued session=<prefix>` (FR9,
  NFR-R2, D4)
- [ ] 2.6 Old-way sweep: `grep -rn "stream-json\|ANTHROPIC_\|acceptEdits\|dontAsk"
  --include=*.java */src/main adapters/*/src/main` → hits only under `adapters/agent/claude`;
  ArchUnit rule pinning it (M2, D8)

## 3. Answer file replaces the decision file

- [ ] 3.1 Add `EnvelopePaths.answerFile`; runner hands `GNOMISH_ANSWER_FILE`; `BranchDecisionFile`
  reads it through `ResultFileReader`; delete `DecisionFileTransport`; remove the
  `GNOMISH_DECISION_FILE` literal everywhere (grep → zero); envelope-paths gate extended;
  kill-point specs of the decision path re-pointed (FR9, D3, D9)
- [ ] 3.2 Remove the `DecisionFileTransport` ↔ `BranchDecisionFile` registry row and markers; at
  spec sync, also retire the `agent-executor` requirement "Decision file is read through the
  shared result reader" added by `define-executor-contract` (superseded by "Decision request is
  the answer file"; it is a delta today, so this change cannot list it under REMOVED)

## 4. Judge on the contract

- [ ] 4.1 `JudgeVoting` issues N `start`s to the registry-resolved executor in the fresh
  environment, derives each vote through `VerdictDerivation`, keeps the majority rule and
  odd-votes check; malformed vote → cannot-verify; `JudgeVoter` removed from `EnginePorts` and
  the ports package; `CliJudgeVoter`, `JudgeRoundExecution`, `JudgeCriteriaPreflight` moved
  into the runner's `verdict` preflight; specs (FR4, M3, D2)
- [ ] 4.2 `judge` manifest check names an executor (default: the stage's); loader validates the
  role; `JudgePromptBuilder` renders the criteria as the `verdict` role input (FR4)

## 5. Deletions and pair dissolution

- [ ] 5.1 Delete `CliStageExecutor`, `ExecutorRoundExecution`, `ResumeVerificationStageExecutor`
  (resume check moves to the engine resume path), `HostRoundEnvironmentSource`,
  `SandboxRoundEnvironmentSource`, `HostJudgeEnvironmentSource`; runner takes the environment
  from the existing `verifyIn` acquisition; registry entry `claude` wired in
  `ExecutorAdapterSelector` (D7, D8)
- [ ] 5.2 Remove the `ExecutorRoundExecution` ↔ `JudgeRoundExecution`, `HostRoundEnvironmentSource`
  ↔ `SandboxRoundEnvironmentSource`, `RoundTimeout` ↔ `AgentSettingsValidator` registry rows;
  `grep -rn "Kept in sync with" adapters/agent` → zero (M1)
- [ ] 5.3 Glossary: agent dialect, agent round runner, continuation key (update), answer file;
  update the `Executor` and `Judge` entries
- Spec contract (`tighten-spec-contract`): this change REMOVES six `agent-executor`
  requirements and the `stage-engine` requirement "Judge majority voting"; their scenarios carry
  `// Scenario: executor/agent-executor / <title>` and `// Scenario: pipeline/stage-engine / <title>` markers
  (41 of the 48 scenarios, today) and eight lines in
  `config/spec-contract-exemptions/executor-agent-executor.txt`. In this same change, every marker of a
  removed scenario goes with the test it sits on (or is retargeted to a scenario this change
  adds), and each exemption line naming a removed scenario is deleted, so `checkSpecContract`
  reports no marker or exemption naming a scenario that no longer exists.

## 6. Integration

- [ ] 6.1 Packaged-jar end-to-end with the fake agent binary through the Claude dialect: transform
  round, judge vote, decision request → answer → continued session; ledger asserts
  `executor=claude@<version>` (NFR-O1)
- [ ] 6.2 `./gradlew check` with PIT; sweep report for D8 rows; recommend a commit message
