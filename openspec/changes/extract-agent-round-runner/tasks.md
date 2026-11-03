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
- [ ] 4.3 Judge exemptions and the judge participant (carried from D10 of
  `define-executor-contract` and task 2.4 of `migrate-verify-to-executor-contract`):
  `OutcomeClassifierOwnerSpec` (`:bootstrap`) allowlists the judge's `new Verdict.CannotVerify(`
  sites (`JudgeVerdictExtractor`, `JudgeCriteriaPreflight`, `JudgeCannotVerify`) under this
  change's pickup; each row leaves with its class's last such construction (2.4, 4.1, D2: votes
  derive through `VerdictDerivation`), and the pickup leaves the spec's `PICKUPS` once no row
  names it. `Participants.JUDGE_EXECUTOR` (`domain/engine`) names every judge vote's participant
  by the `agent-cli` type word; once 4.2 lets a judge check name an executor, the vote's
  participant is the executor the check resolves to, and the constant goes with its javadoc
  (which names this change)
  (FR4)

## 5. Deletions and pair dissolution

- [ ] 5.1 Delete `CliStageExecutor`, `ExecutorRoundExecution`, `ResumeVerificationStageExecutor`
  (resume check moves to the engine resume path), `HostRoundEnvironmentSource`,
  `SandboxRoundEnvironmentSource`, `HostJudgeEnvironmentSource`; runner takes the environment
  from the existing `verifyIn` acquisition; registry entry `claude` wired in
  `ExecutorAdapterSelector` (D7, D8)
- [ ] 5.1a Dispatch arm (carried from D8 and the `ExecutorRegistry` row of D10 of
  `define-executor-contract`): `ExecutorDispatch` (`:bootstrap`) sends a `program` stage to the
  registry by `executor.name` and every other stage to one type-keyed `agent-cli` arm, kept "until
  `extract-agent-round-runner` makes the agent a named executor"; with the `claude` entry of 5.1
  the arm is deleted and an agent stage resolves through `ExecutorRegistry` like any other
  (D8)
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

## 6. Transform `pending` through the one pending-poll owner

Human decision 2026-11-02; design D11. Consumers of the record (D8 row `PendingPoll`):
`StageAttemptLoop` (live and resume), `GitAttemptPersistence` and `EnvironmentAttemptPersistence`
(write the pending round commit), `StateJsonMapper`/`StateAttemptDto` (read and write it), the
shape classifier (reads it as an `InProgress` sub-state). Old way: the `transform` arm "a
`pending` the stage cannot poll" in `RoundFailures`, `AttemptAccounting`, `RoundOutcome`.

- [ ] 6.1 Premise check (`spec-mismatch.md`, item 1): confirm with `file:line` that
  `migrate-verify-to-executor-contract` landed `PendingPoll`, `PendingPollRecord` in the attempt
  state, and task 4.3a's resumable `program` poll (no per-instance `PendingRuns` membership on
  the poll a persisted record drives, superseded and ended keys included), its key rotation
  (FR10 there, "A changed pending key is persisted before the next poll"), and its wire
  obligation with the kit's `pending-repoll` case (FR11, FR12 there). If any is missing, stop and
  report under that rule before 6.2 (FR10, FR11, FR12)
- [ ] 6.2 Record subject: `PendingPollRecord` gains the sealed subject `Check(checkId)` |
  `Round(roundToken)`; state DTO and mapper carry it; wire round-trip spec iterating every subject
  and pinning the unknown-token arm; a record whose round token does not name the round the
  registered commit holds open is refused, never polled (FR10, NFR-R3, D11)
- [ ] 6.3 Engine path, TDD on virtual time with a fake `transform` executor declaring `poll`:
  narrow the refusal in `RoundFailures`/`AttemptAccounting`/`RoundOutcome` to executors without
  `poll`, re-point `ExecutorOutcomeTableSpec`'s marker feature to the reworded scenario and add the
  pollable row; `StageAttemptLoop` hands `pending` to `PendingPoll`; pending interval and ceiling
  as factory-owned bounds beside `ProgramBounds`; ceiling counted from `since`; no verify and no
  advancement on a pending round commit; closing commit drops the record; one feature per
  scenario of the `pipeline/stage-engine` and `executor/wire-contract` deltas, markers included
  (FR10, FR11, NFR-O2, UX3)
- [ ] 6.4 Pending round commit at both ends of the persistence pair: `GitAttemptPersistence` and
  `EnvironmentAttemptPersistence` write it through their existing sequence (gnome range inspected,
  owned paths restored, commit + register in one ref transaction, one atomic push), with no new
  step; the registry row of the pair names the pending round commit; specs per medium (FR10, D7)
- [ ] 6.5 One path, live and resumed: after the pending commit the live instance reads the record
  back through the resume's parse and both call `PendingPoll.resume(record)`; the resume
  materializes the environment at the registered commit; identity spec `PendingRoundIdentitySpec`
  on a real bare origin — the round token, the usage participants and the ledger lines of a round
  closed by a second instance equal those of the round that went pending (FR11, NFR-R4, D11)
- [ ] 6.6 Key rotation for a round (FR12, reuse): `migrate-verify-to-executor-contract` owns the
  rule (its FR10, D12; proposal Q3, closed 2026-11-02); this task adds only the `Round` subject's
  case — the new key lands in a pending round commit through the persistence pair of 6.4, the
  attempt stays open — with a feature for the `pipeline/stage-engine` scenario "A changed key of a
  pending round lands in a pending round commit", marker included; no second rotation path
- [ ] 6.7 Kill-point specs on a real bare origin for windows K1–K4 and after the round close
  (design D11), each resumed by a second instance, each followed by a second recovery pass that
  changes nothing; K1 asserts the re-driven `start` is in the ledger; K3 and K4 assert no `start`
  (the fixture executor conforms to "A pending key stays pollable until its run ends"), K4 also
  the scenario "Kill after the terminal poll closes the round without a start", marker included
  (NFR-R3, NFR-C2)
- [ ] 6.8 Owner gate and sweep: `ExecutorPollOwnerSpec` (`:bootstrap`) — `.poll(` on an `Executor`
  in `domain`/`application` main sources only in `PendingPoll`, the allowlist asserted reached;
  sweep `grep -rn "cannot poll\|PendingRuns\|\.poll(" --include='*.java' */src/main
  adapters/*/src/main` and report each hit as routed, removed or exempt with its reason (D8)
- [ ] 6.9 Glossary: "pending key" entry (from `migrate-verify-to-executor-contract`) extended to
  the `transform` round; new entry "pending round commit"

## 7. Integration

- [ ] 7.1 Packaged-jar end-to-end of M5: a `program` transform executor declaring `poll` against a
  fixture service, killed after the pending commit and resumed by a second instance (one
  `start`, the round closes with the service's changes, second recovery a no-op), and killed
  before it (two `start`s, both in the ledger) (M5, G5)
- [ ] 7.2 Packaged-jar end-to-end with the fake agent binary through the Claude dialect: transform
  round, judge vote, decision request → answer → continued session; ledger asserts
  `executor=claude@<version>` (NFR-O1)
- [ ] 7.3 `./gradlew check` with PIT; sweep report for D8 rows; recommend a commit message
