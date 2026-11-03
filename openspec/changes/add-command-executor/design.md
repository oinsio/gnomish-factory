# Design: add-command-executor

## Context

See proposal.md — Why. The verified current state that constrains the approach:

- `ExecutorType` (`domain/.../pipeline/ExecutorType.java`) holds `API`, `AGENT_CLI` and
  `PROGRAM`; wire tokens are allowlisted in `StructuralValidation.EXECUTOR_TYPES` and
  mapped in `StageDefinitionMapper.mapExecutorType` (an unknown token maps to `API` after
  the structural allowlist refused it). `ApiExecutorRule` rejects every `API` stage at load
  with "the supported executor types are 'agent-cli' and 'program'".
- `StageSanityRule` (lines 70–77) requires a non-blank `executor.model` for every executor
  type but `PROGRAM` — so a `command` stage, which pins no model, needs its own arm of the
  rule, the conflict this change's pipeline-config delta resolves. `StageDefinition.Executor`
  is `(type, model, settings, sandbox, name)`, with `model` and `name` already adjacent
  `String`s.
- After `define-executor-contract` (sequenced before this change) the engine's result is
  the contract's status + answer: `failed/quality` is a recorded quality-failure round
  whose findings join `priorFailures` and whose verify chain is skipped;
  `failed/infrastructure` is `CannotExecute`. The classification lives in the engine's
  single `ExecutorOutcomeClassifier`; an adapter only reports a status.
- `TaskExecutionEnvironment.exec` (`sandbox/core/.../TaskExecutionEnvironment.java:63`)
  is documented as "the sole process-launch seam"; host and container implementations
  (`HostTaskExecutionEnvironment.exec`, `ContainerTaskExecutionEnvironment.exec`) both
  compose the child env via `ChildEnvAllowlist.compose`, which strips declared
  credential names by construction.
- The `command` *verify check* already owns command-run mechanics:
  `CommandProcessRunner` (`:adapters`, package-private in `adapter/check/`) drives
  `environment.exec(new ExecCommand([sh, -c, cmd], env, null, StderrPolicy.MERGED))` with a
  concurrent `BoundedTail` drain, `waitForExitOrTimeout`, and exit-code classification in
  `ShellCommandCheckRunner` (0 → Pass, 126/127 → CannotVerify, other → Fail with
  findings/tail). Its timeout is the installation's `factory.check-command-timeout`, not a
  manifest key.
- `define-executor-contract` D8, as built: `ExecutorRegistry` (`:bootstrap`) is a
  `Map<ExecutorName, StageExecutor>` — an entry is a stage executor of the engine's port, not
  a bare contract `Executor`, because an executor is bound to one round's environment, which
  exists only once the round is open — and `ExecutorRegistry.of` fills it from the law's
  `executors:` declarations the describe preflight described, nothing else. `ExecutorDispatch`
  (`:bootstrap`) is the run's one `StageExecutor` and switches on `ExecutorType`: `PROGRAM`
  resolves the registry by `executor.name`, `AGENT_CLI` and `API` go to the `agent-cli` arm.
  `ProgramStageExecutor` (`:adapters:agent`, `program/`) is the round wrapper of a registry
  entry: it opens the round, probes the guard, constructs `new ProgramExecutor(...)` over the
  round's environment, closes the round, reads the denials and maps the contract result to
  the engine through `ProgramResultMapping.toEngine` — the one owner of the status mapping.
  The contract's `ExecutorResult` carries no findings list: a `failed/quality` result reaches
  the engine as one finding built from its `reason`. Built-ins implement the contract's
  `Executor` mirror interface, `describe`, `start` and `poll`; the conformance kit drives a
  built-in in process through `BuiltInCandidate`.
- `remove-interactive-console` (archived 2026-10-05) removed the interactive executor
  substitution: `ExecutorAdapterSelector` binds the stage executor from the manifest alone,
  with no role switch (its FR2). No run mode substitutes an executor any more.
- `RoundTimeout` (adapters/agent) resolves the `roundTimeout` settings key (Number =
  seconds, String = ISO-8601 with tolerant `PT` prefixing, default 30 min); it forms a
  declared manual-sync pair with `AgentSettingsValidator.isWellFormedRoundTimeout`
  (registry row in `.claude/rules/manual-sync-pairs.md`).

## Decisions

