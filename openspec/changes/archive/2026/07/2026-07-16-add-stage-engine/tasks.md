# Tasks: add-stage-engine

TDD throughout (`.claude/rules/testing.md`): each task pairs a failing Spock spec with the code that passes it; every spec description carries its FR reference. FR/NFR references are to proposal.md; D-references and diagrams to design.md.

## 1. Scaffolding

- [x] 1.1 Create `domain.engine` and `domain.engine.port` packages with `@NullMarked` `package-info.java` (matching the existing domain packages) — D9

## 2. Domain model — inert value types (`domain.engine`)

- [x] 2.1 `TaskContext` + `Decision` records (opaque id, title, body; chronological decisions with optional stage/author/time) — FR7
- [x] 2.2 `Verdict` sealed triad (Pass / Fail with possibly-empty findings / CannotVerify with reason+details) + `Finding` (message, optional location/details) — FR4, D3
- [x] 2.3 `CheckRef` (verify-list index + derived label per check type) + `CheckResult` (checkRef, verdict, duration) — FR4, D3
- [x] 2.4 Usage types: executor usage (wall time, per-tool aggregates, optional tokens) and judge per-vote tokens — FR13, D5, NFR-C1
- [x] 2.5 `ToolTrace`: chronological calls (seq, tool, start, duration) with the (taskId, stage, attempt) key header — FR13, D5
- [x] 2.6 `AttemptRecord` (round no, check results, executor usage, judge usage) — FR13, D4
- [x] 2.7 Immutable `TaskState` with sealed position (`AtStage(name)` | `PipelineEnd`) and transition factories: record unburned round, record quality failure (+1), advance stage (history reset, last stage → `PipelineEnd`) — FR8, FR13, FR14, D4
- [x] 2.8 `ExecutionResult` sealed: Completed / DecisionNeeded(question, options) — both carrying usage and `ToolTrace` (see the choreography diagram) — FR6, FR13
- [x] 2.9 `TaskOutcome` sealed (Completed / Paused(passedStage) / Escalated / Aborted(failedAt, cause), each with final state) + `EscalationReport` five-kind sealed family — FR10
- [x] 2.10 `EngineEvent` sealed hierarchy: seven events, each self-contained with the (taskId, stage, attempt) key — FR12
- [x] 2.11 Explicit `result` classification on `AttemptRecord` (`Passed | QualityFailure | CannotVerify | DecisionNeeded`), set by the engine when recording a round — FR13, D5
- [x] 2.12 Cumulative task usage totals on `TaskState`, updated on every recorded round, surviving advancement and resume — FR13, NFR-C1, D5

## 3. Ports and test fakes (`domain.engine.port`)

- [x] 3.1 `StageExecutor` + its request record: TaskContext, full `StageDefinition`, workspace handle, attempt no, feedback (non-passing check results of all prior attempts) — FR1, D2
- [x] 3.2 Check ports: `BuiltinCheckRunner`, `CommandCheckRunner`, `ExternalCheckClient` (single poll → `PollStatus`: Pass | Fail(findings) | Running | CannotVerify), `JudgeVoter` (single vote; receives TaskContext) — FR3, D2
- [x] 3.3 `EngineEventListener` (single `onEvent`), `AttemptPersistence`, and injected `Clock`/`Sleeper` seams — FR11, FR12, D7, D8
- [x] 3.4 Groovy fakes: scripted executor and check runners, recording listener, in-memory persistence, virtual-time sleeper/clock

## 4. Verification orchestration

