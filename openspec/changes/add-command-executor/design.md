# Design: add-command-executor

## Context

See proposal.md — Why. The verified current state that constrains the approach:

- `ExecutorType` (`domain/.../pipeline/ExecutorType.java`) holds `API` and `AGENT_CLI`;
  wire tokens are allowlisted in `StructuralValidation.EXECUTOR_TYPES` and mapped in
  `StageDefinitionMapper.mapExecutorType`. `ApiExecutorRule` rejects every `API` stage
  at load with "'agent-cli' is the only supported executor type currently".
- `StageSanityRule` (lines 72–77) requires a non-blank `executor.model` for **every**
  executor type — the live conflict this change's pipeline-config delta resolves.
- The engine flow is confirmed: `RoundExecution.execute` calls
  `executor.execute(new StageExecutor.Request(...))` (line 98) and only a `Completed`
  result proceeds to `verified(...)`, which runs the verify chain (line 126);
  `DecisionNeeded` skips verification. `ExecutionResult` is sealed with exactly
  `Completed` and `DecisionNeeded`; retry feedback is `priorFailures(state)` — the
  non-Pass `CheckResult`s of all prior attempts (lines 176–186).
- `TaskExecutionEnvironment.exec` (`sandbox/core/.../TaskExecutionEnvironment.java:65`)
  is documented as "the sole process-launch seam"; host and container implementations
  (`HostTaskExecutionEnvironment.exec`, `ContainerTaskExecutionEnvironment.exec`) both
  compose the child env via `ChildEnvAllowlist.compose`, which strips declared
  credential names by construction.
- The `command` *verify check* already owns command-run mechanics:
  `CommandProcessRunner` (adapters/.../check/) drives `environment.exec(new ExecCommand(
  [sh, -c, cmd], env, null, true))` with a concurrent `BoundedTail` drain,
  `waitForExitOrTimeout`, and exit-code classification in `ShellCommandCheckRunner`
  (0 → Pass, 126/127 → CannotVerify, other → Fail with findings/tail).
- `EnginePorts` carries exactly one `StageExecutor`; `ExecutorAdapterSelector` branches
  on interactive mode only and its javadoc asserts every engine-bound stage is
  `agent-cli` — there is no per-stage executor-type dispatch today.
- `RoundTimeout` (adapters/agent) resolves the `roundTimeout` settings key (Number =
  seconds, String = ISO-8601 with tolerant `PT` prefixing, default 30 min); it forms a
  declared manual-sync pair with `AgentSettingsValidator.isWellFormedRoundTimeout`
  (registry row in `.claude/rules/manual-sync-pairs.md`).

## Decisions

**D1 — Exit ≠ 0 is an executor-reported quality failure (`ExecutionResult.Failed`),
and the verify chain is skipped for that round.** (FR6, NFR-R1, G3.) The sealed
`ExecutionResult` gains a `Failed(usage, trace, findings)` variant; `RoundExecution`'s
exhaustive switch gains one arm mapping it to a recorded quality-failure round whose
synthetic non-Pass result joins `priorFailures` feedback like any failed check. The
attempt loop, strict persistence ordering, events, and escalation are untouched —
`StageAttemptLoop.route` already handles a `Fail`-verdict round. Skipping verification
mirrors the `DecisionNeeded` precedent: the round produced no product worth verifying,
and fail-fast is the engine's stated verification posture. *Rationale:* flaky builds
are real — a quality failure buys retries with the exit code and output tail as
feedback, exactly the loop findings already ride. *Alternatives rejected:* (a) treat
exit ≠ 0 as infrastructure (`CannotExecute`) — burns no attempt, so a legitimately
failing build escalates instantly with no retry and no findings history; (b) complete
the round and let the verify chain discover the damage — the failure signal already
exists (the exit code), running checks over a known-failed transformation wastes them
and buries the true finding; (c) a synthetic implicit verify check — smuggles engine
semantics into manifest space and breaks "verify list is manifest-declared".

**D2 — Sync surfaces.** Four surfaces examined; verdicts per the preference order of
`.claude/rules/manual-sync-pairs.md`:

- *No host/container twin is created.* The command executor calls
  `TaskExecutionEnvironment.exec` — the sole process seam — and mode is resolved
  inside the existing environment implementations. One executor implementation serves
  both modes by construction; there is nothing to keep in sync. This is the design's
  central argument for riding the seam rather than spawning processes directly.
- *Command executor vs `ShellCommandCheckRunner`/`CommandProcessRunner` — shared
  abstraction, not a declared pair.* Both are "run a declared command in the task
  environment with allowlisted env, timeout, bounded output tail, exit-code contract".
  `CommandProcessRunner` is generalized into a shared command-run core (input: argv
  fragment/command string, env fragment, timeout; output: exit code, bounded tail,
  termination kind); the check runner and the new executor each keep only their own
  classification (Verdict vs ExecutionResult) and environment acquisition. *Rationale:*
  the rule's preference order puts a shared abstraction first, the mechanics are
  already a separable component with a clean seam, and divergent timeout/kill or
  tail-bounding semantics between the executor and the check would be a user-visible
  inconsistency. *Alternative rejected:* a second full implementation declared as a
  pair — a third implementation already looms (any future command-shaped runner), and
  the pair's synchronized invariant would be nearly the whole class.
