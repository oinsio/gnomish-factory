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
runner, with the Claude dialect as its first replaceable implementation. It also takes the
last unowned outcome of the contract: today a `transform` executor that returns `pending + key`
fails as infrastructure, so a long round (a remote agent service, a run that outlives a
restart) cannot be expressed; the engine learns to persist, poll and resume such a round through
the one pending-poll owner `migrate-verify-to-executor-contract` builds for `verdict`.

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
- **ADDED**: engine handling of `pending + key` for the `transform` role (human decision
  2026-11-02): a `transform` executor whose describe declares `poll` may return `pending`; the
  round's working copy and the key land in one pending round commit before the first poll, any
  instance resumes the poll from that record, and the round closes with the terminal result.
  The owner is `migrate-verify-to-executor-contract`'s pending-poll mechanism, not a second one.
- **MODIFIED**: the contract's status taxonomy: `pending` from a `transform` executor is an
  infrastructure failure only when its describe declares no `poll`.
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
- G5: a `transform` round that returns `pending` survives a factory kill at any step and is
  finished by any instance, with no `start` repeated once the pending commit is pushed and no
  pending mechanism besides the one the verdict path uses.

## Non-Goals

- NG1: moving the Claude dialect out of process as the reference external executor — follow-up
  `externalize-claude-executor` (also adds the Codex dialect).
- NG2: an ACP dialect — follow-up `add-acp-executor`.
- NG3: mid-round permission prompts as decision requests (ADR 0012 D7 stands).
- NG4: isolating repo-local agent policy files (`.claude/settings.json`) — the describe fields
  land here; their enforcement is `harden-verification-integrity`.
- NG5: the Claude dialect returning `pending` — its round stays a child of the factory's process
  tree, so its describe declares no `poll` and it never yields a pending key; the engine path of
  FR10–FR12 is exercised by `program` executors and fixtures. Pickup condition: the first agent
  dialect or executor whose round runs outside the factory's process tree (a remote agent
  service), which then declares `poll` and implements it.
- NG6: cancelling the abandoned run that a re-driven `start` leaves behind (kill window before
  the pending commit) — `cancel` is not an operation of the contract (NG16 of
  `define-executor-contract`); its pickup condition stands, and this change's re-drive is a
  second engine path that would prefer a cooperative stop.

## Users & Scenarios

- U1 — Pipeline author: `executor: claude` on a stage and `executor: claude` on a `judge`
  check; the judge's `allowedTools` typo is a located load error from the dialect's schema.
- U2 — Operator: a gnome asks a question, the arbiter or a human answers, and the next round
  continues the same session — the ledger shows `continued session=<prefix>`.
- U3 — Executor author: adds a dialect by implementing argv rendering, event parsing and
  policy compilation; the runner, bounds, channels and specs are inherited.
- U4 — Engine maintainer: `EnginePorts` holds one `StageExecutor` and nothing else.
- U5 — Executor author with a remote agent service: a `program` transform executor declaring
  `poll` submits the job in `start` and returns `pending` with the job id; the factory is
  restarted mid-run, another instance polls the same key, and the round closes with the
  service's changes — the ledger shows `pending key=<prefix>` then `resumed key=<prefix>`.

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
- FR10: A `transform` executor whose describe declares `poll` MAY return `pending + key`. The
  engine SHALL then, through the pending-poll owner of `migrate-verify-to-executor-contract`,
  land the round's working copy and a pending record (the round's token, stage, attempt, key,
  start time) in one pending round commit with its register before the first `poll`; poll at the
  factory's pending interval, in the round's environment, until a terminal status or the
  factory's pending ceiling counted from the recorded start time; and close the round with the
  terminal result as any round. A `pending` from a `transform` executor declaring no `poll`
  stays `failed/infrastructure`.
- FR11: A pickup that finds a pending record at the registered commit SHALL continue that round
  with `poll(key)` in an environment materialized at that commit, on any instance, and SHALL NOT
  call `start`; neither the verify chain nor advancement SHALL run on a pending round commit.
- FR12: A pending `transform` round SHALL reuse the key-rotation rule of
  `migrate-verify-to-executor-contract` (its FR10, human decision 2026-11-02): a `poll` that
  returns `pending` with a key different from the recorded one has the new key persisted, in a
  pending round commit, before the next `poll`; this change adds no rotation mechanism of its own.

### Non-Functional Reliability

- NFR-R1: The runner's bounds (startup read, silence, overall) are driven on virtual time in
  specs; expiry kills the process tree and reports the bound.
- NFR-R2: A resumed session that the CLI no longer knows SHALL fall back to a fresh start with
  the answer in feedback, logged, never an infrastructure failure.
- NFR-R3: Every kill window of a pending `transform` round classifies to a named shape with one
  recovery owner: a kill before the pending commit is pushed re-drives `start` (one duplicate
  remote run per kill, logged); a kill after it — including after a key-rotating poll and after a
  terminal poll not yet closed — resumes the poll with no `start`, on the wire obligation of FR11
  of `migrate-verify-to-executor-contract`; recovery of a closed round is a no-op. Kill-point specs cover each window, each resumed by a second instance.
