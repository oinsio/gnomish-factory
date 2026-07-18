# Proposal: add-agent-executor

## Why

Every gnome so far has been a human: add-manual-run proved the QC cycle with interactive adapters, but the factory's core hypothesis — *an AI gnome can pass a verify-driven QC loop* — is still untested. This change adds the first real executor: Claude Code CLI driven through `ProcessBuilder` as a `StageExecutor` adapter, plus a real LLM judge (`JudgeVoter`) on the same CLI machinery, so the full autonomous loop — agent works, judge finds, findings feed back, agent fixes, judge accepts — runs under `gnomish run` with a human only in the escalation/external-check roles.

## What Changes

- **ADDED** `agent-executor`: CLI-based `StageExecutor` and `JudgeVoter` adapters — fresh-run rounds via `claude -p`, decision-file protocol for `DecisionNeeded`, stream-json telemetry (per-model tokens, tool trace, timings), live-progress listener SPI, shared stage-briefing renderer, settings schema with strict validation, manifest-driven wiring with `--interactive` override.
- **MODIFIED** `stage-engine`: telemetry model deltas — `TokenUsage` grows to four fields (input, output, cache creation, cache read); `ExecutorUsage.tokens` and `Vote.tokens` become per-model maps (`tokensByModel`, empty = unreported); money/budget wording tails removed from engine javadocs.
- **MODIFIED** `status-report`: the v1 contract is amended in place (pre-release) — usage DTOs carry `tokensByModel`, `Executing` activity gains live executor detail (current tool, call counter).
- **MODIFIED** `manual-run`: executor/judge wiring becomes manifest-driven by default; `--interactive[=executor|judge]` restores the human-in-role behavior; `api` stages fail fast at startup (exit 3).
- **MODIFIED** `pipeline-config`: money budgets removed from the autonomy-limits wording (token budgets remain a future concern).
- Process-rule fix: `.claude/rules/stage-description.md` §6 says "judge — LLM-as-judge via the executor port"; the code has a dedicated `JudgeVoter` port (D2 of add-stage-engine) — the rule is corrected.

## Capabilities

### New Capabilities
- `agent-executor`: real agent-cli execution of stages and judge votes through the Claude Code CLI — process lifecycle, wire protocols, telemetry, progress, settings, wiring.

### Modified Capabilities
- `stage-engine`: telemetry model deltas (`TokenUsage` 4 fields, `tokensByModel` maps on executor usage and judge votes)
- `status-report`: usage schema amendment + `Executing` activity detail (v1 amended in place)
- `manual-run`: manifest-driven adapter wiring, `--interactive` scoped override, `api` fail-fast
- `pipeline-config`: autonomy-limits wording — money budgets dropped

## Goals

- **G1** — a real agent completes a stage through the full QC loop under `gnomish run`: execute → verify → quality failure → feedback re-run → pass or escalate, with no human playing executor.
- **G2** — a real LLM judge closes the loop's most valuable link: judge findings feed back to the agent, the agent fixes, the judge accepts — fully autonomous between escalations.
- **G3** — real-run telemetry is captured and visible: per-model tokens, top-level tool trace with timings, live progress in logs and `status` output.
- **G4** — the CLI adapters pass the existing port-contract suites, and all protocol/parse behavior is covered deterministically without paid calls (fake agent binary + reference dumps).

## Non-Goals

- **NG1** — git/worktree workflow, persistence, cross-process resume (git-workflow change); state stays in-memory.
- **NG2** — tracker integration; escalation delivery channels stay as in add-manual-run (console dialog).
- **NG3** — `api` executor and the ai-provider port; `api` stages are rejected at startup.
- **NG4** — real `ExternalCheckClient`: external checks stay interactive regardless of flags.
- **NG5** — sandboxing of agent processes: supervised run in the operator's own repo — the same acceptance as command checks in add-manual-run.
- **NG6** — budget *enforcement* (token or otherwise); this change only preserves the raw per-model grain future budgets need. Monetary cost tracking is removed from the project entirely (`total_cost_usd` is fictitious under subscription auth and derivable from tokens+model).
- **NG7** — session resume between attempts (`--resume`): retries are fresh runs by design, not a deferred option — session files are machine-local and would make retry behavior depend on which instance executes attempt N+1.
- **NG8** — MCP-based decision protocol; revisit only if practice shows poor compliance with the decision-file protocol.

## Users & Scenarios

- **U1 — factory developer**: runs the first supervised AI-gnome sessions, watches the QC loop live, validates the hypothesis and the port shapes against a real agent.
- **U2 — pipeline author**: calibrates stage prompts and judge criteria with mixed runs — `--interactive=judge` (real agent, human judge — verdict calibration) and `--interactive=executor` (human executor, real judge — judge-prompt debugging without paying for agent rounds).
- **U3 — operator**: watches the live progress feed during long rounds, answers `DecisionNeeded` escalations raised by the agent through the decision file.

## Requirements

