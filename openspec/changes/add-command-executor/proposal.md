# Proposal: add-command-executor

## Why

Deterministic pipeline stages — codegen, formatting, database migrations, release
assembly — currently require an LLM round to run `make` or `gradlew`: the only executor
types are `api` (unsupported) and `agent-cli`, so a stage whose whole job is "run this
command" must pay for and wait on an agent that merely types the command. Industry
orchestrators model this as a sibling executor type per stage (Dagger treats LLM and
Container as sibling node types), not as a degenerate agent. The factory already has
every mechanical piece — the sole process seam (`TaskExecutionEnvironment.exec`), the
layered child-env allowlist, bounded output capture, and an exit-code contract in the
`command` *verify check* — but no way for a stage's *Mechanism* to be a command.

## What Changes

- **ADDED**: a third executor type `command` beside `api` and `agent-cli`
  (`ExecutorType.COMMAND`, wire token `command`). A `command` stage executes a declared
  shell command in the task's execution environment via the existing sole process seam,
  so it behaves identically in host and container modes by construction.
- **ADDED**: manifest schema for command stages — a required non-blank `executor.command`
  field, an optional `roundTimeout` settings key with the same accepted shapes and
  default as agent-cli rounds.
- **MODIFIED**: `executor.model` becomes conditional on executor type — still required
  non-blank for `api` and `agent-cli` (and for `judge` checks, unchanged), forbidden for
  `command` (a present model on a command stage is a located config error).
- **ADDED**: the command executor reports through the executor contract of
  `define-executor-contract` (sequenced before this change): exit ≠ 0 is the contract's
  `failed/quality` status carrying the exit code and a bounded, sanitized output tail as
  findings, feeding the normal attempt/retry loop; exit 0 is `completed` and the verify
  chain runs as usual. No engine result-model change is made here.
- **ADDED**: `command` registers as the first **built-in executor** of the contract's
  executor registry; the name-keyed dispatch itself comes from `define-executor-contract`,
  and its `COMMAND` arm maps the stage type to the built-in's registry name (design D6).
- No decision-file protocol for command stages — decisions are an agent concept.
  Round boundary checks, attempt persistence, and harvest apply unchanged: a command
  may legitimately modify the working copy — that is its purpose.

## Goals

- G1: A deterministic stage runs its command with zero model calls and zero token
  spend, while keeping the full stage contract — verify chain, attempts, escalation,
  resume — unchanged.
- G2: One command-stage manifest behaves identically in host and container modes,
  with no mode-specific executor implementation.
- G3: A failing command feeds the same findings/retry loop as a failing verify check,
  so flaky builds get meaningful retries instead of instant escalation.

## Non-Goals

- NG1: No decision-file protocol for command stages — a script escalates by failing,
  not by asking; `DecisionNeeded` stays unreachable for command rounds.
- NG2: No structured findings-file channel (`GNOMISH_FINDINGS_FILE`) for command
  *executor* rounds — that channel stays a verify-check feature (see Q1).
- NG3: No change to the `command` *verify check* semantics — the executor and the check
  remain distinct stage-contract roles (Mechanism vs Quality Control).
- NG4: No engine-level pipeline entry precondition — that is the separately proposed
  `add-pipeline-entry-precondition`; this change complements it, not replaces it.
- NG5: `api` stays unsupported — the startup rule rejecting it remains, with its
  message updated to name the supported types.
- NG6: No change to the engine's result model or to dispatch — both are owned by
  `define-executor-contract`; this change only registers a built-in and maps exit codes.

## Users & Scenarios

- U1: A pipeline author declares a `codegen` stage with
  `executor: {type: command, command: "./gradlew generateSources"}` and a verify chain;
  the stage runs, its working-copy effects are harvested and committed like any round.
- U2: An operator reads a failed command stage's tracker report: the exit code and the
  bounded tail of the command's output, sanitized, appear as findings — no adapter
  source required to understand what failed.
- U3: Any factory instance resumes a command stage mid-retry from the task branch,
  exactly as for agent stages — same state file, same attempt records.

## Requirements

### Functional

- FR1: Stage manifests accept the executor type token `command`, mapped to a new
  `ExecutorType.COMMAND`; unknown executor tokens remain located structural errors.
- FR2: A `command` stage declares a required non-blank `executor.command`;
  `executor.model` is a located error when present on a `command` stage and remains
  required non-blank for `api` and `agent-cli` stages and `judge` checks.
- FR3: A `command` stage's `settings` accept exactly `roundTimeout`, with the same
  accepted shapes (number of seconds, ISO-8601 string) and the same 30-minute default
  as agent-cli rounds; an unknown settings key is a located error naming the stage and
  the key.
- FR4: A command round executes the declared command via `exec()` of the bound task
  execution environment — the sole process seam — over the task working copy, with the
  layered child-env allowlist and no `GNOMISH_DECISION_FILE`; the observed protocol
  (exit code, output) is identical in host and container modes.
