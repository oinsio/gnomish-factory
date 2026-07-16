# Proposal: add-stage-engine

## Why

The factory can load a validated `PipelineDefinition` (load-pipeline-config U1) but nothing can execute it. The stage engine is the semantic core of the whole system — the QC loop, failure classes, attempts, escalation, and resume — and every other component (tracker loop, executors, git workflow) is an adapter around it. Building it first, against fake ports, lets the engine discover the port shapes from the consumer side before any real adapter cements a wrong interface.

## What Changes

- ADDED: a pure, reentrant orchestrator that runs one task through the pipeline stages from any valid recorded state to a terminal outcome, entirely through ports
- ADDED: domain model of task execution — `TaskContext` (id, title, body, human decisions), `TaskState` (position, attempt counter, attempt history with metrics), `Verdict`/`Finding`, `TaskOutcome` with a five-variant escalation report family
- ADDED: seven engine ports — `StageExecutor`, `BuiltinCheckRunner`, `CommandCheckRunner`, `ExternalCheckClient`, `JudgeVoter`, `EngineEventListener`, `AttemptPersistence` — with fake/in-memory implementations for tests
- ADDED: orchestration of all four verify-check types, including the `external` poll loop (interval/timeout, injected sleeper) and `judge` majority voting over single-vote calls
- ADDED: per-attempt telemetry (wall time, aggregated tool usage, input/output tokens) recorded in `AttemptRecord`; raw chronological `ToolTrace` emitted as a separate artifact correlated by `(taskId, stage, attempt)`

## Capabilities

### New Capabilities
- `stage-engine`: executing a declarative pipeline for one task — stage loop, verify orchestration, failure classification, attempts and escalation, advancement, resume, events, attempt persistence contract, telemetry

### Modified Capabilities
_None. `pipeline-config` is consumed read-only; its domain-purity requirement already permits SLF4J (it forbids only filesystem, Jackson, and adapter dependencies)._

## Goals

- G1: execute a `PipelineDefinition` end-to-end in-memory with fakes — every stage-contract section (`.claude/rules/stage-description.md` §1–8) has engine semantics
- G2: port interfaces are discovered from the consumer side and locked by contract-style specs that later real adapters must pass
- G3: every attempt produces analytics-grade telemetry (time, tools, tokens) without a separate persistence channel

## Non-Goals

- NG1: tracker port/adapters, claim protocol, factory polling loop (escalation is a returned value, not a tracker call)
- NG2: real persistence — git workflow, state-file serialization/format, worktrees (in-memory `AttemptPersistence` only)
- NG3: real executors (api / agent-cli), ai-provider port, real CI poller (all fakes)
- NG4: token/money budget enforcement (usage fields are the seam; budgets stay deferred as in load-pipeline-config NG8)
- NG5: cross-stage backtracking — a review that demands rework is a verify check of the producing stage, not a stage that moves the task backwards
- NG6: pipeline-config schema extensions (per-check `external` timeout class flag, explicit check ids, artifact paths) — deferred until a consumer exists
- NG7: parallel judge votes, engine-side retries (Resilience4j lives in adapters), sandboxing of `command` checks, inbound HTTP/status endpoint

## Users & Scenarios

- U1: the manual-run change (next) wraps the engine with interactive adapters — a human plays executor/judge/CI and watches the full QC loop live
- U2: the future factory loop runs N concurrent engine calls on virtual threads, one per claimed task, each in its own workspace
- U3: an analyst reconstructs per-stage/per-attempt cost and duration from `AttemptRecord`s and correlated `ToolTrace`s committed to task branches
- U4: a pipeline author relies on documented failure semantics: quality failures burn attempts and feed findings back; infrastructure failures never burn attempts

## Requirements

