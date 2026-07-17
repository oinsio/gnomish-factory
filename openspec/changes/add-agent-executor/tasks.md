# Tasks: add-agent-executor

TDD throughout (`.claude/rules/testing.md`): each task pairs a failing Spock spec with the code that passes it; spec descriptions carry FR references. FR/NFR/UX/M are proposal.md ids; D-references are design.md. Depends on add-manual-run being implemented and archived (ports, contract suites, briefing, runner, status v1). The fake agent binary (section 2) lands early — most later sections test against it.

## 1. Domain telemetry deltas (`domain.engine`)

- [ ] 1.1 `TokenUsage` → four counts (`input`, `output`, `cacheCreation`, `cacheRead`), non-negative validation; all uses updated — FR5, D4
- [ ] 1.2 `ExecutorUsage.tokens` → `tokensByModel: Map<String, TokenUsage>` (map-only, empty = unreported); `plus()` merge = key union + per-field sums; display-total helper for renders — FR5, NFR-C1, D4
- [ ] 1.3 `JudgeVoter.Vote.tokens` → `tokensByModel` (empty map = unreported, nullability gone); `JudgeUsage.perVote` follows; engine aggregation and specs updated — FR9, NFR-C1, D4
- [ ] 1.4 Contract-suite forms updated: interactive adapters report empty maps; `PASS_WITHOUT_TOKENS`-style variants become empty-map variants; engine telemetry specs cover per-model merge and unknown ≠ zero — FR5, FR9, M2
- [ ] 1.5 Money-tail cleanup: javadoc money mentions dropped across `src/main` (`AutonomyLimits` NG8 note, `TaskState` "token/money budgets", DTOs) and test sources (`AutonomyLimitsSpec`), grep-verified — FR16

## 2. Fake agent binary (test infrastructure)

- [ ] 2.1 Fake agent script + Spock harness: reads args, emits scripted stream-json, writes workspace files, exits with a chosen code; wired through the configurable CLI binary path (the `FactoryProperties` seam of 4.1) — FR15, D11
- [ ] 2.2 Scenario library for the fake: plain round, decision-file write, subagent events, judge verdict message, garbage output, premature death — FR15, M2, D11

## 3. Stream-json parser (`adapter.agent`)

- [ ] 3.1 Event model + tolerant Jackson parse loop: known events (init, assistant, tool result, result), unknown types/fields silently ignored — FR4, D3
- [ ] 3.2 Result-event handling: essential — missing/unparseable → infrastructure failure; telemetry best-effort — parse trouble → `ExecutorUsage.none()` + empty trace, round stands — FR4, NFR-R1, NFR-R2, D3
- [ ] 3.3 Token mapping: `modelUsage` → `tokensByModel` with resolved model ids; fallback to the init event's main model when absent — FR5, D4
- [ ] 3.4 Tool trace: top-level filter by parent id (absent field → record all), read-time timestamps, duration to matching tool_result, orphan → process exit, `tools` aggregate derived from trace — FR6, NFR-O3, D3
- [ ] 3.5 Progress SPI: sealed events (`RoundStarted`/`ToolStarted`/`RoundFinished` with final-message summary), synchronous listeners, exceptions swallowed — FR7, D9, D10
- [ ] 3.6 Reference-dump fixtures: parser specs run on committed `*.reference.json` dumps (plain, subagents, judge verdict, with/without `modelUsage`); placeholders recorded via the paid smoke task (11.3) — M3, D11, Q1

## 4. Installation config and process runner (`adapter.agent`)

- [ ] 4.1 `FactoryProperties` (`@ConfigurationProperties`, new — first installation-level config class): CLI binary path (default `claude` from PATH), env passthrough to the CLI process; bound and registered in the run assembly — FR11, D7
- [ ] 4.2 CLI process launcher: ProcessBuilder, workspace cwd, transport flags (`-p`, `--output-format stream-json --verbose`), binary path + env from `FactoryProperties` — FR1, FR12, D7
- [ ] 4.3 Invocation options: `--model` from first-class manifest data (stage executor / judge check), settings rendered to CLI flags (`allowedTools`, `disallowedTools`, `maxTurns`) — FR11, FR12, D7
- [ ] 4.4 Wall-time measurement start → exit, independent of stream parsing — FR6, D3
- [ ] 4.5 `roundTimeout`: expiry kills the process, round classified infrastructure failure — FR13, NFR-R1, D7

## 5. Shared briefing (`adapter.briefing`)

- [ ] 5.1 Extract section renderer from `adapter.console` into a shared package with an explicit public API; sections take pre-read data; console render byte-identical (regression spec) — FR14, D8
- [ ] 5.2 Control-file reading moves to adapters: interactive keeps the placeholder; CLI executor preflight — unreadable control file → infrastructure failure before spawn — FR13, D8

## 6. CLI stage executor (`adapter.agent`)