- FR5: Exit 0 completes the round; the stage's verify chain runs as usual.
- FR6: Exit ≠ 0 is reported as the contract's `failed/quality` status whose `reason` is
  the round's one finding: a text naming the exit code and carrying the bounded, sanitized
  output tail (the contract's result carries no findings list; the contract's one status
  mapping turns the reason into the engine's finding); the engine's own rule
  (define-executor-contract FR7) then burns the attempt, records the round, feeds the
  finding forward and skips the verify chain.
- FR7: A command that cannot start (spawn failure, exit 126/127) or outlives its
  timeout (process tree killed) is reported as `failed/infrastructure` — no attempt
  burned, escalation as "cannot execute".
- FR8: The command executor is a built-in of the executor registry implementing the
  contract's mirror interface (`describe`, `start`, `poll`); a `command` stage resolves to
  it through the registry by the dispatch's `COMMAND` arm, which maps the stage type to the
  built-in's name; `poll` is never answered `pending` — a command round is never pending.
- FR9: Round boundary checks, attempt persistence, and harvest apply to command rounds
  unchanged — the command's working-copy modifications are the stage product.

### Non-Functional Reliability

- NFR-R1: A flaky command (intermittent build failure) retries through the normal
  attempt loop with prior findings in feedback, in the same working copy — retries are
  meaningful, not blind reruns.
- NFR-R2: The change introduces no new durable step and no new kill window: command
  rounds ride the existing round persistence contract, and an unpersisted command
  round is safe to lose (re-executed on resume).

### Non-Functional Observability

- NFR-O1: Command rounds emit the existing engine events unchanged; all captured
  command output that reaches findings, logs, or the tracker is stripped of control
  sequences and bounded (existing findings sanitizer and bounded-tail capture).

### Non-Functional Security

- NFR-S1: The command's environment is composed exclusively by the existing layered
  allowlist — declared credential variables can never reach the command by
  construction, and no factory code path spawns the command outside the sole process
  seam.

### Non-Functional Cost

- NFR-C1: A command round reports no token usage — the contract's `Usage.absent()`,
  provenance `ABSENT` with an empty per-model map (unreported, never fabricated zeros); a
  pipeline's deterministic stages contribute zero model cost.

## Operator Experience Criteria

- UX1: A failed command stage's escalation report reads as "exit code N" plus the tail
  of the command's own output — coherent text, not truncated garbage or raw ANSI noise.
- UX2: Manifest mistakes fail at load with located errors: unknown executor type,
  missing `command`, model present on a command stage, malformed `roundTimeout` — each
  naming the stage file and field, before any stage runs.

## Success Metrics

- M1: A pipeline containing a command stage completes end-to-end in both host and
  container modes with zero model invocations for that stage (asserted by spec).
- M2: A command stage failing twice then passing consumes exactly 3 attempts, with
  attempts 2 and 3 receiving the prior exit-code findings in feedback (asserted by
  spec).
- M3: Mutation score for all new production code is 100% (project gate).

## Open Questions

- Q1: Should command executor rounds later gain the structured findings-file channel
  (`GNOMISH_FINDINGS_FILE`) that command verify checks have, so a script can report
  machine-readable findings beyond its output tail? Deferred — the output-tail finding
  is sufficient for the retry loop; answering later changes no requirement here.
- Q2: Should the shell (`sh -c`) be configurable per stage? Deferred — the command
  verify check runs `sh -c` today and no need has surfaced.

## Capabilities

### New Capabilities

- `command-executor`: runtime behavior of the `command` stage executor — execution
  through the sole process seam, exit-code contract, timeout, environment, telemetry,
  and the absence of the decision protocol.

### Modified Capabilities

- `pipeline-config`: the model-pinning rule becomes conditional on executor type
  (required for agent types, forbidden for `command`), and command stages gain the
  required `executor.command` field with located validation.
  (Layered on the `define-executor-contract` delta of the same requirement, which is
  sequenced before this change.)

## Impact

- `domain/.../pipeline/ExecutorType.java` — new `COMMAND` constant;
  `domain/.../pipeline/StageDefinition.java` — `Executor` carries the command;
  `domain/.../pipeline/StageSanityRule.java` — conditional model rule + command rule;
  `domain/.../pipeline/ApiExecutorRule.java` — message names the supported types.
- No engine change: the command executor returns the contract's `ExecutorResult`;
  `RoundExecution`, `StageAttemptLoop`, persistence and events are untouched.
- `adapters/.../pipeline/` — `ExecutorDto`, `StructuralValidation` (wire token),
  `StageDefinitionMapper`, settings validation for command stages beside
  `AgentSettingsValidator`.
- `adapters/.../check/CommandProcessRunner.java` (`:adapters`, today package-private) —
  generalized into the shared command-run core, a public type of `:adapters`, reused by the
  new executor (see design.md, sync surfaces).
- New `CommandStageExecutor` in `:adapters:agent` beside `program/`, implementing the
  contract's `Executor` interface; `adapters/agent/.../program/ProgramStageExecutor` —
  generalized over an executor factory so one round wrapper serves `program` and the
  built-in (design D2); `bootstrap/.../ExecutorRegistry` — one built-in entry;
  `bootstrap/.../ExecutorDispatch` — a `COMMAND` arm mapping the stage type to that entry's
  name (design D6); the javadocs of `ExecutorRegistry` and `ExecutorDeclaration.Type` brought
  to what is built.
- `docs/glossary.md` — the Executor entry gains the `command` type, disambiguated from
  the `command` verify check.
- No new dependencies; no port-family addition; no tracker or git protocol change.
- Sequencing after `tighten-spec-contract`: this change's MODIFIED block of `pipeline-config`
  ("Local sanity validation of mechanism and check configs") is layered on that change's text
  and then on `define-executor-contract`'s, both of which must sync first.
- Sequencing before `migrate-verify-to-executor-contract` (its `shell` built-in is built on
  this change's shared command-run core) and before `extract-agent-round-runner`: that change
  dissolves the `RoundTimeout` ↔ `AgentSettingsValidator` pair into the describe settings
  schema (its design D6) and deletes the type-keyed `agent-cli` arm (its task 5.1a); it
  therefore inherits two consumers this change adds — the command-settings validation and the
  command executor's `roundTimeout` resolution — and the `COMMAND` arm of the dispatch, which
  stays as the type-to-name mapping of the one built-in a stage names by type. That change's
  artifacts are brought in step through `/opsx:update` once this change is applied.
