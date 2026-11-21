# Design: extract-agent-round-runner

## Context

See proposal.md — Why. Constraints:

- `adapters/agent` (44 files, 4,330 lines when this change was written; 80 files, 7,748 lines
  since the `program/` and `command/` executors landed, which this change does not touch): Claude dialect (`AgentCommandLine`,
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
- The exit code of the CLI is never read; `StreamJsonParser` has no cap. Stderr already goes to a
  file in the round scratch directory and is read after the exit (`agent-executor`, "The round's
  stderr is kept", `fix-terminal-receipt-convergence` FR20, NG12, NG13): the runner inherits that,
  and `Drain` stays the one post-exit join (`DrainOwnerGateSpec`).
- Available from earlier changes: `Executor`, `ExecutorResult`, `ResultFileReader`,
  `ExecutorRegistry`, describe-validated settings, abstract `Policy`, `VerdictDerivation`,
  `ExecutorOutcomeClassifier`, three bounds, continuation field on `start`.
- `remove-interactive-console` (sequenced before) deletes the interactive stage executor and
  judge voter; `add-decision-arbiter` rebases onto role `decision`.
- Transform `pending` (human decision 2026-11-02, FR10–FR12): today a `transform` `pending` is
  refused in `domain/engine` (`RoundFailures`, `AttemptAccounting`, `RoundOutcome`: "a `pending`
  the stage cannot poll"), pinned by `ExecutorOutcomeTableSpec` under the wire-contract scenario
  "Pending from a role whose caller cannot poll". `migrate-verify-to-executor-contract` builds
  the one pending-poll owner for `verdict` (`PendingPoll`; its entries are a
  `List<PendingPollRecord> pendingPolls` on `TaskState`, written by
  `AttemptPersistence.persistPending` as a pending commit — `git-task-persistence`, "Pending
  commits of an open round" — and resumed by `ResumeVerificationStageExecutor`, the one recovery
  owner of `task-branch-contract`, "An interrupted verification is InProgress with one recovery
  owner"; its D2, D7 as amended, D21, D22), and its task 4.3a removes the per-instance key membership of `ProgramExecutor`
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

**D6 — Settings schema from describe; `roundTimeout` is the round's overall bound.** (FR7,
NFR-R1; amended 2026-11-21 by a decision review, after task 2.3 found the first wording
unmeetable.) The Claude dialect's schema accepts exactly the four legacy keys;
`AgentSettingsValidator`'s Claude key list moves into the schema. The `roundTimeout` shape judge
is not Claude's alone: since `add-command-executor` it is also called by `CommandSettingsValidator`
and the command executor resolves `roundTimeout` through `RoundTimeout`. It therefore stays one
shape owner, in `:adapters`, which both the Claude dialect's schema and the command executor
(`adapters/agent` depends on `:adapters`) reach; it keeps exactly the shapes accepted today. The
`RoundTimeout` ↔ `AgentSettingsValidator` pair dissolves into that one owner (shared abstraction,
preference 1), not into a third copy inside the schema. The dispatch's `COMMAND` arm is untouched
(design D6 of `add-command-executor`).

*Who owns each bound.* A round has three time bounds, and they have two owners:

| Bound          | Owner                                                                                   | Why                                                                                                                                                                                              |
|----------------|-----------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| overall        | the law: `roundTimeout` of the stage's executor settings or of the judge check's settings; the factory's overall bound (`ProgramBounds`) when the key is absent | only the stage's author knows how long its work takes; the main specs already promise it (`agent-executor`, "Round timeout and control-file preflight"; `claim-heartbeat`, which names the per-stage `roundTimeout` as what bounds a round) |
| startup read   | the factory (`ProgramBounds`)                                                           | a property of the CLI's start, the same for every stage                                                                                                                                          |
| silence        | the factory (`ProgramBounds`)                                                           | a property of the CLI's liveness cadence, not of the stage's work (measurement below)                                                                                                            |

The three are resolved once per `start` by one owner, `RoundBounds` (`adapters/agent`): the
factory's `ProgramBounds` with `overall` replaced by the request settings' `roundTimeout` when
the settings declare one. The value the runner waits on and the time hints the request carries
are that one value. `RoundTimeout` keeps the shapes and the parse and loses its own default: the
30 minutes are written once, in `ProgramBounds.DEFAULTS`.

*What the first wording got wrong, verified in code.* It promised "the three bounds" as schema
keys. (1) `define-executor-contract` landed after it was written and decided the opposite for
the bounds as a set (`ProgramBounds.java:15-19`; that change's D3, "Bounds and settings, as
built"): neither describe nor the law sets them, an operator key deferred "until an operator
needs a non-default bound". (2) No key names existed anywhere. (3) The runner built in task 1.2
takes all three from the factory's `ProgramBounds` (`AgentRound.java:127-132`) and never reads
`roundTimeout`; the key's only round-time readers are `ExecutorRoundExecution` and
`JudgeRoundExecution`, which tasks 4.1 and 5.1 delete, and `CommandStageExecutor`. Left as
written, `roundTimeout` would have become an accepted key with no reader, and every agent round
would have been killed at the factory's 30 minutes — the `implement` round that found this ran
1 h 37 min under a law that declares `roundTimeout: 28800s`. The deferral's pickup condition is
met for the overall bound, and only for it.

*Containment.* The reason `ProgramBounds` gives — "an executor that set its own would set the
bound meant to contain it" — is about the executor's describe answer. The law is not the
contained party: it is bound from the base ref before any round and the gnome cannot write it,
and it has set this bound since `add-agent-executor`. Describe still sets no bound.

*The silence bound, measured.* A silence bound is sound only for a worker that reports liveness
(Temporal's heartbeat timeout and Step Functions' `HeartbeatSeconds` are off unless the worker
heartbeats; CircleCI's and Travis' no-output timers are the record of what a silence timer does
to a quiet healthy step). Measured 2026-11-21 on `claude` 2.1.325, macOS, with the flags the
dialect renders (`-p --output-format stream-json --verbose`): during one foreground Bash call
of 420 s the CLI wrote a `tool_progress` line with `heartbeat: true` every 30.0 s (14 lines, no
gap above 30 s). `ProgressLines` counts every chunk read as activity, so the factory's 5 minutes
is ten missed heartbeats. The CLI's documentation dates the heartbeat of a foreground subagent
call to 2.1.257 and says tool calls inside a subagent emit none; a subagent's own tool events
reach the parent stream (`subagent-round.reference.json`). Two facts are therefore still
unmeasured — a long tool call inside a subagent, and the Linux CLI of the sandbox base image —
and task 2.3b measures both before the runner serves a stage (`design-decisions.md`, "A platform
fact is measured on every platform CI runs"). What the bound buys: the CLI is known to stop
writing without exiting (anthropics/claude-code issues 39700 and 53584); without it such a round
holds its slot for the whole `roundTimeout`, eight hours under this repository's law.

*Alternative zero — no law key at all* (the escalation's option 1 as worded): the bounds stay
the factory's three constants. What is left per round is one number that differs per stage by a
factor of a hundred (300 s for `cleanup`, 28800 s for `implement`); a constant either kills the
long stages or bounds nothing on the short ones, and the main-spec promise of `roundTimeout`
would be removed to make the code pass. Rejected.
*Alternative rejected — law keys for all three bounds, or keys that may only narrow the
factory's* (the escalation's option 2): the per-step override of a silence timer exists in CI
systems because their steps are silent by nature; here the cadence is the CLI's, measured, and
the same for every stage, so the keys would have no value an author could choose better than
the factory. Narrow-only breaks the one real need: the long stages need a bound above the
factory's default, not below it.
*Alternative rejected — a factory ceiling over `roundTimeout`* (GitLab's runner maximum,
Buildkite's maximum step timeout): a ceiling protects a platform operator from a pipeline author
who is someone else. Today the law's author and the factory's operator are one party and the old
path has no ceiling, so it would be a mechanism with no consumer (NG7 names the pickup). What is
borrowed for that day: refuse an over-ceiling value at law load (Kubernetes `LimitRange`), never
clamp it silently at round time.
*Alternative rejected — no silence bound for an agent round* (the old path's behaviour): it
gives up the only detector of a silent hang earlier than `roundTimeout`, for a risk the
measurement does not show.

**D7 — Sync surfaces.** Scout: `grep -rn "Kept in sync with"` and the registry.
- `ExecutorRoundExecution` ↔ `JudgeRoundExecution`: **dissolved** into `AgentRoundRunner`
  (shared abstraction; role is a request field).
- `HostRoundEnvironmentSource` ↔ `SandboxRoundEnvironmentSource`: **dissolved** — the runner
  takes a `RoundEnvironment` from the one environment acquisition the verify path already
  uses per `verifyIn`; registry row removed.
- `MidRoundPushRounds` ↔ `SandboxRoundEnvironmentSource` (both in `adapters/git`, markers at both
  ends, listed under the Host/Sandbox registry row): both mint the round token once per opened
  round through `RoundToken.of` from a `VerifiedTip`-checked tip. With the sandbox source deleted,
  the box round's mint moves to the environment acquisition that replaces it; since both ends share
  `adapters/git`, the pair **collapses** (preference 1) into one minting step that the host
  decoration and the box acquisition both call, and both `Kept in sync with` markers go (task 5.2).
  *Alternative rejected:* re-pointing `MidRoundPushRounds`' marker at the new owner — a declared
  pair with no classpath barrier is what `manual-sync-pairs.md` forbids.
- `DecisionFileTransport` ↔ `BranchDecisionFile`: **dissolved** (D3): one path owner, one
  reader; registry row removed.
- `RoundTimeout` ↔ `AgentSettingsValidator`: **dissolved** (D6) into the one shape owner the
  command executor shares; registry row removed.
- `GitAttemptPersistence` ↔ `EnvironmentAttemptPersistence`: **touched at both ends** (D11). The
  pair already holds `persistPending` (`AttemptPersistence.java:59`), which writes the pending
  commit `git-task-persistence` specifies ("Pending commits of an open round": exactly
  `state.json`, the round's last registered commit as its only parent, its register in the same
  ref transaction). The mirrored edits are: the `Round` subject on the entry `persistPending`
  already writes; the host snapshot trigger (D22 of `migrate-verify-to-executor-contract`,
  `completed` only) widened to a `pending` `transform` result; and the container's round close
  given a pending-round subject that the recogniser reads. One task changes both ends, and the
  registry row of the pair names the pending commit's subject rule; `MidRoundHarvestListener` ↔ `MidRoundPushListener`: untouched, they subscribe to
  the vendor-neutral progress events (FR6) and read no session id.
- *No new parallel implementation:* the fake dialect in `test-fixtures` is a test double, not a
  second production dialect.

**D8 — Single-owner mechanisms.**

| Owner                                                                       | Value (type)                                                                 | Consumers                                                                                                                                                                                                                                          | Old way removed                                                                                                                                                                                      | Enforced by                                                                                                                                                                                                                                                                                                                       |
|-----------------------------------------------------------------------------|------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `AgentRoundRunner` (adapters/agent/runner)                                  | `ExecutorResult`                                                             | `ExecutorRegistry` entry `claude` (stage dispatch), `JudgeVoting` via registry, arbiter via registry                                                                                                                                               | `CliStageExecutor`, `CliJudgeVoter`, `ExecutorRoundExecution`, `JudgeRoundExecution` deleted; `ResumeVerificationStageExecutor` stays the one recovery owner of an interrupted verification (`task-branch-contract`) and decorates the chain over the `claude` executor as it did the old arm | `JudgeVoter` type deleted (compile); `EnginePorts` holds one executor port (spec)                                                                                                                                                                                                                                                 |
| `AgentDialect` (interface)                                                  | `RenderedArgv`, `ParsedEvents`, `Answer`, `ContinuationKey`                  | `AgentRoundRunner` only                                                                                                                                                                                                                            | `AgentCommandLine`/`AgentInvocationOptions` called from round classes; `AgentAiSeam` static names                                                                                                    | `ArchUnit`: no class outside `adapters/agent/claude` imports a Claude type; grep `stream-json\|ANTHROPIC_\|acceptEdits` → dialect package only (M2)                                                                                                                                                                               |
| `EnvelopePaths.answerFile` (domain)                                         | `EnvelopePath`                                                               | `AgentRoundRunner` (hands the path), `BranchDecisionFile` (reads it), salvage (`FactoryOwnedPaths`). Invariant: `EnvelopePaths.answerFile` is the branch-durable location of a `decision-request` answer, the one path `BranchDecisionFile` reads at the tip; the contract's result channel separately names a per-request scratch answer path | `DecisionFileTransport` deleted; `GNOMISH_DECISION_FILE` literal removed. Exemption: `ProgramSpawn`/`ProgramResultRead` (the `program` executor, `result-channel` capability) keep their per-request answer path, unchanged — a scratch channel of one request, not the branch envelope | existing envelope-paths build gate extended with the new constant                                                                                                                                                                                                                                                                 |
| Policy compilation (`ClaudePolicyCompiler`)                                 | argv fragment from `Policy`                                                  | `ClaudeDialect`                                                                                                                                                                                                                                    | `AgentRole`, `JUDGE_READ_ONLY_TOOLS`, per-role branches in `AgentInvocationOptions`                                                                                                                  | `Policy` sealed type in the request; spec: no argv carries `bypassPermissions`                                                                                                                                                                                                                                                    |
| `PendingPoll` (`domain/engine`, from `migrate-verify-to-executor-contract`) | `PendingPollRecord` (an entry of `TaskState.pendingPolls`) with the sealed subject `Round` beside `Check(CheckRef)`; the round token is already on the record | `StageAttemptLoop` (hands a `transform` `pending` to `PendingPoll`), `PendingPoll` (live and resumed), the two `persistPending` implementations (`GitAttemptPersistence`, `EnvironmentAttemptPersistence`), `StateJsonMapper`/`TaskState` (the `pendingPolls` list), `ResumeVerificationStageExecutor` (the recogniser and one recovery owner; a `Round` entry resumes through `PendingPoll`, never as `Completed`) | the `transform` arm "a `pending` the stage cannot poll" in `RoundFailures`/`AttemptAccounting`/`RoundOutcome` narrowed to executors declaring no `poll`; no second pending record, loop or key store | sealed subject (exhaustive switch); `PendingKey` parameter type; architecture spec `ExecutorPollOwnerSpec` (`:bootstrap`): `Executor.poll(` is called in `domain`/`application` main sources only from `PendingPoll`, the scan asserted to reach its allowlist; identity spec `PendingRoundIdentitySpec`; kill-point specs of D11 |
| Credential names (`ClaudeDialect.describe`)                                 | `ExecutorCredentialNames`                                                    | `ChildEnvAllowlist` layer via the contract's delivery                                                                                                                                                                                              | `AgentAiSeam` deleted (the dead `agent-cli-env-passthrough` knob is already gone, `RemovedKeys.java:14`)                                                                                             | identity spec `ProgramCredentialDeliverySpec` extended for `claude`                                                                                                                                                                                                                                                               |
| `RoundBounds` (`adapters/agent`; D6 as amended) | `ProgramBounds` of one round: the factory's startup-read and silence bounds, the overall bound from the request settings' `roundTimeout` or the factory's | `AgentRound`'s wait (every role: a stage round, each judge vote, a decision round); the time hints of the request an agent executor receives; `CommandStageExecutor`'s timeout | `RoundTimeout.resolve(` in `ExecutorRoundExecution` and `JudgeRoundExecution` (both deleted, 4.1 and 5.1) and in `CommandStageExecutor`; `equipment.bounds()` read for the round's wait in `AgentRound`; the `RoundTimeout.DEFAULT` literal, a second spelling of `ProgramBounds.DEFAULTS.overall()` | `RoundBoundsOwnerSpec` (`:bootstrap`): `RoundTimeout.resolve(` appears only in `RoundBounds.java`, and `new ProgressWait(` in `adapters/agent/runner` only where the bounds come from it (scan asserted reached); identity spec `RoundBoundIdentitySpec`: a stage declaring `roundTimeout` is killed at exactly that value on virtual time, and the request's overall hint equals it |

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
to `PendingPoll`, whose `PendingPollRecord` gains a sealed subject: `Check(CheckRef)` as built by
`migrate-verify-to-executor-contract`, or `Round` (the round token is already a field of the
record, `PendingPollRecord.java:34`). *What is persisted* — the landed mechanism, no second one:
the round's working copy is registered as the round's snapshot (the host snapshot trigger of
D22 there widens from `completed` to a `pending` `transform` result; the container's round close
gets a pending-round subject, so the recogniser never reads that snapshot as a finished round);
then the entry `{subject: Round, attempt, token, key, since}` is appended to
`TaskState.pendingPolls` and written by `AttemptPersistence.persistPending` as the **pending round
commit** — a pending commit as `git-task-persistence` specifies it ("Pending commits of an open
round": exactly `state.json`, the snapshot as its only parent, so its tree carries the working
copy and the record; its register in the same ref transaction, one atomic push; crash-consistency
item 9). It is a round boundary that closes no attempt: the position is unchanged, the verify
chain does not run and no stage advances on it (item 12 — the stop rides the record). Then
`PendingPoll` polls in the round's environment at the engine's pace, `max(pending interval,
pollAfter)` clamped to the ceiling, as `PendingWait` does for a check — no in-poll wait bound
travels on the request (NG13 of `migrate-verify-to-executor-contract`, human decision
2026-11-13); a round-scoped `PendingWait` is built from the factory's pending interval and
ceiling beside the check-scoped one — until a terminal status or the pending ceiling counted from
`since`; expiry is `failed/infrastructure` naming the ceiling, as every factory
bound is (define-executor-contract D3). Interval and ceiling are factory-owned bounds beside
`ProgramBounds`, default 30 s and 24 h, set by neither the law nor describe, for the reason D3
gives — decided (human decision 2026-11-02): no law key. A terminal result closes the round through the normal close, whose commit drops the
round's entry from `pendingPolls` (consumed-message hygiene, item 13). A `poll` returning `pending` with
another key persists it in a pending round commit before the next poll (FR12): the rule is FR10
and D12 of `migrate-verify-to-executor-contract`, subject-neutral, reused here (proposal Q3,
closed 2026-11-02).

*One path, live and resumed (item 14):* the landed rule of `PendingPoll` (D21 of
`migrate-verify-to-executor-contract`) is kept, not amended: the live instance continues from the
entry it just persisted and the pending answer it holds in memory; a resuming instance reads the
entry at the register through the `TaskState` parse and polls it at once; both continue through
`PendingPoll`'s one wait path (`PendingPoll.java:126-128`). Item 14 holds because both paths build
the entry as one `PendingPollRecord` — the live path the value it persisted, the resume the value
parsed back from that commit — so the round token, usage and ledger lines are one identity, which
`PendingRoundIdentitySpec` pins (NFR-R4). *Alternative rejected:* re-reading the record from the
pending commit on the live path — it amends D21 for checks and rounds alike for no failure the
identity spec does not already catch, or adds a round-only path, the second pending mechanism G5
excludes. A resuming instance materializes the environment at the registered commit, so a live
and a resumed poll see the same working copy. The record is accepted only at the registered
commit and only when its round token names the round that commit holds open (item 13: identity,
never presence; the attempt number is not an identity). The recovery owner is the landed one:
`ResumeVerificationStageExecutor` (`task-branch-contract`, "An interrupted verification is
InProgress with one recovery owner") recognises the pending commit; an entry with subject `Round`
resumes through `PendingPoll` with no `start` and is never answered `Completed`.

*Kill windows (durable steps: (1) `start` returns `pending`, an external effect; (2) pending
round commit pushed, above the pending-round snapshot (a snapshot registered without it is still
window K1); (3) a later `pending` with a new key pushed; (4) round close pushed):*

| Window                                   | Registered commit shows                     | Shape                           | Recovery owner                                         | Action                                                                                                                                                                       |
|------------------------------------------|---------------------------------------------|---------------------------------|--------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| K1: after (1), before (2)                | the round-open commit with the gnome range above it, or the round's pending-round snapshot with no pending commit above it | `InProgress` (open round)       | existing round-close owner + `StageAttemptLoop` resume; `ResumeVerificationStageExecutor` for a registered snapshot, which reads the pending-round subject and hands it back to the round instead of re-verifying | salvage the range as today, re-drive `start` (one duplicate remote run, logged, NFR-C2)                                                                                      |
| K2: after (2), during polls              | pending record, subject `Round`             | `InProgress`, pending sub-state | `PendingPoll`                                          | poll the recorded key, never `start`; clock from `since`                                                                                                                     |
| K3: after a new key returned, before (3) | record with the previous key                | `InProgress`, pending sub-state | `PendingPoll`                                          | poll the previous key, never `start`: the contract keeps a superseded key pollable for its run; a non-conforming refusal is `failed/infrastructure` naming the key (visible) |
| K4: after a terminal poll, before (4)    | record still present                        | `InProgress`, pending sub-state | `PendingPoll`                                          | poll the key again, never `start`: the contract returns the ended run's terminal result again, and the round closes with it; a refusal as K3                                 |
| after (4)                                | closed attempt, no record                   | the shape the close produces    | the owner of that shape                                | none: a second pass is a no-op                                                                                                                                               |

K3 and K4 rest on the wire requirement "A pending key stays pollable until its run ends"
(FR11, D11 of `migrate-verify-to-executor-contract`; proposal Q2, closed 2026-11-02), pinned for
every candidate by the conformance kit's `pending-repoll` case; with it, no window after (2)
re-drives `start`, and G5 holds for any conforming executor.

The pending entry is an entry of `TaskState.pendingPolls` on a registered pending commit, as the
verdict entry is (D7 as amended and D21 of `migrate-verify-to-executor-contract`); a registered
pending commit classifies by its envelope as any registered commit does (`task-branch-contract`,
"An interrupted verification is InProgress with one recovery owner"), so the shape set gains no
shape and the sweeper's enumeration is unchanged (item 6). Mutually implied fields — key, subject, stage, attempt,
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
so the cost of the path is one record subject and one snapshot subject over the landed pending
commit. Rejected: the human decision
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

- [The CLI of the sandbox base image, or a tool call inside a subagent, is quiet for longer than the
  factory's silence bound] → a healthy round would be killed as infrastructure on every retry.
  Task 2.3b measures both before the runner serves a stage (task 5.1); a gap above a fifth of the
  bound stops the change under `spec-mismatch.md` for a decision on the bound, never a raised
  constant.
- [The CLI's own timers are not derived from `roundTimeout`] → a foreground Bash call is moved to
  the background at the CLI's `BASH_MAX_TIMEOUT_MS` whatever the stage declares; that is the
  agent's tool budget, the operator's to set through the child environment, and no part of the
  round's bound.

- [Behavior drift while splitting the module's Claude and round classes] → the 51 existing specs and the reference stream-json
  fixtures are kept green through the refactor; the fake dialect proves the seam (M4).
- [`--json-schema` output differs from today's parsed final text] → the Claude dialect falls
  back to final-text extraction when the CLI version lacks the flag, logged at WARN.
- [Dropping `sessionId` from progress breaks dashboards] → its only consumers are in
  `adapters/agent` (`LoggingAgentProgressListener`, `AgentProgressEmitter`, `AgentRoundResult`,
  `AgentRoundResultExtractor`, `StreamJsonEventMapper`, `MissingResultEventException`); each is
  adapted in task 1.1, and no listener outside the module reads it.
- [`add-decision-arbiter` written against `DecisionNeeded`] → its rebase is a named task in that
  change; this design fixes the shape it targets (role `decision`, answer `select`).

## Migration Plan

1. After `migrate-verify-to-executor-contract` and `remove-interactive-console`.
2. Introduce runner + Claude dialect behind the existing registry entry; switch judge; delete
   the old classes; remove four registry rows.
3. Transform `pending` (D11) lands after the runner, on `PendingPoll`, `persistPending` and the
   recogniser as `migrate-verify-to-executor-contract` leaves them, adding the `Round` subject
   and the pending-round snapshot subject.
4. Follow-ups: `externalize-claude-executor` (reference external executor, Codex dialect),
   `add-acp-executor`, `harden-verification-integrity` (uses the describe fields added here).