- [x] 4.1 Verify chain: strict manifest order, first non-Pass stops the chain (later checks never invoked) — FR2
- [x] 4.2 Builtin and command dispatch to their runners with duration capture into `CheckResult` — FR3
- [x] 4.3 External poll loop: sleeps the manifest interval via injected sleeper; timeout per injected clock → quality Fail with a timeout finding — FR3, NFR-R3
- [x] 4.4 Judge voting: sequential single votes (with TaskContext and decisions), majority verdict with early stop once mathematically decided (D10), failing-vote findings aggregated, per-vote tokens captured — FR3, FR7, NFR-C1
- [x] 4.5 Judge infrastructure rule: any CannotVerify vote fails the whole check as CannotVerify — FR3
- [x] 4.6 Check-adapter exceptions caught → CannotVerify with stack trace in details + ERROR log at the point of capture — FR4, NFR-O1
- [x] 4.7 Data-driven classification spec: one row per line of the normative Fail/CannotVerify table (M4) — FR4

## 5. Attempt loop and outcomes

- [x] 5.1 Engine entry `run(definition, context, state, workspace, ports)`: pre-flight validation — PipelineMismatch for a stale `AtStage` position, AttemptsExhausted for `attemptsUsed >= limit` on entry, immediate Completed from `PipelineEnd` — with RunStarted and TaskFinished emitted even when no execution/persistence port is reached — FR1, FR5, FR8, FR9, FR12
- [x] 5.2 Round execution: execute → verify → record round with metrics in the new state — including CannotVerify rounds (recorded, not counted) — FR4, FR13
- [x] 5.3 Quality failure path: attempt counter +1, non-passing check results of all prior attempts flow into the next executor request — FR4, FR5
- [x] 5.4 Persistence ordering for every executed round (including CannotVerify and DecisionNeeded rounds): persist → AttemptFinished → next AttemptStarted; persist failure → Aborted(failedAt, cause) with ERROR log — FR11, NFR-O1
- [x] 5.5 AttemptsExhausted escalation on the last permitted quality failure, carrying the full recorded history; spec that the report renders from outcome + final state alone — FR5, UX1
- [x] 5.6 DecisionNeeded escalation: round recorded with metrics, attempt not burned — FR6
- [x] 5.7 CannotExecute escalation: executor port throws → no round recorded, attempt not burned, stack trace in the report — FR10, NFR-O1
- [x] 5.8 Advancement: auto → next stage / Completed after last; manual → Paused with position advanced (last stage → `PipelineEnd`, resumed run → Completed); history resets on advancement — FR8, FR14
- [x] 5.9 Resume matrix (M3): mid-pipeline, mid-retry (counter and feedback continue), post-pause starts at the next stage, aborted-run resume re-executes the unpersisted round — FR9, NFR-R4
- [x] 5.10 Decision-carrying resume: caller-appended decision + caller-reset counter reach the executor request verbatim — FR7

## 6. Events and observability

- [x] 6.1 Event emission across the whole run: all seven events, synchronous, complete (taskId, stage, attempt) keys shared with the trace — FR12, UX2
- [x] 6.2 Broken-listener spec: listener throwing on every event never breaks the run; each failure logged — FR12, NFR-O1
- [x] 6.3 Status-reconstruction spec: position/attempts/verdicts/metrics rebuilt from recorded events equal the final state — NFR-O2

## 7. Cross-cutting gates

- [x] 7.1 Verify the existing ArchUnit domain-purity rule (owned by pipeline-config, matching `..domain..`) covers `domain.engine..` — add a regression assertion naming the new packages, no rule change — NFR-O1, D9
- [x] 7.2 Reentrancy and no-retry spec: two concurrent runs with independent fakes, identical results, no cross-talk; fakes assert exactly one call per logical port invocation — NFR-R1, NFR-R2
- [x] 7.3 Reference end-to-end in-memory run (M1): all four check types, quality retry with feedback, decision escalation + decision-carrying resume, manual pause
- [x] 7.4 `./gradlew check` green: PIT 100% on the engine domain (M2), Spotless, Error Prone/NullAway, buildHealth
- [x] 7.5 Traceability sweep: grep confirms every FR/NFR/UX of the proposal has an implementing spec or class reference
- [x] 7.6 Refresh the README status sentence ("no factory domain logic yet" no longer holds once the engine lands)
