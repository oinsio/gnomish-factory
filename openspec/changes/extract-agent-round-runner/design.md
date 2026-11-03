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
- Transform `pending` (human decision 2026-11-02, FR10–FR12): today a `transform` `pending` is
  refused in `domain/engine` (`RoundFailures`, `AttemptAccounting`, `RoundOutcome`: "a `pending`
  the stage cannot poll"), pinned by `ExecutorOutcomeTableSpec` under the wire-contract scenario
  "Pending from a role whose caller cannot poll". `migrate-verify-to-executor-contract` builds
  the one pending-poll owner (`PendingPoll`, `PendingPollRecord` in the attempt state, its D2/D7)
  for `verdict`, and its task 4.3a removes the per-instance key membership of `ProgramExecutor`
  (`PendingRuns`) so a persisted key can be polled by another instance.

## Goals / Non-Goals

**Goals:** one runner, one dialect seam (FR1, FR2); Claude as the first dialect with no behavior
loss (FR3); judge on the contract (FR4); every pair in the module dissolved (G3).

**Non-Goals:** out-of-process Claude (NG1), ACP (NG2), repo-policy isolation enforcement
(NG4), a Claude round returning `pending` (NG5), cancelling an abandoned run (NG6). Transform
`pending` in the engine is in scope (D11).

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
- `GitAttemptPersistence` ↔ `EnvironmentAttemptPersistence`: **touched at both ends** (D11):
  each writes the pending round commit through its existing sequence (gnome range judged by
  `GnomeRangeInspection`, owned paths restored, commit and register in one ref transaction, one
  atomic push); the mirrored edit is the state they are handed, with no new step, and one task
  changes both ends; `MidRoundHarvestListener` ↔ `MidRoundPushListener`: untouched, they subscribe to
  the vendor-neutral progress events (FR6) — one mirrored edit each to drop the session id,
  listed as a task.
- *No new parallel implementation:* the fake dialect in `test-fixtures` is a test double, not a
  second production dialect.

**D8 — Single-owner mechanisms.**

| Owner                                                                       | Value (type)                                                                 | Consumers                                                                                                                                                                                                                                          | Old way removed                                                                                                                                                                                      | Enforced by                                                                                                                                                                                                                                                                                                                       |
|-----------------------------------------------------------------------------|------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `AgentRoundRunner` (adapters/agent/runner)                                  | `ExecutorResult`                                                             | `ExecutorRegistry` entry `claude` (stage dispatch), `JudgeVoting` via registry, arbiter via registry                                                                                                                                               | `CliStageExecutor`, `CliJudgeVoter`, `ExecutorRoundExecution`, `JudgeRoundExecution`, `ResumeVerificationStageExecutor` (its check moves to the engine's resume path) deleted                        | `JudgeVoter` type deleted (compile); `EnginePorts` holds one executor port (spec)                                                                                                                                                                                                                                                 |
| `AgentDialect` (interface)                                                  | `RenderedArgv`, `ParsedEvents`, `Answer`, `ContinuationKey`                  | `AgentRoundRunner` only                                                                                                                                                                                                                            | `AgentCommandLine`/`AgentInvocationOptions` called from round classes; `AgentAiSeam` static names                                                                                                    | `ArchUnit`: no class outside `adapters/agent/claude` imports a Claude type; grep `stream-json\|ANTHROPIC_\|acceptEdits` → dialect package only (M2)                                                                                                                                                                               |
| `EnvelopePaths.answerFile` (domain)                                         | `EnvelopePath`                                                               | `AgentRoundRunner` (hands the path), `BranchDecisionFile` (reads it), salvage (`FactoryOwnedPaths`)                                                                                                                                                | `DecisionFileTransport` deleted; `GNOMISH_DECISION_FILE` literal removed                                                                                                                             | existing envelope-paths build gate extended with the new constant                                                                                                                                                                                                                                                                 |
| Policy compilation (`ClaudePolicyCompiler`)                                 | argv fragment from `Policy`                                                  | `ClaudeDialect`                                                                                                                                                                                                                                    | `AgentRole`, `JUDGE_READ_ONLY_TOOLS`, per-role branches in `AgentInvocationOptions`                                                                                                                  | `Policy` sealed type in the request; spec: no argv carries `bypassPermissions`                                                                                                                                                                                                                                                    |
| `PendingPoll` (`domain/engine`, from `migrate-verify-to-executor-contract`) | `PendingPollRecord` with subject `Round(roundToken)` beside `Check(checkId)` | `StageAttemptLoop` (a `transform` round returning `pending`, live and on resume), the round-close persistence pair (writes it), state mappers (`StateJsonMapper`, `StateAttemptDto`), the shape classifier (reads it as an `InProgress` sub-state) | the `transform` arm "a `pending` the stage cannot poll" in `RoundFailures`/`AttemptAccounting`/`RoundOutcome` narrowed to executors declaring no `poll`; no second pending record, loop or key store | sealed subject (exhaustive switch); `PendingKey` parameter type; architecture spec `ExecutorPollOwnerSpec` (`:bootstrap`): `Executor.poll(` is called in `domain`/`application` main sources only from `PendingPoll`, the scan asserted to reach its allowlist; identity spec `PendingRoundIdentitySpec`; kill-point specs of D11 |
| Credential names (`ClaudeDialect.describe`)                                 | `ExecutorCredentialNames`                                                    | `ChildEnvAllowlist` layer via the contract's delivery                                                                                                                                                                                              | `AgentAiSeam` deleted; `FactoryProperties.agentCliEnvPassthrough` dead knob deleted                                                                                                                  | identity spec `ProgramCredentialDeliverySpec` extended for `claude`                                                                                                                                                                                                                                                               |

**D9 — Crash consistency.** No new durable step on the decision path (the pending `transform`
round adds one, D11): the answer file rides the snapshot/salvage
commit exactly as the decision file did; the continuation key is a field of the recorded
`decision-request` in the attempt record (one commit). Kill-point specs of the decision path
are re-pointed at the answer file.

**D10 — Lock scope.** No lock added; `FreshJudgeEnvironments` keeps its resource-serializing
lock (the exception, documented) and becomes the `verifyIn: fresh-box` acquisition for votes.

**D11 — A pending `transform` round rides the one pending-poll owner.** (FR10–FR12, NFR-R3,
NFR-R4, NFR-O2, NFR-C2, UX3, G5; human decision 2026-11-02.)
`StageAttemptLoop` hands a `transform` result `pending(key)` from an executor declaring `poll`
to `PendingPoll`, whose record gains a sealed subject: `Check(checkId)` as built by
`migrate-verify-to-executor-contract`, or `Round(roundToken)`. *What is persisted:* the round's
working copy and the state carrying the open attempt with `pendingPoll = {subject:
Round(roundToken), stage, attempt, key, since}` land in one **pending round commit**, written by
the round-close persistence pair with its register in one ref transaction and one atomic push
(crash-consistency item 9). It is a round boundary that closes no attempt: the position is
unchanged, the verify chain does not run and no stage advances on it (item 12 — the stop rides
the record). Then `PendingPoll` polls in the round's environment at the factory's pending
interval, passing it as the in-poll wait bound, until a terminal status or the pending ceiling
counted from `since`; expiry is `failed/infrastructure` naming the ceiling, as every factory
bound is (define-executor-contract D3). Interval and ceiling are factory-owned bounds beside
`ProgramBounds`, default 30 s and 24 h, set by neither the law nor describe, for the reason D3
gives — decided (human decision 2026-11-02): no law key. A terminal result closes the round through the normal close, whose commit drops the
`pendingPoll` field (consumed-message hygiene, item 13). A `poll` returning `pending` with
another key persists it in a pending round commit before the next poll (FR12): the rule is FR10
and D12 of `migrate-verify-to-executor-contract`, subject-neutral, reused here (proposal Q3,
closed 2026-11-02).

*One path, live and resumed (item 14):* after the pending commit the live instance does not
continue from memory; it reads the record back from the commit it wrote through the same
`PendingPollRecord` parse the resume uses, and both call `PendingPoll.resume(record)`. A
resuming instance materializes the environment at the registered commit, so a live and a
resumed poll see the same working copy. The record is accepted only at the registered commit
and only when its round token names the round that commit holds open (item 13: identity, never
presence; the attempt number is not an identity).

*Kill windows (durable steps: (1) `start` returns `pending`, an external effect; (2) pending
round commit pushed; (3) a later `pending` with a new key pushed; (4) round close pushed):*

| Window                                   | Registered commit shows                     | Shape                           | Recovery owner                                         | Action                                                                                                                                                                       |
|------------------------------------------|---------------------------------------------|---------------------------------|--------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| K1: after (1), before (2)                | the round-open commit, gnome range above it | `InProgress` (open round)       | existing round-close owner + `StageAttemptLoop` resume | salvage the range as today, re-drive `start` (one duplicate remote run, logged, NFR-C2)                                                                                      |
| K2: after (2), during polls              | pending record, subject `Round`             | `InProgress`, pending sub-state | `PendingPoll`                                          | poll the recorded key, never `start`; clock from `since`                                                                                                                     |
| K3: after a new key returned, before (3) | record with the previous key                | `InProgress`, pending sub-state | `PendingPoll`                                          | poll the previous key, never `start`: the contract keeps a superseded key pollable for its run; a non-conforming refusal is `failed/infrastructure` naming the key (visible) |
| K4: after a terminal poll, before (4)    | record still present                        | `InProgress`, pending sub-state | `PendingPoll`                                          | poll the key again, never `start`: the contract returns the ended run's terminal result again, and the round closes with it; a refusal as K3                                 |
| after (4)                                | closed attempt, no record                   | the shape the close produces    | the owner of that shape                                | none: a second pass is a no-op                                                                                                                                               |

K3 and K4 rest on the wire requirement "A pending key stays pollable until its run ends"
(FR11, D11 of `migrate-verify-to-executor-contract`; proposal Q2, closed 2026-11-02), pinned for
every candidate by the conformance kit's `pending-repoll` case; with it, no window after (2)
re-drives `start`, and G5 holds for any conforming executor.

The pending sub-state is a field of an `InProgress` attempt, as the verdict record is (its D7),
so the shape set of the `task-branch-contract` capability gains no shape and the sweeper's
enumeration is unchanged (item 6). Mutually implied fields — key, subject, stage, attempt,
since — land in one commit (item 4); nothing destructive precedes a constructive receipt
(item 5). Recovery is idempotent: K2 twice is one poll sequence, a closed round is untouched.

*The status taxonomy:* the wire-contract requirement "Closed status taxonomy" is MODIFIED over
the text `define-executor-contract` adds (that change archives first; its five scenarios survive
verbatim but one): the WHEN of "Pending from a role whose caller cannot poll" narrows from every
`transform` executor to one declaring no `poll`. This is the decision that narrows it, citing the
verified premise that the refusal lives in `RoundFailures`/`AttemptAccounting`/`RoundOutcome`
and is pinned by `ExecutorOutcomeTableSpec`; a scenario for the pollable case is added.

*Relation to the continuation key (D4):* two different keys. The continuation key is a session
the *next* round `start`s with after a `decision-request` answer; the pending key is the *same*
round's handle for `poll`. Neither becomes the other: a `start` carrying a continuation may go
pending, and a round that ends by `poll` with a `decision-request` answer carries its
continuation key as any round does. The Claude dialect declares no `poll` (NG5), so its rounds
never take this path.

*Alternative zero — no engine path:* a long `transform` stays `failed/infrastructure` and the
executor must finish within one `start` under the overall bound, holding the slot; a kill loses
the run and the round restarts from scratch. What varies per round is only the key, the subject
and `since`; everything else (loop, bounds, persistence, resume) already exists for `verdict`,
so the cost of the path is one record subject and one commit kind. Rejected: the human decision
puts long rounds in scope, and a run that must outlive a restart cannot exist without it.
*Alternative rejected — a pending store of its own for rounds* (a separate ref or file): a second
recovery owner for one kind of message (item 13), and a second parse. *Alternative rejected —
persist the key without the working copy:* a resumed poll would see the round-open tree while the
live one sees the started tree — two inputs for one round.

## Risks / Trade-offs

- [A non-conforming executor that refuses a superseded or ended key (K3, K4)] → the conformance
  kit's `pending-repoll` case fails it before use; at run time the refusal is a visible
  infrastructure failure naming the key, never a silent loss or a silent `start`.
- [`migrate-verify-to-executor-contract` task 4.3a lands a design that does not let another
  instance poll a `program` key] → FR11 cannot hold for `program` executors; task 6.1 checks the
  premise first and stops under `spec-mismatch.md` if it fails.

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
3. Transform `pending` (D11) lands after the runner, on `PendingPoll` as
   `migrate-verify-to-executor-contract` leaves it.
4. Follow-ups: `externalize-claude-executor` (reference external executor, Codex dialect),
   `add-acp-executor`, `harden-verification-integrity` (uses the describe fields added here).
