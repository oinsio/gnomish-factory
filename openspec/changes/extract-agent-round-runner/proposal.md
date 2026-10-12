# Proposal: extract-agent-round-runner

## Why

`adapters/agent` is 4,330 lines in which Claude Code's `stream-json` dialect (about 1,900
lines) is interleaved with the generic mechanics of running one agent round (about 1,300
lines), duplicated per role (`ExecutorRoundExecution` ↔ `JudgeRoundExecution`), and leaking
outward: judge read-only policy is spelled as Claude tool names, settings validation accepts
Claude keys for every stage, progress events carry Claude `subtype` and session ids, the
agent's exit code and stderr are never read, and the stdout drain has no size cap. After
`define-executor-contract` and `migrate-verify-to-executor-contract` the agent CLI is the
last mechanism outside the contract, and `judge` the last check outside it. This change
turns the agent CLI into one named executor with three roles, behind one generic round
runner, with the Claude dialect as its first replaceable implementation.

## What Changes

- **ADDED**: `agent-cli` becomes a named executor implementing the contract's `Executor`
  interface in roles `transform`, `verdict` and `decision`; its describe declares its
  credentials, egress hosts, config-directory variable, repo-policy-disabling flags, settings
  schema and enforcement level `TOOL_GATE`.
- **ADDED**: a generic **agent round runner** (launch through the environment seam, capped
  concurrent drains, three bounds, result and answer through the shared result-file channel,
  denials, post-hoc working-copy check for `verdict`/`decision`) parameterized by an
  **agent dialect**: argv rendering, policy compilation, event parsing, usage and trace
  extraction, continuation (session id). Claude is the first dialect.
- **ADDED**: `judge` checks run as `verdict` requests to a named executor; `JudgeVoter`
  leaves `EnginePorts`; votes are N `start`s reduced by the existing majority rule; the verdict
  arrives as a SARIF answer produced through the CLI's schema-enforced output.
- **ADDED**: decision continuation: the agent's session id is the `decision-request`
  continuation key; after the answer the next round `start`s with the continuation and the
  dialect resumes the session instead of starting from scratch.
- **MODIFIED**: policy is the contract's abstract policy compiled by the dialect; progress
  events lose vendor fields; settings are validated by the executor's schema; the agent's
  exit code and stderr are read and capped.
- **REMOVED**: `CliStageExecutor`, `CliJudgeVoter`, `ExecutorRoundExecution`,
  `JudgeRoundExecution`, `AgentAiSeam`, `AgentSettingsValidator`'s Claude key list,
  `AgentRole`, the host/sandbox round-environment source pair, `DecisionFileTransport` as a
  separate channel (the decision request is the answer file).
- **BREAKING** (manifest): `settings.allowedTools`/`disallowedTools` move under the executor's
  own settings schema; existing keys remain accepted by the Claude dialect's schema.

## Goals

- G1: a second agent dialect (Codex) is one dialect class or one external program, with zero
  changes in `domain`, `application` or the runner.
- G2: `adapters/agent` holds no role-specific round class; one runner serves three roles.
- G3: every manual-sync pair inside `adapters/agent` and across to `adapters/git` is dissolved.
- G4: the arbiter (`add-decision-arbiter`) is a `decision` request to a named executor with no
  port of its own.

## Non-Goals

- NG1: moving the Claude dialect out of process as the reference external executor — follow-up
  `externalize-claude-executor` (also adds the Codex dialect).
- NG2: an ACP dialect — follow-up `add-acp-executor`.
- NG3: mid-round permission prompts as decision requests (ADR 0012 D7 stands).
- NG4: isolating repo-local agent policy files (`.claude/settings.json`) — the describe fields
  land here; their enforcement is `harden-verification-integrity`.
- NG5: `pending` for `transform` beyond the continuation key (no long-round resume token).

## Users & Scenarios

- U1 — Pipeline author: `executor: claude` on a stage and `executor: claude` on a `judge`
  check; the judge's `allowedTools` typo is a located load error from the dialect's schema.
- U2 — Operator: a gnome asks a question, the arbiter or a human answers, and the next round
  continues the same session — the ledger shows `continued session=<prefix>`.
- U3 — Executor author: adds a dialect by implementing argv rendering, event parsing and
  policy compilation; the runner, bounds, channels and specs are inherited.
- U4 — Engine maintainer: `EnginePorts` holds one `StageExecutor` and nothing else.

## Requirements

### Functional

- FR1: One `AgentRoundRunner` SHALL execute any role: build the request, render argv through
  the dialect, launch through the environment seam, drain stdout/stderr concurrently with
  byte and line caps, apply the three bounds, read the result and answer through the shared
  result-file reader, attach denials, run the post-hoc working-copy check for `verdict` and
  `decision`, and return `ExecutorResult`.