**D1 — Exit ≠ 0 is reported as the contract's `failed/quality` status; the engine does
the rest.** (FR6, NFR-R1, G3.) The command executor builds an `ExecutorResult` with
`failed/quality` whose `reason` is the round's one finding — the text names the exit code
and carries the bounded, sanitized output tail — because the contract's result carries no
findings list and `ProgramResultMapping` (the one status mapping) turns a quality reason into
exactly one engine finding; `define-executor-contract` owns what follows (attempt burned,
round recorded, findings fed forward, verify chain skipped). `poll` is answered
`failed/infrastructure`: a command round is never `pending` (NG7 of
`define-executor-contract` makes a pending transform an infrastructure failure, and its
pickup is `extract-agent-round-runner`'s). This change adds no engine arm and no result
variant. *Rationale:* flaky builds are real — a quality failure
buys retries with the exit code and output tail as feedback, exactly the loop findings
already ride; and the classification must not be decided in an adapter (the contract's
single-owner rule). *Alternatives rejected:* (a) report exit ≠ 0 as
`failed/infrastructure` — burns no attempt, so a legitimately failing build escalates
instantly with no retry and no findings history; (b) report `completed` and let the
verify chain discover the damage — the failure signal already exists (the exit code),
running checks over a known-failed transformation wastes them and buries the true
finding; (c) a synthetic implicit verify check — smuggles engine semantics into manifest
space and breaks "verify list is manifest-declared".

**D2 — Sync surfaces.** Six surfaces examined; verdicts per the preference order of
`.claude/rules/manual-sync-pairs.md`:

- *The executor is a built-in of the contract, not a parallel path.* `CommandStageExecutor`
  implements the contract's `Executor` mirror interface and returns `ExecutorResult`, so it
  shares the status taxonomy, the classifier and the conformance kit with every other
  executor (define-executor-contract D2); no second result shape appears.
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
  termination kind), a public type of `:adapters`, which `:adapters:agent` already depends
  on; the check runner and the new executor each keep only their own classification
  (Verdict vs the contract status) and environment acquisition. *Rationale:*
  the rule's preference order puts a shared abstraction first, the mechanics are
  already a separable component with a clean seam, and divergent timeout/kill or
  tail-bounding semantics between the executor and the check would be a user-visible
  inconsistency. *Alternative rejected:* a second full implementation declared as a
  pair — a third implementation already looms (any future command-shaped runner), and
  the pair's synchronized invariant would be nearly the whole class.
- *`RoundTimeout` ↔ `AgentSettingsValidator` declared pair — reused, not widened.* The
  command executor resolves its timeout through the same `RoundTimeout` class (opened within
  `:adapters:agent`, where the executor lives), and the command-settings validation reuses
  the same well-formedness check. No third implementation of the accepted shapes appears;
  the registry row's invariant is unchanged with one more caller on each end.
  `extract-agent-round-runner` (sequenced after this change) dissolves the pair into the
  describe settings schema and inherits both callers; the proposal's sequencing names it.
- *The round wrapper of a registry entry — shared abstraction, not a second wrapper.* A
  registry entry is a `StageExecutor`, and `ProgramStageExecutor` today constructs
  `ProgramExecutor` concretely. The command built-in needs the same wrapper — open the
  round, probe the guard, run the executor over the round's environment, close the round,
  read the denials, map through `ProgramResultMapping` — so `ProgramStageExecutor` is
  generalized over an executor factory (round environment in, contract `Executor` out) and
  serves both; `ProgramResultMapping.toEngine` stays the one mapping of the contract's
  status taxonomy to the engine's, for the built-in as for a program. *Alternative
  rejected:* a `CommandStageExecutor` that implements `StageExecutor` itself and maps its own
  statuses — a second round wrapper to keep in step by hand and a second owner of the
  status mapping, which `define-executor-contract`'s single-owner table forbids.
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
`ShellCommandCheckRunner`'s CannotVerify classification, reported as the contract's
`failed/infrastructure` status. *Alternative rejected:* quality failure on timeout (the command
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

**D6 — `command` is one built-in entry of `ExecutorRegistry`, reached by a `COMMAND` arm
of `ExecutorDispatch` that maps the stage type to the entry's name.** (FR8.) A `command`
stage carries its mechanism on the stage (`executor.command`, D3) and names no executor,
while `ExecutorRegistry.of` fills the registry from law declarations only and
`ExecutorDispatch` already switches on `ExecutorType`. So the registry gains one built-in
entry at wiring — `ExecutorName` `command`, a stage executor built by the shared round
wrapper (D2) over the command built-in — passed to `ExecutorRegistry.of` beside the
declarations, and the dispatch gains `case COMMAND -> registry.resolve(<command name>)`.
That arm is a mapping of a stage type to a registry name, not a second dispatch mechanism:
the registry stays the one place a name resolves to what runs the round, and the arm holds
no executor of its own. `ExecutorDeclaration.Type` keeps `PROGRAM` only — nothing is
declared in the law for a built-in a stage names by type — and its javadoc, with
`ExecutorRegistry`'s ("add-command-executor registers command here"), is brought to what
is built. `ApiExecutorRule` stays; its message names the supported types. *Alternatives
rejected:* (a) a built-in id in `ExecutorDeclaration.Type` with a law declaration
`executors.<name>: {type: command, …}` and the stage referencing it as `executor: {type:
program, name: …}` — the command string would leave the stage manifest that D3 pins it to,
and a deterministic stage would need two documents to read; (b) a `command` arm that runs
the executor itself beside the registry — the second dispatch mechanism the contract's
single-owner table forbids. *Sequencing:* `extract-agent-round-runner` deletes the
type-keyed `agent-cli` arm when the agent becomes a named executor; the `COMMAND` arm is
the one type-to-name mapping that remains, and that change's artifacts say so (proposal,
Impact).

