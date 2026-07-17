# Delta spec: stage-engine

add-stage-engine is archived (immutable), so these model deltas ride this change as MODIFIED requirements of the merged `stage-engine` capability.

## MODIFIED Requirements

### Requirement: Pure orchestration run
The engine SHALL run one task from its recorded state to a terminal outcome via `run(definition, context, state, workspace, ports)`, touching no tracker, filesystem, or git — the engine executes nothing itself; all effects happen behind the injected `EnginePorts` members: the seven behavioral ports (`StageExecutor`, `BuiltinCheckRunner`, `CommandCheckRunner`, `ExternalCheckClient`, `JudgeVoter`, `EngineEventListener`, `AttemptPersistence`) plus the `Clock` and `Sleeper` environment ports.
<!-- implements FR15 of add-manual-run -->

#### Scenario: Reference run with fakes only
- **WHEN** a pipeline exercising all four check types runs with fake ports, including a quality-failure retry, a decision escalation with a decision-carrying re-run, and a manual pause
- **THEN** the run reaches the expected terminal outcomes with no I/O outside the ports

### Requirement: Two-level attempt telemetry
Every executed round SHALL be recorded in `TaskState.attempts` with an explicit result classification (Passed, QualityFailure, CannotVerify, DecisionNeeded), a `startedAt` timestamp read from the engine's Clock port when the round begins, and optional metrics — wall time, per-tool aggregates (name, call count, total duration), input/output tokens (judge tokens per vote) — including rounds ending in CannotVerify, which are recorded but not counted. `TaskState` SHALL carry cumulative usage totals for the whole task, updated on every recorded round and preserved across stage advancement and resume. The raw chronological ToolTrace SHALL stay out of TaskState, correlated by the (taskId, stage, attempt) key. The history SHALL cover only the current stage, resetting on advancement.
<!-- implements FR15 of add-manual-run -->

#### Scenario: Unburned round is still recorded
- **WHEN** a round's verification ends in CannotVerify
- **THEN** the round appears in `attempts` with its token usage while `attemptsUsed` is unchanged

#### Scenario: History resets on advancement
- **WHEN** a stage passes and the pipeline advances
- **THEN** the new state's attempt history is empty and the previous rounds were persisted

#### Scenario: Round result is explicit
- **WHEN** a round ends in DecisionNeeded before any check runs
- **THEN** its record carries the DecisionNeeded result rather than leaving consumers to infer it from an empty check list

#### Scenario: Round start is timestamped
- **WHEN** the engine records any round — passed, failed, unverifiable, or decision-needed
- **THEN** the record carries `startedAt` equal to the Clock reading taken when the round began

#### Scenario: Cumulative totals survive advancement
- **WHEN** a stage passes and the pipeline advances
- **THEN** the new state's attempt history is empty while the task totals still include the passed stage's usage