- *`RoundTimeout` ↔ `AgentSettingsValidator` declared pair — reused, not widened.* The
  command executor resolves its timeout through the same `RoundTimeout` class (moved or
  opened as needed), and the command-settings validation reuses the same
  well-formedness check. No third implementation of the accepted shapes appears; the
  registry row's invariant is unchanged with one more caller on each end.
- *`ExecutorType` wire vocabulary* — the undeclared spread `ExecutorType` ↔
  `StructuralValidation.EXECUTOR_TYPES` ↔ `StageDefinitionMapper.mapExecutorType` ↔
  glossary is touched at every end in this change; per `testing.md`, the token
  round-trip gets a data-driven spec over all `ExecutorType.values()` so a constant
  mapped on one side only fails a spec, not production.

**D3 — Manifest shape: first-class `executor.command`, `model` forbidden, settings =
`{roundTimeout}`.** (FR2, FR3, UX2.) The command string is the mechanism identity of a
command stage — the exact analogue of the pinned `model` for agent stages — so it is
first-class manifest data, not an opaque settings key. `model` on a command stage is a
located error rather than silently ignored: a manifest must not lie about what runs.
*Alternatives rejected:* command inside `settings` (dodges located validation and the
"settings are opaque at load" contract); tolerating an ignored `model` (silent manifest
lies; the sanity rule's whole point is that the manifest pins what runs).

**D4 — Timeout expiry and unstartable commands are infrastructure failures.** (FR7.)
Expiry kills the process tree via the existing `waitForExitOrTimeout` and classifies
as infrastructure, matching the executor-round timeout contract already spec'd for
agent rounds (agent-executor: "roundTimeout expiry SHALL ... classify the round as an
infrastructure failure"). Spawn failure and exits 126/127 mirror
`ShellCommandCheckRunner`'s CannotVerify classification, mapped to the executor's
infrastructure channel. *Alternative rejected:* quality failure on timeout (the command
*check*'s behavior) — executor rounds already define timeout = infrastructure, and two
timeout classes for the same executor concept, varying by executor type, would make
engine behavior unpredictable from the manifest. The check keeps its own contract; the
two roles differ (Mechanism vs Quality Control), and the shared core (D2) carries the
mechanics, not the classification.

**D5 — No decision protocol for command stages.** (NG1.) `DecisionNeeded` is
unreachable from the command executor and `GNOMISH_DECISION_FILE` is never set — the
decision file is an agent wire protocol (declared pair `DecisionFileTransport` ↔
`BranchDecisionFile`), and a script escalates by failing. Stated explicitly so the
pair's audit scope is unchanged. *Alternative rejected:* a script-facing decision
channel — no use case, and it would drag the decision-file pair into a third medium.

**D6 — Dispatch is a type-keyed composite at wiring, outside the engine.** (FR8.)
`EnginePorts` keeps exactly one `StageExecutor`; a dispatching implementation selects
per `stage.executor().type()` and is assembled in `ExecutorAdapterSelector`.
Interactive substitution wraps the agent branch only — command stages always run for
real (running the command is what a human at the console would do anyway, and it costs
no tokens). `ApiExecutorRule` stays; its message text now names both supported types.
*Alternative rejected:* a second executor port in `EnginePorts` — an engine structural
change for what is purely an adapter-selection concern, and every future executor type
would widen the port set again.

**D7 — Crash consistency: no new durable steps.** Per `.claude/rules/
crash-consistency.md`: this change adds no multi-step transition. A command round rides
the existing round persistence contract (persist → AttemptFinished → next attempt); a
kill during a command round freezes a state the `task-branch-contract` shape set
already names (unpersisted round → re-executed on resume, NFR-R2). No new kill window,
no new recovery owner.

## Risks / Trade-offs

- [Skipping verify on `Failed` hides check regressions until the command passes] →
  acceptable: checks verify the product, and the failed command *is* the finding; the
  verify chain runs on the first exit-0 round.
- [Generalizing `CommandProcessRunner` touches the verify-check path] → the existing
  `ShellCommandCheckRunner` specs pin its behavior; the refactor must keep them green
  unchanged (behavior-preserving extraction).
- [A command stage can still hang inside `roundTimeout` for up to 30 min by default] →
  same exposure as agent rounds; `roundTimeout` is per-stage tunable.
- [Interactive mode running real commands may surprise an operator simulating a
  pipeline] → glossary and operator-facing docs state it; commands are deterministic
  and token-free, which is the mode's cost concern.

## Migration Plan

Purely additive: existing pipelines declare no `command` stages and load unchanged.
No rollback machinery needed — removing a command stage from a manifest restores the
prior behavior.

## Open Questions

- Q1/Q2 from proposal.md (findings-file channel for command rounds; per-stage shell) —
  both deferrable without touching specs, approach, or tasks.