### Functional

- **FR1** — CLI `StageExecutor` adapter: one `execute()` call = one fresh `claude -p` subprocess in the stage workspace (`ProcessBuilder`, stream-json output); no session reuse between attempts; workspace continuity — not session memory — carries work across retries.
- **FR2** — round prompt is built from shared briefing sections (task goal, input artifacts, prior-attempt feedback, decisions, control-file content) plus the executor epilogue: the full verify plan including judge acceptance criteria, the decision-file instruction, and — on attempts > 0 — a rework preamble stating the working copy already contains the prior attempt's result.
- **FR3** — decision protocol: the adapter creates a per-round temp directory outside the workspace and passes a decision-file path via `$GNOMISH_DECISION_FILE`; after process exit, file present → `DecisionNeeded`, absent → `Completed`. Tolerant read: invalid JSON → entire file content becomes the question (empty options); empty file → fallback question text; raw content logged at WARN on any parse trouble. The adapter owns the temp dir lifecycle (create → run → read → delete).
- **FR4** — stream-json result event is essential: an unparseable/missing result event is an infrastructure failure of the round. Telemetry is best-effort: on telemetry parse trouble the round still completes with `ExecutorUsage.none()` and an empty trace; unknown event types and fields are ignored silently.
- **FR5** — token telemetry: `TokenUsage` carries `input`, `output`, `cacheCreation`, `cacheRead` (primitive longs); `ExecutorUsage` reports `tokensByModel: Map<String, TokenUsage>` keyed by resolved model id from the result event's `modelUsage` (map-only, no duplicate total; empty map = unreported; merge = union of keys with per-field sums). Fallback for CLIs without `modelUsage`: all usage under the main model from the init event.
- **FR6** — tool trace: top-level tool calls only (nested subagent calls filtered by parent id — no double counting); the adapter timestamps events at read time; duration = tool_use read → matching tool_result read; orphaned tool_use (process died first) → duration until process exit; `ExecutorUsage.tools` is derived from the trace; `wallTime` is measured by the adapter process start → exit.
- **FR7** — live progress: the adapter's parse loop emits a sealed progress event type (round started with model/session id, top-level tool started, round finished with the agent's final-message summary) through a listener SPI with `EngineEventListener` semantics (synchronous, return fast, exceptions swallowed). Two subscribers ship: an SLF4J renderer (progress lines under the attempt's MDC keys) and a status enricher adding current tool + call counter to `Executing` activity. Judge rounds feed the same log renderer; the status enricher applies to executor rounds only (a vote runs under the verifying activity).
- **FR8** — CLI `JudgeVoter` adapter: one `vote()` = one `claude -p` round in the workspace with the check's model; prompt = criteria file + task context (goal, decisions) + structured-verdict instruction — no prior-attempt feedback (a vote grades current state only). Verdict is extracted tolerantly from the final message (strip fences, first JSON object); no verdict obtainable (unparseable, process died) → `CannotVerify`, never a silent pass; raw final message logged at WARN on parse failure.
- **FR9** — judge votes report `tokensByModel` the same way as executor rounds (`Vote.tokensByModel`, empty map = unreported; the interactive judge reports an empty map); per-vote grain is preserved in `JudgeUsage`.
- **FR10** — wiring: the manifest is the source of truth — `agent-cli` stages get the CLI executor, judge checks get the CLI judge with the check's pinned model; `api` stages fail fast during startup validation (exit 3, before any dialog). `--interactive` restores add-manual-run behavior entirely; `--interactive=executor` / `--interactive=judge` swap just that role.
- **FR11** — settings: the manifest `settings` map accepts exactly `allowedTools`, `disallowedTools`, `maxTurns`, `roundTimeout` (stage-behavior settings that travel with the repo); unknown keys are a startup error (fail fast, before any dialog). Installation-level configuration (CLI binary path defaulting to `claude` on PATH, env passthrough to the CLI process) lives in application properties, never the manifest.
- **FR12** — hard-wired adapter policy (not settings): the judge runs strictly read-only (Read/Grep/Glob-class tools; a judge check's `allowedTools` may only narrow further); the executor round gets a pinpoint write-allowlist hole for the decision-file path the adapter itself generated; transport flags (`-p`, `--output-format stream-json --verbose`) are protocol internals.
- **FR13** — `roundTimeout` expiry and an unreadable control file are infrastructure failures (no attempt burned): timeout kills the process with no verdict; the control-file check happens before process start ("cannot execute" escalation), and the same executor preflight covers the judge acceptance-criteria files the verify plan embeds into the prompt. The judge side mirrors this preflight: an unreadable acceptance-criteria file yields `CannotVerify` before any process starts — never a silently criteria-less vote. The interactive adapters keep their placeholder behavior for unreadable files.
- **FR14** — the briefing section renderer is extracted into a shared component with an explicit public API (sections take pre-read data; file reading stays with each adapter); interactive-adapter rendering behavior is unchanged.
- **FR15** — both CLI adapters pass the existing `StageExecutorContract` / `JudgeVoterContract` suites, driven by a fake agent binary (configurable CLI path) emitting scripted stream-json.
- **FR16** — money-tail cleanup: pipeline-config spec autonomy-limits wording drops money budgets; `AutonomyLimits`/DTO/state javadocs drop money mentions; `stage-description.md` §6 points judge checks at the `JudgeVoter` port.

### Non-Functional — Reliability

- **NFR-R1** — failure-class discipline: a missed decision signal degrades to `Completed` and is caught by verify (costs one attempt); process crash, unparseable result event, round timeout, and unreadable control file are infrastructure failures (no attempt burned); judge degradation is inverted — no verdict is ever a pass.
- **NFR-R2** — telemetry can never fail a round: any telemetry parse failure yields `ExecutorUsage.none()` + empty trace, and the round outcome stands.
- **NFR-R3** — a stale decision file from a previous attempt is impossible by construction (per-round unique temp path).

### Non-Functional — Observability

- **NFR-O1** — a round is never a black box: round start (model, session id), every top-level tool call, and the round's final summary are logged live under the attempt's MDC keys; raw stream-json events at DEBUG.
- **NFR-O2** — every tolerant-parse degradation (decision file, judge verdict) logs the full raw content at WARN, correlated to the attempt, so protocol non-compliance is diagnosable from logs alone.
- **NFR-O3** — timing precision is telemetry-grade, not billing-grade, and documented as such: parallel tool calls may overlap, so summed tool durations may exceed wall time.

### Non-Functional — Security

- **NFR-S1** — the judge cannot mutate the workspace it grades: the read-only tool policy is enforced by the adapter, not by manifest configuration.
- **NFR-S2** — the decision temp directory lives outside the workspace: nothing from the decision protocol can leak into a future task branch.

### Non-Functional — Cost

- **NFR-C1** — the per-model token grain is preserved end to end (adapter → engine records → status report): resolved model ids, cache fields distinct from input, per-vote granularity — the raw material future token budgets need. No monetary fields anywhere.

## Operator Experience Criteria

- **UX1** — during a round the operator sees a live feed (round start, each tool call, final summary) in the console log stream, and `status` shows the current tool and call count instead of a bare `Executing(since)`.
- **UX2** — `gnomish run` without flags on an `agent-cli` manifest deliberately starts paid CLI rounds — that is the tool's purpose and the operator is present; no confirmation gate. Misconfiguration (api stage, unknown settings key) is rejected before any dialog with a message naming the offending stage/key.
- **UX3** — an agent-raised question reaches the operator as a normal escalation dialog: question and options rendered exactly like engine escalations, decision recorded and fed back on resume.

## Success Metrics

- **M1** — local E2E: the real `claude` CLI pointed at Ollama (native Anthropic API since v0.14) completes «agent passes a trivial stage → judge issues a verdict» through `gnomish run`; skipped with a clear message when Ollama is absent. Ollama is a native dev prerequisite, not Testcontainers (Docker on macOS gets no Metal).
- **M2** — both CLI adapters pass their port-contract suites against the fake agent binary, including decision-file present/absent/garbage, verdict clean/fenced/garbage, and killed-process cases.
- **M3** — the stream-json parser passes unit tests on committed reference dumps: plain round, subagent round, judge verdict, result with and without `modelUsage`.
- **M4** — a paid smoke Gradle task exists outside `check` (never in CI): fails fast without a logged-in `claude`; run manually on CLI version bumps, parser changes, unexplained 3a/1 divergence, and before archive — its dumps refresh the reference set.
- **M5** — PIT mutation score 100% on new Java production code, justified exceptions only at process/IO boundaries.

## Open Questions

- **Q1** — exact field shapes in real stream-json (`modelUsage` keys, subagent parent-id field name) are confirmed against reference dumps recorded during implementation; fallbacks are specified for their absence.
- **Q2** — decision-file and final-message-verdict protocols are the simplest thing that degrades softly; if real runs show poor compliance, revisit MCP tool (executor) or scoped-write file (judge) — explicitly deferred until practice demands it.

## Impact

- **New**: CLI adapter package (executor, judge, process runner, stream-json parser, progress SPI), shared briefing package, fake-agent test binary, reference dumps, Ollama E2E + paid smoke Gradle tasks.
- **Modified**: `TokenUsage`, `ExecutorUsage`, `JudgeVoter.Vote`, `JudgeUsage` (+ their specs and status DTOs, `status-report-v1.reference.json`); manual-run wiring/args; interactive judge (empty map); briefing extraction from `adapter.console`; contract suites (token forms).
- **Dependencies**: none new at runtime (ProcessBuilder, Jackson already in stack); test-side: none new (fake binary is a script).
- **Depends on**: add-manual-run implemented (ports, contract suites, briefing, runner); its archive should precede this change's apply so deltas target merged specs.