**Single-owner mechanisms.**

| Mechanism | Owner and invariant | Value | Consumers | Old way removed / exemptions | Enforcement |
|-----------|---------------------|-------|-----------|------------------------------|-------------|
| Stage type → executor name for the built-in | `ExecutorDispatch` (`:bootstrap`): the only place a `command` stage's type becomes the registry name `command` | `ExecutorName` | `ExecutorRegistry.resolve` | none pre-exists; no other arm runs a command | the wiring spec of task 6.2: a `command` stage never reaches the agent arm |
| Contract status → engine result | `ProgramResultMapping.toEngine` (`:adapters:agent`): the one mapping of `Status` + `FailureClass` to `ExecutionResult`, for a program and for the built-in alike | `ExecutionResult` | the shared round wrapper (D2) | a built-in mapping its own statuses — refused at design (D2, rejected alternative) | `ProgramResultMappingSpec` iterates both taxonomies; the round wrapper has one mapping call site |
| Round wrapper of a registry entry | the generalized `ProgramStageExecutor` (`:adapters:agent`): open, probe, run, close, denials, map — once | `StageExecutor` | `ExecutorRegistry` entries (programs, `command`) | `new ProgramExecutor(...)` inside the wrapper — replaced by the factory | `ProgramStageExecutor`'s existing specs stay green unchanged; `grep "implements StageExecutor"` in `:adapters:agent` lists the one wrapper |

**D7 — Crash consistency: no new durable steps.** (NFR-R2.) Per `.claude/rules/
crash-consistency.md`: this change adds no multi-step transition. A command round rides
the existing round persistence contract (persist → AttemptFinished → next attempt); a
kill during a command round freezes a state the `task-branch-contract` shape set
already names (unpersisted round → re-executed on resume, NFR-R2). No new kill window,
no new recovery owner.

## Risks / Trade-offs

- [Skipping verify on `failed/quality` hides check regressions until the command passes] →
  acceptable: checks verify the product, and the failed command *is* the finding; the
  verify chain runs on the first exit-0 round.
- [Generalizing `CommandProcessRunner` touches the verify-check path] → the existing
  `ShellCommandCheckRunner` specs pin its behavior; the refactor must keep them green
  unchanged (behavior-preserving extraction).
- [A command stage can still hang inside `roundTimeout` for up to 30 min by default] →
  same exposure as agent rounds; `roundTimeout` is per-stage tunable.
- [Generalizing `ProgramStageExecutor` touches the `program` round path] → its existing
  specs pin the behavior; the factory parameter is the only change, and the `program`
  factory constructs exactly what the wrapper constructed before.

## Migration Plan

Purely additive: existing pipelines declare no `command` stages and load unchanged.
No rollback machinery needed — removing a command stage from a manifest restores the
prior behavior.

## Open Questions

- Q1/Q2 from proposal.md (findings-file channel for command rounds; per-stage shell) —
  both deferrable without touching specs, approach, or tasks.