### Functional
- FR1: the engine SHALL run one task from its recorded `TaskState` to a terminal `TaskOutcome`, driven only by `PipelineDefinition`, `TaskContext`, an opaque workspace handle, and the seven ports; it SHALL be a pure orchestrator with no tracker, filesystem, or git knowledge
- FR2: verify checks SHALL run strictly in manifest order; the first non-`Pass` verdict stops the chain
- FR3: all four check types SHALL be orchestrated: `builtin` and `command` dispatched to their runners; `external` polled by the engine (interval/timeout from the manifest, injected sleeper; timeout = quality failure); `judge` resolved by majority over `votes` sequential single-vote calls — any `CannotVerify` vote fails the whole check as infrastructure
- FR4: verdicts SHALL classify as `Pass` | `Fail(findings, possibly empty)` | `CannotVerify(reason, details)`; a quality failure increments the attempt counter and feeds the failed check results of ALL prior attempts of the stage into the next executor request; `CannotVerify` escalates without burning an attempt; adapter exceptions are caught and treated as `CannotVerify` with the stack trace preserved
- FR5: when the resolved attempt limit is exhausted (including `attemptsUsed >= limit` on entry), the engine SHALL escalate with the full recorded attempt history of the stage
- FR6: an executor SHALL be able to return `DecisionNeeded(question, options)` instead of completing; the engine escalates immediately without burning an attempt
- FR7: human decisions SHALL be carried in `TaskContext.decisions` (chronological, free text with optional stage/author/time) and passed through to executor and judge requests unmodified; the engine never interprets them — resume adjustments (attempt reset, position change) are the caller's state manipulation
- FR8: after verification passes, advancement SHALL follow the stage's mode: `auto` proceeds to the next stage; `manual` returns `Paused(stage)`
- FR9: the engine SHALL resume from any valid `TaskState` at attempt-boundary granularity; a position naming a stage absent from the pipeline SHALL escalate as `PipelineMismatch` before any port call
- FR10: `TaskOutcome` SHALL be `Completed | Paused | Escalated(report) | Aborted(failedAt, cause)`, each carrying the final `TaskState`; escalation reports SHALL be data-only values of five kinds — `AttemptsExhausted`, `DecisionNeeded`, `CannotVerify`, `PipelineMismatch`, `CannotExecute` (executor infrastructure failure: no attempt burned, no round recorded)
- FR11: the engine SHALL call `AttemptPersistence.persist(taskId, state, trace)` after every executed round — before the `AttemptFinished` event and before any next attempt starts; a persistence failure SHALL abort the run with `Aborted`
- FR12: the engine SHALL emit seven sealed events (`RunStarted`, `AttemptStarted`, `ExecutionFinished`, `CheckStarted`, `CheckFinished`, `AttemptFinished` with new state and trace, `TaskFinished`), each self-contained with the `(taskId, stage, attempt)` key, delivered synchronously; listener exceptions are logged and swallowed
- FR13: every executed round SHALL be recorded in `TaskState.attempts` with its metrics — wall time, per-tool aggregate (name, call count, total duration), input/output tokens, all optional — including rounds ending in `CannotVerify`, which are recorded but not counted against the limit; the raw chronological `ToolTrace` SHALL be kept out of `TaskState` and correlated by the attempt key
- FR14: the attempt history in `TaskState` SHALL cover only the current stage; it resets on stage advancement (git history of committed states is the long-term archive)

### Non-Functional Reliability
- NFR-R1: the engine SHALL be reentrant — no static or shared mutable state; concurrent runs with independent ports must not interfere
- NFR-R2: the engine SHALL NOT retry any port call; transient-fault handling belongs to adapters
- NFR-R3: engine behavior SHALL be deterministic under injected `Clock`/sleeper — poll loops and timestamps are testable without real time
- NFR-R4: an unpersisted round is safe to lose: resume re-executes it from the last persisted state (idempotency at the attempt boundary)

### Non-Functional Observability
- NFR-O1: exceptional paths (adapter exceptions, listener failures, abort) SHALL log at ERROR/WARN via SLF4J at the point of capture, with stack traces also preserved in `CannotVerify.details` / `Aborted.cause`
- NFR-O2: the event stream SHALL be sufficient to reconstruct a task status view (position, attempt, per-check results, aggregate metrics) without reading engine internals

### Non-Functional Security
- NFR-S1: the engine SHALL execute nothing itself — commands, processes, and model calls happen only behind ports; `command` check sandboxing is explicitly deferred to the change that implements a real runner

### Non-Functional Cost
- NFR-C1: token usage (input/output) SHALL be captured per round for executor and per vote for judge when adapters report it — the accounting seam for future budget enforcement (NG4)

## Operator Experience Criteria

- UX1: an escalation report is self-describing when rendered: the human sees the stage, the failure class, the question and options (if any), and the findings history of all attempts without reading logs
- UX2: log lines and telemetry share the `(taskId, stage, attempt)` key, so a task's history is filterable in any log viewer

## Success Metrics

- M1: a reference in-memory run exercising all four check types, a quality-failure retry with feedback, a `DecisionNeeded` escalation, a decision-carrying resume, and a `manual` pause completes in the Spock suite with fakes only
- M2: engine domain classes reach 100% PIT mutation score via `./gradlew check`
- M3: resume specs cover mid-pipeline, mid-retry, post-pause, and stale-position (`PipelineMismatch`) starts
- M4: the Fail/CannotVerify classification table from the design is verified row-by-row by a data-driven spec

## Open Questions

- Q1 (resolved): package layout — single `domain.engine` with ports in `domain.engine.port`; no `domain.task` split. See design.md D9 for rationale.