- NFR-R4: The live path and the resume path continue a pending round from the record through one
  parse, so the round token, usage and ledger lines of a resumed round are those of the round
  that went pending (identity spec on a real bare origin).

### Non-Functional Observability

- NFR-O1: Usage carries provenance; the Claude dialect reports `REPORTED`; the ledger shows
  `executor=claude@<version> role=<role>`.
- NFR-O2: Each poll of a pending `transform` round logs executor, key prefix, elapsed and
  remaining time under the task's MDC, as a verdict poll does; usage reported by `start` and by
  each `poll` is recorded under the one round identity.

### Non-Functional Security

- NFR-S1: Agent credentials come from the describe declaration resolved through the secrets
  port — `AgentAiSeam` is deleted; no production source names `ANTHROPIC_*`.
- NFR-S2: `verdict` and `decision` runs are refused if the working copy changed (post-hoc
  check), in addition to the compiled read-only policy.

### Non-Functional Cost

- NFR-C1: Turn and budget hints are passed to the dialect (`--max-turns`, budget flag); the
  ledger enforces after the round regardless.
- NFR-C2: The duplicate remote run a re-driven `start` may cause is bounded to one per kill
  before the pending commit and named in the ledger; it is not cancelled (NG6).

## Operator Experience Criteria

- UX1: A judge configuration error is reported at load with the stage, check and key.
- UX2: The escalation report of a `limit` stop reads "budget exhausted: <bound>".
- UX3: The ledger shows a pending `transform` round as `pending key=<prefix>` and its
  continuation on another instance as `resumed key=<prefix>` (the verdict path's token).

## Success Metrics

- M1: `grep -rn "Kept in sync with" adapters/agent` → zero; the four registry rows involving
  `adapters/agent` are removed.
- M2: `grep -rn "ANTHROPIC_\|stream-json\|acceptEdits" --include=*.java` → hits only in the
  Claude dialect package.
- M3: `EnginePorts` holds exactly one executor-facing port.
- M4: A fake second dialect (in `test-fixtures`) runs all three roles through the runner with
  no production change outside its own class.
- M5: A `program` transform executor declaring `poll`, run from the packaged jar against a
  fixture service, killed after the pending commit and resumed by a second instance: the service
  counts exactly one `start`, the round closes with the service's changes, and a second recovery
  pass changes nothing; killed before the pending commit: exactly two `start`s, both in the ledger.

## Open Questions

- Q1: Whether the Claude dialect's schema should keep accepting `allowedTools` after policy
  compilation covers the common cases.
- Q2 — closed (human decision 2026-11-02): yes. The wire contract requires that every key an
  executor issued stays pollable until its run ends and that a poll of an ended run's key returns
  its terminal result again, with a conformance-kit re-poll case; the requirement, the kit case
  and the stand-in update are FR11, FR12 of `migrate-verify-to-executor-contract` (the first
  change that persists a key across a kill), and design D11 relies on them for K3 and K4.
- Q3 — closed (human decision 2026-11-02): yes. Re-persisting a changed key is FR10 of
  `migrate-verify-to-executor-contract`, which ADDs the `verification/pending-poll` requirement;
  this change reuses it for the `transform` round (FR12) and adds no delta to that capability.
- Scope — decided (human decision 2026-11-02): the change keeps its grown scope (the agent round
  runner and transform `pending` together); no split.

## Capabilities

### New Capabilities
- `executor/agent-round-runner`: the generic runner and the dialect contract.
- `executor/claude-dialect`: the Claude Code dialect's observable behavior.

### Modified Capabilities
- `agent-executor`: round mechanics, policy, settings, progress, judge move onto the contract.
- `stage-engine`: judge votes as verdict requests; `JudgeVoter` removed; pending `transform`
  rounds persisted, polled and resumed.
- `executor/wire-contract` (added by `define-executor-contract`): `pending` from `transform` is
  refused only without `poll`.

## Impact

- `adapters/agent` restructured into `runner` and `claude` packages; `application/port/agent`
  progress types trimmed; `domain/engine` `JudgeVoting` and `EnginePorts`.
- `domain/engine` `PendingPoll`/`PendingPollRecord` (from `migrate-verify-to-executor-contract`)
  gain the round subject; the round-close persistence pair (`GitAttemptPersistence`,
  `EnvironmentAttemptPersistence`) writes the pending round commit.
- Sequencing: after `migrate-verify-to-executor-contract` and `remove-interactive-console`;
  FR10–FR12 depend on its `PendingPoll`, `PendingPollRecord`, its key rotation (FR10), its wire
  obligation and re-poll kit case (FR11, FR12) and its task 4.3a (a `program` executor's poll
  resumable by another instance); the `executor/wire-contract` MODIFIED block is
  layered on `define-executor-contract`'s ADDED text, so that change archives first
  (`migrate-verify-to-executor-contract` only ADDs to that capability, so the layering is
  unchanged);
  before `harden-verification-integrity`; `add-decision-arbiter` rebases onto role `decision`.
- Sequenced after `tighten-spec-contract`: its deltas carry the `**Implements**:` lines and the
  500-character requirement bodies that change introduces; it has no MODIFIED block to layer, and
  the markers and exemption lines of the scenarios its REMOVED requirements take with them are
  removed in this change (tasks.md, section 5).