- [ ] 6.1 Round prompt composition: briefing sections + verify plan (incl. judge criteria content, read in the same preflight — unreadable criteria file → infrastructure failure before spawn, mirroring 5.2) + decision-file instruction; rework preamble on attempt > 0 — FR2, FR13, D8, D9
- [ ] 6.2 Decision-protocol transport: per-round temp dir outside the workspace, `$GNOMISH_DECISION_FILE` env, present → `DecisionNeeded` / absent → `Completed`, dir lifecycle owned by the adapter (create → run → read → delete) — FR3, NFR-R3, NFR-S2, D1
- [ ] 6.3 Tolerant decision-file read: garbage → raw text becomes the question (empty options), empty file → fallback text, raw content logged at WARN — FR3, NFR-O2, D1
- [ ] 6.4 Pinpoint Write allowance for the generated decision-file path (hard-wired policy, closes the permission risk) — FR12, NFR-S2, D7
- [ ] 6.5 `execute()` assembly: launcher + parser + decision mapping → `ExecutionResult` with usage and trace on both `Completed` and `DecisionNeeded`; passes `StageExecutorContract` on the fake agent (decision present/absent/garbage, killed process) — FR1, FR3, FR15, M2

## 7. CLI judge voter (`adapter.agent`)

- [ ] 7.1 Judge prompt: criteria file + goal + decisions + structured-verdict instruction; no prior-attempt feedback — FR8, D5, D8
- [ ] 7.2 Hard-wired read-only tool policy; check's `allowedTools` may only narrow — FR12, NFR-S1, D7
- [ ] 7.3 Criteria-file preflight: unreadable acceptance-criteria file → `CannotVerify` before any process starts, never a criteria-less vote — FR13, NFR-R1, D8
- [ ] 7.4 Verdict extraction from the final message (strip fences, first JSON object); no verdict → `CannotVerify`, never pass; raw message at WARN — FR8, NFR-R1, NFR-O2, D5
- [ ] 7.5 `vote()` assembly with per-model vote tokens; passes `JudgeVoterContract` on the fake agent (clean/fenced/garbage verdict, killed process) — FR9, FR15, M2

## 8. Live progress subscribers

- [ ] 8.1 SLF4J renderer: round start (model, session id), each top-level tool, round finish with summary — structured lines under attempt MDC; raw events DEBUG; judge rounds feed the same renderer — FR7, NFR-O1, UX1, D10
- [ ] 8.2 Status enricher: `Executing` activity gains nullable `currentTool` + `toolCalls`, fed from progress events into the snapshot holder, cleared when the round finishes; executor rounds only — votes run under `verifying` — FR7, UX1, D10, D12

## 9. Settings and wiring (`app`)

- [ ] 9.1 Settings schema validation at startup: exactly `allowedTools`/`disallowedTools`/`maxTurns`/`roundTimeout` per `agent-cli` executor and judge check, with well-formed values; unknown key or malformed value → startup error naming stage/check and key, before any dialog; validator owned by the adapter package — the loader stays opaque (D5a of load-pipeline-config) — FR11, UX2, D7
- [ ] 9.2 `api` stage fail-fast in the startup validation chain: exit 3, names the stage, before any dialog — FR10, UX2, D6
- [ ] 9.3 `--interactive[=executor|judge]` parsing + wiring: flagless → CLI adapters from the manifest; scoped values swap one role; external check always interactive — FR10, D6
- [ ] 9.4 Progress-subscriber wiring: SLF4J renderer and status enricher registered on the CLI adapters in the run assembly — FR7, NFR-O1, UX1, D10
- [ ] 9.5 Agent-raised decision round-trip spec: decision file → escalation dialog → operator answer → decision in the next round's prompt — FR3, UX3, D1

## 10. Status contract amendment (`status`)

- [ ] 10.1 Usage DTOs → `tokensByModel` (four counts per model), `JudgeUsageDto.perVote` follows; usage text renders updated — FR5, FR9, D12
- [ ] 10.2 `Executing` activity DTO gains nullable `currentTool` + `toolCalls`; text render updated — FR7, D12
- [ ] 10.3 `status-report-v1.reference.json` regenerated; byte-identity, attempt-boundary equivalence, and optional-usage specs updated (empty maps valid) — FR5, NFR-C1, D12

## 11. E2E and verification

- [ ] 11.1 Ollama E2E harness: Gradle task + env wiring (`ANTHROPIC_BASE_URL`, auth token, default-model env) pointing the real `claude` CLI at local Ollama; skip with a clear message when Ollama is absent — M1, D11
- [ ] 11.2 Ollama E2E scenario: `agent-cli` fixture manifest + spec «agent creates a file → judge issues a verdict» through `gnomish run`, deliberately trivial — M1, D11
- [ ] 11.3 Paid smoke Gradle task outside `check`: fails fast without a logged-in `claude`; records/refreshes the reference dumps (resolved model ids, cache tokens; sensitive data scrubbed); run before archive — M4, D11, Q1
- [ ] 11.4 PIT 100% on new Java production code; justified exceptions only at process/IO boundaries — M5

## 12. Docs and rules cleanup

- [ ] 12.1 `.claude/rules/stage-description.md` §6: judge checks go through the `JudgeVoter` port, not the executor port — FR16
- [ ] 12.2 README: manifest-driven run and `--interactive[=executor|judge]` modes, manifest settings keys vs installation properties, Ollama E2E prerequisite — FR10, FR11, UX2
- [ ] 12.3 Traceability sweep: every FR/NFR/UX of this change has at least one implementing entity (grep per `.claude/rules/traceability.md`) — proposal verification rule