- FR2: An `AgentDialect` SHALL provide: describe fields, argv for a request (including the
  compiled policy, model, bounds, output schema for `verdict`/`decision`, session resume),
  event parsing into progress and usage, final-answer extraction, exit-code mapping to status
  (`limit` for turn/budget stops), and continuation key extraction.
- FR3: The Claude dialect SHALL reproduce today's observable behavior: prompt on stdin,
  stream-json parsing, per-model usage, top-level trace, `--strict-mcp-config`, permission
  mode per policy (`acceptEdits` for `workspaceWrite`, `dontAsk` for `readOnly`), decision
  request via the answer file; and SHALL add `--json-schema` for `verdict` and `decision`
  answers and `--resume <session>` for continuation.
- FR4: `judge` checks SHALL resolve to a named executor in role `verdict`; `JudgeVoting` SHALL
  issue N `start`s and reduce with the existing majority rule; a vote's malformed answer is
  cannot-verify for the whole check; `JudgeVoter` is removed from `EnginePorts`.
- FR5: Policy SHALL be the contract's abstract policy; no engine or application type SHALL
  name a vendor tool; `writeExactly` is used only for legacy decision-file compatibility and
  removed when the answer file replaces it.
- FR6: Progress events SHALL carry only vendor-neutral fields (model, tool name, final summary,
  usage); the session id SHALL travel as the continuation key in the result, not in events.
- FR7: Settings of an agent executor SHALL be validated at law load against the dialect's
  describe schema; the Claude schema accepts `allowedTools`, `disallowedTools`, `maxTurns`,
  `roundTimeout` and the three bounds.
- FR8: The agent's exit code and stderr SHALL be read: non-zero exit with no result event is
  `failed/infrastructure`; a turn or budget stop is `failed/limit`; stderr is a capped log.
- FR9: Decision continuation: a `decision-request` answer SHALL carry the session id as its
  continuation key; a `start` with a continuation and an answer SHALL resume the session.

### Non-Functional Reliability

- NFR-R1: The runner's bounds (startup read, silence, overall) are driven on virtual time in
  specs; expiry kills the process tree and reports the bound.
- NFR-R2: A resumed session that the CLI no longer knows SHALL fall back to a fresh start with
  the answer in feedback, logged, never an infrastructure failure.

### Non-Functional Observability

- NFR-O1: Usage carries provenance; the Claude dialect reports `REPORTED`; the ledger shows
  `executor=claude@<version> role=<role>`.

### Non-Functional Security

- NFR-S1: Agent credentials come from the describe declaration resolved through the secrets
  port — `AgentAiSeam` is deleted; no production source names `ANTHROPIC_*`.
- NFR-S2: `verdict` and `decision` runs are refused if the working copy changed (post-hoc
  check), in addition to the compiled read-only policy.

### Non-Functional Cost

- NFR-C1: Turn and budget hints are passed to the dialect (`--max-turns`, budget flag); the
  ledger enforces after the round regardless.

## Operator Experience Criteria

- UX1: A judge configuration error is reported at load with the stage, check and key.
- UX2: The escalation report of a `limit` stop reads "budget exhausted: <bound>".

## Success Metrics

- M1: `grep -rn "Kept in sync with" adapters/agent` → zero; the four registry rows involving
  `adapters/agent` are removed.
- M2: `grep -rn "ANTHROPIC_\|stream-json\|acceptEdits" --include=*.java` → hits only in the
  Claude dialect package.
- M3: `EnginePorts` holds exactly one executor-facing port.
- M4: A fake second dialect (in `test-fixtures`) runs all three roles through the runner with
  no production change outside its own class.

## Open Questions

- Q1: Whether the Claude dialect's schema should keep accepting `allowedTools` after policy
  compilation covers the common cases.

## Capabilities

### New Capabilities
- `executor/agent-round-runner`: the generic runner and the dialect contract.
- `executor/claude-dialect`: the Claude Code dialect's observable behavior.

### Modified Capabilities
- `agent-executor`: round mechanics, policy, settings, progress, judge move onto the contract.
- `stage-engine`: judge votes as verdict requests; `JudgeVoter` removed.

## Impact

- `adapters/agent` restructured into `runner` and `claude` packages; `application/port/agent`
  progress types trimmed; `domain/engine` `JudgeVoting` and `EnginePorts`.
- Sequencing: after `migrate-verify-to-executor-contract` and `remove-interactive-console`;
  before `harden-verification-integrity`; `add-decision-arbiter` rebases onto role `decision`.
- Sequenced after `tighten-spec-contract`: its deltas carry the `**Implements**:` lines and the
  500-character requirement bodies that change introduces; it has no MODIFIED block to layer, and
  the markers and exemption lines of the scenarios its REMOVED requirements take with them are
  removed in this change (tasks.md, section 5).
